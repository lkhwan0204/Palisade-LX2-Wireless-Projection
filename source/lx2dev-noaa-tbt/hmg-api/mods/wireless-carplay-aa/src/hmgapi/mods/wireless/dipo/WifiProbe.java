package hmgapi.mods.wireless.dipo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * What can be seen of the head unit's Wi-Fi without changing anything: the network interfaces, the bus device behind them (SDIO or
 * USB), the driver, the firmware files, the properties of the Wi-Fi services, what the framework reports (feature flags, WifiManager
 * state, the hidden hotspot methods) and the addresses on the interfaces. READ ONLY: this class has no way to write a file, run a
 * command or call a service; the Android side hands it the facts (Fs, Props, Fw). The answer is a list of log lines and a verdict.
 *
 * The verdict is evidence, not proof: HARDWARE_SEEN (an interface, an SDIO function or a driver module), NO_EVIDENCE (every source was
 * readable and none showed a WLAN device; the interface may still appear once the driver is loaded by the first Wi-Fi request),
 * UNKNOWN (the interface list itself could not be read), API_MISSING (no WifiManager or no hidden method to start a hotspot).
 * No names, keys or hardware addresses are ever put in a line.
 */
final class WifiProbe {
    private WifiProbe() {}

    interface Fs {
        /** names in a directory, null when it cannot be listed */
        String[] list(String dir);
        /** the first max characters of a small file, trimmed; null when it cannot be read */
        String read(String path, int max);
        /** the symlink-free path, null when it cannot be resolved */
        String canonical(String path);
        /** file size, -1 when there is no such file */
        long size(String path);
    }

    interface Props { String get(String key); }

    /** the framework's side, filled in by the Android code */
    static final class Fw {
        boolean manager, featureWifi, featureDirect, wifiOn;
        int wifiState = -1, apState = -1, hotspotMode = -1;
        /** names of the hidden WifiManager methods the hotspot needs that do not exist (setWifiApEnabledForConnectivity, getWifiApState) */
        String missingEssential = "";
        /** names of the optional ones that do not exist (getWifiApMode, getWifiApConfiguration, ...) */
        String missingOptional = "";
        String settingWifiApOn;   // raw wifi_ap_on, null when absent
        /** "name|up|ip/prefix", one per IPv4 address */
        List<String> addrs = new ArrayList<String>();
    }

    enum Verdict { HARDWARE_SEEN, NO_EVIDENCE, UNKNOWN, API_MISSING }

    static final class Report {
        final List<String> lines = new ArrayList<String>();
        Verdict verdict = Verdict.UNKNOWN;
        /** the first WLAN-like interface name, or null */
        String wlan;
    }

    private static final Pattern WLAN_IFACE = Pattern.compile("^(wlan|wl|ath|eth_wifi|p2p)\\d*$|^wlan.*");
    private static final Pattern WIFI_FILE = Pattern.compile("(?i).*(bcm|dhd|wlan|wifi|nvram|fw_|brcm|cfg80211|rtl|ath).*");
    private static final String[] DRIVER_MODULES = { "bcmdhd", "dhd", "wlan", "brcmfmac", "cfg80211", "bcm4330", "bcm43xx", "8189es", "8723bs" };
    private static final String[] FW_DIRS = { "/system/wifi", "/system/etc/firmware", "/system/vendor/firmware", "/system/etc/wifi" };
    private static final String[] PROPS = {
        "wifi.interface", "wifi.direct.interface", "wlan.driver.status", "init.svc.wpa_supplicant", "init.svc.p2p_supplicant", "init.svc.netd",
        "dhcp.wlan0.result", "persist.sys.iap2.wifi", "persist.iap2.iden.wireless", "persist.ium.support", "persist.ev6.cpwl.enable",
        "persist.ev6.cpwl.handover", "persist.hmgapi.cpwl.rearm"
    };

