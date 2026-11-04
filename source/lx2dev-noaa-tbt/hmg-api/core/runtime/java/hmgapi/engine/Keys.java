package hmgapi.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The car's buttons a mod may bind (mod.json "input"), by name. Each name is a
 * KeyEvent constant on the car (KEYCODE_SHORTCUT_MAP ...), looked up by name at
 * run time, so one name works on every car that has the button. Which buttons a
 * car has comes from its key layout; binding one it doesn't have does nothing.
 *
 *   "input": [{"key": "custom", "press": "hold", "open": "dev.example/.Launcher"},
 *             {"key": "tune_up", "press": "down", "broadcast": "dev.example.KNOB"}]
 *
 * press: hold (held 0.8 s; always instead of the car's own hold, and a shorter
 * press still reaches the car when it's released), short (one press; stock keep
 * by default; replaced, a press held 0.8 s still goes on to the car as a long
 * press), down (every press and repeat at once, watching only: knobs).
 * No multi-press (x2-x4): it would delay every press of the key or let the car act
 * on each one. A short press of a star key is the owner's to assign in the car's
 * star-key menu (mods offer entries there); a held star key is a mod's.
 * stock: keep (default for short: the car still does what the button does) or
 * replace (HMG-API takes that press; other presses still reach the car).
 * Two mods taking the same key and press: one wins (the owner picks in
 * hmgapi.json overrides.input {"custom hold": "<mod>"}; otherwise the first by id)
 * and the plan says so. Keeping bindings never clash.
 */
public final class Keys {
    private Keys() {}

    /** name -> KeyEvent constant without KEYCODE_ */
    public static final Map<String, String> KEYS;
    /** where the button usually is, for the docs and the plan */
    public static final Map<String, String> PLACE;
    /** emergency buttons: watched, never taken from the car */
    public static final List<String> KEEP_ONLY = Collections.unmodifiableList(Arrays.asList("sos", "power"));
    public static final List<String> PRESSES = Collections.unmodifiableList(Arrays.asList("short", "hold", "down"));

    static {
        Map<String, String> k = new LinkedHashMap<String, String>(), w = new LinkedHashMap<String, String>();
        String hu = "head unit", sw = "steering wheel", oh = "overhead console";
        add(k, w, "power", "POWER_AVN", hu);
        add(k, w, "home", "SHORTCUT_HOME", hu);
        add(k, w, "map", "SHORTCUT_MAP", hu);
        add(k, w, "navi", "SHORTCUT_NAVI", hu);
        add(k, w, "radio", "SHORTCUT_RADIO", hu);
        add(k, w, "media", "SHORTCUT_MEDIA", hu);
        add(k, w, "custom", "SHORTCUT_CUSTOM", hu + " (star)");
        add(k, w, "setup", "SHORTCUT_SETUP", hu);
        add(k, w, "phone", "SHORTCUT_PHONE", hu);
        add(k, w, "seek_up", "SEEK_UP", hu);
        add(k, w, "seek_down", "SEEK_DOWN", hu);
        add(k, w, "volume_up", "VOLUME_UP", hu + " and " + sw);
        add(k, w, "volume_down", "VOLUME_DOWN", hu + " and " + sw);
        add(k, w, "volume_knob_up", "VOLUME_UP_DUMMY", hu + " (volume knob turned right; EV6: its own codes, not volume_up)");
        add(k, w, "volume_knob_down", "VOLUME_DOWN_DUMMY", hu + " (volume knob turned left)");
        add(k, w, "tune_up", "TUNE_UP", hu + " (TUNE knob turned right)");
        add(k, w, "tune_down", "TUNE_DOWN", hu + " (TUNE knob turned left)");
        add(k, w, "tune_enter", "TUNE_ENTER", hu + " (TUNE knob pressed)");
        add(k, w, "apps", "SHORTCUT_APPS", hu);
        add(k, w, "mymenu", "SHORTCUT_MYMENU", hu);
        add(k, w, "info", "SHORTCUT_INFO", hu);
        add(k, w, "dest", "SHORTCUT_DEST", hu);
        add(k, w, "fmam", "SHORTCUT_FMAM", hu);
        add(k, w, "fm", "SHORTCUT_FM", hu);
        add(k, w, "am", "SHORTCUT_AM", hu);
        add(k, w, "xm", "SHORTCUT_XM", hu);
        add(k, w, "dmb", "SHORTCUT_DMB", hu);
        add(k, w, "ev", "SHORTCUT_EV", hu);
        add(k, w, "nmode", "SHORTCUT_NMODE", sw + " (N models)");
        add(k, w, "mood_lamp", "SHORTCUT_MOOD_LAMP", hu);
        add(k, w, "performance_gauge", "SHORTCUT_PERFORMANCE_GAUGE", hu);
        add(k, w, "channel_up", "CHANNEL_UP", sw + " (seek up)");
        add(k, w, "channel_down", "CHANNEL_DOWN", sw + " (seek down)");
        add(k, w, "mode", "SHORTCUT_MODE", sw);
        add(k, w, "mute", "MUTE", sw);
        add(k, w, "ptt", "SHORTCUT_PTT", sw + " (voice)");
        add(k, w, "ptt_rear", "SHORTCUT_PTT_REAR", "rear seat");
        add(k, w, "wheel_custom", "SHORTCUT_SWRC_CUSTOM", sw + " (star)");
        add(k, w, "call", "CALL", sw);
        add(k, w, "connect", "SHORTCUT_BLUELINK_CENTER", oh + " (Kia Connect / Bluelink)");
        add(k, w, "poi", "SHORTCUT_POI", oh);
        add(k, w, "sos", "SHORTCUT_SOS", oh + " (emergency call: watched only)");
        KEYS = Collections.unmodifiableMap(k);
        PLACE = Collections.unmodifiableMap(w);
    }

