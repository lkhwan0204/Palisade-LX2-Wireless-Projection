package hmgapi.mods.wireless.pp;

import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiConfiguration;
import android.os.SystemClock;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import hmgapi.hook.Hooks;
import hmgapi.hook.LoadedApp;
import hmgapi.hook.MethodHook;
import hmgapi.hook.ModContext;

/**
 * Wireless Android Auto in the PhoneProjection process (com.daudio.app.phoneprojection): the connectivity hotspot must be up when the
 * stock decides to connect a phone, and nothing starts it (see {@link ApReady}). Two stock entry points end silently when it is off;
 * both are hooked by name (the names are this firmware's, LX2 260902: obfuscated classes, interface method names):
 *
 *  1. wireless.service.a.requestWLDeviceReadyStatusUpdate(WLDeviceInfo, int): GPM hands a phone over after the version exchange.
 *  2. wireless.service.h.onReceive(Context, Intent) with initPopupDone: the driver answered the first-use question.
 *
 * When, and only when, {@link ApReady#decide} finds the stock about to stop for want of the hotspot, the call is held back (this
 * method returns at once, the caller is not kept waiting), the hotspot is started with the stock's own request and the held call is
 * made exactly once afterwards, on a worker thread, whatever happened to the hotspot (so the stock decides as it would have anyway).
 * Nothing else of the stock is changed or skipped: no switch, no popup, no connect is forced. This class touches nothing of CarPlay.
 * persist.hmgapi.aa.apready=0 leaves the hooks out.
 */
public final class PpHooks {
    private PpHooks() {}

    static final String BUILD = "lx2dev.12";
    static final String IMPL = "com.daudio.app.phoneprojection.wireless.service.a";
    static final String RECEIVER = "com.daudio.app.phoneprojection.wireless.service.h";
    static final String PP_D = "com.daudio.app.phoneprojection.d";
    static final String PP_F = "com.daudio.app.phoneprojection.wireless.a.f";
    static final String INFO = "android.phoneprojection.WLDeviceInfo";
    /** at most this many holds in one process life, whatever happens */
    static final int MAX_HOLDS = 8;

    private static ModContext mod;
    private static Context ctx;
    private static ClassLoader cl;
    private static Class<?> infoClass;
    private static int holds;
    /** set on the thread that hands a held call back: the hooks let that call through untouched */
    private static final ThreadLocal<Boolean> HANDBACK = new ThreadLocal<Boolean>();
    private static final Map<String, Long> lastHold = new HashMap<String, Long>();

    public static void start(ModContext m, LoadedApp app) {
        mod = m;
        ctx = app.context;
        cl = app.classLoader;
        if ("0".equals(prop("persist.hmgapi.aa.apready", "1"))) {
            say("hotspot-ready hooks left out (persist.hmgapi.aa.apready=0)");
            return;
        }
        if (ctx == null || cl == null) {
            say("hotspot-ready hooks not installed: no context in " + app.processName);
            return;
        }
        String ready = hookReadyUpdate();
        String popup = hookPopupAnswer();
        say("hotspot-ready hooks (build " + BUILD + ") in " + app.processName + ": ready update " + ready + ", first-use popup answer " + popup);
    }

    // ---- the two hooks ------------------------------------------------------------------------------------------------------------

