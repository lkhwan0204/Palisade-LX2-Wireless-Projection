package hmgapi.engine;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Maps HMG-API's hook points on a car from its own files: tools/map-car.py's rules
 * (core/hookpoints: classes, members and their shapes, strings in their code), on
 * the dex in each stock .odex (Dex) instead of baksmali's output, so it runs on the
 * head unit itself (`engine map-car`) and off-car on a stock tree (tests: it must
 * find what map-car.py finds).
 *
 * Points described by a regex over smali ("pattern", "code") can't be matched from
 * the dex: for those it tries the names the other cars' maps give the same point,
 * and keeps one only if this car has it with the point's shape ("by reference").
 * Everything else that doesn't match exactly once is missing, with why.
 *
 *   java -cp <engine classes> hmgapi.engine.MapProbe <stock tree> <catalogue dir> [<maps dir>]
 *   prints {"points": {...}, "missing": {...}, "by_reference": [...]}
 *
 * On the unit: `engine map-car [--write]` (run below).
 */
public final class MapProbe {
    static final String[] BCP = { "core", "conscrypt", "okhttp", "core-junit", "bouncycastle", "ext", "framework", "framework2",
        "telephony-common", "voip-common", "mms-common", "android.policy", "services", "apache-xml", "webviewchromium", "automotive" };

    final String sys;      // what /system is: a stock tree (app/, framework/), or <root>/system
    final String backups;  // HMG-API's stock backups (<root>/data/hmgapi/stock), or null
    final File mapsDir;
    final Map<String, Dex.Cls> framework = new LinkedHashMap<String, Dex.Cls>();
    /** point id -> class name (class points) or member entry */
    public final Map<String, Object> found = new LinkedHashMap<String, Object>();
    /** point id -> its map entry, as map-car.py writes it */
    public final Map<String, Map<String, Object>> points = new TreeMap<String, Map<String, Object>>();
    public final Map<String, String> missing = new TreeMap<String, String>();
    public final Set<String> byReference = new java.util.TreeSet<String>();
    /** stock files read, path -> md5 (car.json "files") */
    public final Map<String, String> files = new TreeMap<String, String>();
    /** catalogue app -> {"package", "files"} (car.json "app_files") */
    public final Map<String, Object> appFiles = new TreeMap<String, Object>();
    final Map<String, List<Map<String, Object>>> references = new LinkedHashMap<String, List<Map<String, Object>>>();

    static final class Miss extends Exception {
        final boolean later;   // needs another point first

        Miss(String why, boolean later) {
            super(why);
            this.later = later;
        }
    }

    /** sys: a stock tree (app/, framework/) or a unit's /system; backups: <root>/data/hmgapi/stock or null; mapsDir: other cars' maps (names for smali-only points) */
    public MapProbe(String sys, String backups, File mapsDir) throws Exception {
        this.sys = sys;
        this.backups = backups;
        this.mapsDir = mapsDir;
        for (String j : BCP) {
            File o = local("/system/framework/" + j + ".odex");
            if (o.isFile()) framework.putAll(Dex.read(o));
        }
        loadReferences();
    }

    /** a stock file: HMG-API's backup of it when it has one (a mod changed the file), else the file */
    File local(String path) {
        if (backups != null && new File(backups + path).isFile()) return new File(backups + path);
        return new File(sys + path.substring("/system".length()));
    }

    @SuppressWarnings("unchecked")
    void loadReferences() {
        if (mapsDir == null || !mapsDir.isDirectory()) return;
        for (File make : CarMap.list(mapsDir)) for (File model : CarMap.list(make)) for (File region : CarMap.list(model)) for (File fw : CarMap.list(region)) {
            File[] js = fw.listFiles();
            if (js == null) continue;
            for (File j : js) {
                if (!j.getName().endsWith(".json") || j.getName().equals("car.json")) continue;
                try {
                    for (Map.Entry<String, Object> e : Json.object(Engine.read(j)).entrySet()) {
                        List<Map<String, Object>> l = references.get(e.getKey());
                        if (l == null) references.put(e.getKey(), l = new ArrayList<Map<String, Object>>());
                        l.add((Map<String, Object>) e.getValue());
                    }
                } catch (Exception ignored) {
                    // a broken map gives no references
                }
            }
        }
    }

    /** Maps every catalogue file (core/hookpoints/*.json) in a directory. */
    @SuppressWarnings("unchecked")
    public void run(File catalogueDir) throws Exception {
        File[] cats = catalogueDir.listFiles();
        java.util.Arrays.sort(cats);
        for (File f : cats) {
            if (!f.getName().endsWith(".json")) continue;
            Map<String, Object> cat = Json.object(Engine.read(f));
            if (!cat.containsKey("points")) continue;
            mapCatalogue(cat);
        }
    }

