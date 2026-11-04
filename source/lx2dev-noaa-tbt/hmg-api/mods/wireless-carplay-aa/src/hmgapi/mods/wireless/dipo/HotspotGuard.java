package hmgapi.mods.wireless.dipo;

/**
 * The head unit's hotspot for wireless CarPlay: ask for it, wait until it is really up, and put back
 * exactly what the request changed. No Android types here (the Android side is AndroidHotspot), so the
 * rules run on a host JVM.
 *
 * - An AP that is already up in mode 2 is used and never stopped or touched. An AP up in another mode
 *   belongs to someone else: CONFLICT, nothing is requested.
 * - The AP configuration is the Wi-Fi service's own object and is never rebuilt, edited or written back here. Before anything
 *   is requested it is read from the service and checked (Platform.configProblem): the stock AP start persists the object it is
 *   given, and a null in it kills system_server (the LX2 crashes of 11-04 01:51 and 01:52). An unusable configuration means
 *   CONFIG_UNSAFE: nothing is requested, journalled or changed.
 * - Before the request, the original state is written to a journal: the wifi_ap_on setting (its value
 *   AND whether it existed at all) and whether the Wi-Fi client was on.
 * - READY only when the AP state is ENABLED and its mode is 2. A refused request, a FAILED state, the
 *   timeout, or an abort (USB took over) restores the original state and says why.
 * - restore() stops the AP only if this guard started it, puts wifi_ap_on back (deleting the row when it
 *   did not exist; the stock service writes 0 on stop), checks it again after the service's own late
 *   writes and turns the Wi-Fi client back on. The stop request carries no configuration object. If something can't be put back
 *   exactly the journal stays (with a try count) and the result says so; nothing claims an exact restore it didn't see.
 * - recover(): after a crash or restart, the journal restores what an earlier run left, unless a session is up.
 */
final class HotspotGuard {
    static final int AP_DISABLING = 10, AP_DISABLED = 11, AP_ENABLING = 12, AP_ENABLED = 13, AP_FAILED = 14;
    static final int MODE_CONNECTIVITY = 2;
    static final int MAX_TRIES = 5;

    enum Result { READY, ALREADY_UP, CONFLICT, CONFIG_UNSAFE, PAIR_MISMATCH, UNSUPPORTED, REQUEST_REFUSED, FAILED, TIMEOUT, ABORTED }

    interface Platform {
        /** WIFI_AP_STATE_* (10..14), -1 when unknown */
        int apState();
        /** the AP's mode, -1 when unknown */
        int apMode();
        /** the Wi-Fi service's AP configuration as the service returns it (opaque, a fresh copy each call), null if none */
        Object apConfig();
        /** null when the service can store and start this configuration; else the names of the fields that make it unusable */
        String configProblem(Object config);
        /** the user-visible fields (credentials, key management, Connectivity pair, carplayIE, vendorSpecific) that differ; "" if none. Channels and apMode are the service's own bookkeeping and are not compared */
        String configDiff(Object a, Object b);
        /** one line about a configuration for the logs: lengths and yes/no only, never a name or a key */
        String describeConfig(Object config);
        /** on: start with exactly this object (already checked, unmodified). off: config is null; a stop never needs one */
        boolean requestAp(Object config, boolean on);
        /** how the last requestAp() went, for the log: the return value or the exception, and the time it took. No names, keys or addresses */
        String requestNote();
        /**
         * Log what can be seen of the Wi-Fi hardware, driver and framework (read only; full = also the facts that do not change).
         * Returns the verdict: HARDWARE_SEEN, NO_EVIDENCE, UNKNOWN or API_MISSING.
         */
        String diagnose(String when, boolean full);
        /** the two credential pairs of this configuration compared (names and lengths only, see SessionPolicy.CredPair) */
        SessionPolicy.CredPair credPair(Object config);
        /**
         * Make the pair the phone is told (ConnectivitySSID/PSK) equal to the pair the AP broadcasts (SSID/preSharedKey): only those two
         * fields are set, on this copy (the service's own, already checked), and the copy is stored with setWifiApConfiguration. False
         * when the service refused or threw (requestNote says how).
         */
        boolean storePair(Object config);
        /** persist.hmgapi.cpwl.align is not "0" */
        boolean alignOn();
        /** log and return what the running AP looks like (processes, hostapd.conf against the service's configuration, the interface) */
        ApAudit.Facts audit(String when);
        boolean staEnabled();
        boolean setSta(boolean on);
        /** the raw wifi_ap_on value, null when the setting does not exist */
        String setting();
        boolean putSetting(String value);
        boolean deleteSetting();
        long now();
        void sleep(long ms);
    }

