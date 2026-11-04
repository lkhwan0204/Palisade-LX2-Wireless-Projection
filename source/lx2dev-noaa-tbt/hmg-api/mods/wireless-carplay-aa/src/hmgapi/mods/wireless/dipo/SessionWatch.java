package hmgapi.mods.wireless.dipo;

import java.util.ArrayList;
import java.util.List;

/**
 * A running wireless CarPlay session seen from outside, every few seconds: how many bytes arrive on and leave the hotspot interface,
 * whether the phone still has an entry on it, the AP's state and whether Bluetooth is still up. Pure (no Android): the bridge takes the
 * samples, this keeps the last ones, says when the traffic has stopped and, when the session ends, which of the visible things changed
 * first. It observes only; nothing here ends, restarts or resets a session.
 *
 * Wireless CarPlay starts over Bluetooth and then runs over Wi-Fi, so the SPP link closing during the session is the normal hand-over and
 * is ignored; only the phone's call/audio Bluetooth (HFP) going down in the last 30 s, before anything on the Wi-Fi side changed, is named.
 */
final class SessionWatch {
    static final int MAX = 24;

    static final class Sample {
        long at;
        long rx = -1, tx = -1, rxErr = -1, rxDrop = -1, txErr = -1, txDrop = -1;
        int arp;
        int apState = -1, apMode = -1;
        boolean sppUp, hfpUp;
    }

    private final List<Sample> ring = new ArrayList<Sample>();
    private long rxMovedAt = -1, firstAt = -1;
    private long lastRx = -1;
    private boolean arpSeen;
    private long sppDownAt = -1, hfpDownAt = -1, arpGoneAt = -1, apDownAt = -1;
    private boolean sppWasUp, hfpWasUp, apWasUp, arpWasUp;

    synchronized void add(Sample s) {
        if (firstAt < 0) firstAt = s.at;
        if (s.rx >= 0 && (lastRx < 0 || s.rx > lastRx)) rxMovedAt = s.at;
        if (s.rx >= 0) lastRx = s.rx;
        if (s.arp > 0) {
            arpSeen = true;
            arpWasUp = true;
        } else if (arpWasUp && arpGoneAt < 0) arpGoneAt = s.at;
        if (s.sppUp) sppWasUp = true;
        else if (sppWasUp && sppDownAt < 0) sppDownAt = s.at;
        if (s.hfpUp) hfpWasUp = true;
        else if (hfpWasUp && hfpDownAt < 0) hfpDownAt = s.at;
        boolean apUp = s.apState == HotspotGuard.AP_ENABLED && s.apMode == HotspotGuard.MODE_CONNECTIVITY;
        if (apUp) apWasUp = true;
        else if (apWasUp && s.apState != -1 && apDownAt < 0) apDownAt = s.at;
        ring.add(s);
        if (ring.size() > MAX) ring.remove(0);
    }

    synchronized int samples() {
        return ring.size();
    }

    /** milliseconds since received bytes last grew, -1 when fewer than two samples */
    synchronized long rxStalledMs(long now) {
        if (ring.size() < 2 || rxMovedAt < 0) return -1;
        return Math.max(0, now - rxMovedAt);
    }

    /**
     * Bytes received on the hotspot interface in the last windowMs, up to currentRx (read just now): the video of wireless CarPlay comes
     * from the phone, so growth here while the picture is stuck means the stream still arrives. -1 when no baseline.
     */
    synchronized long rxSince(long now, long windowMs, long currentRx) {
        if (currentRx < 0 || ring.isEmpty()) return -1;
        Sample base = null;
        for (Sample s : ring) {
            if (s.rx < 0) continue;
            if (s.at < now - windowMs) {   // the newest sample from before the window
                base = s;
                continue;
            }
            if (base == null) base = s;
            break;
        }
        return base == null ? -1 : Math.max(0, currentRx - base.rx);
    }

    synchronized boolean arpEverSeen() {
        return arpSeen;
    }

