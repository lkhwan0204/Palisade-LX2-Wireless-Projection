package hmgapi.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Mod versions (semver: major.minor.patch, missing parts are 0, a "-tag" sorts
 * before the plain release) and version ranges for "hmgapi", "requires" and
 * "conflicts" in mod.json:
 *
 *   ">=2.0.0"   ">1.2"   "<=3"   "<3"   "=1.4.2"   "1.4.2" (same as =)
 *   "^1.2.3"    same major, at least 1.2.3   (^0.x: same minor)
 *   "~1.2.3"    same minor, at least 1.2.3
 *   ">=2.1 <3"  every part must hold          "" or "*": any version
 *
 * A dependency is "id", "id <range>", or {"id": ..., "version": <range>}.
 */
public final class Version implements Comparable<Version> {
    final int[] n = new int[3];
    final String tag;

    public Version(String s) {
        s = s.trim();
        if (s.startsWith("v")) s = s.substring(1);
        int dash = s.indexOf('-');
        tag = dash >= 0 ? s.substring(dash + 1) : "";
        String[] p = (dash >= 0 ? s.substring(0, dash) : s).split("\\.");
        for (int i = 0; i < 3 && i < p.length; i++) {
            try {
                n[i] = Integer.parseInt(p[i].trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("not a version: " + s);
            }
        }
    }

    @Override
    public int compareTo(Version o) {
        for (int i = 0; i < 3; i++) if (n[i] != o.n[i]) return n[i] < o.n[i] ? -1 : 1;
        if (tag.equals(o.tag)) return 0;
        if (tag.isEmpty()) return 1;      // 2.0.0 > 2.0.0-beta
        if (o.tag.isEmpty()) return -1;
        return tag.compareTo(o.tag);
    }

    @Override
    public String toString() {
        return n[0] + "." + n[1] + "." + n[2] + (tag.isEmpty() ? "" : "-" + tag);
    }

    /** Does version v satisfy the range? */
    public static boolean satisfies(String v, String range) {
        if (range == null) return true;
        range = range.trim();
        if (range.isEmpty() || "*".equals(range)) return true;
        Version have = new Version(v);
        for (String part : range.split("\\s+")) {
            if (!one(have, part)) return false;
        }
        return true;
    }

    private static boolean one(Version have, String c) {
        String op;
        if (c.startsWith(">=") || c.startsWith("<=")) op = c.substring(0, 2);
        else if (c.startsWith(">") || c.startsWith("<") || c.startsWith("=") || c.startsWith("^") || c.startsWith("~")) op = c.substring(0, 1);
        else op = "=";
        Version want = new Version(c.substring("=".equals(op) && !c.startsWith("=") ? 0 : op.length()));
        int cmp = have.compareTo(want);
        if (">=".equals(op)) return cmp >= 0;
        if ("<=".equals(op)) return cmp <= 0;
        if (">".equals(op)) return cmp > 0;
        if ("<".equals(op)) return cmp < 0;
        if ("=".equals(op)) return cmp == 0;
        if (cmp < 0) return false;
        if ("^".equals(op)) return want.n[0] == 0 ? have.n[0] == 0 && have.n[1] == want.n[1] : have.n[0] == want.n[0];
        return have.n[0] == want.n[0] && have.n[1] == want.n[1];   // ~
    }

    /** A dependency entry: {id, range}. */
    public static final class Dep {
        public final String id, range;

        Dep(String id, String range) {
            this.id = id;
            this.range = range;
        }

        @Override
        public String toString() {
            return range == null || range.isEmpty() ? id : id + " " + range;
        }
    }

    @SuppressWarnings("unchecked")
    public static List<Dep> deps(Object list) {
        List<Dep> out = new ArrayList<Dep>();
        if (!(list instanceof List)) return out;
        for (Object o : (List<Object>) list) {
            if (o instanceof Map) {
                Map<String, Object> m = (Map<String, Object>) o;
                out.add(new Dep(String.valueOf(m.get("id")), m.get("version") == null ? null : String.valueOf(m.get("version"))));
            } else {
                String s = String.valueOf(o).trim();
                int sp = s.indexOf(' ');
                out.add(sp < 0 ? new Dep(s, null) : new Dep(s.substring(0, sp), s.substring(sp + 1).trim()));
            }
        }
        return out;
    }
}