    private static String hookReadyUpdate() {
        try {
            final Class<?> impl = Hooks.findClass(IMPL, cl);
            infoClass = Hooks.findClass(INFO, cl);
            Hooks.findAndHookMethod(impl, "requestWLDeviceReadyStatusUpdate", infoClass, int.class, new MethodHook() {
                @Override
                protected void beforeHookedMethod(Param p) {
                    try {
                        if (HANDBACK.get() != null || !mod.enabled()) return;
                        Object info = p.args[0];
                        int ready = ((Integer) p.args[1]).intValue();
                        if (info == null) return;
                        String addr = str(info, "getWLDevBtAddr");
                        ApReady.Facts f = new ApReady.Facts();
                        f.ready = ready;
                        f.transfer = num(info, "getWLTransferType");
                        wifiFacts(f);
                        if (f.transfer == ApReady.TRANSFER_WIRELESS && f.apState != ApReady.AP_ENABLED) {
                            // what the stock reads next, read here the same way (only when the hotspot is not up: otherwise nothing is needed)
                            Object row = row(addr, str(info, "getWLDevSerialNum"));
                            if (row != null) {
                                f.initPopup = flag(row, "getWLDInitPopup");
                                f.phoneOn = flag(row, "getWLDOnOff");
                            }
                            f.aaSwitch = aaSwitch(info);
                        }
                        f.sinceLastTry = since(addr + "/r");
                        ApReady.Verdict v = ApReady.decide(f);
                        say("ready update (" + who(addr) + "): ready=" + ready + " transfer=" + f.transfer + " " + describe(f) + " -> " + v + " (" + v.why + ")");
                        if (v == ApReady.Verdict.HOLD && holds < MAX_HOLDS) hold(p, who(addr), addr + "/r", "the ready update");
                    } catch (Throwable t) {
                        mod.error("pp: ready update hook", t);
                    }
                }
            });
            return "ok";
        } catch (Throwable t) {
            mod.error("pp: ready update hook not installed", t);
            return "NOT installed (" + t + ")";
        }
    }

    private static String hookPopupAnswer() {
        try {
            final Class<?> rcv = Hooks.findClass(RECEIVER, cl);
            Hooks.findAndHookMethod(rcv, "onReceive", Context.class, Intent.class, new MethodHook() {
                @Override
                protected void beforeHookedMethod(Param p) {
                    try {
                        if (HANDBACK.get() != null || !mod.enabled()) return;
                        Intent in = (Intent) p.args[1];
                        if (in == null || !in.hasExtra("initPopupDone") || !in.getBooleanExtra("initPopupDone", false)) return;   // not the driver's "yes"
                        String addr = in.getStringExtra("deviceAddress");
                        ApReady.Facts f = new ApReady.Facts();
                        f.popupDone = true;
                        f.transfer = in.getIntExtra("transferType", -1);
                        wifiFacts(f);
                        if (f.transfer == ApReady.TRANSFER_WIRELESS && f.apState != ApReady.AP_ENABLED) f.aaSwitch = aaSwitch(null);
                        f.sinceLastTry = since(addr + "/p");
                        ApReady.Verdict v = ApReady.decide(f);
                        say("first-use popup answered yes (" + who(addr) + "): transfer=" + f.transfer + " " + describe(f) + " -> " + v + " (" + v.why + ")");
                        if (v == ApReady.Verdict.HOLD && holds < MAX_HOLDS) hold(p, who(addr), addr + "/p", "the popup answer");
                    } catch (Throwable t) {
                        mod.error("pp: popup answer hook", t);
                    }
                }
            });
            return "ok";
        } catch (Throwable t) {
            mod.error("pp: popup answer hook not installed", t);
            return "NOT installed (" + t + ")";
        }
    }

    // ---- holding a call and handing it back exactly once ----------------------------------------------------------------------------

    private static void hold(MethodHook.Param p, final String who, String key, final String what) {
        final Method method = (Method) p.method;
        final Object target = p.thisObject;
        final Object[] args = p.args.clone();
        synchronized (PpHooks.class) {
            holds++;
            lastHold.put(key, Long.valueOf(SystemClock.elapsedRealtime()));
        }
        p.setResult(null);   // the stock's own call is skipped now and made by handBack below
        try {
            Thread t = new Thread("hmgapi-aa-apready") {
                @Override
                public void run() {
                    work(method, target, args, who, what);
                }
            };
            t.setDaemon(true);
            t.start();
        } catch (Throwable x) {
            mod.error("pp: could not start the worker, handing " + what + " back now", x);
            handBack(method, target, args, who, what);
        }
    }