    /** full: also the facts that do not change (bus, driver, firmware, properties); the short form is for just before/after a request */
    static Report report(Fs fs, Props pr, Fw fw, boolean full) {
        Report r = new Report();
        String[] net = fs.list("/sys/class/net");
        List<String> wlanLike = new ArrayList<String>();
        if (net != null) for (String n : net) if (isWlanName(n)) wlanLike.add(n);
        r.wlan = wlanLike.isEmpty() ? null : wlanLike.get(0);
        r.lines.add("1 interfaces: " + (net == null ? "/sys/class/net cannot be read" : join(net)) + " | WLAN-like: " + (wlanLike.isEmpty() ? "none" : join(wlanLike.toArray(new String[0]))));
        for (int i = 0; i < wlanLike.size() && i < 2; i++) r.lines.add("2 " + ifaceLine(fs, wlanLike.get(i)));

        String[] sdio = fs.list("/sys/bus/sdio/devices");
        int sdioCount = sdio == null ? 0 : sdio.length;
        boolean driverSeen = false;
        if (full) {
            StringBuilder b = new StringBuilder("3 SDIO functions: ");
            if (sdio == null) b.append("/sys/bus/sdio/devices cannot be read");
            else if (sdio.length == 0) b.append("none");
            else for (int i = 0; i < sdio.length && i < 6; i++) {
                String d = "/sys/bus/sdio/devices/" + sdio[i];
                b.append(i > 0 ? "; " : "").append(sdio[i]).append(" vendor=").append(val(fs.read(d + "/vendor", 16))).append(" device=").append(val(fs.read(d + "/device", 16)))
                        .append(" class=").append(val(fs.read(d + "/class", 16)));
            }
            r.lines.add(b.toString());
            String[] usb = fs.list("/sys/bus/usb/devices");
            r.lines.add("3 USB devices (ids only): " + usbIds(fs, usb));

            String mods = fs.read("/proc/modules", 16384);
            List<String> names = moduleNames(mods);
            StringBuilder m = new StringBuilder("4 driver: /proc/modules ");
            m.append(mods == null ? "cannot be read" : names.isEmpty() ? "lists no WLAN module (built into the kernel, or none loaded)" : "lists " + join(names.toArray(new String[0])));
            for (String dm : DRIVER_MODULES) {
                String[] params = fs.list("/sys/module/" + dm + "/parameters");
                boolean present = params != null || fs.list("/sys/module/" + dm) != null;
                if (!present) continue;
                driverSeen = true;
                m.append(" | /sys/module/").append(dm).append(" present");
                String fwp = fs.read("/sys/module/" + dm + "/parameters/firmware_path", 120);
                String nvp = fs.read("/sys/module/" + dm + "/parameters/nvram_path", 120);
                if (fwp != null) m.append(" firmware_path=").append(val(fwp));
                if (nvp != null) m.append(" nvram_path=").append(val(nvp));
            }
            if (!names.isEmpty()) driverSeen = true;
            r.lines.add(m.toString());

            StringBuilder f = new StringBuilder("5 firmware files (names and sizes):");
            boolean any = false;
            for (String dir : FW_DIRS) {
                String[] ls = fs.list(dir);
                if (ls == null) continue;
                for (String n : ls) {
                    if (!WIFI_FILE.matcher(n).matches()) continue;
                    any = true;
                    f.append(' ').append(dir).append('/').append(n).append('=').append(fs.size(dir + "/" + n));
                }
            }
            r.lines.add(any ? f.toString() : f.append(" none found in ").append(join(FW_DIRS)).toString());

            StringBuilder p = new StringBuilder("6 properties:");
            for (String k : PROPS) {
                String v = pr == null ? null : pr.get(k);
                p.append(' ').append(k).append('=').append(v == null || v.length() == 0 ? "unset" : v);
            }
            r.lines.add(p.toString());
        } else if (sdio != null) {
            // the verdict still needs to know whether any SDIO function exists
            sdioCount = sdio.length;
        }

        r.lines.add("7 framework: WifiManager=" + (fw.manager ? "yes" : "NO") + " feature wifi=" + fw.featureWifi + " wifi.direct=" + fw.featureDirect
                + " wifiState=" + wifiStateName(fw.wifiState) + " apState=" + apStateName(fw.apState) + " apMode=" + fw.hotspotMode + " wifi_ap_on="
                + (fw.settingWifiApOn == null ? "absent" : fw.settingWifiApOn) + " | hotspot API missing: "
                + (fw.missingEssential.length() == 0 ? "none" : fw.missingEssential) + (fw.missingOptional.length() == 0 ? "" : " (optional: " + fw.missingOptional + ")"));
        String conflict = subnetConflict(fw.addrs);
        r.lines.add("8 addresses: " + (fw.addrs.isEmpty() ? "none" : join(fw.addrs.toArray(new String[0]))) + (conflict == null ? "" : " | SAME SUBNET: " + conflict
                + " (the hotspot's DHCP offers would leave through the wrong interface: the phone joins but gets no address)"));

        boolean apiOk = fw.manager && fw.missingEssential.length() == 0;
        r.verdict = verdict(apiOk, net != null, !wlanLike.isEmpty(), sdio != null, sdioCount, driverSeen);
        r.lines.add("9 verdict: " + r.verdict + " (" + explain(r.verdict) + ")");
        return r;
    }

