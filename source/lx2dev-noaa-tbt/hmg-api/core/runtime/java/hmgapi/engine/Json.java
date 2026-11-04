package hmgapi.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader/writer for the engine, so it runs on a desktop JVM for
 * tests as well as on the head unit (no org.json, no Android APIs).
 * Objects are LinkedHashMap<String, Object>, arrays ArrayList<Object>,
 * numbers Long or Double, plus String, Boolean and null.
 */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    public static Object parse(String text) {
        Json j = new Json(text);
        j.ws();
        Object v = j.value();
        j.ws();
        if (j.i != j.s.length()) throw j.err("trailing text");
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(String text) {
        Object o = parse(text);
        if (!(o instanceof Map)) throw new IllegalArgumentException("not a JSON object");
        return (Map<String, Object>) o;
    }

    private IllegalArgumentException err(String what) {
        return new IllegalArgumentException("JSON: " + what + " at offset " + i);
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private Object value() {
        if (i >= s.length()) throw err("unexpected end");
        char c = s.charAt(i);
        if (c == '{') return obj();
        if (c == '[') return arr();
        if (c == '"') return str();
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        if (s.startsWith("null", i)) { i += 4; return null; }
        return num();
    }

    private Map<String, Object> obj() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        i++;
        ws();
        if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
        for (;;) {
            ws();
            if (i >= s.length() || s.charAt(i) != '"') throw err("expected a key");
            String k = str();
            ws();
            if (i >= s.length() || s.charAt(i) != ':') throw err("expected ':'");
            i++;
            ws();
            m.put(k, value());
            ws();
            if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            throw err("expected ',' or '}'");
        }
    }

    private List<Object> arr() {
        List<Object> a = new ArrayList<Object>();
        i++;
        ws();
        if (i < s.length() && s.charAt(i) == ']') { i++; return a; }
        for (;;) {
            ws();
            a.add(value());
            ws();
            if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
            if (i < s.length() && s.charAt(i) == ']') { i++; return a; }
            throw err("expected ',' or ']'");
        }
    }

    private String str() {
        StringBuilder b = new StringBuilder();
        i++;
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c != '\\') { b.append(c); continue; }
            if (i >= s.length()) break;
            char e = s.charAt(i++);
            switch (e) {
                case 'n': b.append('\n'); break;
                case 't': b.append('\t'); break;
                case 'r': b.append('\r'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'u':
                    if (i + 4 > s.length()) throw err("bad \\u");
                    b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                    break;
                default: b.append(e);
            }
        }
        throw err("unterminated string");
    }

    private Object num() {
        int st = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        String t = s.substring(st, i);
        if (t.isEmpty()) throw err("unexpected character '" + s.charAt(st) + "'");
        if (t.indexOf('.') >= 0 || t.indexOf('e') >= 0 || t.indexOf('E') >= 0) return Double.valueOf(t);
        return Long.valueOf(t);
    }

    // ---- writing ----

    public static String write(Object v) {
        StringBuilder b = new StringBuilder();
        write(b, v, 0);
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder b, Object v, int ind) {
        if (v == null) b.append("null");
        else if (v instanceof String) quote(b, (String) v);
        else if (v instanceof Boolean || v instanceof Number) b.append(v);
        else if (v instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) v;
            if (m.isEmpty()) { b.append("{}"); return; }
            b.append("{\n");
            int n = 0;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                pad(b, ind + 2);
                quote(b, e.getKey());
                b.append(": ");
                write(b, e.getValue(), ind + 2);
                if (++n < m.size()) b.append(',');
                b.append('\n');
            }
            pad(b, ind);
            b.append('}');
        } else if (v instanceof List) {
            List<Object> a = (List<Object>) v;
            if (a.isEmpty()) { b.append("[]"); return; }
            b.append("[\n");
            for (int k = 0; k < a.size(); k++) {
                pad(b, ind + 2);
                write(b, a.get(k), ind + 2);
                if (k + 1 < a.size()) b.append(',');
                b.append('\n');
            }
            pad(b, ind);
            b.append(']');
        } else quote(b, String.valueOf(v));
    }

    private static void pad(StringBuilder b, int n) { for (int k = 0; k < n; k++) b.append(' '); }

    private static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\t': b.append("\\t"); break;
                case '\r': b.append("\\r"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        b.append('"');
    }
}