    private static void work(Method method, Object target, Object[] args, String who, String what) {
        try {
            say("holding " + what + " for " + who + " until the connectivity hotspot is up (the stock request, the Wi-Fi service's own configuration)");
            WifiPlatform w = new WifiPlatform(ctx);
            ApReady.Outcome o = ApReady.ensure(w, ApReady.WAIT_MS);
            say("hotspot for " + who + ": " + o.result + " - " + o.note + (o.requested ? " | request: " + w.requestNote() : "") + (o.result == ApReady.Result.READY ? " | " + w.pairNote() : ""));
        } catch (Throwable t) {
            mod.error("pp: waiting for the hotspot", t);
        } finally {
            handBack(method, target, args, who, what);   // always, once: the stock decides, whatever happened to the hotspot
        }
    }

    private static void handBack(Method method, Object target, Object[] args, String who, String what) {
        HANDBACK.set(Boolean.TRUE);
        try {
            try {
                method.setAccessible(true);
            } catch (Throwable t) {
                // already accessible
            }
            method.invoke(target, args);
            say("handed " + what + " for " + who + " back to PhoneProjection (once)");
        } catch (InvocationTargetException e) {
            mod.error("pp: PhoneProjection threw in the handed-back call", e.getCause() != null ? e.getCause() : e);
        } catch (Throwable t) {
            mod.error("pp: could not hand " + what + " back", t);
        } finally {
            HANDBACK.remove();
        }
    }

    // ---- facts -------------------------------------------------------------------------------------------------------------------

    private static void wifiFacts(ApReady.Facts f) {
        WifiPlatform w = new WifiPlatform(ctx);
        f.apState = w.state();
        f.apMode = w.mode();
    }

    /** the phone's own entry in PhoneProjection's list, read the way the stock reads it (null: not found or not readable) */
    private static Object row(String addr, String serial) {
        try {
            if (addr == null) return null;
            Class<?> d = Hooks.findClass(PP_D, cl), f = Hooks.findClass(PP_F, cl);
            Object type = d.getMethod("ea").invoke(null);
            return f.getMethod("a", Context.class, String.class, String.class, int.class).invoke(null, ctx, addr, serial, type);
        } catch (Throwable t) {
            return null;
        }
    }

