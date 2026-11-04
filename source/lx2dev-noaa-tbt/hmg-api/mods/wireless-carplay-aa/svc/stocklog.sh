#!/system/bin/sh
# Re-sends the lines of the STOCK wireless projection code under HMG-API's own tag, so they show in HMG-API's Logs.
#
# Why: HMG-API's log keeper only holds logcat lines tagged HMG-API. The stock apps log under their own tags, so what wireless
# Android Auto does (GPM: AAProjection), what PhoneProjection decides (PhoneProjection), why a CarPlay session ends
# (DiPO: CarPlayService and CarPlay_), what the iAP2 server tells the phone about Wi-Fi (Iap2Service) and what the hotspot does
# (WifiService, WifiStateMachine, SoftapController, hostapd) never showed there, and the main log buffer keeps only seconds.
#
# Only an allow-list of lines passes, and not unchanged: a line with a key, a passphrase or an SSID is dropped, MAC addresses
# are masked, and the value after a "name" label (a phone's name) is hidden. Output is rate limited. Reads only: nothing here
# changes the unit. Started and restarted by hmgapid as root once Android is up; its own output is
# /data/hmgapi/logs/<mod>/stocklog.log (hmgapid service-log stocklog).
#
# lx2dev.11: the wireless Android Auto hand-over is a fixed sequence of stock steps (GPM WirelessExplorer, PhoneProjection). Each step
# has a line in the stock log. This service also writes the LAST step it saw, as "<number> <step name>", to one small file (AASTAGE_FILE,
# else <the mod's run folder>/aa-stage) that the aastage service reads to say where an attempt stopped. Only step names go in the file:
# no phone name, address or key. The first argument is the build name hmgapid passes (a changed argument makes hmgapid restart the
# service after an update, so an old copy does not go on running).
BUILD=${1:-unknown}
TAGS="AAProjection:V GpmManager:I PhoneProjection:V PPLog:V CarPlayService:I CarPlay_:I Iap2Service:I WifiService:I WifiStateMachine:D SoftapController:V hostapd:V"
if [ -n "$AASTAGE_FILE" ]; then
    SF=$AASTAGE_FILE
