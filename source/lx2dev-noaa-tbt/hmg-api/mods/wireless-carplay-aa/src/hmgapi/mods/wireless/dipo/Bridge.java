package hmgapi.mods.wireless.dipo;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.ParcelUuid;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import com.broadcom.bt.hfdevice.hfbtcm.HfDeviceBTCMManager;
import com.broadcom.bt.hfdevice.hfbtcm.HfDeviceStatusListener;
import com.tcc.iap2.IIap2Listener;
import com.tcc.iap2.IIap2Service;
import com.tcc.iap2.Iap2ListenerInfo;
import com.tcc.iap2.Iap2NotiMsg;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import hmgapi.hook.Points;

/**
 * Wireless CarPlay's Bluetooth bridge, in DiPO (from the bridge build, com.ev6.cpwl).
 *
 * An iPhone pairs over Bluetooth; wireless CarPlay starts with iAP2 over Bluetooth
 * (RFCOMM/SPP), which the iAP2 server here can't do by itself. The bridge carries it:
 * SPP data from Broadcom's Bluetooth manager goes into the iAP2 server as transport data
 * (Iap2SvcTransportReadDone), and what the server sends back (notification 80) goes out
 * over SPP. The iAP2 server opens the session for BT_ATTACHED (HMG-API's hook there, part
 * "bridge"). Once the phone has joined the hotspot and the CarPlay session runs, iAP2
 * moves into the CarPlay session's own channel: bytes the phone sends there
 * (oniAPSendMessage) go to the server, and what the server sends for it
 * (notification 81) goes out through the CarPlay library.
 *
 * HMG-API binds the iAP2 server and hooks Broadcom's manager (a library), and only
 * observes DiPO's CarPlay callbacks; DiPO's own iAP2 listener is told to ignore what
 * belongs to the bridge's sessions (Dipo's hooks).
 *
 * persist.ev6.cpwl.enable=0 turns it off; persist.ev6.cpwl.handover=created moves iAP2
 * into the CarPlay channel as soon as the session is created (default: on its first data).
 */
final class Bridge {
    static final String ACTION_ATTACH = "com.ev6.cpwl.BT_ATTACHED", ACTION_DETACH = "com.ev6.cpwl.BT_DETACHED";
    static final String IAP2_PKG = "com.tcc.iap2server";
    static final int APPLE_VENDOR_ID = 76;
    /** iAP2 components: 1 USB, 4 Bluetooth CarPlay, 5 the CarPlay session's channel */
    static final int COMPONENT_USB = 1, COMPONENT_BT_CARPLAY = 4, COMPONENT_CARPLAY_CLIENT = 5;
    static final int LISTENER_EXTERNAL_BT_TRANSPORT = 1024, LISTENER_EXTERNAL_CARPLAY_TRANSPORT = 2048;
    static final int NOTI_IDENTIFICATION_ACCEPTED = 0, NOTI_TRANSPORT_IDENTIFIER = 24;
    static final int NOTI_BT_TRANSPORT_DATA = 80, NOTI_CARPLAY_CLIENT_TRANSPORT_DATA = 81, OBJ_BLOB = 11;
    static final int MAX_PENDING = 64;
    static final long SPP_CONNECT_DELAY_MS = 3000;
    static final int SPP_DISCONNECTED = 0, SPP_CONNECTED = 2, EVENT_BTCM_CONNECTED = 2;

    static volatile String sAddr;
    static volatile HfDeviceBTCMManager sBtcm;
    static volatile Object sCp;
    static volatile boolean sCpClient, sCpConnected, sCpWireless, sSession, sUsb;
    static volatile IIap2Service sIap2;
    /** the phone's Bluetooth address from its last iAP2 session (DiPO's first-connect notice) */
    static volatile String sLastBtId;
    static ClassLoader cl;
    static Context context;
    private static int sCpFail, sCpSent;
    private static Handler sHandler, sCpTx;
    private static Method sNativeSend;
    private static final ArrayList<byte[]> sPendingIn = new ArrayList<byte[]>();
    private static BtListener sBt;
    /** bumped whenever a session attempt is overtaken (USB connected): a late hotspot wait can't start it */
    static volatile int sAttempt;
    /** no new attempt before this time (SystemClock.elapsedRealtime) after the hotspot failed */
    static volatile long sApRetryAt;
    private static volatile int sApRelease;
    private static final SessionPolicy.RetryGate sGate = new SessionPolicy.RetryGate();
    private static volatile boolean sEverWireless;
    private static HotspotGuard sAp;
    private static boolean sApRecovered, sStarted;
    /** bytes moved, for the status lines: shows whether the iAP2 conversation after BT_ATTACHED really flows */
    private static volatile long sSppIn, sSppOut, sCpIn, sCpOut;
    private static volatile String sPrevStage = "";
    private static final String CRUMB = "hmgapi-cpwl-stage", QUAR = "hmgapi-cpwl-quarantine", TRACE = "hmgapi-cpwl-trace", REARM_SEEN = "hmgapi-cpwl-rearm-seen";
    static final String REARM_PROP = "persist.hmgapi.cpwl.rearm";
    /** this build's id: the breadcrumb carries it, and a quarantine or crumb of another build does not count against this one */
    static final String BUILD = "lx2dev.11";
    private static volatile String sStage = "";
    /** the quarantine is in HOLD: wireless sessions are held back */
    private static volatile boolean sQuarantined;
    private static volatile SessionPolicy.Quarantine.Gate sGateState = SessionPolicy.Quarantine.Gate.OPEN;
    private static long sQuarLogAt, sLatchLogAt;
    private static final Object sCrumbLock = new Object();
    private static volatile long sMarkAt;
    private static volatile String sExit = "", sBoot;
    private static final SessionTrace sTrace = new SessionTrace();
    private static final SessionPolicy.ApLatch sApLatch = new SessionPolicy.ApLatch();
    private static AndroidHotspot sAndroidAp;
    /** a wireless CarPlay session seen from outside (SessionWatch), and what the bridge knows about its end */
    private static SessionWatch sWatch;
    private static volatile int sWatchGen, sCpPhase;   // phase: 0 none, 1 created, 2 started over Wi-Fi, 3 finalized
    private static volatile long sCpPhaseAt, sApReqAt;
    /** when a phone that is not an iPhone last connected over Bluetooth (elapsed realtime, -1 never): read only, to say whether a hotspot change fell into its wireless Android Auto hand-over */
    private static volatile long sAaPhoneAt = -1;
    static final long AA_HANDOVER_WINDOW_MS = 120000;
    private static volatile boolean sCpStalled, sCpLost, sApOtherDown;
    private static final SessionPolicy.LostSession sLost = new SessionPolicy.LostSession();
    /** wireless Android Auto, observed only: per Android phone address the inbound SPP stream, and the message types seen */
    private static final java.util.HashMap<String, AaProbe.Stream> sAaStreams = new java.util.HashMap<String, AaProbe.Stream>();
    private static final java.util.HashMap<String, Boolean> sAppleCache = new java.util.HashMap<String, Boolean>();
    private static final java.util.HashSet<Integer> sAaTypes = new java.util.HashSet<Integer>();
    private static volatile int sAaMsgs;
    /** the picture path as DiPO's Java side shows it (ScreenWatch), and the key-frame request that follows a NO_OUTPUT_FRAME report */
    private static final ScreenWatch sScreen = new ScreenWatch();
    /** what crossed the bridge over Bluetooth in the current attempt: scanners, what the phone asked for and was told (never a value) */
    private static volatile IapScan sScanIn = new IapScan(), sScanOut = new IapScan();
    private static volatile boolean sAsked, sTold;
    private static volatile Boolean sToldSsidEq, sToldPskEq;
    private static volatile int sMsgOut, sMsgIn;
    private static final java.util.HashSet<Integer> sSeenOut = new java.util.HashSet<Integer>(), sSeenIn = new java.util.HashSet<Integer>();
    /** the last lines of what happened, kept in a file so that the next process can print what the one before it was doing */
    private static final ArrayList<String> sTraceLines = new ArrayList<String>();
    static final long AP_TIMEOUT_MS = 20000, AP_RETRY_BACKOFF_MS = 60000, AP_RELEASE_GRACE_MS = 15000;
    private static TxListener sTx;

    private Bridge() {}

    static void log(String m) {
        Dipo.log("bridge: " + m);
    }

    /** a phone in the logs: "phone#a1b2", enough to tell two phones apart and nothing of the Bluetooth address */
    static String who(String addr) {
        if (addr == null) return "none";
        String h = Integer.toHexString(addr.hashCode() & 0xffff);
        while (h.length() < 4) h = "0" + h;
        return "phone#" + h;
    }

    static void error(String m, Throwable t) {
        Dipo.error("bridge: " + m, t);
    }

    /** DiPO registered its listener with Broadcom's manager (the manager is connected): ours goes next to it */
    static void onBtcmReady(HfDeviceBTCMManager m) {
        if (m == null) return;
        try {
            sBtcm = m;
            synchronized (Bridge.class) {
                if (sBt == null) sBt = new BtListener();
            }
            m.registerHfDeviceStatusListener(sBt);
            log("BTCM listener registered");
            startupReport();
            startWifiProbe();
            BluetoothDevice d = m.IbtcmGetHfpConnectedDevice();
            boolean apple = d != null && isApple(d);
            boolean sppUp = apple && m.IbtcmIsSppConnectedDevice(d.getAddress());
            recoverAp(sppUp && !sQuarantined);
            atProcessStart(d, sppUp);
        } catch (Throwable t) {
            error("onBtcmReady", t);
        }
    }

    /**
     * The breadcrumb: one line (SessionPolicy.Crumb), rewritten at every stage and every 5 s while a session is being set up.
     * When this process dies the next one reads it, so a restart in the middle of a session is on record even when the log
     * buffer has rolled over, and the build, the last sign of life and a shutdown announcement are on it.
     */
    static void mark(String stage) {
        sStage = stage;
        sMarkAt = SystemClock.elapsedRealtime();
        writeCrumb();
        trace("stage " + stage);
    }

