package hmgapi.mods.wireless.dipo;

/**
 * What can be told of the CarPlay picture from the Java side of DiPO, in one place (pure, no Android): the decoder's failure
 * reports, the display being shown and hidden (with the Surface's validity), the screen callbacks, touches, and the key-frame requests
 * this mod makes.
 *
 * The facts it is built on (DiPO and the Telechips CarPlay libraries on this unit):
 *  - libCarPlayUtils reports "VDEC Fail(NO Output Frame)" when the video decoder gives no picture; CarPlayService.onNotifyDecodeFailure(int)
 *    carries it as CarPlayScreenError: 0 UNKNOWN, 1 NO_OUTPUT_FRAME, 2 SEQ_HEADER_FAILED, 3 BLACK_SCREEN_DETECTED. DiPO's own listener only reacts
 *    to UNKNOWN; for the others it logs that it skips them. Nothing in DiPO calls CarPlayService.forceKeyFrame() (AirPlay's request that the
 *    phone send a fresh key frame).
 *  - the music is a separate stream, so it goes on while the picture is stuck.
 * A key frame is requested only for NO_OUTPUT_FRAME, at most every MIN_GAP_MS and MAX_PER_SESSION times per session. It is not a restart or a
 * reconnect: it asks the sender for the one frame a decoder needs to start again. Whether it helps this failure is not known.
 */
final class ScreenWatch {
    static final long MIN_GAP_MS = 3000;
    static final int MAX_PER_SESSION = 4;
    static final int UNKNOWN = 0, NO_OUTPUT_FRAME = 1, SEQ_HEADER_FAILED = 2, BLACK_SCREEN_DETECTED = 3;

    static String errorName(int t) {
        switch (t) {
            case UNKNOWN: return "UNKNOWN";
            case NO_OUTPUT_FRAME: return "NO_OUTPUT_FRAME";
            case SEQ_HEADER_FAILED: return "SEQ_HEADER_FAILED";
            case BLACK_SCREEN_DETECTED: return "BLACK_SCREEN_DETECTED";
            default: return "unknown(" + t + ")";
        }
    }

    private long sessionAt = -1, lastFailureAt = -1, lastRequestAt = -1, shownAt = -1, hiddenAt = -1, startedAt = -1, stoppedAt = -1, timeoutAt = -1;
    private int failures, noOutput, requests, shows, hides, touches, touchFails, touchesSinceFailure, touchFailsSinceFailure;
    private String surface = "no show yet", lastShowResult = "";

    synchronized void newSession(long now) {
        sessionAt = now;
        lastFailureAt = lastRequestAt = -1;
        failures = noOutput = requests = 0;
        touches = touchFails = touchesSinceFailure = touchFailsSinceFailure = 0;
    }

    synchronized void shown(long now, String surfaceState, String result) {
        shownAt = now;
        shows++;
        surface = surfaceState;
        lastShowResult = result;
    }

    synchronized void hidden(long now) {
        hiddenAt = now;
        hides++;
    }

    /** kind: 0 started, 1 stopped, 2 timeout */
    synchronized void screen(long now, int kind) {
        if (kind == 0) startedAt = now;
        else if (kind == 1) stoppedAt = now;
        else timeoutAt = now;
    }

    synchronized void touch(boolean ok) {
        touches++;
        touchesSinceFailure++;
        if (!ok) {
            touchFails++;
            touchFailsSinceFailure++;
        }
    }

    /**
     * A decoder failure was reported. True when a key frame should be requested for it now: NO_OUTPUT_FRAME only, not within MIN_GAP_MS of
     * the last request, and fewer than MAX_PER_SESSION so far.
     */
    synchronized boolean decodeFailure(long now, int type) {
        failures++;
        lastFailureAt = now;
        touchesSinceFailure = 0;
        touchFailsSinceFailure = 0;
        if (type != NO_OUTPUT_FRAME) return false;
        noOutput++;
        return requests < MAX_PER_SESSION && (lastRequestAt < 0 || now - lastRequestAt >= MIN_GAP_MS);
    }

    synchronized int keyFrameRequested(long now) {
        lastRequestAt = now;
        return ++requests;
    }

    synchronized int requests() {
        return requests;
    }

    synchronized int failures() {
        return failures;
    }

    /** why a failure did not lead to a request, for the log */
    synchronized String declined(long now) {
        if (requests >= MAX_PER_SESSION) return "the budget of " + MAX_PER_SESSION + " requests this session is used up";
        if (lastRequestAt >= 0 && now - lastRequestAt < MIN_GAP_MS) return "one was asked " + (now - lastRequestAt) + " ms ago";
        return "this kind of failure is not answered";
    }

    private static String ago(long now, long at) {
        return at < 0 ? "never" : Math.max(0, (now - at) / 1000) + " s ago";
    }

    /** one line: the state of the picture path as far as Java can see it */
    synchronized String describe(long now) {
        return "decoder failures " + failures + " (NO_OUTPUT_FRAME " + noOutput + "), last " + ago(now, lastFailureAt) + " | touches " + touches + " (not accepted " + touchFails
                + "; " + touchesSinceFailure + " since the last failure, " + touchFailsSinceFailure + " not accepted) | display shown " + shows + "x (last " + ago(now, shownAt)
                + ", Surface " + surface + (lastShowResult.length() > 0 ? ", result " + lastShowResult : "") + "), hidden " + hides + "x (last " + ago(now, hiddenAt)
                + ") | screen started " + ago(now, startedAt) + ", stopped " + ago(now, stoppedAt) + ", timeout " + ago(now, timeoutAt) + " | key frames asked " + requests + "/" + MAX_PER_SESSION;
    }
}
