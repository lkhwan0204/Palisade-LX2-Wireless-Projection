package hmgapi.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * How mods' hooks fit together, decided before any mod code loads (the zygote's
 * mod loader and the engine's plan use the same rules).
 *
 * A mod declares each hook point it hooks, in mod.json:
 *   "hooks": { "jar": ..., "entry": ..., "points": { "<point id>": "observe" | "change" | "replace" } }
 *   observe  reads only (arguments, result)
 *   change   may change arguments (before) or the result (after)
 *   replace  may skip the original method (sets the result before it runs)
 *
 * Order: mods a mod requires come before it, otherwise by id. On one method, every
 * observe/change hook runs before a replace hook, so a replacement never hides
 * another mod's hook. Two mods that both replace the same point conflict: the later
 * one (in that order) isn't loaded, and the reason names both and the point.
 */
public final class HookPlan {
    public static final String OBSERVE = "observe", CHANGE = "change", REPLACE = "replace";

    /** mods in load order */
    public final List<String> order = new ArrayList<String>();
    /** mod -> why it isn't loaded */
    public final Map<String, String> refused = new LinkedHashMap<String, String>();
    /** point -> (mod -> mode), loaded mods only */
    public final Map<String, Map<String, String>> points = new TreeMap<String, Map<String, String>>();

    /**
     * mods: id -> mod.json (enabled mods only). known: the point ids this car's map
     * has (null: don't check). missing: catalogue points this car's map lacks, and why
     * (a mod that hooks one isn't loaded here; its files and settings still apply).
     */
    public static HookPlan of(Map<String, Map<String, Object>> mods, Set<String> known) {
        return of(mods, known, null);
    }

    @SuppressWarnings("unchecked")
    public static HookPlan of(Map<String, Map<String, Object>> mods, Set<String> known, Map<String, String> missing) {
        HookPlan p = new HookPlan();
        // order: requires first, then by id (a loop is broken by id)
        Set<String> done = new LinkedHashSet<String>();
        for (String id : new java.util.TreeSet<String>(mods.keySet())) visit(id, mods, done, new LinkedHashSet<String>());
        p.order.addAll(done);
        for (String id : p.order) {
            Object hooks = mods.get(id).get("hooks");
            if (!(hooks instanceof Map)) continue;
            Object decl = ((Map<String, Object>) hooks).get("points");
            if (decl == null) continue;
            if (!(decl instanceof Map)) {
                p.refused.put(id, "\"hooks\": \"points\" must be an object: {\"<point id>\": \"observe|change|replace\"}");
                continue;
            }
            String why = null;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) decl).entrySet()) {
                String mode = String.valueOf(e.getValue());
                if (!OBSERVE.equals(mode) && !CHANGE.equals(mode) && !REPLACE.equals(mode)) {
                    why = "hook point " + e.getKey() + ": mode \"" + mode + "\" (observe, change or replace)";
                    break;
                }
                if (known != null && !known.contains(e.getKey())) {
                    why = missing != null && missing.containsKey(e.getKey())
                            ? "hook point " + e.getKey() + " isn't mapped on this car (" + missing.get(e.getKey())
                                    + "): its hooks are off here, its other parts still apply"
                            : "hook point " + e.getKey() + " isn't one HMG-API has (a new one can be added to HMG-API)";
                    break;
                }
                if (REPLACE.equals(mode)) {
                    Map<String, String> on = p.points.get(e.getKey());
                    if (on != null) {
                        for (Map.Entry<String, String> o : on.entrySet()) {
                            if (REPLACE.equals(o.getValue())) {
                                why = "conflict: " + id + " and " + o.getKey() + " both replace " + e.getKey()
                                        + "; turn one of them off";
                                break;
                            }
                        }
                    }
                }
                if (why != null) break;
            }
            if (why != null) {
                p.refused.put(id, why);
                continue;
            }
            for (Map.Entry<String, Object> e : ((Map<String, Object>) decl).entrySet()) {
                Map<String, String> on = p.points.get(e.getKey());
                if (on == null) p.points.put(e.getKey(), on = new LinkedHashMap<String, String>());
                on.put(id, String.valueOf(e.getValue()));
            }
        }
        // a mod that requires a refused mod can't load either
        boolean more = true;
        while (more) {
            more = false;
            for (String id : p.order) {
                if (p.refused.containsKey(id)) continue;
                for (Version.Dep d : Version.deps(mods.get(id).get("requires"))) {
                    if (p.refused.containsKey(d.id)) {
                        p.refused.put(id, "requires " + d.id + ", which isn't loaded (" + p.refused.get(d.id) + ")");
                        for (Map<String, String> on : p.points.values()) on.remove(id);
                        more = true;
                        break;
                    }
                }
            }
        }
        return p;
    }

    static void visit(String id, Map<String, Map<String, Object>> mods, Set<String> done, Set<String> path) {
        if (done.contains(id) || !mods.containsKey(id) || !path.add(id)) return;
        for (Version.Dep d : Version.deps(mods.get(id).get("requires"))) visit(d.id, mods, done, path);
        done.add(id);
    }

    /** The plan's lines about hooks, for engine plan. */
    public List<String> report() {
        List<String> out = new ArrayList<String>();
        for (Map.Entry<String, String> r : refused.entrySet()) out.add("hooks    " + r.getKey() + " won't load: " + r.getValue());
        for (Map.Entry<String, Map<String, String>> e : points.entrySet()) {
            if (e.getValue().size() < 2) continue;
            StringBuilder b = new StringBuilder();
            for (Map.Entry<String, String> m : e.getValue().entrySet()) {
                if (b.length() > 0) b.append(", ");
                b.append(m.getKey()).append(" (").append(m.getValue()).append(')');
            }
            out.add("hooks    " + e.getKey() + ": " + b + " share it");
        }
        return out;
    }
}
