package hmgapi.mods.wireless.dipo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A passive look at the iAP2 bytes that cross the bridge over Bluetooth (SPP), to see what the phone was told about Wi-Fi. Nothing is
 * changed or injected: the bytes go on exactly as they came.
 *
 * iAP2 control-session messages start 0x40 0x40, a 2-byte length (the whole message), a 2-byte message id and parameters (2-byte length
 * including its own 4-byte header, 2-byte id, data). The Wi-Fi sharing messages are RequestAccessoryWiFiConfigurationInformation 0x5702
 * (phone -> accessory) and AccessoryWiFiConfigurationInformation 0x5703 (accessory -> phone) as the iAP2 specification numbers them;
 * the ids are not verified on this car, so every new message id is also reported as hex and a wrong number shows up there.
 * The scanner keeps the start of a message that is split across two chunks (at most 4 KB). It is a diagnostic: a false match
 * can only cause a wrong log line.
 */
final class IapScan {
    static final int REQUEST_WIFI = 0x5702, WIFI_INFO = 0x5703;
    private static final int MAX_PENDING = 4096, MAX_MESSAGE = 2048, HEADER = 6;

    static final class Msg {
        final int id;
        final byte[] raw;

        Msg(int id, byte[] raw) {
            this.id = id;
            this.raw = raw;
        }
    }

    private byte[] pend = new byte[0];

    /** the complete messages found in this chunk (and the end of earlier ones) */
    synchronized List<Msg> feed(byte[] data, int len) {
        List<Msg> out = new ArrayList<Msg>();
        if (data == null || len <= 0) return out;
        int add = Math.min(len, data.length);
        byte[] all = new byte[pend.length + add];
        System.arraycopy(pend, 0, all, 0, pend.length);
        System.arraycopy(data, 0, all, pend.length, add);
        int i = 0, keep = -1;
        while (i + HEADER <= all.length) {
            if (all[i] != 0x40 || all[i + 1] != 0x40) {
                i++;
                continue;
            }
            int mlen = u16(all, i + 2);
            if (mlen < HEADER || mlen > MAX_MESSAGE) {
                i++;
                continue;
            }
            if (i + mlen > all.length) {   // the rest has not arrived yet
                keep = i;
                break;
            }
            out.add(new Msg(u16(all, i + 4), Arrays.copyOfRange(all, i, i + mlen)));
            i += mlen;
        }
        if (keep < 0) keep = Math.max(i, all.length - (HEADER - 1));   // a header may be split between chunks
        pend = Arrays.copyOfRange(all, Math.min(keep, all.length), all.length);
        if (pend.length > MAX_PENDING) pend = new byte[0];
        return out;
    }

    static int u16(byte[] b, int off) {
        return (b[off] & 0xff) << 8 | (b[off + 1] & 0xff);
    }

    /** what a Wi-Fi configuration message told the phone, compared with the hotspot's own name and key (never printed) */
    static final class Told {
        boolean ssidEqual, pskEqual;
        String summary = "";
    }

    static Told describeWifiInfo(Msg m, byte[] hotspotSsid, byte[] hotspotPsk) {
        Told t = new Told();
        StringBuilder s = new StringBuilder();
        int off = HEADER;
        byte[] r = m.raw;
        while (off + 4 <= r.length) {
            int plen = u16(r, off), pid = u16(r, off + 2);
            if (plen < 4 || off + plen > r.length) break;
            byte[] d = Arrays.copyOfRange(r, off + 4, off + plen);
            if (hotspotSsid != null && d.length > 0 && Arrays.equals(d, hotspotSsid)) t.ssidEqual = true;
            if (hotspotPsk != null && d.length > 0 && Arrays.equals(d, hotspotPsk)) t.pskEqual = true;
            s.append(s.length() > 0 ? " " : "").append(pid).append(':').append(d.length).append('B');
            if (d.length == 1) s.append('=').append(d[0] & 0xff);
            if (d.length > 0 && hotspotSsid != null && Arrays.equals(d, hotspotSsid)) s.append("(=hotspot name)");
            if (d.length > 0 && hotspotPsk != null && Arrays.equals(d, hotspotPsk)) s.append("(=hotspot key)");
            off += plen;
        }
        t.summary = s.toString();
        return t;
    }
}
