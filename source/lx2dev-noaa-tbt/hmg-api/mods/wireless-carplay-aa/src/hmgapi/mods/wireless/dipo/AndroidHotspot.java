package hmgapi.mods.wireless.dipo;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.wifi.WifiConfiguration;
import android.provider.Settings;
import android.os.SystemClock;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.Inet4Address;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * HotspotGuard's Android side, for the DiPO process (system uid): WifiManager's hidden hotspot calls by
 * reflection (all present in the LX2 framework: getWifiApState, getWifiApMode, getWifiApConfiguration,
 * setWifiApEnabledForConnectivity, isWifiEnabled, setWifiEnabled) and the raw wifi_ap_on row of Settings.Global.
 *
 * The AP configuration is only ever read (getWifiApConfiguration) and handed back unmodified to
 * setWifiApEnabledForConnectivity(config, true). This class never builds a WifiConfiguration, never sets a field on one and
 * never calls setWifiApConfiguration: the service persists what it is given when the AP starts. lx2dev.1 passed a hand-built
 * copy (SSID, BSSID, preSharedKey, hiddenSSID, allowedKeyManagement only), whose ConnectivitySSID was null; the service's
 * writer then died on writeUTF(null) and took system_server with it. Not run on a car yet.
 */
final class AndroidHotspot implements HotspotGuard.Platform {
    static final String SETTING = "wifi_ap_on";
    private final Context c;
    private volatile String lastRequest = "no request yet";

    AndroidHotspot(Context c) {
        this.c = c;
    }

    private Object wifi() {
        return c.getSystemService(Context.WIFI_SERVICE);
    }

    private Object call(String name, Class<?>[] types, Object... args) throws Exception {
        Object w = wifi();
        if (w == null) throw new IllegalStateException("no WifiManager");
        return w.getClass().getMethod(name, types).invoke(w, args);
    }

    @Override
    public int apState() {
        try {
            return (Integer) call("getWifiApState", new Class<?>[0]);
        } catch (Throwable t) {
            try {
                return (Boolean) call("isWifiApEnabled", new Class<?>[0]) ? HotspotGuard.AP_ENABLED : HotspotGuard.AP_DISABLED;
            } catch (Throwable t2) {
                return -1;
            }
        }
    }