    @SuppressWarnings("unchecked")
    void mapCatalogue(Map<String, Object> cat) throws Exception {
        String app = String.valueOf(cat.get("app"));
        List<Map<String, Object>> pts = (List<Map<String, Object>>) cat.get("points");
        List<Object> catFiles = (List<Object>) cat.get("files");
        Map<String, Object> af = new LinkedHashMap<String, Object>();
        af.put("package", cat.get("package"));
        af.put("files", catFiles);
        appFiles.put(app, af);
        Map<String, Dex.Cls> classes = new LinkedHashMap<String, Dex.Cls>(framework);
        String noFiles = null;
        for (Object o : catFiles) {
            String path = String.valueOf(o);
            File lf = local(path);
            if (!lf.isFile()) {
                noFiles = "no stock " + path + (backups != null ? " on this car" : " in the tree");
                continue;
            }
            files.put(path, CarMap.md5(lf));
            String base = lf.getName().replaceAll("\\.odex$", "");
            if (cat.get("package") == null && java.util.Arrays.asList(BCP).contains(base)) continue;   // already in framework
            classes.putAll(Dex.read(lf));
        }
        if (noFiles != null) {
            for (Map<String, Object> p : pts) missing.put(String.valueOf(p.get("id")), noFiles);
            return;
        }
        List<Map<String, Object>> pending = new ArrayList<Map<String, Object>>(pts);
        while (!pending.isEmpty()) {
            boolean progress = false;
            for (Map<String, Object> pt : new ArrayList<Map<String, Object>>(pending)) {
                String id = String.valueOf(pt.get("id"));
                try {
                    Object r = point(classes, pt);
                    found.put(id, r);
                    points.put(id, r instanceof String ? entry("class", r) : (Map<String, Object>) r);
                } catch (Miss m) {
                    if (m.later) continue;
                    missing.put(id, m.getMessage());
                }
                pending.remove(pt);
                progress = true;
            }
            if (!progress) {
                for (Map<String, Object> pt : pending) {
                    missing.put(String.valueOf(pt.get("id")), "needs " + (pt.get("of") != null ? pt.get("of") : "a point that is missing"));
                }
                break;
            }
        }
    }