    private static void add(Map<String, String> k, Map<String, String> w, String name, String constant, String place) {
        k.put(name, constant);
        w.put(name, place);
    }

    /** one validated binding, as the runtime in system_server acts on it */
    public static final class Binding {
        public String mod, key, press, stock = "keep", broadcast, open, when;
        /** "<key> <short|hold>": a press HMG-API makes the car see (a remap); release: the same, when a hold ends */
        public String send, release;
        public final List<String> packages = new ArrayList<String>();

        public String slot() {
            return key + " " + press;
        }

        public Map<String, Object> json() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("mod", mod);
            m.put("key", key);
            m.put("keycode", "KEYCODE_" + KEYS.get(key));
            m.put("press", press);
            m.put("stock", stock);
            if (broadcast != null) m.put("broadcast", broadcast);
            if (open != null) m.put("open", open);
            if (send != null) {
                m.put("send", send);
                m.put("send_keycode", "KEYCODE_" + KEYS.get(send.split(" ")[0]));
            }
            if (release != null) {
                m.put("release", release);
                m.put("release_keycode", "KEYCODE_" + KEYS.get(release.split(" ")[0]));
            }
            m.put("packages", new ArrayList<Object>(packages));
            return m;
        }
    }

    /**
     * Settles the enabled mods' bindings: every keeping one stays; of the ones that
     * replace the same key and press, one wins. notes gets what the owner should see.
     */
    public static List<Binding> settle(List<Binding> all, Map<String, Object> overrides, List<String> notes) {
        Map<String, List<Binding>> replacing = new LinkedHashMap<String, List<Binding>>();
        for (Binding b : all) {
            if (!"replace".equals(b.stock)) continue;
            List<Binding> l = replacing.get(b.slot());
            if (l == null) replacing.put(b.slot(), l = new ArrayList<Binding>());
            l.add(b);
        }
        Object o = overrides == null ? null : overrides.get("input");
        Map<?, ?> picks = o instanceof Map ? (Map<?, ?>) o : Collections.emptyMap();
        List<Binding> out = new ArrayList<Binding>();
        for (Binding b : all) {
            List<Binding> l = replacing.get(b.slot());
            if ("replace".equals(b.stock) && l != null && l.size() > 1) {
                Binding win = l.get(0);
                Object pick = picks.get(b.slot());
                for (Binding c : l) if (c.mod.equals(pick)) win = c;
                if (b != win) {   // reported once per mod left out
                    notes.add("conflict: " + b.mod + " and " + win.mod + " both take " + b.slot() + "; " + win.mod
                            + " has it" + (pick == null ? " (pick another: hmgapi.json overrides.input {\"" + b.slot() + "\": \"<mod>\"})" : " (the owner's choice)"));
                    continue;
                }
            }
            out.add(b);
        }
        return out;
    }
}