elif [ -n "$HMGAPI_DATA" ]; then
    SF=${HMGAPI_DATA%/*}/aa-stage
else
    SF=
fi
seq=0
# A new hand-over (its first step) begins the file again; its later steps are added, so a reader that looks only every few seconds still sees
# each of them. Every 64th mark begins it again too, so the file stays small whatever happens.
mark() {
    seq=$((seq + 1))
    [ -n "$SF" ] || return 0
    case $1 in
        *init-session-usb|*nego-start) echo "$seq $1" > "$SF" 2>/dev/null ;;
        *)
            if [ $((seq % 64)) = 0 ]; then
                echo "$seq $1" > "$SF" 2>/dev/null
            else
                echo "$seq $1" >> "$SF" 2>/dev/null
            fi ;;
    esac
}
# GPM's own steps (tag AAProjection, "Class : message")
stage_gpm() {
    case $l in
        *"Wireless ==> initSession"*) mark init-session-wireless ;;
        *"USB ==> initSession"*) mark init-session-usb ;;
        *"Wireless Session init try"*) mark nego-start ;;
        *"Wireless AA connection sequence is over Start, not re-init"*) mark nego-skipped-session-running ;;
        *"Sent WifiVersionRequest"*) mark version-request-sent ;;
        *"Received WifiVersionResponse"*) mark version-response ;;
        *STATUS_NO_SUPPORTED_WIFI_CHANNELS*|*STATUS_NO_COMPATIBLE_VERSION*) mark version-refused ;;
        *"Wireless ==> Add Info"*|*"Wireless ==> Update Info"*) mark phone-told-to-pp ;;
        *"Already Wireless AA connected devices"*) mark phone-already-connected ;;
        *"==> connectWireless"*) mark connect-requested ;;
        *"isWifiApEnable = false !!!"*) mark hotspot-not-ready-start-not-sent ;;
        *"isWifiApEnable = true"*) mark hotspot-ready ;;
        *"AA is already activated. WifiStartRequest would not be called"*) mark start-request-skipped-active ;;
        *"Sent start request"*) mark start-request-sent ;;
        *"Received WiFiInfoRequest"*) mark wifi-info-requested ;;
        *"Sent WiFi information"*) mark wifi-info-sent ;;
        *STATUS_WIFI_INCORRECT_CREDENTIALS*) mark wifi-credentials-refused ;;
        *"Received Wifi Connect Result"*) mark wifi-connect-result ;;
        *"Received WifiStartResponse"*) mark start-response ;;
        *"Wireless ==> Start Info"*) mark wireless-session-starting ;;
        *"Wireless ==> Connected Info"*) mark wireless-connected ;;
        *"Read EOF for RFCOMM"*|*rfcommDisconnect*) mark rfcomm-closed ;;
    esac
}
# PhoneProjection's decisions (tag PhoneProjection, "[ class ] message")
stage_pp() {
    case $l in
        *"AA global value is disabled"*) mark pp-aa-switch-off ;;
        *"device value is OFF"*) mark pp-phone-switched-off ;;
        *"getAndroidAutoStatus() result="*) v=${l#*result=}; v=${v%%[!0-9]*}; mark "pp-aa-switch-$v" ;;
        *"Device is Not Exist, update Failed"*) mark pp-phone-not-in-list ;;
        *"requestWLDeviceReadyStatusUpdate, APMode.UNKNOWN"*) mark pp-waits-hotspot-unknown-mode ;;
        *"requestWLDeviceReadyStatusUpdate, APMode.HOTSPOT"*) mark pp-hotspot-in-user-mode ;;
        *"APMode.CONNECTIVITY"*) mark pp-hotspot-connectivity-mode ;;
        *"check show Init popup"*) mark pp-first-use-popup ;;
        *"already init popup show"*) mark pp-popup-already-shown ;;
        *"same device is already connected"*|*"another device is already connected"*) mark pp-already-connected ;;
        *"[PP -> AA] connectPP"*) mark pp-asks-gpm-to-connect ;;
        *"canShowWirelessPopup ret = false"*) mark pp-popup-blocked-settings-screen ;;
        *"sendChangeWiFiHotspotPopup"*) mark pp-hotspot-popup ;;
        *"requestWLDeviceReadyStatusUpdate ["*) mark pp-ready-update ;;
    esac
}
sec=-1
cnt=0
last=
rep=0
echo "stocklog: started (build $BUILD; $TAGS)"
log -p i -t HMG-API "[wireless-carplay-aa-lx2dev] stocklog: started (build $BUILD)"
logcat -v brief -s $TAGS 2>&1 | while read -r l; do
    case $l in
        *_PSK*) ;;   # WPA_PSK / WPA2_PSK: the name of a security type, not a key
        *PSK*|*psk*) continue ;;
    esac
    case $l in
        *assword*|*assphrase*|*SSID*|*ssid*|*asskey*|*wpa_*|*secret*|*Secret*) continue ;;
    esac
    tag=${l%%\(*}
    tag=${tag#?/}
    keep=
    case $tag in
        AAProjection)
            stage_gpm
            case $l in
                *ireless*|*connectPP*|*"[PP >>"*|*"[AA >>"*|*"[MD >>"*|*"[HU >>"*|*WifiAp*|*SPP*|*Spp*|*Bye*|*ession*|*isconnect*|*onnect*|*timeout*|*TimeOut*|*Already*) keep=1 ;;
                # lx2dev.11: the rest of the hand-over, whose lines the earlier list let through only by chance: GPM's answer to the phone's
                # version reply, the phone entry it hands to PhoneProjection, the hotspot check that silently ends a start (isWifiApEnable),
                # a refused version, and a failed parse (handleVersionResponse prints an exception there)
                *WifiVersion*|*handleVersionResponse*|*STATUS_*|*DeviceInfo*|*"Info, name"*|*isFirstConnection*|*initSession*|*"start request"*|*"WiFi information"*) keep=1 ;;
                # the video side of an Android Auto session (lx2dev.10): the phone asks for the screen (video focus) and GPM answers, the
                # boot "confirm" gate, the picture surface and the activity's life, the playback / video-config callbacks, emergency and
                # provisioning refusals. These say whether a black screen is a refused request or a missing picture. Not "Video" alone:
                # the VideoSink may log every frame.
                *ideoFocus*|*ideoConfig*|*ideoPause*|*PausedVideo*|*ocus*|*urface*|*onfirmScreen*|*MSG_RUN_GPM*|*rovision*|*layback*|*MainActivity*|*startMain*|*finishMain*|*onCreate*|*onResume*|*onPause*|*onDestroy*) keep=1 ;;
            esac ;;
        GpmManager)
            case $l in
                # status and mode lines; and the two that say the gpm service is not there (a client waits for it)
                *tatus*|*Mode*|*"not exist"*|*"not Running"*|*"binder is null"*) keep=1 ;;
            esac ;;
        *PhoneProjection|*PPLog)
            stage_pp
            case $l in
                *ireless*|*connectPP*|*turnOnWiFi*|*turnOffWiFi*|*WiFi*|*Wifi*|*Hotspot*|*hotspot*|*SPP*|*Spp*|*isSupport*|*Ignore*|*Not*|*imeout*|*isconnect*|*onnectivity*) keep=1 ;;
                # lx2dev.11: the decision lines of PhoneProjection's wireless service: the Android Auto switch it read (getAndroidAutoStatus),
                # the ready update for the phone, the hotspot mode it waits for, the first-use popup
                *getAndroidAutoStatus*|*requestWLDevice*|*APMode*|*"nit popup"*|*InitPopup*|*nitPopup*|*updateReady*|*updateDevice*) keep=1 ;;
            esac ;;
        CarPlayService)
            case $l in
                *ession*|*Transport*|*ransport*|*isconnect*|*Finaliz*|*nitiali*|*Wireless*|*Wifi*|*WiFi*|*timeout*|*imeout*|*rror*|*fail*|*Fail*|*creen*|*isplay*|*eyFrame*|*udio*|*urface*|*ecode*) keep=1 ;;
            esac ;;
        CarPlay_)
            case $l in
                *creen*|*CREEN*|*lack*|*LACK*|*isplay*|*ession*|*ecode*|*urface*) keep=1 ;;
            esac ;;
        Iap2Service)
            case $l in
                *ifi*|*iFi*|*Auth\ Type*|*OnSend*|*onnected*|*isconnect*|*onfiguration*) keep=1 ;;
            esac ;;
        WifiService)
            case $l in
                *Connectivity*|*setWifiApEnabled*|*Last\ mode*|*AP*) keep=1 ;;
            esac ;;
        WifiStateMachine)
            case $l in
                *setWifiApState*|*SoftAp*|*softap*|*CMD_START_AP*|*CMD_STOP_AP*) keep=1 ;;
            esac ;;
        *SoftapController|*hostapd) keep=1 ;;
    esac
    [ -n "$keep" ] || continue
    # MAC addresses: every "HH:" becomes "xx:", then the last octet too
    l=${l//[0-9A-Fa-f][0-9A-Fa-f]:/xx:}
    l=${l//xx:[0-9A-Fa-f][0-9A-Fa-f]/xx:xx}
    # a name label: the value after it is hidden
    case $l in *[Nn]ame\ =\ *) l="${l%%[Nn]ame = *}name = (hidden)" ;; esac
    case $l in *[Nn]ame:\ *) l="${l%%[Nn]ame: *}name: (hidden)" ;; esac
    case $l in *[Nn]ame\[*) l="${l%%[Nn]ame\[*}name[hidden]" ;; esac
    # drop the "I/Tag( pid): " prefix, keep the tag
    msg=${l#*\): }
    # the same line again and again (a poll in the stock code) is counted, not repeated; the count follows with the next other line
    if [ "$tag: $msg" = "$last" ]; then
        rep=$((rep + 1))
        continue
    fi
    # at most 15 lines a second
    now=${SECONDS%.*}   # whole seconds (a float in some shells)
    if [ "$now" != "$sec" ]; then
        sec=$now
        cnt=0
    fi
    cnt=$((cnt + 1))
    [ "$cnt" -le 15 ] || continue
    if [ "$rep" -gt 0 ]; then
        log -p i -t HMG-API "[wireless-carplay-aa-lx2dev] stock (the line above repeated $rep more times)"
        rep=0
    fi
    last="$tag: $msg"
    log -p i -t HMG-API "[wireless-carplay-aa-lx2dev] stock $tag: $msg"
done
[ "$rep" -le 0 ] || log -p i -t HMG-API "[wireless-carplay-aa-lx2dev] stock (the line above repeated $rep more times)"
echo "stocklog: logcat ended"
log -p i -t HMG-API "[wireless-carplay-aa-lx2dev] stocklog: logcat ended"
