package hmgapi.hook;

import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Android Auto settings and events for mods (public/state.json "projection", settled
 * by hmgapi.engine.Projection), in GPM's process. Nothing in GPM is replaced:
 *
 * - density: VideoSink.nearestVideoConfiguration gets the asked DPI as density and
 *   real density (what aa-proxy-rs and AA DPI do), for every video setting
 * - sensors, fuel and connector types: SensorSource.init gets the changed lists
 *   (speed left out: the phone doesn't know the car reports speed and doesn't limit
 *   taps; types added; fuel/EV connector types instead of "unknown")
 * - events: the mods that ask hear hmgapi.intent.action.PROJECTION with extras
 *   "event" (aa_connected, aa_disconnected, aa_navigation_focus, aa_guidance,
 *   aa_navigation_message, cp_connected (value 1: wireless), cp_disconnected) and
 *   "value" (int) or "type" (int) + "data" (byte[], an
 *   Android Auto navigation message: 0x8006 destinations and charging stops, 0x8007
 *   next turn distance and time)
 *
 * Settings are read when GPM's process starts; a change applies once GPM restarts
 * (applying changes in HMG-API restarts the apps they touch).
 */
public final class ProjectionHooks {
    private ProjectionHooks() {}

    public static final String ACTION = "hmgapi.intent.action.PROJECTION";
    static Context context;
    static final List<String> listeners = new ArrayList<String>();

    /** this process's settings and the apps that hear events */
    static JSONObject settings(LoadedApp app) throws Exception {
        context = app.context;
        JSONObject p;
        try {
            p = new JSONObject(Mods.read(Live.STATE)).optJSONObject("projection");
        } catch (Exception e) {
            p = null;
        }
        if (p == null) p = new JSONObject();
        listeners.clear();
        JSONArray ev = p.optJSONArray("events");
        for (int i = 0; ev != null && i < ev.length(); i++) listeners.add(ev.getString(i));
        return p;
    }

    /** CarPlay (DiPO's process): sessions */
    static void dipo(LoadedApp app) throws Exception {
        settings(app);
        if (listeners.isEmpty()) return;
        Points.hook(app.classLoader, "dipo.carplay.session_started", new MethodHook() {
            @Override
            protected void afterHookedMethod(Param m) {
                tell("cp_connected", (Integer) m.args[0]);   // transport: 1 wireless
            }
        });
        Points.hook(app.classLoader, "dipo.carplay.session_finalized", new MethodHook() {
            @Override
            protected void afterHookedMethod(Param m) {
                tell("cp_disconnected", 0);
            }
        });
    }

    static volatile boolean aaConnected;

    static void gpm(LoadedApp app) throws Exception {
        final ClassLoader cl = app.classLoader;
        JSONObject p = settings(app);
        final int density = p.optInt("aa_density", 0);
        final boolean noSpeed = has(p.optJSONArray("aa_sensors_remove"), "speed");
        final int[] add = ints(p.optJSONArray("aa_sensors_add"));
        final int[] fuel = ints(p.optJSONArray("aa_fuel_types")), conn = ints(p.optJSONArray("aa_ev_connectors"));
        if (density > 0) {
            Points.hook(cl, "gpm.video_sink.nearest", new MethodHook() {
                @Override
                protected void beforeHookedMethod(Param m) {
                    m.args[2] = density;
                    m.args[3] = density;
                }
            });
            HmgApi.i("projection: Android Auto density " + density + " (" + p.optString("aa_density_by") + ")");
        }
        final int speed = (Integer) Points.get(cl, "gpm.sensor_source.speed", null);
        Points.hook(cl, "gpm.sensor_source.init", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param m) {
                int[] sensors = (int[]) m.args[2];
                if (sensors != null && (noSpeed || add.length > 0)) m.args[2] = sensors(sensors, noSpeed ? speed : -1, add);
                if (fuel.length > 0) m.args[4] = fuel;
                if (conn.length > 0) m.args[5] = conn;
            }

            @Override
            protected void afterHookedMethod(Param m) {
                aaConnected = true;
                tell("aa_connected", 0);
            }
        });
        // a clean end calls destroy; a phone that's just gone (Wi-Fi off, ping timeout) only
        // onDisconnected. One aa_disconnected per session either way
        MethodHook ended = new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param m) {
                if (!aaConnected) return;
                aaConnected = false;
                tell("aa_disconnected", 0);
            }
        };
        Points.hook(cl, "gpm.receiver.destroy", ended);
        Points.hook(cl, "gpm.receiver_service.disconnected", ended);
        Points.hook(cl, "gpm.receiver.send_navigation_focus", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param m) {
                tell("aa_navigation_focus", (Integer) m.args[0]);   // NAVIGATION_FOCUS_STATE_NATIVE: the car's navigation
            }
        });
        Points.hook(cl, "gpm.navigation_status.status", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param m) {
                tell("aa_guidance", (Integer) m.args[0]);   // 1 active, 2 inactive, 3 rerouting
            }
        });
    }

    /** the offered sensor list without one type (-1: none) and with the added ones */
    static int[] sensors(int[] in, int drop, int[] add) {
        int[] tmp = new int[in.length + add.length];
        int n = 0;
        for (int s : in) if (s != drop) tmp[n++] = s;
        for (int a : add) {
            boolean dup = false;
            for (int i = 0; i < n; i++) dup |= tmp[i] == a;
            if (!dup) tmp[n++] = a;
        }
        int[] out = new int[n];
        System.arraycopy(tmp, 0, out, 0, n);
        return out;
    }

    /**
     * For a mod's code in GPM that sees Android Auto's navigation messages (the
     * wireless mod's library patch hands them over as "S|P <len> <hex>"): passes
     * them on to the mods that asked for projection events.
     */
    public static void navigationDump(String line) {
        if (line == null || listeners.isEmpty()) return;
        String[] w = line.trim().split(" ");
        if (w.length < 3) return;
        int type = "S".equals(w[0]) ? 0x8006 : "P".equals(w[0]) ? 0x8007 : 0;
        if (type == 0 || w[2].length() % 2 != 0) return;
        byte[] data = new byte[w[2].length() / 2];
        try {
            for (int i = 0; i < data.length; i++) data[i] = (byte) Integer.parseInt(w[2].substring(2 * i, 2 * i + 2), 16);
        } catch (NumberFormatException e) {
            return;
        }
        Intent i = new Intent(ACTION).putExtra("event", "aa_navigation_message").putExtra("type", type).putExtra("data", data);
        send(i);
    }

    static void tell(String event, int value) {
        if (listeners.isEmpty()) return;
        send(new Intent(ACTION).putExtra("event", event).putExtra("value", value));
    }

    static void send(Intent i) {
        if (context == null) return;
        for (String pkg : listeners) {
            try {
                // a mod's app that was never opened is "stopped": without the flag Android drops it
                context.sendBroadcast(new Intent(i).setPackage(pkg).addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES));
            } catch (Throwable t) {
                HmgApi.e("projection: event to " + pkg, t);
            }
        }
    }

    static boolean has(JSONArray a, String v) {
        for (int i = 0; a != null && i < a.length(); i++) if (v.equals(a.optString(i))) return true;
        return false;
    }

    static int[] ints(JSONArray a) {
        int n = a == null ? 0 : a.length();
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = a.optInt(i);
        return out;
    }
}
