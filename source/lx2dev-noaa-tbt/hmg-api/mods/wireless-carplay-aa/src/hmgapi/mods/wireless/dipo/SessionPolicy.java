package hmgapi.mods.wireless.dipo;

/**
 * The bridge's decisions that need no Android: what to do when the process starts (the phone's Bluetooth may be
 * up already, an earlier process may have left an iAP2 session behind), how the breadcrumb of the earlier process
 * reads, and when to stop retrying a CarPlay session that keeps failing. Kept pure so a host test covers them.
 */
final class SessionPolicy {
    private SessionPolicy() {}

    enum Resume { NOTHING, CONNECT_SPP, START_NOW }

    /** the earlier process got as far as asking the iAP2 server for a Bluetooth session (or further) */
    static boolean inSession(String stage) {
        return stage != null && (stage.startsWith("bt-attached") || stage.startsWith("iap2-connected") || stage.startsWith("cp-"));
    }

    /**
     * At process start. An iAP2 session the earlier process left in the iAP2 server (a separate, still running
     * process) would make the next BT_ATTACHED be refused: it is cleared whenever the earlier stage says a session
     * was under way, whether or not the phone's Bluetooth is still up.
     */
    static boolean detachOrphanAtStart(String prevStage) {
        return inSession(prevStage);
    }

    /** the phone's Bluetooth is up already: start over (SPP up) or connect SPP first (HFP only) */
    static Resume atStart(boolean applePhone, boolean sppUp) {
        if (!applePhone) return Resume.NOTHING;
        return sppUp ? Resume.START_NOW : Resume.CONNECT_SPP;
    }

    /**
     * One line, rewritten at every stage and every few seconds while a session is being set up: pid, the time of the stage mark,
     * the time of the last sign of life ("alive"), boot id, the BUILD that wrote it, the stage, and "exit" when the head unit said
     * it was shutting down. The build matters: a crumb left by another build (or by one that wrote no build at all) is not this
     * build's failure. Older crumbs have no alive/build/exit.
     */
    static final class Crumb {
        long pid = -1, t = -1, alive = -1;
        String boot = "?", stage = "", build = "", exit = "";

