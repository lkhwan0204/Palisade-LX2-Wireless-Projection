package hmgapi.mods.wireless.dipo;

/**
 * Where a wireless CarPlay attempt got to, step by step, so that one log line says which step did not happen. Pure (no Android):
 * the bridge reports each step, this class keeps the order and the times and words the verdict.
 *
 * The steps in the order they happen on this head unit (the Wi-Fi hotspot comes BEFORE the iAP2 Bluetooth session: the phone is told
 * to join it over that session):
 *   1 BT         the phone's Bluetooth/SPP link is up
 *   2 IAP2       the iAP2 server is bound and the bridge registered with it
 *   3 ATTEMPT    a session attempt started (not held back by the quarantine, USB, the retry gate or a latched hotspot)
 *   4 AP         the hotspot is ready (state ENABLED, mode 2)
 *   5 IAP2_BT    BT_ATTACHED was sent and the iAP2 server reported the Bluetooth session (component 4) connected
 *   6 JOIN       the phone joined the hotspot (an entry for it on the hotspot interface)
 *   7 CP_SESSION DiPO created the CarPlay session
 *   8 CP_WIRELESS the CarPlay session started over Wi-Fi (the CarPlay screen)
 * Steps 1 and 2 belong to the process; 3 to 8 restart with every attempt.
 */
final class SessionTrace {
    static final int BT = 0, IAP2 = 1, ATTEMPT = 2, AP = 3, IAP2_BT = 4, JOIN = 5, CP_SESSION = 6, CP_WIRELESS = 7, STEPS = 8;
    static final String[] NAME = { "BT", "IAP2", "ATTEMPT", "AP", "IAP2_BT", "JOIN", "CP_SESSION", "CP_WIRELESS" };
    static final String[] TITLE = {
        "the phone's Bluetooth/SPP link is up",
        "the iAP2 server is bound",
        "a session attempt started",
        "the hotspot is ready",
        "BT_ATTACHED sent and the iAP2 Bluetooth session connected",
        "the phone joined the hotspot",
        "the CarPlay session was created",
        "the CarPlay session runs over Wi-Fi"
    };

    private final long[] at = new long[STEPS];
    private final boolean[] done = new boolean[STEPS];
    private final String[] note = new String[STEPS];
    private int failed = -1;
    private String failWhy = "";
    private long t0 = -1;
    private int attempt;

    synchronized void begin(int attempt, long now) {
        this.attempt = attempt;
        t0 = now;
        failed = -1;
        failWhy = "";
        for (int i = ATTEMPT; i < STEPS; i++) {
            done[i] = false;
            note[i] = null;
        }
    }

    /** a step no longer holds (the phone's Bluetooth link went down) */
    synchronized void clear(int step) {
        if (step >= 0 && step < STEPS) {
            done[step] = false;
            note[step] = null;
        }
    }

    /** the first report of a step counts; later ones are ignored */
    synchronized boolean ok(int step, long now, String what) {
        if (step < 0 || step >= STEPS || done[step]) return false;
        done[step] = true;
        at[step] = now;
        note[step] = what;
        if (failed == step) {
            failed = -1;
            failWhy = "";
        }
        return true;
    }

    synchronized void fail(int step, long now, String why) {
        if (step < 0 || step >= STEPS || done[step]) return;
        failed = step;
        failWhy = why == null ? "" : why;
        at[step] = now;
    }

    synchronized boolean done(int step) {
        return step >= 0 && step < STEPS && done[step];
    }

    /** the first step that has not happened, or STEPS when all have */
    synchronized int waitingFor() {
        for (int i = 0; i < STEPS; i++) if (!done[i]) return i;
        return STEPS;
    }

    /** "attempt 2: 1 BT ok | 2 IAP2 ok | 3 ATTEMPT ok +0.0s | 4 AP ok +1.9s | 5 IAP2_BT - | ..." */
    synchronized String line(long now) {
        StringBuilder b = new StringBuilder("attempt ").append(attempt).append(':');
        for (int i = 0; i < STEPS; i++) {
            b.append(i == 0 ? " " : " | ").append(i + 1).append(' ').append(NAME[i]).append(' ');
            if (done[i]) b.append("ok").append(i >= ATTEMPT && t0 >= 0 ? " +" + sec(at[i] - t0) : "");
            else if (failed == i) b.append("FAILED");
            else b.append('-');
        }
        return b.toString();
    }

    /** the one-line answer: last step that happened, the one it is waiting for or that failed, and what to look at next */
    synchronized String verdict(long now) {
        int w = waitingFor();
        if (w >= STEPS) return "all steps happened: the CarPlay session runs over Wi-Fi";
        int last = -1;
        for (int i = 0; i < w; i++) if (done[i]) last = i;
        StringBuilder b = new StringBuilder();
        b.append("last step that happened: ").append(last < 0 ? "none" : (last + 1) + " " + NAME[last]);
        if (failed >= 0) b.append("; step ").append(failed + 1).append(' ').append(NAME[failed]).append(" FAILED (").append(failWhy).append(')');
        else b.append("; waiting for step ").append(w + 1).append(' ').append(NAME[w]).append(" (").append(TITLE[w]).append(')');
        if (t0 >= 0 && w >= ATTEMPT) b.append(", ").append(sec(now - t0)).append(" into the attempt");
        b.append(". Look at: ").append(hint(failed >= 0 ? failed : w));
        return b.toString();
    }

    static String hint(int step) {
        switch (step) {
            case BT: return "the phone is not connected over Bluetooth/SPP (pairing, or the head unit's Bluetooth)";
            case IAP2: return "the iAP2 server was not bound: lines 'iAP2 listens' / 'Iap2SvcInit'";
            case ATTEMPT: return "why the attempt was held back: the 'quarantine', 'hotspot unavailable', 'retry' or 'USB' line just before";
            case AP: return "the 'wifi[...]' and 'hotspot:' lines: configuration, request result, AP state timeline, driver/interface facts";
            case IAP2_BT: return "BT_ATTACHED went out but the iAP2 server did not report the Bluetooth session (sppIn/out in the status lines)";
            case JOIN: return "the hotspot is up and iAP2 runs over Bluetooth, but the phone has no entry on the hotspot interface (the phone did not join: its Wi-Fi, the credentials it was given, or the radio); 'wlan rx/tx' in the status lines shows whether any packets arrive, and the 'join check' line says which kind of failure it is";
            case CP_SESSION: return "the phone is on the hotspot but DiPO did not create a CarPlay session (ports or firewall on the hotspot interface, the CarPlay library's own log)";
            case CP_WIRELESS: return "the session exists but did not start over Wi-Fi (CarPlay library log, 'CarPlay session started transport=')";
            default: return "-";
        }
    }

    private static String sec(long ms) {
        long t = Math.max(0, ms);
        return (t / 1000) + "." + ((t % 1000) / 100) + "s";
    }
}