    @Override
    public int apMode() {
        try {
            return (Integer) call("getWifiApMode", new Class<?>[0]);
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * The configuration exactly as the Wi-Fi service holds it. It crosses Binder as a Parcel, which carries every Hyundai field
     * (ConnectivitySSID/PSK, carplayIE, channels, vendorSpecific, apMode), so this is already a full copy of our own. It is NEVER
     * rebuilt by hand from a few fields: the service starts the AP from the object it is given AND persists that same object
     * (SoftApStartingState -> WifiApConfigStore.writeApConfiguration), and a field left null there ends system_server.
     */
    @Override
    public Object apConfig() {
        try {
            return call("getWifiApConfiguration", new Class<?>[0]);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The checks the stock writer (WifiApConfigStore.writeApConfiguration) and start path depend on, read from the object itself:
     * isValid() and getAuthType() are the service's own methods (getAuthType() throws on an invalid key-management set), the strings
     * are the ones writeUTF is called on. Anything that cannot be read counts as a problem: no guess, no default.
     */
    @Override
    public String configProblem(Object cfg) {
        if (cfg == null) return "no configuration";
        boolean valid;
        int auth = -1;
        try {
            valid = Boolean.TRUE.equals(cfg.getClass().getMethod("isValid").invoke(cfg));
        } catch (Throwable t) {
            valid = false;
        }
        if (valid) {
            try {
                auth = (Integer) cfg.getClass().getMethod("getAuthType").invoke(cfg);
            } catch (Throwable t) {
                auth = -1;
            }
        }
        return SessionPolicy.ApConfigCheck.problem(valid, auth, strField(cfg, "SSID"), strField(cfg, "preSharedKey"),
                strField(cfg, "ConnectivitySSID"), strField(cfg, "ConnectivityPSK"), strField(cfg, "vendorSpecific"));
    }

    /** field names only. channel, channel24GHzBand, is24GHzBand and apMode are the service's own bookkeeping (apMode is set to 2 by the request, the channel after the AP is up) */
    @Override
    public String configDiff(Object a, Object b) {
        if (a == null || b == null) return a == b ? "" : "presence";
        StringBuilder d = new StringBuilder();
        String[] names = { "SSID", "preSharedKey", "ConnectivitySSID", "ConnectivityPSK", "carplayIE", "vendorSpecific", "hiddenSSID", "allowedKeyManagement" };
        for (String n : names) {
            Object x = field(a, n), y = field(b, n);
            if (x == null ? y != null : !x.equals(y)) {
                if (d.length() > 0) d.append(", ");
                d.append(n);
            }
        }
        return d.toString();
    }

    private static String strField(Object cfg, String name) {
        try {
            return (String) cfg.getClass().getField(name).get(cfg);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object field(Object cfg, String name) {
        try {
            return cfg.getClass().getField(name).get(cfg);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int len(String s) {
        return s == null ? -1 : s.length();
    }

    @Override
    public String describeConfig(Object cfg) {
        if (cfg == null) return "none";
        String ssid = strField(cfg, "SSID"), psk = strField(cfg, "preSharedKey");
        String cs = strField(cfg, "ConnectivitySSID"), cp = strField(cfg, "ConnectivityPSK"), ie = strField(cfg, "carplayIE");
        return "ssid=" + SessionPolicy.shownSsid(ssid) + " connSsid=" + SessionPolicy.shownSsid(cs) + " ssidLen=" + len(ssid) + " pskLen=" + len(psk) + " connSsidLen=" + len(cs) + " connPskLen=" + len(cp) + " carplayIELen=" + len(ie)
                + " ssid==connSsid=" + (ssid != null && ssid.equals(cs)) + " psk==connPsk=" + (psk != null && psk.equals(cp))
                + " apMode=" + field(cfg, "apMode") + " channel=" + field(cfg, "channel") + " connChannel=" + field(cfg, "connectivityChannel")
                + " is24=" + field(cfg, "is24GHzBand") + " keyMgmt=" + field(cfg, "allowedKeyManagement");
    }

    /**
     * on: starts the AP with exactly the object HotspotGuard read from the service and checked. Nothing in it is changed
     * (no credential alignment, no filled-in value): the service sets apMode on it and persists it, as for any start.
     * off: no object; the stock stop path (setHostApRunning(null, false) -> CMD_STOP_AP) never reads one.
     */
    @Override
    public boolean requestAp(Object config, boolean on) {
        if (on && config == null) {   // never ask the service to start an AP with nothing checked
            lastRequest = "on=true not sent: no checked configuration";
            return false;
        }
        long t0 = SystemClock.elapsedRealtime();
        Bridge.noteApRequest(on);   // so a state change that follows is known to be this mod's own
        try {
            Object r = call("setWifiApEnabledForConnectivity", new Class<?>[] { WifiConfiguration.class, boolean.class }, on ? config : null, on);
            lastRequest = "on=" + on + " returned " + r + " in " + (SystemClock.elapsedRealtime() - t0) + " ms";
            return !Boolean.FALSE.equals(r);
        } catch (Throwable t) {
            Throwable cause = t instanceof InvocationTargetException && t.getCause() != null ? t.getCause() : t;
            String m = cause.getMessage();
            lastRequest = "on=" + on + " threw " + cause.getClass().getName() + (m == null ? "" : ": " + (m.length() > 120 ? m.substring(0, 120) : m))
                    + " after " + (SystemClock.elapsedRealtime() - t0) + " ms";
            Dipo.error("bridge: requestAp(" + on + ")", t);
            return false;
        }
    }

    @Override
    public String requestNote() {
        return lastRequest;
    }

    @Override
    public boolean alignOn() {
        return !"0".equals(Bridge.prop("persist.hmgapi.cpwl.align", "1"));
    }

    /** the hotspot pair against the pair the phone is told: names and lengths only (the values stay in this object) */
    @Override
    public SessionPolicy.CredPair credPair(Object cfg) {
        int auth = -1;
        try {
            if (Boolean.TRUE.equals(cfg.getClass().getMethod("isValid").invoke(cfg))) auth = (Integer) cfg.getClass().getMethod("getAuthType").invoke(cfg);
        } catch (Throwable t) {
            auth = -1;
        }
        return SessionPolicy.CredPair.of(auth, strField(cfg, "SSID"), strField(cfg, "preSharedKey"), strField(cfg, "ConnectivitySSID"), strField(cfg, "ConnectivityPSK"));
    }

    /**
     * The only place a field of the Wi-Fi configuration is set: ConnectivitySSID and ConnectivityPSK take the hotspot's own SSID and
     * preSharedKey, on the service's own copy that HotspotGuard has already checked (every other field, among them the Hyundai ones,
     * stays as the service gave it, so the stock writer finds no null). The copy is then stored with setWifiApConfiguration, which the
     * service validates (isValid) and persists without restarting the AP; the AP itself never reads these two fields.
     */
    @Override
    public boolean storePair(Object cfg) {
        long t0 = SystemClock.elapsedRealtime();
        try {
            String ssid = strField(cfg, "SSID"), psk = strField(cfg, "preSharedKey");
            if (ssid == null) {
                lastRequest = "no SSID to copy";
                return false;
            }
            cfg.getClass().getField("ConnectivitySSID").set(cfg, ssid);
            if (psk != null) cfg.getClass().getField("ConnectivityPSK").set(cfg, psk);
            Object r = call("setWifiApConfiguration", new Class<?>[] { WifiConfiguration.class }, cfg);
            lastRequest = "setWifiApConfiguration returned " + r + " in " + (SystemClock.elapsedRealtime() - t0) + " ms";
            return !Boolean.FALSE.equals(r);
        } catch (Throwable t) {
            Throwable cause = t instanceof InvocationTargetException && t.getCause() != null ? t.getCause() : t;
            String m = cause.getMessage();
            lastRequest = "setWifiApConfiguration threw " + cause.getClass().getName() + (m == null ? "" : ": " + (m.length() > 120 ? m.substring(0, 120) : m));
            Dipo.error("bridge: storePair", t);
            return false;
        }
    }

    /** what the running AP looks like from here (processes, hostapd.conf compared with the service's configuration, the interface), without any value */
    @Override
    public ApAudit.Facts audit(String when) {
        Object cfg = apConfig();
        ApAudit.Facts f = ApAudit.audit(new JavaFs(), cfg == null ? null : strField(cfg, "SSID"), cfg == null ? null : strField(cfg, "preSharedKey"));
        String dev = Bridge.prop("wifi.interface", "wlan0");
        List<String> addrs = addrs();
        for (String a : addrs) {
            String[] x = a.split("\\|");
            if (x.length >= 3 && x[0].equals(dev)) {
                f.ifaceKnown = true;
                f.ifaceUp = x[1].equals("true");
                f.ifaceHasIpv4 = true;
                f.ifaceAddr = x[2];
            }
        }
        if (!f.ifaceKnown && !addrs.isEmpty()) {
            f.ifaceKnown = true;   // the interface list was readable and the hotspot interface has no IPv4 address
        }
        f.subnetClash = WifiProbe.subnetConflict(addrs) != null;
        for (String l : ApAudit.lines(f, SessionPolicy.shownSsid(cfg == null ? null : strField(cfg, "SSID")))) Dipo.log("bridge: ap audit[" + when + "] " + l);
        return f;
    }

    /** the hotspot's SSID and key as bytes, for the one comparison with what the phone was told; kept only for that comparison */
    byte[][] credBytes() {
        try {
            Object cfg = apConfig();
            if (cfg == null) return null;
            String ssid = strField(cfg, "SSID"), psk = strField(cfg, "preSharedKey");
            return new byte[][] { ssid == null ? null : ssid.getBytes("UTF-8"), psk == null ? null : psk.getBytes("UTF-8") };
        } catch (Throwable t) {
            return null;
        }
    }

    /** "name|up|ip/prefix" for every IPv4 address on every interface */
    private List<String> addrs() {
        List<String> out = new ArrayList<String>();
        try {
            Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();
            if (e != null) for (NetworkInterface ni : Collections.list(e)) {
                boolean up;
                try {
                    up = ni.isUp();
                } catch (Throwable t) {
                    up = false;
                }
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getAddress() instanceof Inet4Address) out.add(ni.getName() + "|" + up + "|" + ia.getAddress().getHostAddress() + "/" + ia.getNetworkPrefixLength());
                }
            }
        } catch (Throwable t) {
            // none
        }
        return out;
    }

    /** the Wi-Fi facts for the log (WifiProbe decides what they mean); read only */
    @Override
    public String diagnose(String when, boolean full) {
        try {
            WifiProbe.Report r = WifiProbe.report(new JavaFs(), new SysProps(), framework(), full);
            for (String l : r.lines) Dipo.log("bridge: wifi[" + when + "] " + l);
            return r.verdict.name();
        } catch (Throwable t) {
            Dipo.error("bridge: wifi probe", t);
            return "UNKNOWN";
        }
    }

    /** the framework's side of the probe: what WifiManager has and says, the feature flags, the addresses on the interfaces */
    private WifiProbe.Fw framework() {
        WifiProbe.Fw fw = new WifiProbe.Fw();
        Object w = wifi();
        fw.manager = w != null;
        StringBuilder essential = new StringBuilder(), optional = new StringBuilder();
        if (w != null) {
            List<String> have = new ArrayList<String>();
            for (java.lang.reflect.Method m : w.getClass().getMethods()) have.add(m.getName());
            String[] must = { "getWifiApState", "setWifiApEnabledForConnectivity" };
            String[] nice = { "getWifiApMode", "getWifiApConfiguration", "isWifiEnabled", "setWifiEnabled" };
            for (String n : must) if (!have.contains(n)) essential.append(essential.length() > 0 ? "," : "").append(n);
            for (String n : nice) if (!have.contains(n)) optional.append(optional.length() > 0 ? "," : "").append(n);
            try {
                fw.wifiState = (Integer) w.getClass().getMethod("getWifiState").invoke(w);
            } catch (Throwable t) {
                fw.wifiState = -1;
            }
        } else {
            essential.append("no WifiManager");
        }
        fw.missingEssential = essential.toString();
        fw.missingOptional = optional.toString();
        fw.apState = apState();
        fw.hotspotMode = apMode();
        fw.wifiOn = staEnabled();
        fw.settingWifiApOn = setting();
        try {
            android.content.pm.PackageManager pm = c.getPackageManager();
            fw.featureWifi = pm.hasSystemFeature("android.hardware.wifi");
            fw.featureDirect = pm.hasSystemFeature("android.hardware.wifi.direct");
        } catch (Throwable t) {
            // left false
        }
        fw.addrs.addAll(addrs());
        return fw;
    }

    /** read-only file access for WifiProbe: sysfs/procfs reads and directory listings, nothing is written */
    static final class JavaFs implements WifiProbe.Fs {
        @Override
        public String[] list(String dir) {
            try {
                String[] l = new File(dir).list();
                if (l != null) Arrays.sort(l);
                return l;
            } catch (Throwable t) {
                return null;
            }
        }

        @Override
        public String read(String path, int max) {
            try {
                File f = new File(path);
                if (!f.exists()) return null;
                FileInputStream in = new FileInputStream(f);
                try {
                    byte[] b = new byte[max];
                    int n = in.read(b);
                    return n < 0 ? "" : new String(b, 0, n, "UTF-8").trim();
                } finally {
                    in.close();
                }
            } catch (Throwable t) {
                return null;
            }
        }

        @Override
        public String canonical(String path) {
            try {
                File f = new File(path);
                return f.exists() ? f.getCanonicalPath() : null;
            } catch (Throwable t) {
                return null;
            }
        }

        @Override
        public long size(String path) {
            try {
                File f = new File(path);
                return f.exists() ? f.length() : -1;
            } catch (Throwable t) {
                return -1;
            }
        }
    }

    static final class SysProps implements WifiProbe.Props {
        @Override
        public String get(String key) {
            return Bridge.prop(key, "");
        }
    }

    @Override
    public boolean staEnabled() {
        try {
            return (Boolean) call("isWifiEnabled", new Class<?>[0]);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean setSta(boolean on) {
        try {
            return !Boolean.FALSE.equals(call("setWifiEnabled", new Class<?>[] { boolean.class }, on));
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public String setting() {
        Cursor q = null;
        try {
            ContentResolver r = c.getContentResolver();
            q = r.query(Settings.Global.CONTENT_URI, new String[] { "value" }, "name=?", new String[] { SETTING }, null);
            return q != null && q.moveToFirst() ? q.getString(0) : null;
        } catch (Throwable t) {
            return null;
        } finally {
            if (q != null) q.close();
        }
    }

    @Override
    public boolean putSetting(String value) {
        try {
            return Settings.Global.putString(c.getContentResolver(), SETTING, value);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean deleteSetting() {
        try {
            return c.getContentResolver().delete(Settings.Global.CONTENT_URI, "name=?", new String[] { SETTING }) >= 0;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public long now() {
        return SystemClock.elapsedRealtime();
    }

    @Override
    public void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** the journal in DiPO's own files folder: the original wifi_ap_on and Wi-Fi client state, never the hotspot key */
    static final class FileJournal implements HotspotGuard.Journal {
        private final File f;

        FileJournal(Context c) {
            f = c.getFileStreamPath("hmgapi-ap-baseline");
        }

        @Override
        public void save(HotspotGuard.Baseline b) {
            try {
                FileOutputStream o = new FileOutputStream(f);
                try {
                    o.write(b.serialize().getBytes("UTF-8"));
                    o.getFD().sync();
                } finally {
                    o.close();
                }
            } catch (Throwable t) {
                Dipo.error("bridge: journal save", t);
            }
        }

        @Override
        public HotspotGuard.Baseline load() {
            if (!f.isFile()) return null;
            try {
                FileInputStream in = new FileInputStream(f);
                try {
                    byte[] b = new byte[(int) Math.min(f.length(), 4096)];
                    int n = in.read(b);
                    return HotspotGuard.Baseline.parse(new String(b, 0, Math.max(n, 0), "UTF-8"));
                } finally {
                    in.close();
                }
            } catch (Throwable t) {
                return null;
            }
        }

        @Override
        public void clear() {
            f.delete();
        }
    }
}
