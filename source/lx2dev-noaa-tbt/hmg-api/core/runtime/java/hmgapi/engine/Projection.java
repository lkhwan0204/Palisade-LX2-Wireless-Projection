package hmgapi.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What mods ask of phone projection (mod.json "projection"), settled into one set of
 * settings for the runtime in GPM (hook.Projection), so no mod rewrites GPM:
 *
 *   "projection": {"aa_density": 200,                       Android Auto's DPI (60-480; stock 240 on wide screens)
 *                  "aa_sensors": {"remove": ["speed"],       not offered to the phone (speed: no tap limit)
 *                                 "add": [23]},             sensor types offered as well
 *                  "aa_fuel_types": [10], "aa_ev_connectors": [4, 1],
 *                  "events": true}                          the mod's apps hear hmgapi.intent.action.PROJECTION
 *
 * Density: one value; two mods asking differently is a conflict (the first by id
 * wins, the owner picks with hmgapi.json overrides.projection {"aa_density": "<mod>"}).
 * Sensors, fuel types and connectors: what all mods ask, together.
 */
public final class Projection {
    private Projection() {}

    public static final List<String> REMOVABLE = java.util.Arrays.asList("speed");

    static final class Ask {
        String mod;
        int density;
        final List<String> remove = new ArrayList<String>();
        final List<Integer> add = new ArrayList<Integer>(), fuel = new ArrayList<Integer>(), connectors = new ArrayList<Integer>();
        boolean events;
    }

    @SuppressWarnings("unchecked")
    static Ask of(String id, Object o, List<String> errors) {
        if (!(o instanceof Map)) {
            if (o != null) errors.add(id + ": projection: must be an object");
            return null;
        }
        Map<String, Object> m = (Map<String, Object>) o;
        Ask a = new Ask();
        a.mod = id;
        Object d = m.get("aa_density");
        if (d != null) {
            int v = d instanceof Number ? ((Number) d).intValue() : -1;
            if (v < 60 || v > 480) errors.add(id + ": projection aa_density: 60-480");
            else a.density = v;
        }
        Object s = m.get("aa_sensors");
        if (s instanceof Map) {
            Object r = ((Map<String, Object>) s).get("remove"), ad = ((Map<String, Object>) s).get("add");
            if (r instanceof List) {
                for (Object x : (List<Object>) r) {
                    if (REMOVABLE.contains(String.valueOf(x))) a.remove.add(String.valueOf(x));
                    else errors.add(id + ": projection aa_sensors remove: " + x + " (only " + REMOVABLE + ")");
                }
            }
            ints(id, "aa_sensors add", ad, 1, 64, a.add, errors);
        }
        ints(id, "aa_fuel_types", m.get("aa_fuel_types"), 0, 31, a.fuel, errors);
        ints(id, "aa_ev_connectors", m.get("aa_ev_connectors"), 0, 31, a.connectors, errors);
        a.events = Boolean.TRUE.equals(m.get("events"));
        return a;
    }

    @SuppressWarnings("unchecked")
    static void ints(String id, String what, Object o, int lo, int hi, List<Integer> into, List<String> errors) {
        if (o == null) return;
        if (!(o instanceof List)) {
            errors.add(id + ": projection " + what + ": a list of numbers");
            return;
        }
        for (Object x : (List<Object>) o) {
            int v = x instanceof Number ? ((Number) x).intValue() : -1;
            if (v < lo || v > hi) errors.add(id + ": projection " + what + ": " + x + " isn't " + lo + "-" + hi);
            else if (!into.contains(v)) into.add(v);
        }
    }

    /** the enabled mods' asks -> state.json "projection"; packages: mod -> its apps (for events) */
    static Map<String, Object> settle(List<Ask> asks, Map<String, List<String>> packages, Map<String, Object> overrides, List<String> notes) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        Object o = overrides == null ? null : overrides.get("projection");
        Object pick = o instanceof Map ? ((Map<?, ?>) o).get("aa_density") : null;
        Ask dens = null;
        for (Ask a : asks) {
            if (a.density == 0) continue;
            if (dens == null || a.mod.equals(pick)) dens = a;
        }
        for (Ask a : asks) {
            if (a.density != 0 && dens != null && a != dens && a.density != dens.density) {
                notes.add("conflict: " + a.mod + " asks Android Auto density " + a.density + ", " + dens.mod + " " + dens.density + "; "
                        + dens.mod + " has it" + (pick == null ? " (pick another: hmgapi.json overrides.projection {\"aa_density\": \"<mod>\"})" : " (the owner's choice)"));
            }
        }
        if (dens != null) {
            out.put("aa_density", dens.density);
            out.put("aa_density_by", dens.mod);
        }
        List<Object> remove = new ArrayList<Object>(), add = new ArrayList<Object>(), fuel = new ArrayList<Object>(), conn = new ArrayList<Object>();
        List<Object> events = new ArrayList<Object>();
        for (Ask a : asks) {
            for (String r : a.remove) if (!remove.contains(r)) remove.add(r);
            for (Integer x : a.add) if (!add.contains(x)) add.add(x);
            for (Integer x : a.fuel) if (!fuel.contains(x)) fuel.add(x);
            for (Integer x : a.connectors) if (!conn.contains(x)) conn.add(x);
            if (a.events && packages.get(a.mod) != null) for (String p : packages.get(a.mod)) if (!events.contains(p)) events.add(p);
        }
        if (!remove.isEmpty()) out.put("aa_sensors_remove", remove);
        if (!add.isEmpty()) out.put("aa_sensors_add", add);
        if (!fuel.isEmpty()) out.put("aa_fuel_types", fuel);
        if (!conn.isEmpty()) out.put("aa_ev_connectors", conn);
        if (!events.isEmpty()) out.put("events", events);
        return out;
    }

    static List<String> describe(Ask a) {
        List<String> out = new ArrayList<String>();
        if (a == null) return out;
        if (a.density != 0) out.add("projection Android Auto density " + a.density + " [" + a.mod + "]");
        if (!a.remove.isEmpty()) out.add("projection Android Auto: the phone isn't offered " + a.remove + " (for every mod) [" + a.mod + "]");
        if (!a.add.isEmpty()) out.add("projection Android Auto sensors added " + a.add + " [" + a.mod + "]");
        if (!a.fuel.isEmpty() || !a.connectors.isEmpty()) out.add("projection Android Auto fuel types " + a.fuel + ", EV connectors " + a.connectors + " [" + a.mod + "]");
        if (a.events) out.add("projection events to its apps [" + a.mod + "]");
        return out;
    }
}
