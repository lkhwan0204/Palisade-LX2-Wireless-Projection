package hmgapi.mods.wireless.pp;

/**
 * Wireless Android Auto, PhoneProjection side: the stock needs the connectivity hotspot to be UP before it connects a phone, and nothing
 * on this unit starts it. Pure (no Android, no hooks): the decision whether to wait for the hotspot, and the wait itself.
 *
 * What the stock does (PhoneProjection 260902, read in the deodexed code):
 *  - GPM hands a phone over with requestWLDeviceReadyStatusUpdate(info, ready). For a phone whose first-use question was answered
 *    (InitPopup true) the stock asks WifiManager: AP enabled in mode 2 (connectivity) -> go on and connect; mode 1 (the user's hotspot)
 *    -> ask the driver on the screen; anything else (the AP is off) -> log "APMode.UNKNOWN" and END. Nothing starts the hotspot and nothing
 *    waits for it: PhoneProjection's own "hotspot is up, go on" step (onWiFiConnectivityAP) only works after the driver answered the hotspot
 *    popup, and even then it reads the phone it should continue with after clearing it.
 *  - The first-use popup's "yes" (a broadcast with initPopupDone) ends the same way when the hotspot is off.
 * So with the hotspot off, GPM stays INITIALIZE (it is only asked to connect after this step), the phone is never given the Wi-Fi
 * information and it waits for the car forever.
 *
 * What is done about it: when the stock is about to take that dead end, its call is held back (the caller returns at once), the connectivity
 * hotspot is started with the stock's own request (setWifiApEnabledForConnectivity, the Wi-Fi service's own configuration, checked first)
 * and the held call is then made exactly once, so the stock decides as it would have with the hotspot already up. Everything else stays the
 * stock's: the Android Auto switch, the phone's own on/off, the user's hotspot mode, the popups, auto-connect, driving restrictions.
 */
public final class ApReady {
    private ApReady() {}

    public static final int AP_DISABLING = 10, AP_DISABLED = 11, AP_ENABLING = 12, AP_ENABLED = 13, AP_FAILED = 14;
    public static final int MODE_HOTSPOT = 1, MODE_CONNECTIVITY = 2;
    public static final int TRANSFER_WIRELESS = 1;
    /** a second hold for the same phone inside this time is not made: one try per phone per minute */
    public static final long MIN_GAP_MS = 60000;
    /** how long the held call waits for the hotspot; after that it is made anyway and the stock decides as before */
    public static final long WAIT_MS = 20000;
    static final long POLL_MS = 250;

    /** what is known when the stock is about to decide (-1: could not be read) */
    public static final class Facts {
        public boolean popupDone;      // the call is the first-use popup's "yes" (initPopupDone), not a ready update
        public int ready;              // the ready flag of the update (ready update only)
        public int transfer;           // WLTransferType: 1 wireless
        public int apState = -1, apMode = -1;
        public int initPopup = -1;     // the phone's stored first-use flag: 1 answered, 0 not yet (ready update only)
        public int aaSwitch = -1;      // the Android Auto switch PhoneProjection reads for this user (androidauto_information*)
        public int phoneOn = -1;       // the phone's own on/off in PhoneProjection's list (ready update only)
        public long sinceLastTry = -1; // ms since the last hold for this phone, -1 never
    }

    public enum Verdict {
        HOLD("the hotspot is off and the stock would stop here: wait for the hotspot, then let the stock go on"),
        NOT_A_READY_UPDATE("the update says not ready"),
        NOT_WIRELESS("not a wireless phone"),
        AP_ALREADY_UP("the connectivity hotspot is up already"),
        AP_IN_OTHER_MODE("the hotspot is up in the user's mode: the stock asks the driver, left alone"),
        AP_STATE_UNKNOWN("the hotspot's state could not be read"),
        FIRST_USE_POPUP("the first-use question is not answered yet: the stock shows its popup"),
        INIT_UNKNOWN("the phone's first-use flag could not be read"),
        AA_SWITCH_OFF("the Android Auto switch is off for this user: the stock ignores wireless Android Auto, left alone"),
        AA_SWITCH_UNKNOWN("the Android Auto switch could not be read"),
        PHONE_OFF("the phone is switched off in PhoneProjection's list"),
        PHONE_UNKNOWN("the phone's entry could not be read"),
        TOO_SOON("the hotspot was already waited for, for this phone, within the last minute");

        public final String why;

        Verdict(String why) {
            this.why = why;
        }
    }