    interface Journal {
        void save(Baseline b);
        Baseline load();
        void clear();
    }

    interface Abort { boolean aborted(); }

    interface Log { void log(String m); }

    /** what was there before the request */
    static final class Baseline {
        boolean settingPresent, staOn, requested;
        String settingValue;
        int tries;
        Object config;   // in memory only: never written to the journal (it holds the hotspot key); only used to report what changed

        String serialize() {
            return "v=1\npresent=" + settingPresent + "\nvalue=" + (settingValue == null ? "" : settingValue) + "\nsta=" + staOn
                    + "\nrequested=" + requested + "\ntries=" + tries + "\n";
        }

        static Baseline parse(String s) {
            if (s == null || !s.startsWith("v=1\n")) return null;
            Baseline b = new Baseline();
            for (String l : s.split("\n")) {
                int e = l.indexOf('=');
                if (e < 0) continue;
                String k = l.substring(0, e), v = l.substring(e + 1);
                if (k.equals("present")) b.settingPresent = v.equals("true");
                else if (k.equals("value")) b.settingValue = v;
                else if (k.equals("sta")) b.staOn = v.equals("true");
                else if (k.equals("requested")) b.requested = v.equals("true");
                else if (k.equals("tries")) {
                    try { b.tries = Integer.parseInt(v); } catch (NumberFormatException ignored) { b.tries = MAX_TRIES; }
                }
            }
            return b;
        }
    }

    static final long POLL_MS = 400, SETTLE_MS = 600, STOP_WAIT_MS = 8000;

    private final Platform p;
    private final Journal j;
    private final Log log;
    private final Object lock = new Object();
    private Baseline owned;   // set while this guard started the AP and has not put everything back
    private String lastWhy = "";       // the short reason of the last acquire that did not end READY/ALREADY_UP
    private String lastVerdict = "UNKNOWN";   // the last Platform.diagnose() verdict

    HotspotGuard(Platform p, Journal j, Log log) {
        this.p = p;
        this.j = j;
        this.log = log;
    }

    boolean owns() {
        synchronized (lock) { return owned != null; }
    }

    /** why the last acquire failed, in a few words ("" after a success) */
    String why() {
        synchronized (lock) { return lastWhy; }
    }

    /** the last hardware verdict (HARDWARE_SEEN, NO_EVIDENCE, UNKNOWN, API_MISSING) */
    String verdict() {
        synchronized (lock) { return lastVerdict; }
    }

    /**
     * Make the pair the phone is told equal to the hotspot's own now, without starting or stopping anything. Android Auto's wireless
     * hand-over reads the same two fields (GPM WirelessExplorer.getWiFiInfo) and starts the hotspot by itself (PhoneProjection), so the
     * pair has to be right before any session of either kind. Same checks and the same single write as at a CarPlay start.
     */
    String keepPairEqual(String when) {
        synchronized (lock) {
            if (owned != null) return "skipped, a session owns the hotspot";
            Object cfg = p.apConfig();
            String bad = cfg == null ? "the Wi-Fi service returned no AP configuration" : p.configProblem(cfg);
            if (bad != null) {
                log.log("hotspot: [" + when + "] credentials not touched, the AP configuration is unusable (" + bad + ")");
                return "unusable: " + bad;
            }
            if (ensurePair(cfg) == null) return "failed: " + lastWhy;
            return "ok";
        }
    }

    /** the access point audit now (read only) */
    ApAudit.Facts audit(String when) {
        synchronized (lock) { return p.audit(when); }
    }

    /** log the Wi-Fi facts now (read only) and remember the verdict */
    String diagnose(String when, boolean full) {
        synchronized (lock) {
            String v = p.diagnose(when, full);
            if (v != null) lastVerdict = v;
            return lastVerdict;
        }
    }

