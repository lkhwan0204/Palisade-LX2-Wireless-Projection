#!/system/bin/sh
# aastage: where a wireless Android Auto attempt is, step by step, from outside GPM. Read only: it changes nothing and runs apart from the
# CarPlay bridge (it works with the bridge part off).
#
# lx2dev.11 (the lx2dev.10 version said nothing at all unless GPM's status had reached ATTACHED, and a hand-over that stops early never gets
# there; it also asked GPM "isGpmBtConnected" every 5 s, which GPM logs, so that line in the log came from this service, not from a failure):
#  - IDLE: no GPM call at all in the normal case. It only reads one small file, written by the stocklog service, that holds the stock hand-over
#    steps it saw ("<number> <step name>", see svc/stocklog.sh; step names only, no phone name or address). A fallback read of GPM's status
#    and connected mode (service call gpm 1 / 22) happens every 30 s, in case that file is missing.
#  - ATTEMPT (the first step of a hand-over seen): each new step is said, with the seconds since the attempt began. GPM's status and mode are
#    read every 2 s, and every 10 s a picture of the transport is said (hotspot counters, the phone's entry, TCP connections on the hotspot
#    address). If no new step comes for 15 s before a session exists, ONE verdict names the last step and what the stock code does next
#    ("STALLED_AFTER_<step>"). After 150 s without a step the attempt is over and the service is idle again.
#  - SESSION (GPM status 3 or more; the connected mode alone is not one, it can rise as soon as GPM is asked to connect): as in lx2dev.10: ATTACHED/CONNECTED/AUTHENTICATING/ACTIVATED, the video-focus gate,
#    the TCP connection, the traffic, the activity in front (VIDEO_FOCUS_GATED, SESSION_ACTIVATED_NO_TRANSPORT, ACTIVATED_NO_VIDEO_TRAFFIC,
#    VIDEO_TRAFFIC_ARRIVES, HANDSHAKE_STUCK_AT_<state>).
# At the start of an attempt the Android Auto switch values PhoneProjection reads (Settings.System androidauto_information, _dr1, _dr2) and the
# boot confirm gate are said once. Everything goes to HMG-API's log (tag HMG-API, "[wireless-carplay-aa-lx2dev] aastage: ...") and to the
# service's own log file.
#
# GPM's status (com.hkmc.gpm.GpmConstants): 0 INITIALIZE, 1 DISCONNECTED, 2 READY, 3 ATTACHED, 4 CONNECTED, 5 AUTHENTICATING, 6 ACTIVATED.
# GPM's connected mode: 0 disconnected, 1 USB, 2 WIRELESS. The Android Auto screen (GPM's MainActivity) closes at once unless the status is 6.
# It only calls: service call gpm 1 / 22 (getGpmStatus, getGPMConnectedMode), settings get, getprop, dumpsys activity activities, ifconfig wlan0
# and reads /proc/net/dev|arp|tcp and the stage file.
# Off switch: setprop persist.hmgapi.aa.stage 0 (then restart the head unit or the service); it then reads nothing.
# Test hooks (host tests only): AASTAGE_PROC (the /proc to read), AASTAGE_CLOCK (a file holding "now" in seconds), AASTAGE_MAX_LOOPS,
# AASTAGE_SLEEP, AASTAGE_FILE (the stage file).
umask 077
set -f   # no file name expansion anywhere (ifconfig prints "[up ...]")
BUILD=${1:-unknown}
PROC=${AASTAGE_PROC:-/proc}
NAP=${AASTAGE_SLEEP:-sleep}   # host tests swap in a fake clock; ksh93 has its own builtin sleep
TAG="[wireless-carplay-aa-lx2dev] aastage:"
if [ -n "$AASTAGE_FILE" ]; then
    SF=$AASTAGE_FILE