    /**
     * Hold the stock's call back and bring the hotspot up? Only when the stock would otherwise stop silently for want of it. Anything not
     * clearly in that situation, or that the stock itself refuses for another reason, is left to the stock: the verdict says which.
     */
    public static Verdict decide(Facts f) {
        if (!f.popupDone && f.ready != 1) return Verdict.NOT_A_READY_UPDATE;
        if (f.transfer != TRANSFER_WIRELESS) return Verdict.NOT_WIRELESS;
        if (f.apState < 0) return Verdict.AP_STATE_UNKNOWN;
        if (f.apState == AP_ENABLED) return f.apMode == MODE_CONNECTIVITY ? Verdict.AP_ALREADY_UP : Verdict.AP_IN_OTHER_MODE;
        // off, stopping, starting or failed: the stock would see "off" right now. Every check below is the stock's own refusal, kept as it is.
        if (!f.popupDone) {
            if (f.initPopup < 0) return Verdict.INIT_UNKNOWN;
            if (f.initPopup == 0) return Verdict.FIRST_USE_POPUP;
        }
        if (f.aaSwitch < 0) return Verdict.AA_SWITCH_UNKNOWN;
        if (f.aaSwitch == 0) return Verdict.AA_SWITCH_OFF;
        if (!f.popupDone) {
            if (f.phoneOn < 0) return Verdict.PHONE_UNKNOWN;
            if (f.phoneOn == 0) return Verdict.PHONE_OFF;
        }
        if (f.sinceLastTry >= 0 && f.sinceLastTry < MIN_GAP_MS) return Verdict.TOO_SOON;
        return Verdict.HOLD;
    }

    // ---- bringing the hotspot up ---------------------------------------------------------------------------------------------------

    public interface Platform {
        /** WIFI_AP_STATE_* (10..14), -1 unknown */
        int state();
        int mode();
        /** the Wi-Fi service's own AP configuration object (a copy), null if none */
        Object config();
        /** null when the service can store and start it; else field names only (never values) */
        String configProblem(Object cfg);
        /** setWifiApEnabledForConnectivity(cfg, true) with exactly this object; false when refused or thrown */
        boolean request(Object cfg);
        String requestNote();
        long now();
        void sleep(long ms);
    }

    public enum Result { READY, ALREADY_UP, OTHER_MODE, CONFIG_UNSAFE, REFUSED, FAILED, TIMEOUT }

    public static final class Outcome {
        public final Result result;
        public final String note;
        public final boolean requested;

        Outcome(Result r, String n, boolean requested) {
            this.result = r;
            this.note = n;
            this.requested = requested;
        }
    }

    /**
     * Start the connectivity hotspot unless it is up or coming up, and wait for it (enabled, mode 2). At most one request, with the
     * service's own checked configuration, never edited. A hotspot in another mode is not touched.
     */
    public static Outcome ensure(Platform p, long timeoutMs) {
        long t0 = p.now(), end = t0 + timeoutMs;
        boolean requested = false;
        int s = p.state();
        while (s == AP_DISABLING && p.now() < end) {   // let a stop finish first
            p.sleep(POLL_MS);
            s = p.state();
        }
        if (s == AP_ENABLED) {
            return p.mode() == MODE_CONNECTIVITY ? new Outcome(Result.ALREADY_UP, "up already", false)
                    : new Outcome(Result.OTHER_MODE, "up in mode " + p.mode() + ", not touched", false);
        }
        if (s != AP_ENABLING) {
            Object cfg = p.config();
            String bad = p.configProblem(cfg);
            if (bad != null) return new Outcome(Result.CONFIG_UNSAFE, "no request: the configuration is unusable (" + bad + ")", false);
            requested = true;
            if (!p.request(cfg)) return new Outcome(Result.REFUSED, "the request was refused (" + p.requestNote() + ")", true);
        }
        int wrongMode = -1;
        while (true) {
            s = p.state();
            if (s == AP_ENABLED) {
                int m = p.mode();
                if (m == MODE_CONNECTIVITY) return new Outcome(Result.READY, "enabled in mode 2 after " + (p.now() - t0) + " ms", requested);
                wrongMode = m;
            } else if (s == AP_FAILED) {
                return new Outcome(Result.FAILED, "the system reports the access point failed after " + (p.now() - t0) + " ms", requested);
            }
            if (p.now() >= end) {
                return new Outcome(Result.TIMEOUT, "not up in " + timeoutMs + " ms (state " + s + (wrongMode >= 0 ? ", enabled but in mode " + wrongMode : "") + ")", requested);
            }
            p.sleep(POLL_MS);
        }
    }

    /**
     * The same field checks the CarPlay side makes before it hands a configuration to the Wi-Fi service (the service's writer kills
     * system_server on a null field): names only.
     */
    public static String configProblem(boolean keyMgmtValid, int authType, String ssid, String preSharedKey, String connSsid, String connPsk, String vendorSpecific) {
        StringBuilder b = new StringBuilder();
        if (!keyMgmtValid) add(b, "allowedKeyManagement invalid");
        if (keyMgmtValid && authType < 0) add(b, "authType unreadable");
        if (ssid == null) add(b, "SSID null");
        else if (ssid.length() == 0) add(b, "SSID empty");
        if (connSsid == null) add(b, "ConnectivitySSID null");
        else if (connSsid.length() == 0) add(b, "ConnectivitySSID empty");
        if (vendorSpecific == null) add(b, "vendorSpecific null");
        if (authType > 0) {
            if (preSharedKey == null) add(b, "preSharedKey null");
            if (connPsk == null) add(b, "ConnectivityPSK null");
            else if (connPsk.length() == 0) add(b, "ConnectivityPSK empty");
        }
        return b.length() == 0 ? null : b.toString();
    }

    private static void add(StringBuilder b, String what) {
        if (b.length() > 0) b.append(", ");
        b.append(what);
    }
}