    Result acquire(long timeoutMs, Abort abort) {
        synchronized (lock) {
            lastWhy = "";
            long deadline = p.now() + timeoutMs;
            int st = p.apState();
            if (st == AP_ENABLING || st == AP_DISABLING) {   // someone else is switching it: wait for it to settle
                st = waitStable(deadline, abort);
                if (st == -2) return fail(Result.ABORTED, "aborted");
            }
            if (st == AP_ENABLED) {
                int mode = p.apMode();
                if (mode == MODE_CONNECTIVITY) {
                    // an AP is up already: it is used as it is, but the phone is still told the Connectivity pair, which must be its own
                    Object up = p.apConfig();
                    String upBad = up == null ? "the Wi-Fi service returned no AP configuration" : p.configProblem(up);
                    if (upBad != null) {
                        log.log("hotspot: the AP is up but its configuration is unusable (" + upBad + "): " + p.describeConfig(up));
                        return fail(Result.CONFIG_UNSAFE, "AP up, configuration unusable: " + upBad);
                    }
                    if (ensurePair(up) == null) return fail(Result.PAIR_MISMATCH, lastWhy);
                    return owned != null ? Result.READY : Result.ALREADY_UP;
                }
                log.log("hotspot: AP is up in mode " + mode + ", not ours; not touching it");
                return fail(Result.CONFLICT, "AP up in mode " + mode);
            }
            String v = p.diagnose("before the AP request", false);
            if (v != null) lastVerdict = v;
            if ("API_MISSING".equals(lastVerdict)) {
                // nothing to call: the framework has no (usable) hidden hotspot method; no request, no journal, nothing changed
                log.log("hotspot: NOT started, this framework has no hotspot API the mod can use (see the wifi lines)");
                return fail(Result.UNSUPPORTED, "no hotspot API");
            }
            Object cfg = p.apConfig();
            String bad = cfg == null ? "the Wi-Fi service returned no AP configuration" : p.configProblem(cfg);
            if (bad != null) {
                // nothing is filled in, repaired or written: the service would store whatever it is given and may die on a null
                log.log("hotspot: NOT started, the AP configuration is unusable (" + bad + "): " + p.describeConfig(cfg));
                if (owned != null) restore(owned);   // an adopted earlier run: put its state back, the AP is not up
                return fail(Result.CONFIG_UNSAFE, "AP configuration unusable: " + bad);
            }
            Object paired = ensurePair(cfg);   // the pair the phone is told becomes the pair the AP broadcasts; null: cannot
            if (paired == null) {
                if (owned != null) restore(owned);
                return fail(Result.PAIR_MISMATCH, lastWhy);
            }
            cfg = paired;
            Baseline b = owned != null ? owned : snapshot(cfg);
            log.log("hotspot: configuration as found: " + p.describeConfig(cfg));
            b.requested = true;
            owned = b;
            j.save(b);   // before the request: a crash after it can still be put back
            long t0 = p.now();
            StringBuilder tl = new StringBuilder();
            int lastState = Integer.MIN_VALUE;
            boolean accepted = p.requestAp(cfg, true);   // the very object that was checked, unmodified
            log.log("hotspot: request setWifiApEnabledForConnectivity: " + p.requestNote());
            if (!accepted) {
                log.log("hotspot: the request was refused");
                diagnoseFailure("request refused");
                restore(b);
                return fail(Result.REQUEST_REFUSED, "request refused or threw (" + p.requestNote() + ")");
            }
            while (true) {
                int s = p.apState();
                if (s != lastState) {   // the state timeline: what the system did and when
                    tl.append(tl.length() > 0 ? " " : "").append(s).append('@').append(p.now() - t0).append("ms");
                    lastState = s;
                }
                if (abort != null && abort.aborted()) {
                    log.log("hotspot: aborted while waiting (AP states " + tl + ")");
                    restore(b);
                    return fail(Result.ABORTED, "aborted");
                }
                if (s == AP_ENABLED && p.apMode() == MODE_CONNECTIVITY) {
                    Object now = p.apConfig();
                    log.log("hotspot: ready (mode 2) after " + (p.now() - t0) + " ms; AP states " + tl + "; configuration now: " + p.describeConfig(now)
                            + "; changed by the start: " + changed(cfg, now));
                    p.diagnose("hotspot ready", false);
                    return Result.READY;
                }
                if (s == AP_FAILED) {
                    log.log("hotspot: the system reports the AP failed (AP states " + tl + ")");
                    diagnoseFailure("AP state FAILED");
                    restore(b);
                    return fail(Result.FAILED, "AP state FAILED(14) after " + (p.now() - t0) + " ms");
                }
                if (p.now() >= deadline) {
                    log.log("hotspot: not ready in " + timeoutMs + " ms (state " + s + ", mode " + p.apMode() + "; AP states " + tl + ")");
                    diagnoseFailure("timeout");
                    restore(b);
                    return fail(Result.TIMEOUT, "timeout in state " + s + (s == AP_ENABLING ? " (stuck enabling)" : s == AP_DISABLED ? " (the service never started it)" : ""));
                }
                p.sleep(POLL_MS);
            }
        }
    }