        static Crumb parse(String line) {
            if (line == null) return null;
            Crumb c = new Crumb();
            for (String w : line.trim().split(" ")) {
                int e = w.indexOf('=');
                if (e < 0) continue;
                String k = w.substring(0, e), v = w.substring(e + 1);
                try {
                    if (k.equals("pid")) c.pid = Long.parseLong(v);
                    else if (k.equals("t")) c.t = Long.parseLong(v);
                    else if (k.equals("alive")) c.alive = Long.parseLong(v);
                    else if (k.equals("boot")) c.boot = v;
                    else if (k.equals("stage")) c.stage = v;
                    else if (k.equals("build")) c.build = v;
                    else if (k.equals("exit")) c.exit = v;
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
            return c.pid < 0 ? null : c;
        }

        String line() {
            return "pid=" + pid + " t=" + t + " alive=" + alive + " boot=" + boot + " build=" + build + " stage=" + stage
                    + (exit.length() > 0 ? " exit=" + exit : "") + "\n";
        }

        /** both boot ids known: only then can "the head unit restarted" be told from "the process died" */
        static boolean bootKnown(String a, String b) {
            return a != null && b != null && !a.equals("?") && !b.equals("?");
        }

        String describe(String bootNow, long nowMs) {
            String head = "pid=" + pid + " stage=" + stage + " build=" + (build.length() == 0 ? "(none recorded)" : build)
                    + (alive >= 0 && t >= 0 && alive >= t ? " lived " + (alive - t) + " ms after the stage mark" : "")
                    + (exit.length() > 0 ? " exit=" + exit : "");
            if (!bootKnown(boot, bootNow)) return head + " (boot id unreadable: a head-unit restart cannot be told from a process death)";
            if (boot.equals(bootNow)) return head + (t >= 0 ? " (" + (nowMs - t) + " ms ago, same boot: only this app's process restarted)" : " (same boot)");
            return head + " (a different boot: the head unit restarted since)";
        }
    }

    /**
     * What the earlier process's last crumb means for THIS build.
     * NONE: no crumb. NOT_SETUP: it was not setting a session up. FOREIGN_BUILD: it was, but another build (or an unknown one)
     * wrote it: not this build's failure, not counted. CLEAN_EXIT: it was, but the head unit announced a shutdown or reboot
     * first (a user restart or power-off is not a crash). INTERRUPTED: this build was setting a session up and the process
     * ended with no such announcement: counted.
     */
    enum End { NONE, NOT_SETUP, FOREIGN_BUILD, CLEAN_EXIT, INTERRUPTED }

    static End classifyEnd(Crumb c, String build) {
        if (c == null) return End.NONE;
        if (!setupStage(c.stage)) return End.NOT_SETUP;
        if (c.build.length() == 0 || !c.build.equals(build)) return End.FOREIGN_BUILD;
        if (c.exit.length() > 0) return End.CLEAN_EXIT;
        return End.INTERRUPTED;
    }

    /**
     * A CarPlay session that ends before it ever ran over Wi-Fi is a failure. Two failures within WINDOW_MS stop the
     * automatic restarts for PAUSE_MS (the phone reconnecting later, or the pause ending, allows the next try); one
     * session that did run clears the count. No reboot, no forced state: only "don't start again right now".
     */
    static final class RetryGate {
        static final long WINDOW_MS = 120000, PAUSE_MS = 120000;
        static final int MAX_FAILS = 2;
        private int fails;
        private long firstFailAt;
        private long blockedUntil;

        synchronized void failure(long now) {
            if (fails == 0 || now - firstFailAt > WINDOW_MS) {
                fails = 0;
                firstFailAt = now;
            }
            fails++;
            if (fails >= MAX_FAILS) blockedUntil = now + PAUSE_MS;
        }

        synchronized void success() {
            fails = 0;
            blockedUntil = 0;
        }

        synchronized boolean blocked(long now) {
            return now < blockedUntil;
        }

        synchronized int fails() {
            return fails;
        }
    }

    /**
     * Whether an AP configuration may be handed to the Wi-Fi service. Every time an AP is started with a configuration object,
     * the stock SoftApStartingState sends that same object to WifiApConfigStore, which writes it to softap.conf in a plain thread
     * of system_server (writeApConfiguration). That writer calls DataOutputStream.writeUTF on SSID, ConnectivitySSID and
     * vendorSpecific always, and on preSharedKey and ConnectivityPSK when getAuthType() is not 0 (open). writeUTF(null) throws a
     * NullPointerException nothing catches: system_server dies (the 01:51:10 and 01:52:33 crashes on the car, writeApConfiguration
     * line 345 = ConnectivitySSID). getAuthType() itself throws when allowedKeyManagement is not valid.
     *
     * The answer is a list of field NAMES, never values. null: nothing found that would break the writer or make the AP useless.
     * An empty SSID or ConnectivitySSID would not crash anything but no phone could be told to join it, so it counts too.
     */
    static final class ApConfigCheck {
        private ApConfigCheck() {}

        /**
         * @param keyMgmtValid WifiConfiguration.isValid() (the combination of allowedKeyManagement bits the service accepts)
         * @param authType     WifiConfiguration.getAuthType(), or -1 when it could not be read
         */
        static String problem(boolean keyMgmtValid, int authType, String ssid, String preSharedKey, String connSsid, String connPsk, String vendorSpecific) {
            StringBuilder b = new StringBuilder();
            if (!keyMgmtValid) add(b, "allowedKeyManagement invalid");
            if (keyMgmtValid && authType < 0) add(b, "authType unreadable");
            if (ssid == null) add(b, "SSID null");
            else if (ssid.length() == 0) add(b, "SSID empty");
            if (connSsid == null) add(b, "ConnectivitySSID null");
            else if (connSsid.length() == 0) add(b, "ConnectivitySSID empty");
            if (vendorSpecific == null) add(b, "vendorSpecific null");
            if (authType > 0) {   // a protected AP: the writer also writes both keys (an invalid or unreadable auth type is already reported)
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

    /**
     * Stages a wireless session passes through before CarPlay itself runs over Wi-Fi. A process that ends while its last
     * stage is one of these did not finish setting the session up: the head unit may have restarted around it.
     * "setup-stalled" (set 60 s after BT_ATTACHED when nothing moved) and the normal end stages are NOT setup stages, so
     * an idle phone, a user disconnect or switching the car off later is not mistaken for a crash.
     */
    static boolean setupStage(String stage) {
        return "ap-wait".equals(stage) || "ap-ready".equals(stage) || "bt-attached".equals(stage)
                || "iap2-connected-4".equals(stage) || "cp-created".equals(stage);
    }

    /**
     * The mod's own safe mode, per build, with states: OPEN (nothing on record) -> PROBATION (one interrupted setup of THIS build
     * on record: sessions are allowed, as one trial) -> HOLD (a second one: wireless sessions are held back so that a deterministic
     * crash is not repeated at every connection; each repeat is a head-unit restart, and HMG-API's boot guard turns the hooks off
     * after three). Back to OPEN: a trial session that runs over Wi-Fi, or that outlives its setup window; a new build; or an
     * explicit re-arm (rearmRequested). Nothing reboots anything and no limit is raised.
     */
    static final class Quarantine {
        static final int HOLD_AT = 2;
        enum Gate { OPEN, PROBATION, HOLD }

        final String build, stage;
        final int crashes;
        final long at;   // wall clock of the last count, 0 when unknown

        Quarantine(String build, int crashes, String stage, long at) {
            this.build = build;
            this.crashes = crashes;
            this.stage = stage;
            this.at = at;
        }

        String serialize() {
            return "v=2\nbuild=" + build + "\ncrashes=" + crashes + "\nstage=" + stage + "\nat=" + at + "\n";
        }

        /** v=1 (lx2dev.4/5) and v=2 */
        static Quarantine parse(String s) {
            if (s == null || !(s.startsWith("v=1\n") || s.startsWith("v=2\n"))) return null;
            String build = null, stage = "";
            int crashes = -1;
            long at = 0;
            for (String l : s.split("\n")) {
                int e = l.indexOf('=');
                if (e < 0) continue;
                String k = l.substring(0, e), v = l.substring(e + 1);
                if (k.equals("build")) build = v;
                else if (k.equals("stage")) stage = v;
                else if (k.equals("crashes")) {
                    try { crashes = Integer.parseInt(v); } catch (NumberFormatException x) { return null; }
                } else if (k.equals("at")) {
                    try { at = Long.parseLong(v); } catch (NumberFormatException x) { at = 0; }
                }
            }
            return build == null || crashes < 0 ? null : new Quarantine(build, crashes, stage, at);
        }

        /** one more interrupted setup of this build (records of other builds start from 1) */
        static Quarantine counted(Quarantine q, String build, String stage, long at) {
            int n = q != null && build.equals(q.build) ? q.crashes + 1 : 1;
            return new Quarantine(build, n, stage, at);
        }

        static Gate gate(Quarantine q, String currentBuild) {
            if (q == null || q.crashes < 1 || !q.build.equals(currentBuild)) return Gate.OPEN;
            return q.crashes >= HOLD_AT ? Gate.HOLD : Gate.PROBATION;
        }

        static boolean blocks(Quarantine q, String currentBuild) {
            return gate(q, currentBuild) == Gate.HOLD;
        }
    }

    /**
     * The explicit way out of a HOLD without a new build: the property persist.hmgapi.cpwl.rearm. A value that is not empty/"0"
     * and differs from the last one used re-arms once; empty or "0" forgets the last one so the same value can be used again.
     */
    static boolean rearmRequested(String prop, String lastUsed) {
        if (prop == null) return false;
        String p = prop.trim();
        if (p.length() == 0 || p.equals("0")) return false;
        return !p.equals(lastUsed == null ? "" : lastUsed.trim());
    }

    static boolean rearmForget(String prop) {
        return prop == null || prop.trim().length() == 0 || prop.trim().equals("0");
    }

    /**
     * DiPO's CarPlayReturn (the int the native CarPlay library gives back, e.g. for TccCarPlayNativeMethod_iAPSendMessage), by value.
     * 11 is INVALID_SESSION: the native CarPlay session the call refers to does not exist (any more, or yet). It is not a full buffer
     * and not a Wi-Fi error: it says the session handle is gone.
     */
    static String carPlayReturn(int rc) {
        String[] n = { "OK", "ERROR", "ALREADY_IN_USE", "BUFFER_OVER_RUN", "BUFFER_UNDER_RUN", "CREATE_SCREEN_FAILED", "CREATE_THREAD_FAILED",
            "DEFINES_VERSION_MISMATCHED", "HEADER_VERSION_MISMATCHED", "INVALID_ARGUMENT", "INVALID_HANDLE", "INVALID_SESSION", "IS_NOT_STARTED",
            "IS_NOT_TCC", "NOT_ENOUGH_MEMORY", "NOT_FOUND", "NOT_IN_USE", "NOT_INITIALIZED", "NOT_READY", "NOT_SUPPORTED", "UNKNOWN_ERROR" };
        return rc >= 0 && rc < n.length ? n[rc] : "unknown(" + rc + ")";
    }

    /** the native session is not there: a send after this cannot work (INVALID_HANDLE, INVALID_SESSION, IS_NOT_STARTED, NOT_INITIALIZED) */
    static boolean sessionGone(int rc) {
        return rc == 10 || rc == 11 || rc == 12 || rc == 17;
    }

    /**
     * "The native CarPlay session is gone although DiPO has not said so": this many sends in a row failed with a session-gone code within
     * WINDOW_MS. One is a race at the end of a session; three over two seconds is a session that is not coming back.
     */
    static final class LostSession {
        static final int NEEDED = 3;
        static final long WINDOW_MS = 5000, MIN_SPAN_MS = 1500;
        private final long[] at = new long[NEEDED];
        private int n;

        synchronized boolean failure(long now, int rc) {
            if (!sessionGone(rc)) return false;
            if (n > 0 && now - at[n - 1] > WINDOW_MS) n = 0;
            if (n < NEEDED) at[n++] = now;
            else {
                System.arraycopy(at, 1, at, 0, NEEDED - 1);
                at[NEEDED - 1] = now;
            }
            return n >= NEEDED && at[NEEDED - 1] - at[0] >= MIN_SPAN_MS;
        }

        synchronized void reset() {
            n = 0;
        }
    }

    /**
     * The two credential pairs of the stock AP configuration, and why a phone told to join cannot find the AP when they differ.
     * The AP itself (NetworkManagementService.startAccessPoint -> hostapd) uses SSID and preSharedKey. What the iPhone is told over
     * iAP2 (Iap2Service.OnSendWifiInformation, also Android Auto's WirelessExplorer) is ConnectivitySSID and ConnectivityPSK. Nothing in
     * the stock framework makes them equal, and the defaults are generated independently (setDefaultApConfiguration: SSID
     * "Hyundai_Hotspot_" + 4 characters, ConnectivitySSID "Hyundai_" + 4 characters, a 10 and a 12 character key). Names and
     * lengths only, never a value.
     */
    static final class CredPair {
        /** null when the hotspot pair can be used as the phone's pair; else why not (no values) */
        String problem;
        boolean ssidDiffers, pskDiffers;

        boolean equal() {
            return problem == null && !ssidDiffers && !pskDiffers;
        }

        /** the Connectivity fields that differ from the hotspot's own, as field names */
        String differing() {
            StringBuilder b = new StringBuilder();
            if (ssidDiffers) b.append("ConnectivitySSID");
            if (pskDiffers) b.append(b.length() > 0 ? ", " : "").append("ConnectivityPSK");
            return b.toString();
        }

        /** authType: WifiConfiguration.getAuthType() (0 open, else a PSK network), -1 unreadable */
        static CredPair of(int authType, String ssid, String psk, String connSsid, String connPsk) {
            CredPair c = new CredPair();
            if (authType < 0) {
                c.problem = "authType unreadable";
                return c;
            }
            if (ssid == null || ssid.length() == 0) {
                c.problem = "SSID missing";
                return c;
            }
            try {
                if (ssid.getBytes("UTF-8").length > 32) {
                    c.problem = "SSID longer than 32 bytes";
                    return c;
                }
            } catch (java.io.UnsupportedEncodingException e) {
                c.problem = "SSID not encodable";
                return c;
            }
            if (authType > 0) {   // a WPA/WPA2 passphrase: 8..63 printable ASCII characters
                if (psk == null) {
                    c.problem = "preSharedKey missing";
                    return c;
                }
                if (psk.length() < 8 || psk.length() > 63) {
                    c.problem = "preSharedKey is " + psk.length() + " characters, a WPA passphrase needs 8 to 63";
                    return c;
                }
                for (int i = 0; i < psk.length(); i++) {
                    char ch = psk.charAt(i);
                    if (ch < 0x20 || ch > 0x7e) {
                        c.problem = "preSharedKey has a character outside printable ASCII";
                        return c;
                    }
                }
            }
            c.ssidDiffers = !ssid.equals(connSsid);
            c.pskDiffers = authType > 0 && !psk.equals(connPsk);
            return c;
        }
    }

    /**
     * An SSID for the log: the hotspot's default names are random public tokens ("Hyundai_Hotspot_5szR": brand, "_Hotspot_" or "_",
     * four characters), so those are shown whole and the user can compare them with the phone's Wi-Fi list. Any other name may be
     * something personal: only its length.
     */
    static String shownSsid(String ssid) {
        if (ssid == null) return "null";
        if (ssid.matches("^(Hyundai|Kia|Genesis|DAUDIO)(_Hotspot)?_[A-Za-z0-9]{4}$")) return ssid;
        return "(" + ssid.length() + " chars)";
    }

    /**
     * Hotspot failures in this process. A failure that more tries cannot fix (an unusable AP configuration, no hotspot API, no sign
     * of a WLAN device at all) latches at once; any other failure latches at the second one. A latched hotspot is not requested
     * again until the process restarts: the earlier behaviour (a retry every 60 s for as long as the phone kept connecting) is gone.
     */
    static final class ApLatch {
        static final int SOFT_LIMIT = 2;
        private int fails;
        private boolean hard;
        private String why = "";

        /**
         * Whether more tries cannot help: an unusable configuration, credentials that cannot be made one pair, no hotspot API, or a request that was made and failed while
         * every source showed no sign of a WLAN device. A CONFLICT (someone else's AP is up) or an abort is never hard.
         */
        static boolean hardFailure(String result, String verdict) {
            if ("CONFIG_UNSAFE".equals(result) || "UNSUPPORTED".equals(result) || "PAIR_MISMATCH".equals(result)) return true;
            boolean requested = "REQUEST_REFUSED".equals(result) || "FAILED".equals(result) || "TIMEOUT".equals(result);
            return requested && "NO_EVIDENCE".equals(verdict);
        }

        synchronized void failure(String why, boolean hard) {
            fails++;
            this.why = why;
            if (hard) this.hard = true;
        }

        synchronized void success() {
            fails = 0;
            hard = false;
            why = "";
        }

        synchronized boolean latched() {
            return hard || fails >= SOFT_LIMIT;
        }

        synchronized String why() {
            return why;
        }

        synchronized int fails() {
            return fails;
        }
    }
}