    /** the Android Auto switch PhoneProjection reads (Settings.System androidauto_information*, per user): 1 on, 0 off, -1 not readable */
    private static int aaSwitch(Object info) {
        try {
            Class<?> d = Hooks.findClass(PP_D, cl);
            Object type = d.getMethod("ea").invoke(null);
            Object on = d.getMethod("ak", int.class).invoke(null, type);
            return Boolean.TRUE.equals(on) ? 1 : 0;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static long since(String key) {
        synchronized (PpHooks.class) {
            Long t = lastHold.get(key);
            return t == null ? -1 : SystemClock.elapsedRealtime() - t.longValue();
        }
    }

    private static String describe(ApReady.Facts f) {
        return "hotspot state=" + f.apState + " mode=" + f.apMode + " first-use-answered=" + (f.popupDone ? "yes" : tri(f.initPopup))
                + " android-auto-switch=" + tri(f.aaSwitch) + " phone-on=" + (f.popupDone ? "-" : tri(f.phoneOn));
    }

    private static String tri(int v) {
        return v < 0 ? "?" : v == 0 ? "no" : "yes";
    }

    // ---- reflection helpers --------------------------------------------------------------------------------------------------------

    private static String str(Object o, String m) {
        try {
            Object r = o.getClass().getMethod(m).invoke(o);
            return r instanceof String ? (String) r : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int num(Object o, String m) {
        try {
            Object r = o.getClass().getMethod(m).invoke(o);
            return r instanceof Number ? ((Number) r).intValue() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int flag(Object o, String m) {
        try {
            Object r = o.getClass().getMethod(m).invoke(o);
            return r instanceof Boolean ? (((Boolean) r).booleanValue() ? 1 : 0) : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** a short stand-in for a Bluetooth address in the log: never the address itself */
    static String who(String addr) {
        return addr == null ? "phone#?" : "phone#" + Integer.toHexString(addr.hashCode() & 0xffff);
    }

    static void say(String m) {
        mod.log("pp: " + m);
    }

    static String prop(String name, String def) {
        try {
            Object v = Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class).invoke(null, name, def);
            return v == null ? def : String.valueOf(v);
        } catch (Throwable t) {
            return def;
        }
    }

    // ---- the Wi-Fi service, as the stock asks it ------------------------------------------------------------------------------------

    static final class WifiPlatform implements ApReady.Platform {
        private final Context c;
        private volatile String last = "no request yet";
        private volatile Object lastConfig;

        WifiPlatform(Context c) {
            this.c = c;
        }

        private Object call(String name, Class<?>[] types, Object... args) throws Exception {
            Object w = c.getSystemService(Context.WIFI_SERVICE);
            if (w == null) throw new IllegalStateException("no WifiManager");
            return w.getClass().getMethod(name, types).invoke(w, args);
        }

        @Override
        public int state() {
            try {
                return ((Integer) call("getWifiApState", new Class<?>[0])).intValue();
            } catch (Throwable t) {
                try {
                    return Boolean.TRUE.equals(call("isWifiApEnabled", new Class<?>[0])) ? ApReady.AP_ENABLED : ApReady.AP_DISABLED;
                } catch (Throwable t2) {
                    return -1;
                }
            }
        }

        @Override
        public int mode() {
            try {
                return ((Integer) call("getWifiApMode", new Class<?>[0])).intValue();
            } catch (Throwable t) {
                return -1;
            }
        }

        @Override
        public Object config() {
            try {
                Object cfg = call("getWifiApConfiguration", new Class<?>[0]);
                lastConfig = cfg;
                return cfg;
            } catch (Throwable t) {
                return null;
            }
        }

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
                    auth = ((Integer) cfg.getClass().getMethod("getAuthType").invoke(cfg)).intValue();
                } catch (Throwable t) {
                    auth = -1;
                }
            }
            return ApReady.configProblem(valid, auth, strField(cfg, "SSID"), strField(cfg, "preSharedKey"),
                    strField(cfg, "ConnectivitySSID"), strField(cfg, "ConnectivityPSK"), strField(cfg, "vendorSpecific"));
        }

        /** the service's own object, handed back unmodified: exactly what PhoneProjection's turnOnWiFi() does */
        @Override
        public boolean request(Object cfg) {
            if (cfg == null) {
                last = "not sent: no checked configuration";
                return false;
            }
            long t0 = SystemClock.elapsedRealtime();
            try {
                Object r = call("setWifiApEnabledForConnectivity", new Class<?>[] { WifiConfiguration.class, boolean.class }, cfg, Boolean.TRUE);
                last = "returned " + r + " in " + (SystemClock.elapsedRealtime() - t0) + " ms";
                return !Boolean.FALSE.equals(r);
            } catch (Throwable t) {
                Throwable cause = t instanceof InvocationTargetException && t.getCause() != null ? t.getCause() : t;
                String m = cause.getMessage();
                last = "threw " + cause.getClass().getName() + (m == null ? "" : ": " + (m.length() > 100 ? m.substring(0, 100) : m)) + " after " + (SystemClock.elapsedRealtime() - t0) + " ms";
                return false;
            }
        }

        @Override
        public String requestNote() {
            return last;
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

        /** whether the name and key the phone is told equal the hotspot's own (yes/no only, never the values) */
        String pairNote() {
            Object cfg = lastConfig != null ? lastConfig : config();
            if (cfg == null) return "pair: unreadable";
            boolean ssid = same(strField(cfg, "SSID"), strField(cfg, "ConnectivitySSID"));
            boolean key = same(strField(cfg, "preSharedKey"), strField(cfg, "ConnectivityPSK"));
            return "the name and key the phone is told equal the hotspot's: name " + (ssid ? "yes" : "NO") + ", key " + (key ? "yes" : "NO");
        }

        private static boolean same(String a, String b) {
            return a != null && a.equals(b);
        }

        private static String strField(Object cfg, String name) {
            try {
                return (String) cfg.getClass().getField(name).get(cfg);
            } catch (Throwable t) {
                return null;
            }
        }
    }
}