    /**
     * The AP broadcasts SSID/preSharedKey (NetworkManagementService.startAccessPoint); the phone is told ConnectivitySSID/ConnectivityPSK
     * (Iap2Service.OnSendWifiInformation). The stock never makes them equal and generates them independently, so a phone told to join
     * "Hyundai_xxxx" while the AP is "Hyundai_Hotspot_xxxx" can never find it. With alignment on, the Connectivity pair is made equal to
     * the hotspot's own pair (only those two fields, on the service's own checked copy; the hotspot's name, key, channel and security stay),
     * stored, read back and checked again; the copy that is read back is what the AP is started with. Returns the configuration to use,
     * or null with lastWhy set.
     */
    private Object ensurePair(Object cfg) {
        SessionPolicy.CredPair cp = p.credPair(cfg);
        if (cp.problem != null) {
            log.log("hotspot: credentials: the hotspot's own name and key cannot be given to a phone (" + cp.problem + ")");
            if (!p.alignOn()) return cfg;
            lastWhy = "hotspot credentials unusable for the phone: " + cp.problem;
            return null;
        }
        if (cp.equal()) {
            log.log("hotspot: credentials: the phone is told the hotspot's own name and key (the two pairs are equal)");
            return cfg;
        }
        if (!p.alignOn()) {
            log.log("hotspot: credentials DIFFER (" + cp.differing() + "): the AP broadcasts one pair, the phone is told the other. Not changed (persist.hmgapi.cpwl.align=0)");
            return cfg;
        }
        log.log("hotspot: credentials DIFFER (" + cp.differing() + "): the AP broadcasts one pair, the phone is told the other. Making the phone's pair equal to the hotspot's (only those fields)");
        if (!p.storePair(cfg)) {
            log.log("hotspot: the Wi-Fi service did not take the configuration (" + p.requestNote() + ")");
            lastWhy = "could not store the aligned credentials (" + p.requestNote() + ")";
            return null;
        }
        Object back = p.apConfig();
        String bad = back == null ? "no configuration read back" : p.configProblem(back);
        SessionPolicy.CredPair after = back == null ? null : p.credPair(back);
        if (bad != null || after == null || !after.equal()) {
            log.log("hotspot: credentials still differ after storing (" + (bad != null ? bad : after == null ? "unreadable" : after.problem != null ? after.problem : after.differing()) + ")");
            lastWhy = "the aligned credentials were not stored (read back: " + (bad != null ? bad : after == null ? "unreadable" : after.problem != null ? after.problem : after.differing()) + ")";
            return null;
        }
        log.log("hotspot: credentials: now equal (changed: " + cp.differing() + "; the hotspot's name, key, channel and security were not touched); read back: " + p.describeConfig(back));
        return back;
    }

    private Result fail(Result r, String why) {
        lastWhy = why;
        return r;
    }

    /** after a failed start the facts are read again in full: they show what the driver/interface looked like at the failure */
    private void diagnoseFailure(String what) {
        String v = p.diagnose("after the AP failure (" + what + ")", true);
        if (v != null) lastVerdict = v;
    }

    /** put back what acquire changed; true only if everything was seen to be back */
    boolean release(String why) {
        synchronized (lock) {
            if (owned == null) return true;
            log.log("hotspot: releasing (" + why + ")");
            return restore(owned);
        }
    }