    /** "-25s rx=123 (+0) tx=45 arp=1 ap=13/2 spp=true hfp=true" for the last n samples, newest last */
    synchronized String trail(long now, int n) {
        StringBuilder b = new StringBuilder();
        int from = Math.max(0, ring.size() - n);
        for (int i = from; i < ring.size(); i++) {
            Sample s = ring.get(i), prev = i > 0 ? ring.get(i - 1) : null;
            b.append(b.length() > 0 ? " | " : "").append('-').append(Math.max(0, (now - s.at) / 1000)).append("s rx=").append(s.rx)
                    .append(prev != null && prev.rx >= 0 && s.rx >= 0 ? "(+" + (s.rx - prev.rx) + ")" : "").append(" tx=").append(s.tx)
                    .append(s.rxErr > 0 || s.rxDrop > 0 || s.txErr > 0 || s.txDrop > 0 ? " err/drop=" + s.rxErr + "/" + s.rxDrop + "/" + s.txErr + "/" + s.txDrop : "")
                    .append(" arp=").append(s.arp).append(" ap=").append(s.apState).append('/').append(s.apMode).append(" spp=").append(s.sppUp).append(" hfp=").append(s.hfpUp);
        }
        return b.length() == 0 ? "no samples" : b.toString();
    }

    static final String WIFI_STALLED = "WIFI_STALLED", PHONE_LEFT_AP = "PHONE_LEFT_AP", AP_WENT_DOWN = "AP_WENT_DOWN", BT_DROPPED_FIRST = "BT_DROPPED_FIRST",
            NO_VISIBLE_CAUSE = "NO_VISIBLE_CAUSE", TOO_SHORT = "TOO_SHORT_TO_TELL";

    /**
     * Which visible thing changed first before the session ended, by what was sampled. apChangedByOthers: the system reported the AP
     * going down without a request from this mod. The answer is a code and a sentence; "NO_VISIBLE_CAUSE" is honest: the session ended
     * while the traffic, the phone's entry, the AP and Bluetooth all looked the same as before (the phone or the CarPlay library ended it).
     */
    synchronized String classify(long endAt, boolean apChangedByOthers) {
        if (ring.size() < 3) return TOO_SHORT + ": fewer than three samples (the session was shorter than " + (3 * 5) + " s)";
        long stalled = rxStalledMs(endAt);
        long first = Long.MAX_VALUE;
        String what = null;
        if (apChangedByOthers || (apDownAt >= 0)) {
            long t = apDownAt >= 0 ? apDownAt : endAt;
            if (t < first) {
                first = t;
                what = AP_WENT_DOWN + ": the access point left the enabled connectivity state" + (apChangedByOthers ? " without a request from this mod" : "") + " " + (Math.max(0, (endAt - t) / 1000)) + " s before the end";
            }
        }
        if (arpGoneAt >= 0 && arpGoneAt < first) {
            first = arpGoneAt;
            what = PHONE_LEFT_AP + ": the phone's entry on the hotspot interface disappeared " + (Math.max(0, (endAt - arpGoneAt) / 1000)) + " s before the end";
        }
        if (stalled >= 8000 && rxMovedAt < first) {
            first = rxMovedAt;
            what = WIFI_STALLED + ": no bytes arrived on the hotspot interface for the last " + (stalled / 1000) + " s before the end";
        }
        // The SPP link closing is the normal hand-over to Wi-Fi and is ignored. The phone's call/audio Bluetooth (HFP) going down shortly
        // before the end, with nothing else changed earlier, is what a "the phone disconnected" freeze looks like.
        if (hfpDownAt >= 0 && endAt - hfpDownAt <= 30000 && hfpDownAt < first) {
            first = hfpDownAt;
            what = BT_DROPPED_FIRST + ": the phone's Bluetooth (HFP) went down " + (Math.max(0, (endAt - hfpDownAt) / 1000)) + " s before the end, before the Wi-Fi side changed";
        }
        return what != null ? what : NO_VISIBLE_CAUSE + ": traffic, the phone's entry, the AP and Bluetooth looked unchanged until the end";
    }
}