    /**
     * API_MISSING: no way to ask for a hotspot. HARDWARE_SEEN: an interface, an SDIO function or a driver module. NO_EVIDENCE: the
     * interface list and the SDIO list were both readable and empty of WLAN. UNKNOWN: the interface list could not be read.
     */
    static Verdict verdict(boolean apiOk, boolean netReadable, boolean wlanIface, boolean sdioReadable, int sdioCount, boolean driverSeen) {
        if (!apiOk) return Verdict.API_MISSING;
        if (wlanIface || sdioCount > 0 || driverSeen) return Verdict.HARDWARE_SEEN;
        if (netReadable && sdioReadable) return Verdict.NO_EVIDENCE;
        return Verdict.UNKNOWN;
    }

    static String explain(Verdict v) {
        switch (v) {
            case HARDWARE_SEEN: return "a WLAN interface, SDIO function or driver module is there; this does not prove the radio works";
            case NO_EVIDENCE: return "no WLAN interface, SDIO function or driver module seen before the first request; the driver may load on the first Wi-Fi request, so the request result decides";
            case API_MISSING: return "the framework has no WifiManager or no hidden hotspot method; no hotspot will be requested";
            default: return "the interface list could not be read; the request result decides";
        }
    }

    static boolean isWlanName(String n) {
        return n != null && WLAN_IFACE.matcher(n).matches();
    }

    private static String ifaceLine(Fs fs, String n) {
        String base = "/sys/class/net/" + n;
        String dev = fs.canonical(base + "/device");
        String drv = fs.canonical(base + "/device/driver");
        return "iface " + n + ": operstate=" + val(fs.read(base + "/operstate", 16)) + " flags=" + val(fs.read(base + "/flags", 16)) + " bus=" + busOf(dev)
                + " device=" + lastPart(dev) + " driver=" + lastPart(drv) + " phy80211=" + (fs.list(base + "/phy80211") != null ? "yes" : "no")
                + " wireless=" + (fs.list(base + "/wireless") != null ? "yes" : "no");
    }

    static String busOf(String canonicalDevicePath) {
        if (canonicalDevicePath == null) return "unknown";
        String p = canonicalDevicePath.toLowerCase();
        if (p.contains("/mmc") || p.contains("sdio")) return "sdio";
        if (p.contains("/usb")) return "usb";
        if (p.contains("/pci")) return "pci";
        return "platform";
    }

    private static String lastPart(String path) {
        if (path == null) return "?";
        int i = path.lastIndexOf('/');
        return i >= 0 && i + 1 < path.length() ? path.substring(i + 1) : path;
    }

    private static String usbIds(Fs fs, String[] usb) {
        if (usb == null) return "/sys/bus/usb/devices cannot be read";
        StringBuilder b = new StringBuilder();
        int n = 0, shown = 0;
        for (String d : usb) {
            if (d.indexOf(':') >= 0 || d.startsWith("usb")) continue;   // interfaces and root hubs
            n++;
            if (shown >= 6) continue;
            String v = fs.read("/sys/bus/usb/devices/" + d + "/idVendor", 8), p = fs.read("/sys/bus/usb/devices/" + d + "/idProduct", 8);
            if (v == null || p == null) continue;
            b.append(shown++ > 0 ? " " : "").append(v).append(':').append(p);
        }
        return n + " device(s)" + (shown > 0 ? ": " + b : "");
    }

    static List<String> moduleNames(String procModules) {
        List<String> out = new ArrayList<String>();
        if (procModules == null) return out;
        for (String l : procModules.split("\n")) {
            int sp = l.indexOf(' ');
            String name = sp > 0 ? l.substring(0, sp) : l.trim();
            if (name.length() > 0 && WIFI_FILE.matcher(name).matches() && out.size() < 8) out.add(name);
        }
        return out;
    }

    /** "192.168.0.123/24 on eth0 and 192.168.0.1/24 on wlan0" when two interfaces that are up share an IPv4 network (not loopback) */
    static String subnetConflict(List<String> addrs) {
        for (int i = 0; i < addrs.size(); i++) {
            String[] a = addrs.get(i).split("\\|");
            if (a.length < 3 || !a[1].equals("true") || a[0].equals("lo")) continue;
            for (int j = i + 1; j < addrs.size(); j++) {
                String[] b = addrs.get(j).split("\\|");
                if (b.length < 3 || !b[1].equals("true") || b[0].equals("lo") || a[0].equals(b[0])) continue;
                if (sameNet(a[2], b[2])) return a[2] + " on " + a[0] + " and " + b[2] + " on " + b[0];
            }
        }
        return null;
    }