    /**
     * after a restart of the process that owned the hotspot. sessionActive: this process already runs a session (skip).
     * resumable: the phone's Bluetooth link is still up, so the new process will start the session again: the earlier
     * run's baseline is adopted (the AP is NOT stopped; the release at the end of that session puts everything back).
     * Otherwise what the earlier run left is put back now. null: nothing to do or skipped.
     */
    Boolean recover(boolean sessionActive, boolean resumable) {
        synchronized (lock) {
            if (owned != null) return null;
            Baseline b = j.load();
            if (b == null) return null;
            if (sessionActive) {
                log.log("hotspot: an earlier run's journal is kept: a session is up");
                return null;
            }
            if (b.tries >= MAX_TRIES) {
                log.log("hotspot: giving up on an earlier run's journal after " + b.tries + " tries; wifi_ap_on and the AP may differ from before");
                j.clear();
                return false;
            }
            if (resumable) {
                owned = b;
                log.log("hotspot: an earlier run's hotspot is kept and adopted: the phone's Bluetooth is still connected (journal: wifi_ap_on "
                        + (b.settingPresent ? "was " + b.settingValue : "was absent") + ", Wi-Fi client was " + (b.staOn ? "on" : "off") + ")");
                return null;
            }
            log.log("hotspot: putting back what an earlier run left");
            return restore(b);
        }
    }

    /** one line of state for the logs: no names, keys or addresses */
    String describe() {
        synchronized (lock) {
            String s = p.setting();
            return "ap state=" + p.apState() + " mode=" + p.apMode() + " wifi_ap_on=" + (s == null ? "absent" : s) + " wifiClient=" + p.staEnabled()
                    + " owned=" + (owned != null);
        }
    }

    private Baseline snapshot(Object cfg) {
        Baseline b = new Baseline();
        b.settingValue = p.setting();
        b.settingPresent = b.settingValue != null;
        b.staOn = p.staEnabled();
        b.config = cfg;
        return b;
    }

    private String changed(Object before, Object after) {
        if (after == null) return "unreadable";
        String d = p.configDiff(before, after);
        return d.length() == 0 ? "nothing" : d;
    }

    /** the state once it is no longer ENABLING/DISABLING; -2 aborted, or the last state at the deadline */
    private int waitStable(long deadline, Abort abort) {
        int s = p.apState();
        while (s == AP_ENABLING || s == AP_DISABLING) {
            if (abort != null && abort.aborted()) return -2;
            if (p.now() >= deadline) return s;
            p.sleep(POLL_MS);
            s = p.apState();
        }
        return s;
    }

    private boolean sameSetting(String raw, Baseline b) {
        return b.settingPresent ? b.settingValue.equals(raw) : raw == null;
    }

    private boolean restore(Baseline b) {
        boolean ok = true;
        int st = p.apState();
        if (b.requested && (st == AP_ENABLED || st == AP_ENABLING)) {
            p.requestAp(null, false);
            long end = p.now() + STOP_WAIT_MS;
            while (p.apState() != AP_DISABLED && p.now() < end) p.sleep(POLL_MS);
            if (p.apState() != AP_DISABLED) {
                log.log("hotspot: the AP did not stop (state " + p.apState() + ")");
                ok = false;
            }
        }
        p.sleep(SETTLE_MS);   // the service writes wifi_ap_on on its own, after the state changes
        boolean exact = false;
        for (int i = 0; i < 4 && !exact; i++) {
            if (!sameSetting(p.setting(), b)) {
                if (b.settingPresent) p.putSetting(b.settingValue);
                else p.deleteSetting();
            }
            p.sleep(SETTLE_MS / 2);
            exact = sameSetting(p.setting(), b);
            if (exact) {   // once more after a pause: a late write by the service would undo it
                p.sleep(SETTLE_MS / 2);
                exact = sameSetting(p.setting(), b);
            }
        }
        if (!exact) {
            log.log("hotspot: wifi_ap_on could not be put back (" + (b.settingPresent ? "was " + b.settingValue : "was absent") + ")");
            ok = false;
        }
        if (b.requested && b.config != null) {
            // report only: the configuration is the Wi-Fi service's. An older snapshot is never written over it (a stale or
            // damaged copy handed to the writer is exactly what killed system_server), and a change made meanwhile may be the user's.
            log.log("hotspot: AP configuration after the session, changed since before: " + changed(b.config, p.apConfig()));
        }
        if (b.requested && b.staOn && !p.staEnabled() && !p.setSta(true)) {
            log.log("hotspot: the Wi-Fi client could not be turned back on");
            ok = false;
        }
        if (ok) {
            j.clear();
            owned = null;
            log.log("hotspot: back to how it was");
        } else {
            b.tries++;
            j.save(b);
        }
        return ok;
    }
}