    static Map<String, Object> entry(String k, Object v) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put(k, v);
        return m;
    }

    String resolve(String t) throws Miss {
        if (!t.startsWith("@")) return t;
        String base = t.substring(1), arr = "";
        while (base.endsWith("[]")) {
            base = base.substring(0, base.length() - 2);
            arr += "[]";
        }
        Object c = found.get(base);
        if (!(c instanceof String)) throw new Miss("needs " + base + " first", true);
        return c + arr;
    }

    List<String> resolveAll(Object list) throws Miss {
        List<String> out = new ArrayList<String>();
        if (list instanceof List) for (Object t : (List<?>) list) out.add(resolve(String.valueOf(t)));
        return out;
    }

    // ---- shapes (map-car.py: class_matches, field_ok, method_ok)

    @SuppressWarnings("unchecked")
    boolean classMatches(Dex.Cls c, Map<String, Object> f) throws Miss {
        if (f.containsKey("name") && !c.name.equals(f.get("name"))) return false;
        if (f.containsKey("package") && !c.name.startsWith(f.get("package") + ".")) return false;
        if (f.containsKey("super") && !String.valueOf(f.get("super")).equals(c.sup)) return false;
        for (Object ct : list(f.get("ctors"))) {
            List<String> want = resolveAll(ct);
            boolean any = false;
            for (Dex.Member m : c.methods) if (m.name.equals("<init>") && m.params.equals(want)) any = true;
            if (!any) return false;
        }
        for (Object fs : list(f.get("fields"))) {
            boolean any = false;
            for (Dex.Member x : c.fields) if (fieldOk(x, (Map<String, Object>) fs)) any = true;
            if (!any) return false;
        }
        for (Object ms : list(f.get("methods"))) {
            boolean any = false;
            for (Dex.Member x : c.methods) if (methodOk(x, (Map<String, Object>) ms)) any = true;
            if (!any) return false;
        }
        for (Object s : list(f.get("strings"))) if (!c.strings.contains(String.valueOf(s))) return false;
        return true;
    }

    static List<Object> list(Object o) {
        return o instanceof List ? (List<Object>) o : new ArrayList<Object>();
    }

    boolean fieldOk(Dex.Member x, Map<String, Object> fs) throws Miss {
        if (fs.containsKey("name") && !x.name.equals(fs.get("name"))) return false;
        if (fs.containsKey("type") && !x.type.equals(resolve(String.valueOf(fs.get("type"))))) return false;
        if (fs.containsKey("static") && x.isStatic != Boolean.TRUE.equals(fs.get("static"))) return false;
        return true;
    }

    boolean methodOk(Dex.Member x, Map<String, Object> ms) throws Miss {
        if (x.name.equals("<init>") || x.name.equals("<clinit>")) return false;
        if (ms.containsKey("name") && !x.name.equals(ms.get("name"))) return false;
        if (ms.containsKey("params") && !x.params.equals(resolveAll(ms.get("params")))) return false;
        if (ms.containsKey("ret") && !x.ret.equals(resolve(String.valueOf(ms.get("ret"))))) return false;
        if (ms.containsKey("static") && x.isStatic != Boolean.TRUE.equals(ms.get("static"))) return false;
        for (Object s : list(ms.get("strings"))) if (!x.strings.contains(String.valueOf(s))) return false;
        return true;
    }

    // ---- points (map-car.py: point)

    @SuppressWarnings("unchecked")
    Object point(Map<String, Dex.Cls> classes, Map<String, Object> pt) throws Miss {
        String id = String.valueOf(pt.get("id")), kind = String.valueOf(pt.get("kind"));
        Map<String, Object> f = (Map<String, Object>) pt.get("find");
        boolean smali = f.containsKey("pattern") || f.containsKey("code");
        if ("class".equals(kind)) {
            if (smali) return byReferenceClass(classes, id, f);
            List<String> hits = new ArrayList<String>();
            for (Dex.Cls c : classes.values()) if (classMatches(c, f)) hits.add(c.name);
            if (hits.size() != 1) throw new Miss(hits.size() + " classes match" + (hits.isEmpty() ? "" : " (" + first(hits) + ")"), false);
            return hits.get(0);
        }
        Object owner = found.get(String.valueOf(pt.get("of")));
        if (!(owner instanceof String)) throw new Miss("its class " + pt.get("of") + " isn't mapped", true);
        Dex.Cls c = classes.get(owner);
        if (c == null) throw new Miss("no class " + owner, false);
        if ("field".equals(kind)) {
            List<Dex.Member> hits = new ArrayList<Dex.Member>();
            for (Dex.Member x : c.fields) {
                if (fieldOk(x, f) && (!smali || referenced(id, "field", x.name))) hits.add(x);
            }
            if (smali) byRef(id, hits.size());
            if (hits.size() != 1) throw new Miss(hits.size() + " fields match in " + owner, false);
            Map<String, Object> e = new LinkedHashMap<String, Object>();
            e.put("field", hits.get(0).name);
            e.put("type", hits.get(0).type);
            e.put("static", hits.get(0).isStatic);
            return e;
        }
        if ("ctor".equals(kind)) {
            List<String> want = resolveAll(f.get("params"));
            for (Dex.Member m : c.methods) {
                if (m.name.equals("<init>") && m.params.equals(want)) return entry("ctor", new ArrayList<Object>(want));
            }
            throw new Miss("no such constructor in " + owner, false);
        }
        if ("method".equals(kind)) {
            List<Dex.Member> hits = new ArrayList<Dex.Member>();
            for (Dex.Member x : c.methods) {
                if (methodOk(x, f) && (!smali || referenced(id, "method", x.name))) hits.add(x);
            }
            if (smali) byRef(id, hits.size());
            if (hits.size() != 1) throw new Miss(hits.size() + " methods match in " + owner, false);
            Dex.Member h = hits.get(0);
            Map<String, Object> e = new LinkedHashMap<String, Object>();
            e.put("method", h.name);
            e.put("params", new ArrayList<Object>(h.params));
            e.put("ret", h.ret);
            e.put("static", h.isStatic);
            return e;
        }
        throw new Miss("unknown kind " + kind, false);
    }

    void byRef(String id, int hits) throws Miss {
        if (!references.containsKey(id)) throw new Miss("found by a smali pattern only, and no other car's map has it", false);
        if (hits == 1) byReference.add(id);
    }

    /** another car's map names this point so (smali-only points) */
    boolean referenced(String id, String key, String name) {
        List<Map<String, Object>> l = references.get(id);
        if (l != null) for (Map<String, Object> e : l) if (name.equals(e.get(key))) return true;
        return false;
    }

    String byReferenceClass(Map<String, Dex.Cls> classes, String id, Map<String, Object> f) throws Miss {
        List<Map<String, Object>> l = references.get(id);
        if (l == null) throw new Miss("found by a smali pattern only, and no other car's map has it", false);
        Set<String> hits = new LinkedHashSet<String>();
        for (Map<String, Object> e : l) {
            String n = String.valueOf(e.get("class"));
            if (classes.containsKey(n) && (!f.containsKey("package") || n.startsWith(f.get("package") + "."))) hits.add(n);
        }
        if (hits.size() != 1) throw new Miss("found by a smali pattern only; " + hits.size() + " of the other cars' names fit", false);
        byReference.add(id);
        return hits.iterator().next();
    }

    static String first(List<String> l) {
        java.util.Collections.sort(l);
        return l.size() > 3 ? l.subList(0, 3) + "..." : l.toString();
    }

    public Map<String, Object> result() {
        Map<String, Object> r = new LinkedHashMap<String, Object>();
        r.put("points", new TreeMap<String, Object>(points));
        r.put("missing", new TreeMap<String, Object>(missing));
        r.put("by_reference", new ArrayList<Object>(byReference));
        return r;
    }

    /**
     * engine map-car [--write]: maps this car from its own files and keeps the result
     * (state/map-probe.json, for the Car Report). --write also saves it as this car's
     * map (status "probed") when HMG-API has none for it; like any unchecked map, its
     * hooks run after `engine verify-map` passes or with hmgapi.json testing_maps.
     */
    static int run(Engine e, boolean write) throws Exception {
        File maps = new File(e.root + "/data/hmgapi/maps");
        MapProbe p = new MapProbe(e.root + "/system", e.root + "/data/hmgapi/stock", maps);
        p.run(new File(maps, "catalogue"));
        Map<String, Object> r = p.result();
        r.put("product", e.facts.get("product"));
        r.put("files", new TreeMap<String, Object>(p.files));
        r.put("date", new java.util.Date().toString());
        Engine.write(new File(e.stateDir, "map-probe.json"), Json.write(r));
        e.out.add("map-car: " + p.points.size() + " hook point(s) found on this car (" + p.byReference.size()
                + " by other cars' names), " + p.missing.size() + " not found (state/map-probe.json)");
        int shown = 0;
        for (Map.Entry<String, String> m : p.missing.entrySet()) {
            if (m.getValue().startsWith("no stock ")) continue;   // apps this car doesn't have
            if (shown++ < 20) e.out.add("  not found: " + m.getKey() + " (" + m.getValue() + ")");
        }
        if (!write) return 0;
        CarMap now = CarMap.load(e.root, e.facts, e.hmgapi);
        if (now.folder != null && !"probed".equals(String.valueOf(now.carJson.get("status")))) {
            e.out.add("not written: HMG-API has a map for this car (" + now.folder + ")");
            return 0;
        }
        String model = e.facts.get("model"), region = e.facts.get("region"), fw = e.facts.get("firmware");
        if (model == null || region == null || fw == null) {
            e.out.add("not written: this unit's product (" + e.facts.get("product") + ") doesn't say its model, region and firmware");
            return 5;
        }
        String make = e.facts.get("make") != null ? e.facts.get("make") : "unknown";
        File d = new File(maps, make.toLowerCase() + "/" + model.toLowerCase() + "/" + region.toLowerCase() + "/" + fw);
        d.mkdirs();
        Map<String, Map<String, Object>> byApp = new TreeMap<String, Map<String, Object>>();
        for (String app : p.appFiles.keySet()) byApp.put(app, new TreeMap<String, Object>());
        for (Map.Entry<String, Map<String, Object>> en : p.points.entrySet()) {
            byApp.get(CarMap.appOfId(en.getKey())).put(en.getKey(), en.getValue());
        }
        for (Map.Entry<String, Map<String, Object>> en : byApp.entrySet()) {
            Engine.write(new File(d, en.getKey() + ".json"), Json.write(en.getValue()));
        }
        Map<String, Object> car = new LinkedHashMap<String, Object>();
        car.put("make", make.toLowerCase());
        car.put("model", model.toLowerCase());
        car.put("name", e.facts.get("name") != null ? e.facts.get("name") : model);
        car.put("region", region.toLowerCase());
        car.put("firmware", fw);
        List<Object> products = new ArrayList<Object>();
        products.add(model + "." + region + ".");
        car.put("products", products);
        car.put("status", "probed");
        car.put("verified", null);
        car.put("files", new TreeMap<String, Object>(p.files));
        car.put("apps", new ArrayList<Object>(byApp.keySet()));
        car.put("app_files", p.appFiles);
        car.put("missing", new TreeMap<String, Object>(p.missing));
        car.put("by_reference", new ArrayList<Object>(p.byReference));
        Engine.write(new File(d, "car.json"), Json.write(car));
        e.out.add("written: " + CarMap.rel(maps, d) + " (status probed): run `hmgapid engine verify-map`, then restart, for its hooks");
        return 0;
    }

    public static void main(String[] a) throws Exception {
        MapProbe p = new MapProbe(new File(a[0]).getAbsolutePath(), null, a.length > 2 ? new File(a[2]) : null);
        p.run(new File(a[1]));
        System.out.println(Json.write(p.result()));
    }
}
