package hmgapi.mods.wireless.dipo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What the running access point is doing, as far as this app can see (read only, like WifiProbe): are hostapd and the DHCP server
 * running, and does the configuration hostapd was started with say what the Wi-Fi service's configuration says. hostapd.conf holds the
 * hotspot's key, so it is only ever COMPARED here: the answers are lengths and yes/no, no SSID or key value from it is kept or logged.
 * The few settings that carry no secret (security, cipher, band, channel) are shown as they are.
 */
final class ApAudit {
    private ApAudit() {}

    static final String HOSTAPD_CONF = "/data/misc/wifi/hostapd.conf";
    private static final List<String> PUBLIC_KEYS = Arrays.asList("interface", "driver", "hw_mode", "channel", "country_code", "ieee80211n",
            "ieee80211ac", "ieee80211d", "wmm_enabled", "wpa", "wpa_key_mgmt", "wpa_pairwise", "rsn_pairwise", "auth_algs",
            "ignore_broadcast_ssid", "max_num_sta", "beacon_int", "dtim_period");

    static final class Facts {
        boolean procRead, hostapdRunning, dnsmasqRunning, dhcpcdRunning;
        boolean confRead, confSsidMatches, confPskMatches;
        int confSsidLen = -1, confPskLen = -1;
        String confPublic = "";
        // from the interface list, filled in by the Android side
        boolean ifaceKnown, ifaceUp, ifaceHasIpv4, subnetClash;
        String ifaceAddr = "";
    }

    /** ssid and psk: the Wi-Fi service's configuration (psk may be null for an open AP) */
    static Facts audit(WifiProbe.Fs fs, String ssid, String psk) {
        Facts f = new Facts();
        String[] procs = fs.list("/proc");
        if (procs != null) {
            f.procRead = true;
            int seen = 0;
            for (String n : procs) {
                if (n.length() == 0 || n.charAt(0) < '0' || n.charAt(0) > '9') continue;
                if (++seen > 600) break;
                String cmd = fs.read("/proc/" + n + "/cmdline", 96);
                if (cmd == null) continue;
                String name = baseName(cmd);
                if (name.equals("hostapd")) f.hostapdRunning = true;
                else if (name.equals("dnsmasq")) f.dnsmasqRunning = true;
                else if (name.equals("dhcpcd")) f.dhcpcdRunning = true;
            }
        }
        String conf = fs.read(HOSTAPD_CONF, 4096);
        if (conf != null) {
            f.confRead = true;
            StringBuilder pub = new StringBuilder();
            for (String l : conf.split("\n")) {
                int e = l.indexOf('=');
                if (e <= 0) continue;
                String k = l.substring(0, e).trim(), v = l.substring(e + 1);
                if (k.equals("ssid")) {
                    f.confSsidLen = v.length();
                    f.confSsidMatches = ssid != null && v.equals(ssid);
                } else if (k.equals("wpa_passphrase")) {
                    f.confPskLen = v.length();
                    f.confPskMatches = psk != null && v.equals(psk);
                } else if (PUBLIC_KEYS.contains(k)) {
                    pub.append(pub.length() > 0 ? " " : "").append(k).append('=').append(v.trim());
                }
            }
            f.confPublic = pub.toString();
        }
        return f;
    }

    /** the first token of a /proc/<pid>/cmdline, without its directory */
    static String baseName(String cmdline) {
        int end = 0;
        while (end < cmdline.length() && cmdline.charAt(end) != 0 && cmdline.charAt(end) != ' ') end++;
        String first = cmdline.substring(0, end);
        int slash = first.lastIndexOf('/');
        return slash >= 0 ? first.substring(slash + 1) : first;
    }

    static List<String> lines(Facts f, String ssidShown) {
        List<String> out = new ArrayList<String>();
        out.add("processes: hostapd=" + (f.procRead ? (f.hostapdRunning ? "running" : "NOT running") : "?") + " dnsmasq=" + (f.procRead ? (f.dnsmasqRunning ? "running" : "NOT running") : "?")
                + " dhcpcd=" + (f.procRead ? (f.dhcpcdRunning ? "running" : "not running") : "?"));
        if (f.confRead) {
            out.add("hostapd.conf as started: ssid " + f.confSsidLen + " chars, equals the Wi-Fi service's SSID " + ssidShown + ": " + f.confSsidMatches + "; passphrase " + f.confPskLen
                    + " chars, equals the service's preSharedKey: " + f.confPskMatches + " | " + (f.confPublic.length() == 0 ? "no public settings" : f.confPublic));
        } else {
            out.add("hostapd.conf cannot be read by this app");
        }
        out.add("interface: " + (f.ifaceKnown ? "up=" + f.ifaceUp + " ipv4=" + (f.ifaceHasIpv4 ? f.ifaceAddr : "NONE") + (f.subnetClash ? " SAME SUBNET as another interface" : "") : "unknown"));
        return out;
    }
}