elif [ -n "$HMGAPI_DATA" ]; then
    SF=${HMGAPI_DATA%/*}/aa-stage
else
    SF=
fi
# one line to HMG-API's log and to this service's own log file
say() {
    log -p i -t HMG-API "$TAG $*"
    echo "$*"
}
# service call gpm <code>: "Result: Parcel(00000000 00000006   '........')" -> 6 ; -1 when the service does not answer
gpm_int() {
    o=$(service call gpm "$1" 2>/dev/null)
    case $o in
        *Parcel\(00000000\ *) ;;
        *) echo -1; return ;;
    esac
    set -- $o
    case $3 in
        [0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F]) echo $((16#$3)) ;;
        *) echo -1 ;;
    esac
}
status_name() {
    case $1 in
        0) echo INITIALIZE ;; 1) echo DISCONNECTED ;; 2) echo READY ;; 3) echo ATTACHED ;; 4) echo CONNECTED ;;
        5) echo AUTHENTICATING ;; 6) echo ACTIVATED ;; *) echo "unknown($1)" ;;
    esac
}
mode_name() {
    case $1 in 0) echo disconnected ;; 1) echo USB ;; 2) echo WIRELESS ;; *) echo "unknown($1)" ;; esac
}
# whole-number counters of the hotspot interface: RX TX (bytes), or -1
net_counters() {
    RX=-1
    TX=-1
    while read -r line; do
        case $line in
            *wlan0:*) rest=${line#*:}; set -- $rest; RX=$1; TX=$9; break ;;
        esac
    done < "$PROC/net/dev"
}
arp_count() {
    ARP=0
    while read -r ip hw flags mac mask dev; do
        if [ "$dev" = wlan0 ] && [ "$flags" != 0x0 ]; then ARP=$((ARP + 1)); fi
    done < "$PROC/net/arp"
}
# ESTABLISHED TCP connections whose local address is the hotspot's: TCPN (count) and TCPP (local ports)
tcp_on_hotspot() {
    TCPN=0
    TCPP=
    want=
    prev=
    for w in $(ifconfig wlan0 2>/dev/null); do
        if [ "$prev" = ip ]; then
            oldifs=$IFS
            IFS=.
            set -- $w
            IFS=$oldifs
            if [ $# = 4 ]; then want=$(( $4 << 24 | $3 << 16 | $2 << 8 | $1 )); fi
        fi
        prev=$w
    done
    [ -n "$want" ] || return
    while read -r tsl tlocal trem tstate trest; do
        [ "$tstate" = 01 ] || continue
        addr=${tlocal%:*}
        port=${tlocal#*:}
        [ $((16#$addr)) = "$want" ] || continue
        TCPN=$((TCPN + 1))
        TCPP="$TCPP $((16#$port))"
    done < "$PROC/net/tcp"
}
setting() {
    v=$(settings get system "$1" 2>/dev/null)
    if [ -z "$v" ]; then v="?"; fi
    echo "$v"
}
# the activity in front (component name only), for the moments that matter
foreground() {
    a=$(dumpsys activity activities 2>/dev/null | grep mResumedActivity)
    a=${a#*u0 }
    a=${a%% *}
    if [ -z "$a" ]; then a="?"; fi
    echo "activity in front $a"
}
# the boot "confirm" gate GPM checks before it grants a phone the screen: true after the driver confirmed the boot screen
prop() {
    v=$(getprop "$1" 2>/dev/null)
    if [ -z "$v" ]; then v="unset"; fi
    echo "$v"
}
gate_open() {
    g=$(prop sys.boot.confirm_screen)
    case $g in 1|true) return 0 ;; esac
    return 1
}
# the Android Auto switch values PhoneProjection reads, once per attempt (each is a "settings get": a small Java program, so not every few seconds)
read_switch() {
    s0=$(setting androidauto_information)
    s1=$(setting androidauto_information_dr1)
    s2=$(setting androidauto_information_dr2)
    AASET="androidauto_information=$s0 _dr1=$s1 _dr2=$s2"
}
# what a hand-over step means in the stock code (GPM WirelessExplorer, PhoneProjection WirelessPhoneProjectionService): MEAN
meaning() {
    case $1 in
        init-session-wireless|nego-start) MEAN="GPM began the wireless hand-over over Bluetooth; the version request to the phone should follow at once" ;;
        init-session-usb) MEAN="GPM began a USB (wired) session; the status should rise to ATTACHED" ;;
        nego-skipped-session-running) MEAN="GPM saw a session already under way (its status above READY) and did not start a new hand-over: a session left from before; the stock only clears it by a head unit restart" ;;
        version-request-sent) MEAN="GPM asked the phone for its protocol version and no answer line followed: the phone or the Bluetooth link did not answer" ;;
        version-response) MEAN="the phone answered the version request, but GPM did not pass the phone on to PhoneProjection (no Add Info / Update Info line); a refused version or a parse error would have printed STATUS_ or handleVersionResponse lines" ;;
        version-refused) MEAN="GPM refused the phone's version or Wi-Fi channel answer; the hand-over ends here by design" ;;
        phone-told-to-pp) MEAN="GPM handed the phone to PhoneProjection and PhoneProjection logged no decision after that: the Android Auto switch line, the phone-list lines and the popup lines are the ones to read" ;;
        phone-already-connected) MEAN="GPM treats this phone as connected already, so no new hand-over starts" ;;
        pp-ready-update) MEAN="PhoneProjection got GPM's ready update for the phone and logged no decision after it (no switch, popup, hotspot-mode or connect line)" ;;
        pp-aa-switch-0) MEAN="PhoneProjection read the Android Auto switch (androidauto_information for this user type) as 0: wireless Android Auto is off for it" ;;
        pp-aa-switch-1) MEAN="PhoneProjection read the Android Auto switch as 1 (on); it should go on to the phone list, popup or connect" ;;
        pp-aa-switch-off) MEAN="PhoneProjection stopped on purpose: the Android Auto switch is not 1 for this user type, so it ignores wireless Android Auto: no popup, no connection, no icon. This is a setting; where it is changed on this unit is not known" ;;
        pp-phone-switched-off) MEAN="this phone is switched OFF in PhoneProjection's own device list, so PhoneProjection ignores its connection request" ;;
        pp-phone-not-in-list) MEAN="PhoneProjection could not find the phone in its own device list and dropped GPM's ready update: nothing follows" ;;
        pp-waits-hotspot-unknown-mode) MEAN="PhoneProjection needs the hotspot in connectivity mode (mode 2) for a phone it has seen before, and the hotspot was off or in another mode, so it did nothing. From lx2dev.12 the mod holds this update while it starts the hotspot, so this step should only show when the hotspot did not come up in 20 s or the stock call was not held: read the pp: lines in HMG-API log" ;;
        pp-hotspot-in-user-mode) MEAN="the hotspot is on in the user (tethering) mode: PhoneProjection asks on the screen whether it may switch it and waits for the answer there" ;;
        pp-hotspot-connectivity-mode) MEAN="the hotspot is in connectivity mode (mode 2); PhoneProjection should now ask GPM to connect" ;;
        pp-first-use-popup) MEAN="PhoneProjection is showing its first-use question for this phone on the car screen and waits for the answer there" ;;
        pp-popup-already-shown) MEAN="a first-use popup is open already; PhoneProjection starts no second one" ;;
        pp-popup-blocked-settings-screen) MEAN="PhoneProjection did not show its popup because the Bluetooth device settings screen is in front; go back to the home screen" ;;
        pp-already-connected) MEAN="PhoneProjection believes a phone is connected already and does nothing for this one" ;;
        pp-hotspot-popup) MEAN="PhoneProjection asks on the car screen about the hotspot and waits for the answer there" ;;
        pp-asks-gpm-to-connect) MEAN="PhoneProjection asked GPM to connect the phone; GPM should log ==> connectWireless" ;;
        connect-requested) MEAN="GPM was asked to connect; it now checks that the hotspot is enabled in mode 2 and then sends the start request to the phone" ;;
        hotspot-not-ready-start-not-sent) MEAN="GPM stopped without a word to the phone: the hotspot is not enabled in mode 2 (isWifiApEnable = false), so no start request was sent; the phone keeps waiting and tells the driver to look at the car" ;;
        hotspot-ready) MEAN="the hotspot is enabled in mode 2; the start request (address and port) should follow" ;;
        start-request-skipped-active) MEAN="GPM thinks Android Auto is active already and sent no start request" ;;
        start-request-sent) MEAN="GPM sent the start request (address and port); the phone should ask for the Wi-Fi information" ;;
        wifi-info-requested) MEAN="the phone asked for the Wi-Fi information; GPM should answer it" ;;
        wifi-info-sent) MEAN="GPM sent the Wi-Fi information; the phone should join the hotspot and report a connect result, and none came: see the hostapd / association lines and the phone entries" ;;
        wifi-credentials-refused) MEAN="the phone reports that the Wi-Fi name or key it was given is wrong" ;;
        wifi-connect-result) MEAN="the phone reported its Wi-Fi connect result; the TCP session to GPM's port should start next (status ATTACHED)" ;;
        start-response) MEAN="the start request was answered; the TCP session to GPM's port should start next (status ATTACHED)" ;;
        wireless-session-starting|wireless-connected) MEAN="GPM reports the wireless session starting; its status should now rise to ATTACHED and beyond" ;;
        rfcomm-closed) MEAN="the Bluetooth link used for the hand-over closed (normal once the Wi-Fi side has taken over, an end if nothing followed)" ;;
        *) MEAN="no explanation is known for this step" ;;
    esac
}

if [ "$(getprop persist.hmgapi.aa.stage 2>/dev/null)" = 0 ]; then
    say "switched off by persist.hmgapi.aa.stage=0 (nothing is read)"
    off=0
    while true; do
        off=$((off + 1))
        [ -z "$AASTAGE_MAX_LOOPS" ] || [ "$off" -le "$AASTAGE_MAX_LOOPS" ] || break
        $NAP 3600
    done
    exit 0
fi

# the start: which build this is, which tools exist, whether GPM's binder answers (the only proof that the service is doing its job)
have=
for c in service settings getprop dumpsys ifconfig log; do
    if type $c > /dev/null 2>&1; then have="$have $c=yes"; else have="$have $c=NO"; fi
done
if [ -n "$SF" ]; then sfnote="stage file $SF"; else sfnote="no stage file (the steps cannot be followed, only GPM's status)"; fi
say "started (build $BUILD; commands:$have; $sfnote)"
st=-1; md=-1; last_st=-1; last_md=-1; was_active=; t0=0; tstat=0; verdict=; rx10=-1; tx10=-1; t10=0; peak=0; loops=0
att=; att0=0; t_ev=0; seen=0; last_code=; stall_said=; next_idle=0; noanswer=; first=1; AASET=
while true; do
    loops=$((loops + 1))
    [ -z "$AASTAGE_MAX_LOOPS" ] || [ "$loops" -le "$AASTAGE_MAX_LOOPS" ] || break
    if [ -n "$AASTAGE_CLOCK" ]; then
        read -r t < "$AASTAGE_CLOCK"
    else
        t=${SECONDS%.*}
    fi
    # 1. new hand-over steps from the stage file (a shell read: no process)
    if [ -n "$SF" ] && [ -r "$SF" ]; then
        maxs=0
        while read -r s c; do
            [ -n "$c" ] || continue
            maxs=$s
            if [ "$s" -gt "$seen" ] 2>/dev/null; then
                if [ -z "$att" ]; then
                    att=1
                    att0=$t
                    stall_said=
                    read_switch
                    gate=$(prop sys.boot.confirm_screen)
                    say "a hand-over attempt began (first step $c): Android Auto switch $AASET | boot confirm gate sys.boot.confirm_screen=$gate"
                    verdict=
                    rx10=-1
                    t10=$t
                    net_counters
                    rx10=$RX
                    tx10=$TX
                fi
                say "step $c (+$((t - att0)) s into this attempt)"
                last_code=$c
                t_ev=$t
                stall_said=
                seen=$s
            fi
        done < "$SF"
        # the stocklog service restarted: its numbering begins again
        if [ "$maxs" -lt "$seen" ] 2>/dev/null; then seen=0; fi
    fi
    # 2. GPM's own status: every 2 s while an attempt or a session is on, every 30 s otherwise (a fallback if there is no stage file)
    poll=
    if [ -n "$att" ] || [ -n "$was_active" ]; then
        poll=1
    elif [ "$t" -ge "$next_idle" ]; then
        poll=1
        next_idle=$((t + 30))
    fi
    if [ -n "$poll" ]; then
        st=$(gpm_int 1)
        md=$(gpm_int 22)
        if [ "$st" = -1 ]; then
            if [ -z "$noanswer" ]; then
                o=$(service call gpm 1 2>&1)
                set -- $o
                say "the gpm service does not answer (GPM not running, or its binder refuses a shell caller); its reply was: $1 $2 $3 $4 $5 $6 $7 $8. The steps from the stage file are still followed"
            fi
            noanswer=1
        else
            noanswer=
            sname=$(status_name "$st")
            mname=$(mode_name "$md")
            if [ -n "$first" ]; then
                first=
                say "GPM answers: status $st ($sname), connected mode $mname"
                last_st=$st
                last_md=$md
                tstat=$t
            elif [ "$st" != "$last_st" ] || [ "$md" != "$last_md" ]; then
                lname=$(status_name "$last_st")
                say "GPM status $last_st ($lname) -> $st ($sname), connected mode $mname"
                last_st=$st
                last_md=$md
                tstat=$t
            fi
            session=
            if [ "$st" -ge 3 ]; then session=1; fi   # the connected MODE can rise earlier (when GPM is asked to connect): it is said, it does not make a session
            if [ -n "$session" ]; then
                if [ -z "$was_active" ]; then
                    was_active=1
                    t0=$t
                    verdict=
                    peak=$st
                    if [ -z "$att" ]; then
                        att=1
                        att0=$t
                        stall_said=
                        t_ev=$t
                        read_switch
                        say "a session began without a stage step (a wired session, or no stage file): Android Auto switch $AASET"
                        rx10=-1
                        t10=$t
                        net_counters
                        rx10=$RX
                        tx10=$TX
                    fi
                fi
                if [ "$st" -gt "$peak" ]; then peak=$st; fi
            elif [ -n "$was_active" ]; then
                pname=$(status_name "$peak")
                say "session over after $((t - t0)) s: GPM is back to $sname (it got as far as $pname)"
                was_active=
                verdict=
                att=   # the attempt ended with the session: the stall rule below is for hand-overs that never reached one
                stall_said=
            fi
        fi
    fi
    # 3. every 10 s while an attempt or session is on: the picture of the transport, and the verdicts
    if [ -n "$att" ] && [ $((t - t10)) -ge 10 ]; then
        net_counters
        arp_count
        tcp_on_hotspot
        rxd=-1
        if [ "$rx10" -ge 0 ] && [ "$RX" -ge 0 ]; then rxd=$((RX - rx10)); fi
        txd=-1
        if [ "$tx10" -ge 0 ] && [ "$TX" -ge 0 ]; then txd=$((TX - tx10)); fi
        gate=$(prop sys.boot.confirm_screen)
        ports=
        if [ -n "$TCPP" ]; then ports=" (local ports$TCPP)"; fi
        sname=$(status_name "$st")
        mname=$(mode_name "$md")
        say "attempt $((t - att0)) s: GPM $sname/$mname | wlan0 received +$rxd B, sent +$txd B in $((t - t10)) s | phone entries $ARP | TCP connections on the hotspot address $TCPN$ports | boot confirm gate sys.boot.confirm_screen=$gate | $AASET"
        rx10=$RX
        tx10=$TX
        t10=$t
        v=
        if [ -n "$was_active" ]; then
            case $st in
                6)
                    if [ $((t - tstat)) -ge 10 ]; then
                        if ! gate_open; then
                            v="VIDEO_FOCUS_GATED: GPM is ACTIVATED but the boot confirm gate (sys.boot.confirm_screen) is not true, and GPM then keeps the phone's screen request waiting instead of granting it (the stock rule in GalConnectionSession.onVideoFocusRequest); the stock lines 'onVideoFocusRequest' / 'isClickedConfirmScreen' say the same"
                        elif [ "$TCPN" = 0 ]; then
                            v="SESSION_ACTIVATED_NO_TRANSPORT: GPM says ACTIVATED but no TCP connection to the phone is open on the hotspot address"
                        elif [ "$rxd" -ge 0 ] && [ "$rxd" -lt 20000 ]; then
                            v="ACTIVATED_NO_VIDEO_TRAFFIC: the session is up and the TCP connection is open but almost nothing arrives from the phone (under 20 KB in 10 s): the phone sends no picture"
                        elif [ "$rxd" -ge 20000 ]; then
                            v="VIDEO_TRAFFIC_ARRIVES: the session is up and data flows; a black screen then means the picture is received and not shown (decoder, Surface or focus), not a missing session"
                        fi
                    fi ;;
                3|4|5)
                    if [ $((t - tstat)) -ge 20 ]; then v="HANDSHAKE_STUCK_AT_$(status_name "$st"): the session did not get past this step in 20 s"; fi ;;
            esac
            if [ -n "$v" ] && [ "$v" != "$verdict" ]; then
                verdict=$v
                fg=$(foreground)
                say "VERDICT $v | $fg"
            fi
        fi
    fi
    # 4. no session and no new step for 15 s: one verdict naming the last step; after 150 s the attempt is over
    if [ -n "$att" ] && [ -z "$was_active" ]; then
        sname=$(status_name "$st")
        mname=$(mode_name "$md")
        if [ $((t - t_ev)) -ge 15 ] && [ "$stall_said" != "$seen" ]; then
            stall_said=$seen
            meaning "$last_code"
            say "VERDICT STALLED_AFTER_$last_code: $MEAN | GPM status $sname, connected mode $mname"
        fi
        if [ $((t - t_ev)) -ge 150 ]; then
            say "attempt over: the last step was $last_code, $((t - t_ev)) s ago; GPM status $sname, connected mode $mname"
            att=
            stall_said=
        fi
    elif [ -n "$att" ] && [ -n "$was_active" ]; then
        t_ev=$t   # a session is on: the stall rule is for hand-overs that never reach one
    fi
    if [ -n "$att" ]; then $NAP 2; else $NAP 3; fi
done