    private static void writeCrumb() {
        Context c = context;
        if (c == null) return;
        synchronized (sCrumbLock) {
            try {
                SessionPolicy.Crumb k = new SessionPolicy.Crumb();
                k.pid = android.os.Process.myPid();
                k.t = sMarkAt;
                k.alive = SystemClock.elapsedRealtime();
                k.boot = bootId();
                k.build = BUILD;
                k.stage = sStage;
                k.exit = sExit;
                FileOutputStream o = new FileOutputStream(c.getFileStreamPath(CRUMB));
                try {
                    o.write(k.line().getBytes("UTF-8"));
                } finally {
                    o.close();
                }
            } catch (Throwable t) {
                // a crumb is a convenience
            }
        }
    }

    private static String bootId() {
        String b = sBoot;
        if (b != null) return b;
        try {
            FileInputStream in = new FileInputStream("/proc/sys/kernel/random/boot_id");
            try {
                byte[] buf = new byte[64];
                int n = in.read(buf);
                b = n > 0 ? new String(buf, 0, n, "UTF-8").trim() : "?";
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            b = "?";
        }
        sBoot = b;
        return b;
    }

    /** every 5 s: while a session is being set up, "alive" on the crumb moves, so the next process knows how long this one lived */
    private static void heartbeat() {
        handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (SessionPolicy.setupStage(sStage)) writeCrumb();
                } finally {
                    heartbeat();
                }
            }
        }, 5000);
    }

    /**
     * The head unit announcing a shutdown or reboot (ACTION_SHUTDOWN, REBOOT) is written on the crumb: the next process then knows
     * that the end was announced and does not count it as an interrupted setup. Whether this head unit sends them on every way of
     * restarting is not verified: a restart without one stays "interrupted".
     */
    private static void watchShutdown() {
        Context c = context;
        if (c == null) return;
        try {
            IntentFilter f = new IntentFilter("android.intent.action.ACTION_SHUTDOWN");
            f.addAction("android.intent.action.REBOOT");
            c.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    try {
                        sExit = "shutdown";
                        writeCrumb();
                        trace("head unit announced " + (intent == null ? "?" : intent.getAction()) + " (stage '" + sStage + "')");
                    } catch (Throwable t) {
                        // never out of a receiver
                    }
                }
            }, f);
        } catch (Throwable t) {
            error("shutdown watch", t);
        }
    }

    /** a line in the trace file (the last 60 are kept, fsync'ed: a restart right after it must still find it) */
    static void trace(String m) {
        Context c = context;
        synchronized (sTraceLines) {
            String line = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date()) + " up=" + (SystemClock.elapsedRealtime() / 1000) + "s " + m;
            sTraceLines.add(line.length() > 200 ? line.substring(0, 200) : line);
            while (sTraceLines.size() > 60) sTraceLines.remove(0);
            if (c == null) return;
            try {
                FileOutputStream o = new FileOutputStream(c.getFileStreamPath(TRACE));
                try {
                    StringBuilder b = new StringBuilder();
                    for (String l : sTraceLines) b.append(l).append('\n');
                    o.write(b.toString().getBytes("UTF-8"));
                    o.getFD().sync();
                } finally {
                    o.close();
                }
            } catch (Throwable t) {
                // a trace is a convenience
            }
        }
    }

    private static List<String> readTrace() {
        List<String> out = new ArrayList<String>();
        try {
            Context c = context;
            File f = c == null ? null : c.getFileStreamPath(TRACE);
            if (f == null || !f.isFile()) return out;
            FileInputStream in = new FileInputStream(f);
            try {
                byte[] b = new byte[(int) Math.min(f.length(), 16384)];
                int n = in.read(b);
                if (n > 0) for (String l : new String(b, 0, n, "UTF-8").split("\n")) if (l.length() > 0) out.add(l);
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            // none
        }
        return out;
    }

    /** a step of the session happened: kept in the trace and shown as one line of the path so far */
    static void traceOk(int step, String note) {
        long now = SystemClock.elapsedRealtime();
        if (sTrace.ok(step, now, note)) {
            trace("step " + (step + 1) + " " + SessionTrace.NAME[step] + " ok: " + note);
            log("trace: " + sTrace.line(now));
        }
    }

    static void traceFail(int step, String why) {
        long now = SystemClock.elapsedRealtime();
        sTrace.fail(step, now, why);
        trace("step " + (step + 1) + " " + SessionTrace.NAME[step] + " FAILED: " + why);
        log("trace: " + sTrace.line(now) + " -- " + sTrace.verdict(now));
    }

    /** first call in a process: what the earlier process got to and how it ended, then this one's own mark */
    private static void startupReport() {
        if (sStarted) return;
        sStarted = true;
        String prev = readSmall(CRUMB);
        List<String> old = readTrace();
        synchronized (sTraceLines) {
            sTraceLines.addAll(old);
        }
        String stage = "";
        String note = "no earlier run on record";
        SessionPolicy.Crumb cr = SessionPolicy.Crumb.parse(prev);
        if (cr != null) {
            stage = cr.stage;
            note = "earlier run: " + cr.describe(bootId(), SystemClock.elapsedRealtime());
        } else if (prev != null) {
            note = "earlier run: unreadable crumb";
        }
        sPrevStage = stage;
        SessionPolicy.End end = SessionPolicy.classifyEnd(cr, BUILD);
        evaluateQuarantine(cr, end);
        log("process start pid=" + android.os.Process.myPid() + " build=" + BUILD + "; " + note);
        int from = Math.max(0, old.size() - 25);
        if (!old.isEmpty()) log("trace of the earlier run(s), last " + (old.size() - from) + " line(s):");
        for (int i = from; i < old.size(); i++) log("  prev| " + old.get(i));
        mark("process-start");
        trace("== process start pid=" + android.os.Process.myPid() + " build=" + BUILD + " earlier stage='" + stage + "' end=" + end + " gate=" + sGateState + " ==");
        watchShutdown();
        watchApState();
        heartbeat();
    }

    private static String readSmall(String name) {
        try {
            Context c = context;
            File f = c == null ? null : c.getFileStreamPath(name);
            if (f == null || !f.isFile()) return null;
            FileInputStream in = new FileInputStream(f);
            try {
                byte[] b = new byte[512];
                int n = in.read(b);
                return n > 0 ? new String(b, 0, n, "UTF-8") : null;
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static void writeSmall(String name, String text) {
        try {
            FileOutputStream o = new FileOutputStream(context.getFileStreamPath(name));
            try {
                o.write(text.getBytes("UTF-8"));
                o.getFD().sync();
            } finally {
                o.close();
            }
        } catch (Throwable t) {
            error("write " + name, t);
        }
    }

    private static void deleteFile(String name) {
        try {
            Context c = context;
            if (c != null) c.getFileStreamPath(name).delete();
        } catch (Throwable t) {
            // none
        }
    }

    /**
     * What the earlier process's end means for THIS build, and the quarantine state that follows (SessionPolicy.Quarantine):
     * only an end that this build's own crumb shows, in a setup stage, with no shutdown announced, is counted. The first one is a
     * PROBATION (one trial session is allowed), the second a HOLD. A crumb of another build is reported but not counted: the
     * lx2dev.5 quarantine on the car came from a crumb that carried no build at all.
     */
    private static void evaluateQuarantine(SessionPolicy.Crumb cr, SessionPolicy.End end) {
        SessionPolicy.Quarantine q = SessionPolicy.Quarantine.parse(readSmall(QUAR));
        if (q != null && !BUILD.equals(q.build)) {
            log("quarantine: a record of build " + q.build + " (" + q.crashes + " interrupted setup(s), last stage '" + q.stage + "') is not this build's (" + BUILD + "): ignored");
        }
        if (end == SessionPolicy.End.INTERRUPTED) {
            q = SessionPolicy.Quarantine.counted(q, BUILD, cr.stage, System.currentTimeMillis());
            writeSmall(QUAR, q.serialize());
            log("the earlier process ended while setting a wireless session up (stage '" + cr.stage + "', build " + BUILD + ", no shutdown announced): count " + q.crashes + " for this build");
        } else if (end == SessionPolicy.End.FOREIGN_BUILD) {
            log("the earlier process ended in setup stage '" + cr.stage + "', but it was " + (cr.build.length() == 0 ? "a run that recorded no build (an older build of this mod)" : "build " + cr.build)
                    + ", not " + BUILD + ": not counted against this build");
        } else if (end == SessionPolicy.End.CLEAN_EXIT) {
            log("the earlier process was in setup stage '" + cr.stage + "' when the head unit announced a shutdown or reboot (" + cr.exit + "): a restart, not a crash; not counted");
        }
        sGateState = SessionPolicy.Quarantine.gate(q, BUILD);
        sQuarantined = sGateState == SessionPolicy.Quarantine.Gate.HOLD;
        if (applyRearm()) q = null;
        if (sGateState == SessionPolicy.Quarantine.Gate.HOLD) {
            log("QUARANTINE: wireless sessions are held back (build " + BUILD + ", " + q.crashes + " interrupted setup(s), last stage '" + q.stage
                    + "'). Stock behaviour continues. Re-arm: install a new build, or set the property " + REARM_PROP + " to a value not used before (hmgapid sh 'setprop "
                    + REARM_PROP + " 1') and connect the phone's Bluetooth again, or remove the state file " + QUAR + " in DiPO's files folder.");
        } else if (sGateState == SessionPolicy.Quarantine.Gate.PROBATION) {
            log("quarantine: PROBATION (build " + BUILD + ", " + q.crashes + " interrupted setup on record, stage '" + q.stage
                    + "'): one trial session is allowed; it ends the probation when it runs over Wi-Fi or outlives its 60 s setup window, and a second interrupted setup holds wireless sessions back");
        } else {
            log("quarantine: OPEN (build " + BUILD + ")");
        }
    }

    /** the explicit way out of a HOLD or PROBATION without a new build; true when it lifted something now */
    private static boolean applyRearm() {
        String pv = prop(REARM_PROP, "0");
        if (SessionPolicy.rearmForget(pv)) {
            if (readSmall(REARM_SEEN) != null) deleteFile(REARM_SEEN);
            return false;
        }
        if (!SessionPolicy.rearmRequested(pv, readSmall(REARM_SEEN))) return false;
        writeSmall(REARM_SEEN, pv.trim());   // the value is used up now, whether or not there was anything to lift
        SessionPolicy.Quarantine.Gate was = sGateState;
        if (was == SessionPolicy.Quarantine.Gate.OPEN) return false;   // nothing on record: a value set in advance must not pre-lift a later HOLD
        deleteFile(QUAR);
        sGateState = SessionPolicy.Quarantine.Gate.OPEN;
        sQuarantined = false;
        log("REARM: " + REARM_PROP + " is '" + pv.trim() + "' (not used before): the quarantine of build " + BUILD + " was " + was + ", now OPEN. Counting starts again from zero");
        trace("rearm " + was + " -> OPEN by " + REARM_PROP);
        return true;
    }

    /** a trial or an ordinary session proved this build did not take the head unit down: the record is removed */
    private static void quarantineClear(String why) {
        if (sGateState == SessionPolicy.Quarantine.Gate.OPEN) return;
        SessionPolicy.Quarantine.Gate was = sGateState;
        deleteFile(QUAR);
        sGateState = SessionPolicy.Quarantine.Gate.OPEN;
        sQuarantined = false;
        log("quarantine: " + was + " -> OPEN (" + why + ")");
        trace("quarantine " + was + " -> OPEN: " + why);
    }

    /** one line per minute at most */
    private static void quarantineNote(String what) {
        long now = SystemClock.elapsedRealtime();
        if (now - sQuarLogAt < 60000) return;
        sQuarLogAt = now;
        log("quarantine: not " + what);
    }

    /** every request this mod makes to the Wi-Fi service is stamped, so a state change that follows is known to be its own */
    static void noteApRequest(boolean on) {
        sApReqAt = SystemClock.elapsedRealtime();
    }

    /**
     * The access point's state changes, as the system announces them (WIFI_AP_STATE_CHANGED: state, previous state, mode). The stock
     * PhoneProjection also switches the connectivity hotspot (turnOnWiFi/turnOffWiFi, hotspot popups): a change that this mod did not
     * ask for, while a wireless CarPlay session runs, is the kind of thing that would freeze it, so that case is named.
     */
    private static void watchApState() {
        Context c = context;
        if (c == null) return;
        try {
            c.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    try {
                        int st = intent.getIntExtra("wifi_state", -1), prev = intent.getIntExtra("previous_wifi_state", -1), mode = intent.getIntExtra("ap_mode", -1);
                        boolean ours = SystemClock.elapsedRealtime() - sApReqAt < 20000;
                        boolean down = st == HotspotGuard.AP_DISABLING || st == HotspotGuard.AP_DISABLED || st == HotspotGuard.AP_FAILED;
                        log("AP state " + WifiProbe.apStateName(prev) + " -> " + WifiProbe.apStateName(st) + " mode " + mode
                                + (isInitialStickyBroadcast() ? " (the state when this process started)" : ours ? " (after a request of this mod)" : " (NOT requested by this mod)"));
                        long aaAge = sAaPhoneAt < 0 ? -1 : SystemClock.elapsedRealtime() - sAaPhoneAt;
                        if (down && !isInitialStickyBroadcast() && aaAge >= 0 && aaAge <= AA_HANDOVER_WINDOW_MS) {
                            log("!! the access point went down " + (aaAge / 1000) + " s after an Android phone connected over Bluetooth, " + (ours
                                    ? "and this mod had asked for it (its release of the hotspot after a CarPlay session)"
                                    : "and this mod had not asked for it (the stock PhoneProjection or the system)")
                                    + ". Wireless Android Auto needs the access point enabled in mode 2 at this point of its hand-over");
                        }
                        if (sCpWireless && down && !ours) {
                            sApOtherDown = true;
                            log("!! the access point went down during a wireless CarPlay session without a request from this mod (the stock PhoneProjection or the system did it)");
                            trace("AP went down during the session, not requested by this mod");
                        }
                    } catch (Throwable t) {
                        // never out of a receiver
                    }
                }
            }, new IntentFilter("android.net.wifi.WIFI_AP_STATE_CHANGED"));
        } catch (Throwable t) {
            error("AP state watch", t);
        }
    }

    // ---- a running wireless CarPlay session, seen from outside -------------------------------------------------------------

    /** the session runs over Wi-Fi: every 5 s the hotspot interface's byte counters, the phone's entry, the AP, Bluetooth are sampled (SessionWatch) */
    private static void startSessionWatch() {
        final int gen = ++sWatchGen;
        sWatch = new SessionWatch();
        sCpStalled = false;
        sCpLost = false;
        sApOtherDown = false;
        sLost.reset();
        new Thread("hmgapi-cpwl-sessionaudit") {
            @Override
            public void run() {
                try {
                    HotspotGuard g = ap();
                    if (g != null) g.audit("session started");   // the band, channel and security hostapd runs with
                } catch (Throwable t) {
                    error("session audit", t);
                }
            }
        }.start();
        handler().postDelayed(new Runnable() {
            private boolean quietLogged;

            @Override
            public void run() {
                try {
                    SessionWatch w = sWatch;
                    if (gen != sWatchGen || w == null || !sCpWireless) return;
                    long now = SystemClock.elapsedRealtime();
                    SessionWatch.Sample s = new SessionWatch.Sample();
                    s.at = now;
                    long[] st = WifiProbe.devStats(readProc("/proc/net/dev"), wlanName());
                    if (st != null) {
                        s.rx = st[0]; s.tx = st[1]; s.rxErr = st[2]; s.rxDrop = st[3]; s.txErr = st[4]; s.txDrop = st[5];
                    }
                    s.arp = WifiProbe.arpClients(readProc("/proc/net/arp"), wlanName());
                    AndroidHotspot a = sAndroidAp;
                    if (a != null) {
                        s.apState = a.apState();
                        s.apMode = a.apMode();
                    }
                    HfDeviceBTCMManager m = sBtcm;
                    String addr = sAddr;
                    try {
                        if (m != null) {
                            s.sppUp = addr != null && m.IbtcmIsSppConnectedDevice(addr);
                            s.hfpUp = m.IbtcmGetHfpConnectedDevice() != null;
                        }
                    } catch (Throwable t) {
                        // Bluetooth manager busy: the sample says false
                    }
                    w.add(s);
                    long quiet = w.rxStalledMs(now);
                    boolean apDown = s.apState != -1 && !(s.apState == HotspotGuard.AP_ENABLED && s.apMode == HotspotGuard.MODE_CONNECTIVITY);
                    // a session that is only quiet is not a dead one: the phone must also be gone from the interface (or the AP down)
                    sCpStalled = quiet >= 20000 && (s.arp == 0 || apDown);
                    if (quiet >= 10000 && !quietLogged) {
                        quietLogged = true;
                        log("session watch: nothing received on " + wlanName() + " for " + (quiet / 1000) + " s (an idle screen can be this quiet; compare with the time the picture froze) | " + w.trail(now, 6));
                        trace("session quiet " + (quiet / 1000) + " s, arp=" + s.arp + " ap=" + s.apState + "/" + s.apMode);
                        auditLater("session quiet");
                    } else if (quiet >= 0 && quiet < 5000) {
                        quietLogged = false;
                    }
                    handler().postDelayed(this, 5000);
                } catch (Throwable t) {
                    error("session watch", t);
                }
            }
        }, 5000);
    }

    /** the session is over (finalized, lost, abandoned): one report line says which visible thing changed first */
    private static void endSessionWatch(String why) {
        SessionWatch w = sWatch;
        sWatch = null;
        sWatchGen++;
        if (w == null) return;
        long now = SystemClock.elapsedRealtime();
        String verdict = w.classify(now, sApOtherDown);
        log("session end report (" + why + "): " + verdict + " | last samples: " + w.trail(now, 5));
        log("session end, picture path: " + sScreen.describe(now));
        trace("session end (" + why + "): " + verdict);
        auditLater("session end");
    }

    /** the access point audit reads /proc and a file: not on DiPO's callback thread or the bridge's handler */
    private static void auditLater(final String when) {
        new Thread("hmgapi-cpwl-audit") {
            @Override
            public void run() {
                try {
                    HotspotGuard g = sAp;
                    if (g != null) g.audit(when);
                } catch (Throwable t) {
                    error("audit " + when, t);
                }
            }
        }.start();
    }

    /** where the CarPlay session is, for the lines about sends that fail: before it was created, running (since when), or already finalized */
    private static String sessionPhase() {
        long ago = (SystemClock.elapsedRealtime() - sCpPhaseAt) / 1000;
        switch (sCpPhase) {
            case 0: return " [no CarPlay session has been created in this process]";
            case 1: return " [created " + ago + " s ago, not started over Wi-Fi yet]";
            case 2: return " [running over Wi-Fi since " + ago + " s]";
            default: return " [finalized " + ago + " s ago]";
        }
    }

    /** the bridge's flags say wireless CarPlay is up but the Bluetooth link is back and the session looks dead: clear them so a new session can start */
    private static void abandonStaleSession(String why) {
        log("the Bluetooth link came back while the previous CarPlay session looks dead (" + why + "): clearing the bridge's state so a new session can start");
        trace("abandoned a dead session: " + why);
        endSessionWatch("abandoned: " + why);
        sCpWireless = false;
        sCpClient = false;
        sCpConnected = false;
        sCpLost = false;
        sCpStalled = false;
        synchronized (sPendingIn) {
            sPendingIn.clear();
        }
        sendDetach();
    }

    // ---- the picture path (DiPO's Java side) -----------------------------------------------------------------------------------

    private static boolean keyFrameOn() {
        return !"0".equals(prop("persist.hmgapi.cp.keyframe", "1"));
    }

    /**
     * The native decoder reported a failure (CarPlayService.onNotifyDecodeFailure, on a native thread: nothing slow here). NO_OUTPUT_FRAME is
     * the VDEC giving no picture while the music goes on; DiPO's own handler skips it. The line says whether the stream is still arriving
     * (bytes received on the hotspot interface in the last 10 s), what the display and touch did, and whether a key frame was asked for.
     */
    static void onCpDecodeFailure(final int type) {
        final long now = SystemClock.elapsedRealtime();
        final boolean wireless = sCpWireless;
        final boolean ask = sScreen.decodeFailure(now, type) && wireless && keyFrameOn();
        handler().post(new Runnable() {
            @Override
            public void run() {
                try {
                    SessionWatch w = sWatch;
                    long[] st = WifiProbe.devStats(readProc("/proc/net/dev"), wlanName());
                    long rx10 = w == null || st == null ? -1 : w.rxSince(now, 10000, st[0]);
                    long age = (now - sCpPhaseAt) / 1000;
                    log("CarPlay decoder failure: " + ScreenWatch.errorName(type) + " (type " + type + ")" + (sCpPhase == 2 ? " " + age + " s into the " + (wireless ? "wireless" : "wired") + " session" : sessionPhase())
                            + "; bytes received on " + wlanName() + " in about the last 10 s: " + (rx10 < 0 ? "unknown" : rx10 + (rx10 > 0 ? " (the stream is still arriving)" : " (nothing arrived)"))
                            + " | " + sScreen.describe(SystemClock.elapsedRealtime()));
                    trace("decoder failure " + ScreenWatch.errorName(type) + ", rx10=" + rx10);
                    if (!ask) {
                        if (type == ScreenWatch.NO_OUTPUT_FRAME) {
                            log("no key frame asked: " + (!wireless ? "not a wireless session" : !keyFrameOn() ? "persist.hmgapi.cp.keyframe=0" : sScreen.declined(SystemClock.elapsedRealtime())));
                        }
                        return;
                    }
                    handler().postDelayed(new Runnable() {   // out of the native callback's way, and not holding this thread
                        @Override
                        public void run() {
                            requestKeyFrame();
                        }
                    }, 300);
                } catch (Throwable t) {
                    error("decode failure", t);
                }
            }
        });
    }

    /** CarPlayService.forceKeyFrame(): AirPlay's request that the phone send a fresh key frame. No restart, no reconnect */
    private static void requestKeyFrame() {
        try {
            Object cp = sCp;
            if (cp == null || !sCpWireless) {
                log("no key frame asked: no running wireless CarPlay session");
                return;
            }
            int n = sScreen.keyFrameRequested(SystemClock.elapsedRealtime());
            Object r = cp.getClass().getMethod("forceKeyFrame").invoke(cp);
            log("asked the phone for a key frame (CarPlayService.forceKeyFrame -> " + r + "), request " + n + "/" + ScreenWatch.MAX_PER_SESSION
                    + ". Whether the picture comes back shows in the lines that follow: another 'decoder failure' means it did not");
            trace("key frame asked " + n + "/" + ScreenWatch.MAX_PER_SESSION + " -> " + r);
        } catch (Throwable t) {
            error("key frame request", t);
        }
    }

    static void onCpScreen(int kind) {
        long now = SystemClock.elapsedRealtime();
        sScreen.screen(now, kind);
        log("CarPlay screen " + (kind == 0 ? "started" : kind == 1 ? "stopped" : "TIMEOUT") + " (DiPO callback)" + sessionPhase() + (kind == 0 ? "" : " | " + sScreen.describe(now)));
        trace("screen " + (kind == 0 ? "started" : kind == 1 ? "stopped" : "timeout"));
    }

    static void onCpShowDisplay(Object surface, Object result) {
        String state;
        try {
            state = surface == null ? "null" : ((android.view.Surface) surface).isValid() ? "valid" : "INVALID";
        } catch (Throwable t) {
            state = "unreadable";
        }
        sScreen.shown(SystemClock.elapsedRealtime(), state, String.valueOf(result));
        log("CarPlay display shown: Surface " + state + ", result " + result + sessionPhase() + " | " + sScreen.describe(SystemClock.elapsedRealtime()));
        trace("display shown, surface " + state + " -> " + result);
    }

    static void onCpHideDisplay(Object result) {
        sScreen.hidden(SystemClock.elapsedRealtime());
        log("CarPlay display hidden, result " + result + sessionPhase() + " | " + sScreen.describe(SystemClock.elapsedRealtime()));
        trace("display hidden -> " + result);
    }

    /** every touch counts; nothing is logged per touch. result is DiPO's CarPlayReturn (0 = OK) */
    static void onCpTouch(Object result) {
        boolean ok = !(result instanceof Enum) || ((Enum<?>) result).ordinal() == 0;
        sScreen.touch(ok);
    }

    // ---- wireless Android Auto: what can be seen of it (the stock side does all of it) -----------------------------------------

    /** only "yes" is remembered: a phone whose vendor or name could not be read yet is asked again, never taken for an Android phone for good */
    private static boolean isAppleCached(BluetoothDevice d) {
        String a = d.getAddress();
        synchronized (sAppleCache) {
            if (sAppleCache.containsKey(a)) return true;
        }
        boolean apple = isApple(d);
        if (apple) {
            synchronized (sAppleCache) {
                sAppleCache.put(a, Boolean.TRUE);
            }
        }
        return apple;
    }

    private static List<String> uuidList(ParcelUuid[] u) {
        if (u == null) return null;
        List<String> out = new ArrayList<String>();
        for (ParcelUuid x : u) out.add(x == null ? "" : x.toString());
        return out;
    }

    /** the Bluetooth services the stack knows the phone offers (the cached SDP result); null when it knows none */
    private static List<String> remoteUuids(BluetoothDevice d) {
        try {
            return uuidList(d.getUuids());
        } catch (Throwable t) {
            return null;
        }
    }

    /** the services this head unit's own Bluetooth adapter offers (BluetoothAdapter.getUuids is hidden); null when not readable */
    private static List<String> localUuids() {
        try {
            Object a = Class.forName("android.bluetooth.BluetoothAdapter").getMethod("getDefaultAdapter").invoke(null);
            Object u = a == null ? null : a.getClass().getMethod("getUuids").invoke(a);
            return u instanceof ParcelUuid[] ? uuidList((ParcelUuid[]) u) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean feature(String name) {
        try {
            return Boolean.TRUE.equals(Class.forName("com.hkmc.Feature").getMethod("hasFeature", String.class).invoke(null, name));
        } catch (Throwable t) {
            return false;
        }
    }

    /** the whole picture of what wireless Android Auto needs, one block of lines (no names, no addresses, no keys) */
    private static void aaReport(String when) {
        try {
            StringBuilder f = new StringBuilder();
            for (String n : AaProbe.FEATURES) f.append(f.length() > 0 ? " " : "").append(AaProbe.featureTail(n)).append('=').append(feature(n) ? "yes" : "NO");
            log("aa[" + when + "] feature flags the stock side starts on: " + f);
            log("aa[" + when + "] stock processes: " + AaProbe.processReport(new AndroidHotspot.JavaFs()));
            log("aa[" + when + "] this head unit offers over Bluetooth: " + AaProbe.describeUuids(localUuids()));
            AndroidHotspot a = sAndroidAp;
            if (a != null) {
                Object cfg = a.apConfig();
                SessionPolicy.CredPair cp = cfg == null ? null : a.credPair(cfg);
                log("aa[" + when + "] hotspot: state " + WifiProbe.apStateName(a.apState()) + " mode " + a.apMode() + " (wireless Android Auto needs ENABLED in mode 2); name and key the phone is given equal the hotspot's: "
                        + (cp == null ? "unknown" : cp.problem != null ? "cannot be (" + cp.problem + ")" : String.valueOf(cp.equal())));
            }
        } catch (Throwable t) {
            error("aaReport", t);
        }
    }

    /** a phone that is not an iPhone connected over Bluetooth (HFP): what it offers, and the state of the stock side at that moment */
    private static void aaPhoneConnected(final BluetoothDevice d) {
        if (d == null) return;
        sAaPhoneAt = SystemClock.elapsedRealtime();
        handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    List<String> uu = remoteUuids(d);
                    int major = -1;
                    try {
                        major = d.getBluetoothClass().getMajorDeviceClass();
                    } catch (Throwable t) {
                        // unknown
                    }
                    log("aa: a phone that is not an iPhone connected over Bluetooth (device class 0x" + Integer.toHexString(major) + "); the services it offers: " + AaProbe.describeUuids(uu)
                            + (AaProbe.hasAaWireless(uu) ? "" : " <- no wireless Android Auto service seen (the stack may not have asked it yet)"));
                    aaReport("phone connected");
                    if ("1".equals(prop("persist.hmgapi.aa.sppconnect", "0")) && AaProbe.hasAaWireless(uu) && !sUsb && sBtcm != null) aaSppConnect(d);
                } catch (Throwable t) {
                    error("aaPhoneConnected", t);
                }
            }
        }, 1500);
    }

    /**
     * Opt-in (persist.hmgapi.aa.sppconnect=1, default off): ask the Bluetooth stack for the SPP link to an Android phone that offers the
     * wireless Android Auto service, once per connection, the way the bridge does for an iPhone. The stock expects the link to come from
     * its own Bluetooth service; if it does not, the stock side never starts the hand-over and the phone stays at "connecting".
     */
    private static void aaSppConnect(final BluetoothDevice d) {
        final String addr = d.getAddress();
        handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    HfDeviceBTCMManager m = sBtcm;
                    if (m == null || sUsb) return;
                    if (m.IbtcmIsSppConnectedDevice(addr)) {
                        log("aa: the SPP link to the Android phone is up already (the stock made it)");
                        return;
                    }
                    log("aa: asking the Bluetooth stack for the SPP link to the Android phone (persist.hmgapi.aa.sppconnect=1): IbtcmSppConnect = " + m.IbtcmSppConnect(addr));
                } catch (Throwable t) {
                    error("aaSppConnect", t);
                }
            }
        }, SPP_CONNECT_DELAY_MS);
    }

    private static void aaSppStatus(BluetoothDevice d, int status) {
        synchronized (sAaStreams) {
            if (status == SPP_CONNECTED) sAaStreams.put(d.getAddress(), new AaProbe.Stream());
            else sAaStreams.remove(d.getAddress());
        }
        log("aa: SPP link to an Android phone: " + (status == SPP_CONNECTED ? "UP" : status == SPP_DISCONNECTED ? "down" : "status " + status));
    }

    /** the phone's bytes on that link, read as wireless-handshake messages ([2-byte length][2-byte type][payload]); the stock side reads them too */
    private static void aaSppData(BluetoothDevice d, byte[] data, int len) {
        try {
            AaProbe.Stream st;
            synchronized (sAaStreams) {
                st = sAaStreams.get(d.getAddress());
                if (st == null) {
                    st = new AaProbe.Stream();
                    sAaStreams.put(d.getAddress(), st);
                }
            }
            for (AaProbe.Msg m : st.feed(data, len)) {
                sAaMsgs++;
                boolean first;
                synchronized (sAaTypes) {
                    first = sAaTypes.size() < 12 && sAaTypes.add(m.type);
                }
                if (first) log("aa: the Android phone sent " + AaProbe.typeName(m.type) + " (type " + m.type + ", " + m.payload + " bytes) over SPP; messages so far: " + sAaMsgs);
            }
        } catch (Throwable t) {
            error("aaSppData", t);
        }
    }

    /** the Wi-Fi hardware/driver/framework facts, once per process, in the background; read only */
    private static void startWifiProbe() {
        new Thread("hmgapi-cpwl-wifiprobe") {
            @Override
            public void run() {
                try {
                    HotspotGuard g = ap();
                    if (g != null) {
                        trace("wifi probe at process start: " + g.diagnose("process start", true));
                        // Android Auto's hand-over reads the same two fields as CarPlay's: the pair is made right before either one starts
                        trace("credentials at process start: " + g.keepPairEqual("process start"));
                    }
                    aaReport("process start");
                } catch (Throwable t) {
                    error("wifiProbe", t);
                }
            }
        }.start();
    }

    /**
     * A process has just started. An iAP2 session the earlier process left in the iAP2 server is cleared first
     * (otherwise the next BT_ATTACHED is refused), whether or not the phone's Bluetooth is still up; if it is, the
     * session starts over.
     */
    private static void atProcessStart(BluetoothDevice d, boolean sppUp) {
        boolean orphan = SessionPolicy.detachOrphanAtStart(sPrevStage);
        if (orphan) {
            log("earlier process was at stage '" + sPrevStage + "': clearing a possible orphaned iAP2 session");
            sendDetach();
        }
        SessionPolicy.Resume r = SessionPolicy.atStart(d != null && isApple(d), sppUp);
        if (r == SessionPolicy.Resume.NOTHING) return;
        final String addr = d.getAddress();
        if (r == SessionPolicy.Resume.CONNECT_SPP) {
            scheduleSppConnect(addr);
            return;
        }
        log("SPP to the phone is already up at process start: starting the session over");
        sAddr = addr;
        handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                onSppUp(addr);
            }
        }, orphan ? 2500 : 500);
    }

    static boolean isOurs(Object listener) {
        return listener != null && listener == sBt;
    }

    /** the iAP2 server is bound: register for Bluetooth and CarPlay-channel transport data */
    static void onIap2Bound(IIap2Service s) {
        if (s == null) return;
        try {
            sIap2 = s;
            synchronized (Bridge.class) {
                if (sTx == null) sTx = new TxListener();
            }
            Iap2ListenerInfo info = new Iap2ListenerInfo();
            info.onSetSupportType(LISTENER_EXTERNAL_BT_TRANSPORT | LISTENER_EXTERNAL_CARPLAY_TRANSPORT);
            info.onSetListenerName("EV6_CPWL_BT");
            boolean init = s.Iap2SvcInit(sTx, info);
            log("Iap2SvcInit(EV6_CPWL_BT, 0xc00) = " + init);
            if (init) traceOk(SessionTrace.IAP2, "Iap2SvcInit = true");
            else traceFail(SessionTrace.IAP2, "Iap2SvcInit = false");
        } catch (Throwable t) {
            error("onIap2Bound", t);
        }
    }

    static boolean enabled() {
        return !"0".equals(prop("persist.ev6.cpwl.enable", "1"));
    }

    /** a system property (android.os.SystemProperties isn't in the public SDK) */
    static String prop(String key, String fallback) {
        try {
            return (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class).invoke(null, key, fallback);
        } catch (Throwable t) {
            return fallback;
        }
    }

    static synchronized Handler handler() {
        if (sHandler == null) {
            HandlerThread t = new HandlerThread("hmgapi-cpwl");
            t.start();
            sHandler = new Handler(t.getLooper());
        }
        return sHandler;
    }

    static boolean isApple(BluetoothDevice d) {
        if (d == null) return false;
        try {
            Object v = BluetoothDevice.class.getMethod("getVendor").invoke(d);
            if (v instanceof Integer) return (Integer) v == APPLE_VENDOR_ID;
        } catch (Throwable t) {
            // no vendor id on this build: by name
        }
        try {
            String n = d.getName();
            return n != null && (n.contains("iPhone") || n.contains("iPad"));
        } catch (Throwable t) {
            return false;
        }
    }

    static void scheduleSppConnect(final String addr) {
        if (addr == null || !enabled() || sUsb || sSession || sCpWireless) return;
        if (sQuarantined) applyRearm();
        if (sQuarantined) {
            quarantineNote("connecting SPP to the phone");
            return;
        }
        handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    HfDeviceBTCMManager m = sBtcm;
                    if (m == null || sUsb || sSession || sCpWireless || !enabled()) return;
                    if (m.IbtcmIsSppConnectedDevice(addr)) {
                        log("SPP already up for " + who(addr));
                        onSppUp(addr);
                    }
                    else log("IbtcmSppConnect(" + who(addr) + ") = " + m.IbtcmSppConnect(addr));
                } catch (Throwable t) {
                    error("sppConnect", t);
                }
            }
        }, SPP_CONNECT_DELAY_MS);
    }

    static void onSppUp(final String addr) {
        if (addr == null || sSession) return;
        sAddr = addr;
        traceOk(SessionTrace.BT, "SPP up");
        if (!enabled() || sUsb) return;
        if (sCpClient || sCpWireless) {
            // only a session that is visibly dead (sends fail with INVALID_SESSION, or no traffic and the phone gone from the interface) gives way
            if (!(sCpLost || sCpStalled)) return;
            abandonStaleSession(sCpLost ? "the native CarPlay session is gone" : "no Wi-Fi traffic and the phone is gone from the hotspot interface");
        }
        if (sQuarantined) applyRearm();
        if (sQuarantined) {
            quarantineNote("starting a wireless session");
            traceFail(SessionTrace.ATTEMPT, "held back: quarantine HOLD (" + BUILD + ")");
            return;
        }
        if (sApLatch.latched()) {
            long n = SystemClock.elapsedRealtime();
            if (n - sLatchLogAt >= 60000) {
                sLatchLogAt = n;
                log("hotspot unavailable (" + sApLatch.why() + "; " + sApLatch.fails() + " failure(s) in this process): no hotspot request until DiPO restarts. The wifi[...] lines show the hardware, driver and request facts");
            }
            traceFail(SessionTrace.ATTEMPT, "held back: hotspot latched off (" + sApLatch.why() + ")");
            return;
        }
        if (SystemClock.elapsedRealtime() < sApRetryAt) {
            log("hotspot failed a moment ago, not starting a session yet");
            traceFail(SessionTrace.ATTEMPT, "held back: hotspot failed a moment ago");
            return;
        }
        if (sGate.blocked(SystemClock.elapsedRealtime())) {
            log("CarPlay sessions failed twice in a row: no automatic restart for now (the phone connecting again, or a pause, allows the next try)");
            traceFail(SessionTrace.ATTEMPT, "held back: two CarPlay sessions failed in a row");
            return;
        }
        sSession = true;
        final int attempt = ++sAttempt;
        cancelApRelease();
        sEverWireless = false;
        sTrace.begin(attempt, SystemClock.elapsedRealtime());
        resetScan();
        mark("spp-up");
        traceOk(SessionTrace.ATTEMPT, "attempt " + attempt + " started");
        log("session attempt " + attempt + " for the phone: waiting for the hotspot");
        // the hotspot wait can take seconds: its own thread, not the bridge's handler
        new Thread("hmgapi-cpwl-ap") {
            @Override
            public void run() {
                try {
                    HotspotGuard g = ap();
                    if (g == null) {
                        log("no context, cannot start session");
                        if (attempt == sAttempt) sSession = false;
                        return;
                    }
                    mark("ap-wait");
                    HotspotGuard.Result r = g.acquire(apTimeout(), new HotspotGuard.Abort() {
                        @Override
                        public boolean aborted() {
                            return attempt != sAttempt || sUsb || !sSession;
                        }
                    });
                    boolean up = r == HotspotGuard.Result.READY || r == HotspotGuard.Result.ALREADY_UP;
                    mark(up ? "ap-ready" : "ap-failed");
                    if (up) {
                        sApLatch.success();
                        traceOk(SessionTrace.AP, "hotspot " + r);
                        try {
                            g.audit("hotspot ready");
                        } catch (Throwable t) {
                            error("ap audit", t);
                        }
                    } else if (r != HotspotGuard.Result.ABORTED && attempt == sAttempt) {
                        boolean hard = SessionPolicy.ApLatch.hardFailure(r.name(), g.verdict());
                        sApLatch.failure(r + ": " + g.why(), hard);
                        traceFail(SessionTrace.AP, r + ": " + g.why() + " [hardware verdict " + g.verdict() + "]");
                        if (sApLatch.latched()) log("hotspot unavailable (" + sApLatch.why() + "; hardware verdict " + g.verdict() + "): not requested again until DiPO restarts");
                    }
                    if (!up || attempt != sAttempt || sUsb || sCpWireless || !sSession) {
                        log("not attaching Bluetooth iAP2: hotspot " + r + (attempt != sAttempt || sUsb ? " (USB took over)" : ""));
                        if (attempt == sAttempt) {
                            sSession = false;
                            if (r != HotspotGuard.Result.ABORTED) sApRetryAt = SystemClock.elapsedRealtime() + AP_RETRY_BACKOFF_MS;
                        }
                        scheduleApRelease(attempt == sAttempt ? AP_RELEASE_GRACE_MS : 0);
                        return;
                    }
                    Context c = context;
                    Intent i = new Intent(ACTION_ATTACH);
                    i.setPackage(IAP2_PKG);
                    i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                    i.putExtra("component", COMPONENT_BT_CARPLAY);
                    i.putExtra("serial", addr);
                    c.sendBroadcast(i);
                    mark("bt-attached");
                    log("BT_ATTACHED sent for " + who(addr) + " (hotspot " + r + ")");
                    trace("BT_ATTACHED sent (hotspot " + r + ")");
                    statusLines("after BT_ATTACHED");
                    watchJoin(attempt);
                    setupWatchdog(attempt);
                } catch (Throwable t) {
                    if (attempt == sAttempt) sSession = false;
                    error("onSppUp", t);
                    scheduleApRelease(AP_RELEASE_GRACE_MS);
                }
            }
        }.start();
    }

    /** the hotspot rules (HotspotGuard) on this device; null before the context is known */
    private static synchronized HotspotGuard ap() {
        if (sAp == null && context != null) {
            sAndroidAp = new AndroidHotspot(context);
            sAp = new HotspotGuard(sAndroidAp, new AndroidHotspot.FileJournal(context), new HotspotGuard.Log() {
                @Override
                public void log(String m) {
                    Bridge.log(m);
                }
            });
        }
        return sAp;
    }

    /** where the session is, 5 / 15 / 40 s from now: the blocked step shows as the first step that stays "-" in the trace line */
    static void statusLines(final String why) {
        final long[] at = { 5000, 15000, 40000 };
        for (final long ms : at) {
            handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        HotspotGuard g = sAp;
                        log("status +" + (ms / 1000) + "s (" + why + "): session=" + sSession + " cpClient=" + sCpClient + " cpConnected=" + sCpConnected
                                + " cpWireless=" + sCpWireless + " usb=" + sUsb + " sppIn/out=" + sSppIn + "/" + sSppOut + " cpIn/out=" + sCpIn + "/" + sCpOut
                                + " | " + (g == null ? "hotspot unknown" : g.describe()) + " | " + wlanName() + " " + WifiProbe.devCounters(readProc("/proc/net/dev"), wlanName())
                                + " arp=" + WifiProbe.arpClients(readProc("/proc/net/arp"), wlanName()));
                        long now = SystemClock.elapsedRealtime();
                        log("trace: " + sTrace.line(now));
                        if (ms >= 15000 && sTrace.waitingFor() < SessionTrace.STEPS) log("verdict: " + sTrace.verdict(now));
                    } catch (Throwable t) {
                        error("statusLines", t);
                    }
                }
            }, ms);
        }
    }

    private static String wlanName() {
        String n = prop("wifi.interface", "wlan0");
        return n == null || n.length() == 0 ? "wlan0" : n;
    }

    private static String readProc(String path) {
        try {
            FileInputStream in = new FileInputStream(path);
            try {
                byte[] b = new byte[8192];
                int n = in.read(b);
                return n > 0 ? new String(b, 0, n, "UTF-8") : "";
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static long rxBytes() {
        String c = WifiProbe.devCounters(readProc("/proc/net/dev"), wlanName());
        try {
            int i = c.indexOf("rx="), j = c.indexOf(' ', i);
            return i < 0 ? -1 : Long.parseLong(c.substring(i + 3, j < 0 ? c.length() : j));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * After BT_ATTACHED the phone is told over iAP2 to join the hotspot. Every 2 s for 60 s: does the hotspot interface show it
     * (an ARP entry), or at least data from it (received bytes grew)? The first sign is the JOIN step of the trace. At 20 s and at 60 s
     * without one, "join check" says WHY by what can be seen from here (JoinDiagnosis): what the phone was told, whether hostapd and the
     * DHCP server run and hostapd was started with the service's name and key, the interface address, and what arrived on it.
     */
    static void watchJoin(final int attempt) {
        final long t0 = SystemClock.elapsedRealtime(), rx0 = rxBytes();
        handler().postDelayed(new Runnable() {
            private boolean early;

            @Override
            public void run() {
                try {
                    if (attempt != sAttempt || sUsb || !(sSession || sCpClient || sCpWireless) || sTrace.done(SessionTrace.JOIN)) return;
                    long el = SystemClock.elapsedRealtime() - t0, rx = rxBytes();
                    int arp = WifiProbe.arpClients(readProc("/proc/net/arp"), wlanName());
                    if (arp > 0) {
                        traceOk(SessionTrace.JOIN, arp + " ARP entr" + (arp == 1 ? "y" : "ies") + " on " + wlanName() + " after " + (el / 1000) + " s");
                        return;
                    }
                    if (rx0 >= 0 && rx > rx0) {
                        traceOk(SessionTrace.JOIN, "data from the phone: " + wlanName() + " received " + (rx - rx0) + " bytes after " + (el / 1000) + " s (no ARP entry yet)");
                        return;
                    }
                    if (!early && el >= 20000) {
                        early = true;
                        joinCheck("20 s", rx0 >= 0 && rx >= 0 ? rx - rx0 : -1, arp, false);
                    }
                    if (el >= 60000) {
                        log("the phone did not appear on " + wlanName() + " within 60 s of BT_ATTACHED: no ARP entry; interfaces: " + WifiProbe.devCountersWlan(readProc("/proc/net/dev")) + " (rx before: " + rx0 + ")");
                        joinCheck("60 s", rx0 >= 0 && rx >= 0 ? rx - rx0 : -1, arp, true);
                        return;
                    }
                    handler().postDelayed(this, 2000);
                } catch (Throwable t) {
                    error("watchJoin", t);
                }
            }
        }, 2000);
    }

    /** the JOIN failure told apart: one line "join check (when): CODE: sentence", the access point audit before it, the trace marks the step at the end */
    private static void joinCheck(String when, long rxDelta, int arp, boolean finalCheck) {
        JoinDiagnosis.In in = new JoinDiagnosis.In();
        in.told = sTold ? Boolean.TRUE : (sMsgOut > 0 ? Boolean.FALSE : null);
        in.asked = sAsked;
        in.toldSsidEqual = sToldSsidEq;
        in.toldPskEqual = sToldPskEq;
        in.rx = rxDelta;
        in.arp = arp;
        try {
            HotspotGuard g = sAp;
            if (g != null) in.ap = g.audit("join check " + when);
        } catch (Throwable t) {
            error("join audit", t);
        }
        JoinDiagnosis.Out out = JoinDiagnosis.classify(in);
        log("join check (" + when + "): " + out + " | iAP2 messages to/from the phone: " + sMsgOut + "/" + sMsgIn);
        trace("join check " + when + ": " + out.code);
        if (finalCheck) traceFail(SessionTrace.JOIN, out.code);
    }

    private static void resetScan() {
        sScanIn = new IapScan();
        sScanOut = new IapScan();
        sAsked = false;
        sTold = false;
        sToldSsidEq = null;
        sToldPskEq = null;
        sMsgOut = 0;
        sMsgIn = 0;
        synchronized (sSeenOut) {
            sSeenOut.clear();
        }
        synchronized (sSeenIn) {
            sSeenIn.clear();
        }
    }

    /** iAP2 bytes the server sends the phone over SPP: which messages, and what the Wi-Fi configuration message says (a look, nothing is changed) */
    private static void scanOut(byte[] data) {
        try {
            for (IapScan.Msg m : sScanOut.feed(data, data.length)) {
                sMsgOut++;
                noteMessage("to the phone", sSeenOut, m);
                if (m.id == IapScan.WIFI_INFO) {
                    byte[][] c = sAndroidAp == null ? null : sAndroidAp.credBytes();
                    IapScan.Told t = IapScan.describeWifiInfo(m, c == null ? null : c[0], c == null ? null : c[1]);
                    sTold = true;
                    sToldSsidEq = c == null ? null : Boolean.valueOf(t.ssidEqual);
                    sToldPskEq = c == null ? null : Boolean.valueOf(t.pskEqual);
                    log("iAP2 told the phone its Wi-Fi configuration: parameters [" + t.summary + "]; name equals the hotspot's: " + sToldSsidEq + ", key equals the hotspot's: " + sToldPskEq
                            + (Boolean.FALSE.equals(sToldSsidEq) || Boolean.FALSE.equals(sToldPskEq) ? " <- the phone is told a network that is not the one the AP broadcasts" : ""));
                    trace("phone told its Wi-Fi configuration: name equal=" + sToldSsidEq + " key equal=" + sToldPskEq);
                }
            }
        } catch (Throwable t) {
            error("scanOut", t);
        }
    }

    private static void scanIn(byte[] data, int len) {
        try {
            for (IapScan.Msg m : sScanIn.feed(data, len)) {
                sMsgIn++;
                noteMessage("from the phone", sSeenIn, m);
                if (m.id == IapScan.REQUEST_WIFI) {
                    sAsked = true;
                    log("the phone asked for the accessory's Wi-Fi configuration");
                    trace("phone asked for the Wi-Fi configuration");
                }
            }
        } catch (Throwable t) {
            error("scanIn", t);
        }
    }

    /** every new message id once per attempt (at most 24 per direction): the ids themselves show what the conversation did, whatever they are called */
    private static void noteMessage(String dir, java.util.HashSet<Integer> seen, IapScan.Msg m) {
        synchronized (seen) {
            if (seen.size() >= 24 || !seen.add(m.id)) return;
        }
        log("iAP2 message " + dir + ": 0x" + Integer.toHexString(m.id) + " (" + m.raw.length + " bytes)");
    }

    /**
     * 60 s after BT_ATTACHED, in the process that is still alive: the head unit did not go down during the setup window, so a
     * quarantine on probation ends; and a setup that is still waiting (a phone that never joins) is no longer a crash window
     * (switching the car off with a stalled phone would otherwise look like a head-unit crash during setup).
     */
    static void setupWatchdog(final int attempt) {
        handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    long now = SystemClock.elapsedRealtime();
                    if (attempt == sAttempt && SessionPolicy.setupStage(sStage)) {
                        log("session setup has not finished after 60 s (stage '" + sStage + "'): no longer treated as a crash window");
                        log("trace: " + sTrace.line(now));
                        log("verdict: " + sTrace.verdict(now));
                        trace("setup window over: " + sTrace.verdict(now));
                        mark("setup-stalled");
                    }
                    if (attempt == sAttempt && sGateState == SessionPolicy.Quarantine.Gate.PROBATION) {
                        quarantineClear("the trial session outlived its 60 s setup window");
                    }
                } catch (Throwable t) {
                    error("setupWatchdog", t);
                }
            }
        }, 60000);
    }

    static long apTimeout() {
        try {
            long v = Long.parseLong(prop("persist.hmgapi.cpwl.ap_timeout_ms", String.valueOf(AP_TIMEOUT_MS)));
            return Math.max(5000, Math.min(60000, v));
        } catch (NumberFormatException e) {
            return AP_TIMEOUT_MS;
        }
    }

    private static boolean sessionActive() {
        return sSession || sCpClient || sCpWireless;
    }

    private static void cancelApRelease() {
        sApRelease++;
    }

    /** stop and put back the hotspot we started, after a pause, if no session came back meanwhile */
    static void scheduleApRelease(long delayMs) {
        final int token = ++sApRelease;
        handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                if (token != sApRelease || sessionActive()) return;
                new Thread("hmgapi-cpwl-ap-release") {
                    @Override
                    public void run() {
                        try {
                            HotspotGuard g = ap();
                            long aaAge = sAaPhoneAt < 0 ? -1 : SystemClock.elapsedRealtime() - sAaPhoneAt;
                            if (g != null && token == sApRelease && !sessionActive() && aaAge >= 0 && aaAge <= AA_HANDOVER_WINDOW_MS) {
                                log("!! this mod releases the hotspot it started " + (aaAge / 1000) + " s after an Android phone connected over Bluetooth: if that phone is starting wireless Android Auto, the stock side loses its access point");
                            }
                            if (g != null && token == sApRelease && !sessionActive()) g.release("session ended");
                        } catch (Throwable t) {
                            error("releaseAp", t);
                        }
                    }
                }.start();
            }
        }, delayMs);
    }

    /** once per start: what a run that died left behind (hotspot on, wifi_ap_on changed) is put back */
    private static void recoverAp(final boolean resumable) {
        if (sApRecovered) return;
        sApRecovered = true;
        new Thread("hmgapi-cpwl-ap-recover") {
            @Override
            public void run() {
                try {
                    HotspotGuard g = ap();
                    if (g != null) {
                        g.recover(sessionActive(), resumable);
                        if (g.owns()) scheduleApRelease(AP_RELEASE_GRACE_MS * 4);   // adopted: released unless a session starts
                    }
                } catch (Throwable t) {
                    error("recoverAp", t);
                }
            }
        }.start();
    }

    /** USB CarPlay connected: it wins. An attempt waiting for the hotspot or attaching Bluetooth is dropped */
    static void onUsbConnected() {
        sAttempt++;
        boolean pending = sSession && !sCpClient && !sCpWireless;
        if (pending) {
            sSession = false;
            sendDetach();
        }
        if (!sCpClient && !sCpWireless) scheduleApRelease(0);
    }

    static void onUsbGone() {
        final String addr = sAddr;
        if (addr == null || sSession) return;
        handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    HfDeviceBTCMManager m = sBtcm;
                    if (m != null && !sUsb && !sSession && addr.equals(sAddr) && m.IbtcmIsSppConnectedDevice(addr)) onSppUp(addr);
                } catch (Throwable t) {
                    error("onUsbGone", t);
                }
            }
        }, SPP_CONNECT_DELAY_MS);
    }

    static void onCpSessionCreated(Object cp) {
        if (cp != null) sCp = cp;
        mark("cp-created");
        sCpPhase = 1;
        sCpPhaseAt = SystemClock.elapsedRealtime();
        log("CarPlay session created (usb=" + sUsb + " btAddr=" + (sAddr != null) + ")");
        if (!sUsb) traceOk(SessionTrace.CP_SESSION, "DiPO created the CarPlay session");
        if ("created".equals(prop("persist.ev6.cpwl.handover", "data")) && !sUsb && sAddr != null) {
            startCpClient("session created");
        }
    }

    static void onCpSessionStarted(int transport) {
        sCpWireless = transport == 1 && !sUsb;
        if (sCpWireless) {
            sEverWireless = true;
            sGate.success();
        }
        mark("cp-started");
        log("CarPlay session started transport=" + transport + " wireless=" + sCpWireless);
        sCpPhase = 2;
        sCpPhaseAt = SystemClock.elapsedRealtime();
        if (sCpWireless) {
            sScreen.newSession(sCpPhaseAt);
            traceOk(SessionTrace.CP_WIRELESS, "CarPlay session started over Wi-Fi (transport 1)");
            quarantineClear("a wireless CarPlay session ran over Wi-Fi");
            startSessionWatch();
        }
        statusLines("session started");
    }

    /** iAP2 bytes from the phone over the CarPlay channel: to the iAP2 server (buffered until its session is up) */
    static void onCpIapIn(Object cp, byte[] data, int len) {
        try {
            if (cp != null) sCp = cp;
            if (data == null || len <= 0 || sUsb) return;
            if (len >= data.length) len = data.length;
            byte[] b = new byte[len];
            System.arraycopy(data, 0, b, 0, len);
            sCpIn += len;
            if (!sCpClient) startCpClient("iAP2 data from CarPlay channel");
            IIap2Service s = sIap2;
            if (sCpConnected && s != null) {
                flushPending(s);
                s.Iap2SvcTransportReadDone(b, len);
            } else {
                synchronized (sPendingIn) {
                    if (sPendingIn.size() < MAX_PENDING) sPendingIn.add(b);
                }
            }
        } catch (Throwable t) {
            error("onCpIapIn", t);
        }
    }

    static void onCpSessionFinalized(int type) {
        try {
            mark("cp-finalized");
            sCpPhase = 3;
            sCpPhaseAt = SystemClock.elapsedRealtime();
            endSessionWatch("DiPO finalized the session");
            sCpLost = false;
            sCpStalled = false;
            log("CarPlay session finalized (transport " + type + ": 0 wired, 1 wireless; DiPO gives no reason code) cpClient=" + sCpClient + " wireless=" + sCpWireless + " sppIn/out=" + sSppIn + "/" + sSppOut + " cpIn/out=" + sCpIn + "/" + sCpOut);
            boolean client = sCpClient, wireless = sCpWireless;
            if (!wireless && !sEverWireless && sAddr != null) {
                sGate.failure(SystemClock.elapsedRealtime());
                log("Bluetooth-started CarPlay session ended without ever running over Wi-Fi (failure " + sGate.fails() + ")");
                log("verdict: " + sTrace.verdict(SystemClock.elapsedRealtime()));
                trace("CarPlay session finalized without Wi-Fi: " + sTrace.verdict(SystemClock.elapsedRealtime()));
            }
            sCpWireless = false;
            sCpClient = false;
            sCpConnected = false;
            synchronized (sPendingIn) {
                sPendingIn.clear();
            }
            if (client) sendDetach();
            if (client || wireless) onUsbGone();
            scheduleApRelease(AP_RELEASE_GRACE_MS);
        } catch (Throwable t) {
            error("onCpSessionFinalized", t);
        }
    }

    /** DiPO's iAP2 listener: ignore the Bluetooth and CarPlay-channel sessions ending while wireless CarPlay is up */
    static boolean dipoIgnoreDisconnect(int component) {
        boolean ignore = (sCpClient || sCpWireless) && (component == COMPONENT_BT_CARPLAY || component == COMPONENT_CARPLAY_CLIENT);
        if (ignore) log("DiPO: ignoring iAP2 disconnect comp=" + component + " (wireless CarPlay up)");
        return ignore;
    }

    /** DiPO's iAP2 listener: the CarPlay-channel session's identification isn't a new phone */
    static boolean dipoDropNoti(int what) {
        boolean drop = sCpClient && (what == NOTI_IDENTIFICATION_ACCEPTED || what == NOTI_TRANSPORT_IDENTIFIER);
        if (drop) log("DiPO: dropping iAP2 noti " + what + " from the CarPlay-channel session");
        return drop;
    }

    static void startCpClient(final String why) {
        if (sCpClient) return;
        sCpClient = true;
        sCpConnected = false;
        final String addr = sAddr;
        handler().post(new Runnable() {
            @Override
            public void run() {
                try {
                    Context c = context;
                    if (c == null) return;
                    Intent i = new Intent(ACTION_ATTACH);
                    i.setPackage(IAP2_PKG);
                    i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                    i.putExtra("component", COMPONENT_CARPLAY_CLIENT);
                    i.putExtra("serial", addr);
                    c.sendBroadcast(i);
                    log("iAP2 handover to CarPlay channel requested (" + why + ")");
                } catch (Throwable t) {
                    error("startCpClient", t);
                }
            }
        });
    }

    static void onCpClientConnected() {
        sCpConnected = true;
        mark("cp-client-connected");
        IIap2Service s = sIap2;
        if (s != null) flushPending(s);
    }

    private static void flushPending(IIap2Service s) {
        ArrayList<byte[]> all;
        synchronized (sPendingIn) {
            if (sPendingIn.isEmpty()) return;
            all = new ArrayList<byte[]>(sPendingIn);
            sPendingIn.clear();
        }
        for (byte[] b : all) {
            try {
                s.Iap2SvcTransportReadDone(b, b.length);
            } catch (Throwable t) {
                error("flushPending", t);
            }
        }
        log("flushed " + all.size() + " buffered CarPlay-channel packets");
    }

    private static synchronized Handler cpTx() {
        if (sCpTx == null) {
            HandlerThread t = new HandlerThread("hmgapi-cp-tx");
            t.start();
            sCpTx = new Handler(t.getLooper());
        }
        return sCpTx;
    }

    private static Method nativeSend() {
        if (sNativeSend == null) sNativeSend = Points.method(cl, "dipo.carplay.native_iap_send");
        return sNativeSend;
    }

    /** iAP2 bytes from the server for the CarPlay channel: out through the CarPlay library */
    static void writeCp(byte[] data) {
        final Object cp = sCp;
        if (cp == null || data == null || data.length == 0 || !sCpClient) return;
        final byte[] b = data.clone();
        cpTx().post(new Runnable() {
            @Override
            public void run() {
                try {
                    Object r = nativeSend().invoke(cp, b, b.length);
                    int rc = r instanceof Integer ? (Integer) r : -1;
                    if (rc == 0) {
                        sCpSent++;
                        sCpOut += b.length;
                        if (sCpSent <= 3 || sCpSent % 200 == 0) log("CarPlay-channel iAP2 sent " + b.length + " bytes (#" + sCpSent + ")");
                    } else {
                        sCpFail++;
                        // rc is DiPO's CarPlayReturn: 11 = INVALID_SESSION, the native CarPlay session the send refers to is not there
                        if (sCpFail <= 5 || sCpFail % 50 == 0) {
                            log("CarPlay-channel iAP2 send failed rc=" + rc + " (" + SessionPolicy.carPlayReturn(rc) + ")" + (SessionPolicy.sessionGone(rc) ? ": the native CarPlay session is not there" + sessionPhase() : "") + " len=" + b.length);
                        }
                        if (sCpWireless && sLost.failure(SystemClock.elapsedRealtime(), rc) && !sCpLost) {
                            sCpLost = true;
                            log("CarPlay session lost: " + SessionPolicy.LostSession.NEEDED + " sends in a row failed with " + SessionPolicy.carPlayReturn(rc) + ", DiPO has not reported the end yet");
                            trace("session lost (sends fail " + SessionPolicy.carPlayReturn(rc) + ")");
                        }
                    }
                } catch (Throwable t) {
                    error("writeCp native", t);
                }
            }
        });
    }

    static void onSppDown(String addr) {
        if (addr == null || !addr.equals(sAddr)) return;
        if (sCpWireless) {
            sSession = false;
            startCpClient("bluetooth dropped during wireless CarPlay");
            return;
        }
        sAddr = null;
        mark("spp-down");
        sTrace.clear(SessionTrace.BT);
        if (sSession) {
            log("Bluetooth SPP went down during the attempt. verdict: " + sTrace.verdict(SystemClock.elapsedRealtime()));
            trace("SPP down during the attempt: " + sTrace.verdict(SystemClock.elapsedRealtime()));
            sSession = false;
            sAttempt++;
            sendDetach();
        }
        scheduleApRelease(AP_RELEASE_GRACE_MS);
    }

    static void sendDetach() {
        handler().post(new Runnable() {
            @Override
            public void run() {
                try {
                    Context c = context;
                    if (c == null) return;
                    Intent i = new Intent(ACTION_DETACH);
                    i.setPackage(IAP2_PKG);
                    i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                    c.sendBroadcast(i);
                    log("BT_DETACHED sent");
                } catch (Throwable t) {
                    error("sendDetach", t);
                }
            }
        });
    }

    /** SPP data from the phone: to the iAP2 server */
    static void onSppData(BluetoothDevice d, byte[] data, int len) {
        try {
            if (d != null && data != null && len > 0 && !isAppleCached(d)) {
                aaSppData(d, data, len);
                return;
            }
            String addr = sAddr;
            IIap2Service s = sIap2;
            if (addr == null || s == null || d == null || data == null || len <= 0 || !sSession || !addr.equals(d.getAddress())) return;
            if (len >= data.length) len = data.length;
            if (len != data.length) {
                byte[] b = new byte[len];
                System.arraycopy(data, 0, b, 0, len);
                data = b;
            }
            sSppIn += len;
            scanIn(data, len);
            s.Iap2SvcTransportReadDone(data, len);
        } catch (Throwable t) {
            error("onSppData", t);
        }
    }

    /** iAP2 bytes from the server for the Bluetooth session: out over SPP */
    static void writeSpp(byte[] data) {
        try {
            String addr = sAddr;
            HfDeviceBTCMManager m = sBtcm;
            if (addr == null || m == null || data == null || data.length == 0 || !sSession) return;
            if (!m.IbtcmSppOutPutStreamWrite(addr, data)) log("SPP write failed, " + data.length + " bytes");
            else sSppOut += data.length;
            scanOut(data);
        } catch (Throwable t) {
            error("writeSpp", t);
        }
    }

    /** Broadcom's status callbacks: an iPhone connects (start SPP), SPP up/down, SPP data */
    static final class BtListener extends HfDeviceStatusListener {
        @Override
        public void onBTCMDeviceConnectionEventChanged(BluetoothDevice d, int event, boolean[] profiles) {
            if (event != EVENT_BTCM_CONNECTED) return;
            try {
                if (isApple(d)) {
                    log("iPhone BT connected: " + who(d.getAddress()));
                    scheduleSppConnect(d.getAddress());
                } else {
                    aaPhoneConnected(d);
                }
            } catch (Throwable t) {
                error("connEvent", t);
            }
        }

        @Override
        public void onBTCMSppStatusChanged(BluetoothDevice d, int status) {
            if (d == null) return;
            try {
                if (!isAppleCached(d)) {
                    aaSppStatus(d, status);
                    return;
                }
                String addr = d.getAddress();
                log("SPP status " + status + " for " + who(addr));
                if (status == SPP_CONNECTED) onSppUp(addr);
                else if (status == SPP_DISCONNECTED) onSppDown(addr);
            } catch (Throwable t) {
                error("sppStatus", t);
            }
        }

        @Override
        public void onBTCMSppInPutStreamChanged(BluetoothDevice d, byte[] buffer, int length) {
            onSppData(d, buffer, length);
        }
    }

    /** the iAP2 server's side: USB and CarPlay-channel sessions, and transport data to send */
    static final class TxListener extends IIap2Listener.Stub {
        @Override
        public void connected(int component, String serial) {
            try {
                onConnected(component, serial);
            } catch (Throwable t) {
                error("iAP2 connected", t);   // never out of a Binder callback
            }
        }

        @Override
        public void disconnected(int component) {
            try {
                onDisconnected(component);
            } catch (Throwable t) {
                error("iAP2 disconnected", t);
            }
        }

        private void onConnected(int component, String serial) {
            log("iAP2 connected comp=" + component);
            if (component == COMPONENT_BT_CARPLAY) {
                mark("iap2-connected-4");
                traceOk(SessionTrace.IAP2_BT, "iAP2 Bluetooth session (component 4) connected");
            }
            else if (component == COMPONENT_CARPLAY_CLIENT) mark("iap2-connected-5");
            // what DiPO keeps as the current device (setCurrentIapDeviceName): a Bluetooth address is the notice's key
            if ((component == COMPONENT_USB || component == COMPONENT_BT_CARPLAY) && serial != null && serial.contains(":")) sLastBtId = serial;
            if (component == COMPONENT_USB) {
                sUsb = true;
                onUsbConnected();
            } else if (component == COMPONENT_CARPLAY_CLIENT) onCpClientConnected();
        }

        private void onDisconnected(int component) {
            log("iAP2 disconnected comp=" + component);
            if (component == COMPONENT_BT_CARPLAY) mark("iap2-disconnected-4");
            if (component == COMPONENT_USB) {
                sUsb = false;
                onUsbGone();
            } else if (component == COMPONENT_BT_CARPLAY) {
                if (sSession && !sCpClient && !sEverWireless) {
                    sGate.failure(SystemClock.elapsedRealtime());
                    log("iAP2 Bluetooth session ended before CarPlay ran (failure " + sGate.fails() + "). verdict: " + sTrace.verdict(SystemClock.elapsedRealtime()));
                    trace("iAP2 Bluetooth session ended: " + sTrace.verdict(SystemClock.elapsedRealtime()));
                }
                sSession = false;
            } else if (component == COMPONENT_CARPLAY_CLIENT) {
                sCpConnected = false;
            }
        }

        @Override
        public void notify(int what, int a1, int a2, Iap2NotiMsg msg) {
            if ((what != NOTI_BT_TRANSPORT_DATA && what != NOTI_CARPLAY_CLIENT_TRANSPORT_DATA) || msg == null) return;
            try {
                Iap2NotiMsg.Iap2ObjGroup g = msg.getItemGroup();
                if (g == null) return;
                for (int k = 0; k < g.getItemCnt(); k++) {
                    Iap2NotiMsg.Iap2ObjItem it = g.getObjItem(k);
                    if (it == null || it.getItemParamType() != OBJ_BLOB) continue;
                    if (what == NOTI_CARPLAY_CLIENT_TRANSPORT_DATA) writeCp(it.getItemByteArray());
                    else writeSpp(it.getItemByteArray());
                }
            } catch (Throwable t) {
                error("notify", t);
            }
        }
    }
}
