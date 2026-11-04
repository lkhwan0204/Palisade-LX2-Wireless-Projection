package hmgapi.mods.wireless.dipo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Wireless Android Auto, seen from the bridge, read only. The stock head unit does all of it itself: GPM (WirelessExplorer, the
 * Bluetooth messages and the Wi-Fi hand-over to the phone), PhoneProjection (which device, when, the hotspot) and the Bluetooth stack
 * (the link GPM's listener reads). This class only decodes what can be seen of it, so a failing Galaxy phone can be located:
 *
 *   1 the phone offers / the head unit offers the Android Auto wireless Bluetooth service (UUID below)
 *   2 an RFCOMM/SPP link to the phone comes up
 *   3 the messages of the wireless handshake cross it: [2-byte length][2-byte type][payload], types 1..7 (GPM's WifiMessageType)
 *   4 the hotspot is up in the connectivity mode and the name and key the phone gets are the hotspot's own
 *
 * No address, no name and no key is ever put in a line: UUIDs are only counted and checked against the well-known ones.
 */
final class AaProbe {
    private AaProbe() {}

    static final String UUID_AA_WIRELESS = "4de17a00-52cb-11e6-bdf4-0800200c9a66";
    static final String UUID_IAP2 = "00000000-deca-fade-deca-deafdecacafe";
    static final String UUID_SPP = "00001101-0000-1000-8000-00805f9b34fb";
    static final String UUID_HFP = "0000111e-0000-1000-8000-00805f9b34fb", UUID_HFP_AG = "0000111f-0000-1000-8000-00805f9b34fb";
    static final String UUID_A2DP_SINK = "0000110b-0000-1000-8000-00805f9b34fb", UUID_A2DP_SRC = "0000110a-0000-1000-8000-00805f9b34fb";
    static final String UUID_PBAP = "0000112f-0000-1000-8000-00805f9b34fb", UUID_PBAP_SRV = "0000112e-0000-1000-8000-00805f9b34fb";

    /** the feature flags that decide whether the stock side starts at all (com.hkmc.Feature reads /system/etc/permissions/com.hkmc*.xml) */
    static final String[] FEATURES = {
        "com.hkmc.software.connectivity.androidauto", "com.hkmc.software.connectivity.androidauto_wireless",
        "com.hkmc.software.connectivity.carplay", "com.hkmc.software.connectivity.carplay_wireless", "com.hkmc.software.wifihotspot"
    };

    /** the processes of the stock side that must be alive for it to work */
    static final String[][] PROCESSES = {
        { "com.google.android.projection.sink", "GPM (Android Auto)" }, { "com.daudio.app.phoneprojection", "PhoneProjection" },
        { "com.hkmc.bluetooth", "Bluetooth service" }, { "com.daudio.app.dipo", "DiPO" }, { "com.tcc.iap2server", "iAP2 server" }
    };

    static String shortName(String uuid) {
        if (uuid == null) return "?";
        String u = uuid.toLowerCase();
        if (u.equals(UUID_AA_WIRELESS)) return "AA-wireless";
        if (u.equals(UUID_IAP2)) return "iAP2";
        if (u.equals(UUID_SPP)) return "SPP";
        if (u.equals(UUID_HFP) || u.equals(UUID_HFP_AG)) return "HFP";
        if (u.equals(UUID_A2DP_SINK) || u.equals(UUID_A2DP_SRC)) return "A2DP";
        if (u.equals(UUID_PBAP) || u.equals(UUID_PBAP_SRV)) return "PBAP";
        return null;
    }

    /** "n=12 AA-wireless=yes iAP2=no SPP=yes HFP=yes A2DP=yes PBAP=no": only counts and well-known services, never the other UUIDs */
    static String describeUuids(List<String> uuids) {
        if (uuids == null) return "not readable";
        boolean aa = false, iap2 = false, spp = false, hfp = false, a2dp = false, pbap = false;
        for (String u : uuids) {
            String n = shortName(u);
            if (n == null) continue;
            if (n.equals("AA-wireless")) aa = true;
            else if (n.equals("iAP2")) iap2 = true;
            else if (n.equals("SPP")) spp = true;
            else if (n.equals("HFP")) hfp = true;
            else if (n.equals("A2DP")) a2dp = true;
            else pbap = true;
        }
        return "n=" + uuids.size() + " AA-wireless=" + yn(aa) + " iAP2=" + yn(iap2) + " SPP=" + yn(spp) + " HFP=" + yn(hfp) + " A2DP=" + yn(a2dp) + " PBAP=" + yn(pbap);
    }

    /** "GPM_(Android_Auto)=running PhoneProjection=running ..." from a /proc scan: whether the stock side of the projection is alive */
    static String processReport(WifiProbe.Fs fs) {
        String[] procs = fs.list("/proc");
        if (procs == null) return "/proc cannot be read";
        boolean[] up = new boolean[PROCESSES.length];
        int seen = 0;
        for (String n : procs) {
            if (n.length() == 0 || n.charAt(0) < '0' || n.charAt(0) > '9') continue;
            if (++seen > 600) break;
            String cmd = fs.read("/proc/" + n + "/cmdline", 64);
            if (cmd == null) continue;
            String first = cmd;
            int z = first.indexOf(0);
            if (z >= 0) first = first.substring(0, z);
            for (int i = 0; i < PROCESSES.length; i++) if (first.equals(PROCESSES[i][0])) up[i] = true;
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < PROCESSES.length; i++) b.append(i > 0 ? " " : "").append(PROCESSES[i][1].replace(' ', '_')).append('=').append(up[i] ? "running" : "NOT_running");
        return b.toString();
    }

    /** "androidauto_wireless" from "com.hkmc.software.connectivity.androidauto_wireless" */
    static String featureTail(String f) {
        int i = f.lastIndexOf('.');
        return i >= 0 ? f.substring(i + 1) : f;
    }

    static boolean hasAaWireless(List<String> uuids) {
        if (uuids != null) for (String u : uuids) if (UUID_AA_WIRELESS.equalsIgnoreCase(u)) return true;
        return false;
    }

    private static String yn(boolean b) {
        return b ? "yes" : "no";
    }

    /** GPM's WifiMessageType numbers (WifiDiscoveryProtos), the framing of WirelessExplorer.sendMessage */
    static String typeName(int t) {
        switch (t) {
            case 1: return "WIFI_START_REQUEST";
            case 2: return "WIFI_INFO_REQUEST";
            case 3: return "WIFI_INFO_RESPONSE";
            case 4: return "WIFI_VERSION_REQUEST";
            case 5: return "WIFI_VERSION_RESPONSE";
            case 6: return "WIFI_CONNECT_STATUS";
            case 7: return "WIFI_START_RESPONSE";
            default: return "unknown";
        }
    }

    static final class Msg {
        final int type, payload;

        Msg(int type, int payload) {
            this.type = type;
            this.payload = payload;
        }
    }

    /** The inbound bytes of the SPP link to one Android phone as wireless-handshake messages; keeps an unfinished message for the next chunk (at most 4 KB). */
    static final class Stream {
        private byte[] pend = new byte[0];
        private int junk;

        synchronized List<Msg> feed(byte[] data, int len) {
            List<Msg> out = new ArrayList<Msg>();
            if (data == null || len <= 0) return out;
            int add = Math.min(len, data.length);
            byte[] all = new byte[pend.length + add];
            System.arraycopy(pend, 0, all, 0, pend.length);
            System.arraycopy(data, 0, all, pend.length, add);
            int i = 0;
            while (all.length - i >= 4) {
                int plen = (all[i] & 0xff) << 8 | (all[i + 1] & 0xff), type = (all[i + 2] & 0xff) << 8 | (all[i + 3] & 0xff);
                if (type < 1 || type > 7 || plen > 4096) {   // not a handshake message: look one byte further
                    i++;
                    junk++;
                    continue;
                }
                if (all.length - i < 4 + plen) break;           // the rest has not arrived yet
                out.add(new Msg(type, plen));
                i += 4 + plen;
            }
            pend = Arrays.copyOfRange(all, i, all.length);
            if (pend.length > 4096) pend = new byte[0];
            return out;
        }

        synchronized int junkBytes() {
            return junk;
        }
    }
}
