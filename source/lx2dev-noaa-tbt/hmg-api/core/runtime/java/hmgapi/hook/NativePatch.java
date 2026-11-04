package hmgapi.hook;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import hmgapi.engine.MemRecipe;

/**
 * Native patches in memory (ModContext.nativePatch): a mod's recipes for a library are
 * put on it in this process once it's loaded (now, or right after the Runtime.load /
 * loadLibrary that loads it, its dependencies included), never on the file.
 *
 * Mixing: each recipe owns its verify regions in the library; a recipe overlapping
 * another mod's on the same library isn't applied, and the log names both. The first
 * of a mod's recipes that fits the library (its stock bytes) is the one used, so one
 * mod can carry a recipe per build of a library.
 */
final class NativePatch {
    private NativePatch() {}

    static final class Want {
        final ModContext mod;
        final String lib;
        final List<MemRecipe> recipes;
        boolean done;

        Want(ModContext mod, String lib, List<MemRecipe> recipes) {
            this.mod = mod;
            this.lib = lib;
            this.recipes = recipes;
        }
    }

    static final class Owned {
        final String mod, recipe;
        final List<long[]> regions;

        Owned(String mod, String recipe, List<long[]> regions) {
            this.mod = mod;
            this.recipe = recipe;
            this.regions = regions;
        }
    }

    static final List<Want> wants = new ArrayList<Want>();
    /** library path -> recipes applied to it in this process */
    static final Map<String, List<Owned>> owned = new HashMap<String, List<Owned>>();
    static boolean hooked;

    static final MemRecipe.Memory MEMORY = new MemRecipe.Memory() {
        @Override
        public byte[] peek(long addr, int len) {
            return Bridge.peekNative(addr, len);
        }

        @Override
        public boolean poke(long addr, byte[] data) {
            return Bridge.pokeNative(addr, data) == 0;
        }

        @Override
        public long mapCode(long hint, int len) {
            return Bridge.mapCodeNative(hint, len);
        }
    };

    static synchronized void request(ModContext mod, String lib, List<MemRecipe> recipes) {
        hookLoads();
        Want w = new Want(mod, lib, recipes);
        wants.add(w);
        tryAll();
    }

    /** after every library load in this process, the patches still waiting are tried */
    private static void hookLoads() {
        if (hooked) return;
        hooked = true;
        MethodHook after = new MethodHook() {
            @Override
            protected void afterHookedMethod(Param p) {
                if (p.getThrowable() == null) tryAll();
            }
        };
        if (!Points.needAll("native patches", "framework.runtime.load_library", "framework.runtime.load")) return;
        Points.hook(null, "framework.runtime.load_library", after);
        Points.hook(null, "framework.runtime.load", after);
    }

    static synchronized void tryAll() {
        Map<String, long[]> maps = null;
        for (Want w : wants) {
            if (w.done) continue;
            if (maps == null) maps = maps();
            long[] m = null;
            String path = null;
            for (Map.Entry<String, long[]> e : maps.entrySet()) {
                if (e.getKey().endsWith("/" + w.lib)) {
                    path = e.getKey();
                    m = e.getValue();
                }
            }
            if (m == null) continue;
            w.done = true;
            apply(w, path, m[0], m[1]);
        }
    }

    private static void apply(Want w, String path, long base, long textEnd) {
        String md5 = ModContext.md5(path);
        List<Owned> here = owned.get(path);
        if (here == null) owned.put(path, here = new ArrayList<Owned>());
        StringBuilder tried = new StringBuilder();
        for (MemRecipe r : w.recipes) {
            Owned clash = overlap(here, r.regions());
            if (clash != null) {
                tried.append("; ").append(r.id).append(": conflict with ").append(clash.mod).append("'s ").append(clash.recipe);
                continue;
            }
            MemRecipe.Result res;
            try {
                res = r.apply(MEMORY, base, textEnd, md5);
            } catch (Throwable t) {
                w.mod.error("native patch " + r.id + " on " + w.lib, t);
                return;
            }
            if (res.ok()) {
                here.add(new Owned(w.mod.id, r.id, r.regions()));
                w.mod.log("native patch " + w.lib + ": " + res.message);
                return;
            }
            tried.append("; ").append(res.message);
            if (res.status == MemRecipe.Result.FAILED) break;   // something was written: no other recipe on top
        }
        w.mod.error("native patch " + w.lib + " not applied" + tried, null);
    }

    static Owned overlap(List<Owned> here, List<long[]> regions) {
        for (Owned o : here) {
            for (long[] a : o.regions) {
                for (long[] b : regions) {
                    if (a[0] < b[1] && b[0] < a[1]) return o;
                }
            }
        }
        return null;
    }

    /** loaded libraries: path -> {address of file offset 0 (its code mapping), the code mapping's length} */
    static Map<String, long[]> maps() {
        Map<String, long[]> r = new LinkedHashMap<String, long[]>();
        try {
            BufferedReader in = new BufferedReader(new FileReader("/proc/self/maps"));
            try {
                String l;
                while ((l = in.readLine()) != null) {
                    // 4011c000-402e2000 r-xp 00000000 b3:02 1234   /system/lib/libX.so
                    String[] w = l.trim().split("\\s+");
                    if (w.length < 6 || !w[5].endsWith(".so") || w[1].indexOf('x') < 0 || Long.parseLong(w[2], 16) != 0) continue;
                    int dash = w[0].indexOf('-');
                    long start = Long.parseLong(w[0].substring(0, dash), 16), end = Long.parseLong(w[0].substring(dash + 1), 16);
                    if (!r.containsKey(w[5])) r.put(w[5], new long[] { start, end - start });
                }
            } finally {
                in.close();
            }
        } catch (Exception e) {
            HmgApi.i("native patch: can't read /proc/self/maps (" + e + ")");
        }
        return r;
    }

    /** a mod's recipe files (paths in its folder) */
    static List<MemRecipe> read(ModContext mod, List<String> files) throws Exception {
        List<MemRecipe> r = new ArrayList<MemRecipe>();
        for (String f : files) r.add(MemRecipe.parse(Mods.read(new File(mod.dir, f))));
        return r;
    }
}