    static boolean sameNet(String ipPrefixA, String ipPrefixB) {
        long[] a = parse(ipPrefixA), b = parse(ipPrefixB);
        if (a == null || b == null) return false;
        int prefix = (int) Math.min(a[1], b[1]);
        long mask = prefix == 0 ? 0 : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
        return (a[0] & mask) == (b[0] & mask);
    }

    private static long[] parse(String s) {
        try {
            int sl = s.indexOf('/');
            String[] o = (sl < 0 ? s : s.substring(0, sl)).split("\\.");
            if (o.length != 4) return null;
            long ip = 0;
            for (String x : o) {
                int v = Integer.parseInt(x);
                if (v < 0 || v > 255) return null;
                ip = ip << 8 | v;
            }
            int prefix = sl < 0 ? 32 : Integer.parseInt(s.substring(sl + 1));
            return prefix < 0 || prefix > 32 ? null : new long[] { ip, prefix };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** /proc/net/arp: rows on dev whose flags are not 0x0 (an entry that was resolved: a device answered on that interface) */
    static int arpClients(String arp, String dev) {
        if (arp == null || dev == null) return 0;
        int n = 0;
        String[] rows = arp.split("\n");
        for (int i = 1; i < rows.length; i++) {
            String[] c = rows[i].trim().split("\\s+");
            if (c.length >= 6 && c[5].equals(dev) && !c[2].equals("0x0")) n++;
        }
        return n;
    }

    /** "rx=<bytes> tx=<bytes>" of dev from /proc/net/dev, or "no such interface" */
    static String devCounters(String procNetDev, String dev) {
        if (procNetDev == null || dev == null) return "unreadable";
        for (String row : procNetDev.split("\n")) {
            int c = row.indexOf(':');
            if (c < 0 || !row.substring(0, c).trim().equals(dev)) continue;
            String[] f = row.substring(c + 1).trim().split("\\s+");
            if (f.length >= 9) return "rx=" + f[0] + " tx=" + f[8];
        }
        return "no such interface";
    }

    /** {rx bytes, tx bytes, rx errors, rx drops, tx errors, tx drops} of dev from /proc/net/dev, or null */
    static long[] devStats(String procNetDev, String dev) {
        if (procNetDev == null || dev == null) return null;
        for (String row : procNetDev.split("\n")) {
            int c = row.indexOf(':');
            if (c < 0 || !row.substring(0, c).trim().equals(dev)) continue;
            String[] f = row.substring(c + 1).trim().split("\\s+");
            if (f.length < 12) return null;
            try {
                return new long[] { Long.parseLong(f[0]), Long.parseLong(f[8]), Long.parseLong(f[2]), Long.parseLong(f[3]), Long.parseLong(f[10]), Long.parseLong(f[11]) };
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** "wlan0 rx=.. tx=..; eth0 rx=.. tx=.." for every WLAN-like interface and eth0 in /proc/net/dev */
    static String devCountersWlan(String procNetDev) {
        if (procNetDev == null) return "unreadable";
        StringBuilder b = new StringBuilder();
        for (String row : procNetDev.split("\n")) {
            int c = row.indexOf(':');
            if (c < 0) continue;
            String n = row.substring(0, c).trim();
            if (!isWlanName(n) && !n.equals("eth0")) continue;
            String[] f = row.substring(c + 1).trim().split("\\s+");
            if (f.length >= 9) b.append(b.length() > 0 ? "; " : "").append(n).append(" rx=").append(f[0]).append(" tx=").append(f[8]);
        }
        return b.length() == 0 ? "none" : b.toString();
    }

    static String apStateName(int s) {
        switch (s) {
            case 10: return "DISABLING(10)";
            case 11: return "DISABLED(11)";
            case 12: return "ENABLING(12)";
            case 13: return "ENABLED(13)";
            case 14: return "FAILED(14)";
            default: return "unknown(" + s + ")";
        }
    }

    static String wifiStateName(int s) {
        switch (s) {
            case 0: return "DISABLING(0)";
            case 1: return "DISABLED(1)";
            case 2: return "ENABLING(2)";
            case 3: return "ENABLED(3)";
            case 4: return "UNKNOWN(4)";
            default: return "unknown(" + s + ")";
        }
    }

    private static String val(String s) {
        return s == null ? "?" : s.replace('\n', ' ');
    }

    private static String join(String[] a) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < a.length; i++) b.append(i > 0 ? "," : "").append(a[i]);
        return b.toString();
    }

    static List<String> sorted(String[] a) {
        List<String> l = new ArrayList<String>(Arrays.asList(a));
        java.util.Collections.sort(l);
        return l;
    }
}
