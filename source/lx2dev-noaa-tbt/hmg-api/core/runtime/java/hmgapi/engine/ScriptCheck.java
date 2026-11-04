package hmgapi.engine;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Does a shell script only call commands the head unit has? These units run mksh
 * with Android 4.4's toolbox: no sed, tr, head, tail, wc, awk, cut, sort, find,
 * xargs, expr, basename or printf (grep, cat, md5, date and the shell's own test,
 * [[, read, ${var%x} are there). A mod's script that calls a missing one fails on
 * the car, often quietly. Checked at install (a warning per script and command) and
 * off the car on every mod in the repo (tools/check-scripts, unit-commands.txt).
 *
 * The parse is a shell-word sketch, not a shell: the first word of each command
 * (after ; | & && || newlines, $( and backticks, then, do, else ...), skipping
 * assignments, redirections, words with $ or quotes, case patterns and here-documents.
 *
 *   java hmgapi.engine.ScriptCheck <unit-commands.txt> <script|mod dir>...
 */
public final class ScriptCheck {
    private ScriptCheck() {}

    /** mksh's builtins and keywords (and HMG-API's own client, on every unit with HMG-API) */
    static final Set<String> SHELL = new HashSet<String>(Arrays.asList(
            "echo", "print", "read", "test", "[", "[[", "]]", "cd", "pwd", "export", "local", "typeset", "set", "unset",
            "shift", "exit", "return", "break", "continue", "eval", "exec", "trap", "wait", "true", "false", "let",
            "alias", "unalias", "ulimit", "umask", "getopts", "command", "type", "whence", ":", ".", "times", "jobs",
            "fg", "bg", "kill", "readonly", "builtin", "if", "then", "else", "elif", "fi", "for", "in", "do", "done",
            "while", "until", "case", "esac", "function", "select", "time", "{", "}", "!", "hmgapid"));

    /** what to use instead, for the usual ones */
    static final String[][] HINTS = {
        { "sed", "${var#x} ${var%x} ${var/a/b} or a case" },
        { "tr", "${var//a/b}, or an unquoted $var to turn newlines into spaces" },
        { "head", "read the first lines in a while read loop" },
        { "tail", "keep the last line in a while read loop" },
        { "wc", "count in a while read loop with let" },
        { "awk", "while read a b c; do ...; done" },
        { "cut", "while read a b c (IFS for other separators), or ${var%%x}" },
        { "printf", "print (mksh) or echo" },
        { "expr", "$((...)) arithmetic" },
        { "basename", "${path##*/}" },
        { "dirname", "${path%/*}" },
        { "seq", "a while loop with let" },
        { "sort", "do it in your app or service code" },
        { "find", "for f in dir/*; or ls" },
        { "xargs", "a for or while read loop" },
        { "md5sum", "md5" },
        { "busybox", "the unit has none" },
    };

    /** The commands a script calls that the unit doesn't have, each once, with a hint: "sed (use ...)". */
    public static List<String> missing(String script, Set<String> unit) {
        return missing(script, unit, functions(script));
    }

    /** functions: ones the script (or a script it sources) defines */
    public static List<String> missing(String script, Set<String> unit, Set<String> functions) {
        Set<String> out = new LinkedHashSet<String>();
        String heredoc = null;
        for (String raw : script.split("\n")) {
            String line = raw;
            if (heredoc != null) {
                if (line.trim().equals(heredoc)) heredoc = null;
                continue;
            }
            int h = line.indexOf("<<");
            if (h >= 0 && line.charAt(Math.min(h + 2, line.length() - 1)) != '<') {
                String tag = line.substring(h + 2).replaceAll("^[-~]?\\s*['\"]?", "").replaceAll("['\"\\s].*$", "");
                if (!tag.isEmpty()) heredoc = tag;
            }
            line = line.replaceAll("\\$\\(\\([^)]*\\)\\)", "0");   // $((arithmetic))
            for (String seg : commands(stripComment(line))) {
                String cmd = first(seg);
                if (cmd == null || SHELL.contains(cmd) || unit.contains(cmd) || functions.contains(cmd)) continue;
                if (cmd.startsWith("/system/") && unit.contains(cmd.substring(cmd.lastIndexOf('/') + 1))) continue;
                if (cmd.startsWith("/") || cmd.startsWith(".")) continue;   // a file the mod ships, or a path
                out.add(cmd + hint(cmd));
            }
        }
        return new ArrayList<String>(out);
    }

    /** a line's simple commands: split at ; | & && || outside quotes; $( and ` start one, even inside "..." */
    static List<String> commands(String line) {
        List<String> out = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        boolean sq = false, dq = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i), n = i + 1 < line.length() ? line.charAt(i + 1) : 0;
            char p = i > 0 ? line.charAt(i - 1) : 0;
            if (c == '\\' && !sq) {   // an escaped character: part of the word
                cur.append(c).append(n);
                i++;
                continue;
            }
            if (c == '\'' && !dq) sq = !sq;
            else if (c == '"' && !sq) dq = !dq;
            boolean starts = !sq && ((c == '$' && n == '(' && line.charAt(Math.min(i + 2, line.length() - 1)) != '(') || c == '`');
            boolean sep = !sq && !dq && (c == ';' || c == '|' || (c == '&' && p != '>' && p != '<' && n != '>'));
            if (starts || sep) {
                out.add(cur.toString());
                cur.setLength(0);
                if (starts && c == '$') i++;
                if (starts) dq = false;   // what follows is a command, whatever quote it sits in
                continue;
            }
            cur.append(c);
        }
        out.add(cur.toString());
        return out;
    }

    /** a command's words, a quoted part (spaces and all) staying in its word */
    static List<String> words(String seg) {
        List<String> out = new ArrayList<String>();
        StringBuilder w = new StringBuilder();
        char q = 0;
        for (int i = 0; i < seg.length(); i++) {
            char c = seg.charAt(i);
            if (q != 0) {
                if (c == q) q = 0;
                w.append(c);
            } else if (c == '\'' || c == '"') {
                q = c;
                w.append(c);
            } else if (Character.isWhitespace(c)) {
                if (w.length() > 0) out.add(w.toString());
                w.setLength(0);
            } else {
                w.append(c);
            }
        }
        if (w.length() > 0) out.add(w.toString());
        return out;
    }

    /** names defined as shell functions: name() { or function name */
    public static Set<String> functions(String script) {
        Set<String> f = new HashSet<String>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?m)^\\s*(?:function\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*\\(\\s*\\)").matcher(script);
        while (m.find()) f.add(m.group(1));
        m = java.util.regex.Pattern.compile("(?m)^\\s*function\\s+([A-Za-z_][A-Za-z0-9_]*)").matcher(script);
        while (m.find()) f.add(m.group(1));
        return f;
    }

    static String hint(String cmd) {
        for (String[] h : HINTS) if (h[0].equals(cmd)) return " (not on the head unit; use " + h[1] + ")";
        return " (not on the head unit)";
    }

    static String stripComment(String l) {
        boolean sq = false, dq = false;
        for (int i = 0; i < l.length(); i++) {
            char c = l.charAt(i);
            if (c == '\'' && !dq) sq = !sq;
            else if (c == '"' && !sq) dq = !dq;
            else if (c == '#' && !sq && !dq && (i == 0 || Character.isWhitespace(l.charAt(i - 1)))) return l.substring(0, i);
        }
        return l;
    }

    /** the command word of a simple command, or null */
    static String first(String seg) {
        for (String w : words(seg)) {
            if (w.isEmpty() || w.equals("(") || w.equals("((")) continue;
            if (w.matches("[A-Za-z_][A-Za-z0-9_]*=.*")) continue;           // VAR=value before a command
            if (w.matches("[0-9]*[<>].*") || w.equals("2>&1")) return null;   // redirection: what follows isn't a command
            if (w.equals("then") || w.equals("do") || w.equals("else") || w.equals("elif") || w.equals("if")
                    || w.equals("while") || w.equals("until") || w.equals("!") || w.equals("time") || w.equals("{")) continue;
            if (w.endsWith(")") || w.contains("$") || w.contains("\"") || w.contains("'") || w.contains("*")) return null;
            if (!w.matches("[A-Za-z0-9_./+-]+")) return null;
            return w;
        }
        return null;
    }

    /** The commands this unit has: /system/bin and /system/xbin (root "" on the unit) */
    public static Set<String> unit(String root) {
        Set<String> s = new HashSet<String>();
        for (String d : new String[] { "/system/bin", "/system/xbin" }) {
            String[] n = new File(root + d).list();
            if (n != null) s.addAll(Arrays.asList(n));
        }
        return s;
    }

    /** A list file: one command per line, # comments */
    public static Set<String> list(File f) throws Exception {
        Set<String> s = new HashSet<String>();
        for (String l : Engine.read(f).split("\n")) if (!l.trim().isEmpty() && !l.startsWith("#")) s.add(l.trim());
        return s;
    }

    /** a mod's scripts that run on the unit: every *.sh but its build/test tooling (as tools/mkmodpkg.sh packs it) */
    public static List<File> scripts(File modDir) {
        List<File> out = new ArrayList<File>();
        walk(modDir, modDir, out);
        return out;
    }

    static void walk(File root, File d, List<File> out) {
        File[] fs = d.listFiles();
        if (fs == null) return;
        Arrays.sort(fs);
        for (File f : fs) {
            String rel = f.getPath().substring(root.getPath().length() + 1);
            if (rel.equals("build.sh") || rel.equals("test.sh") || rel.startsWith("tools") || rel.startsWith("native")
                    || rel.startsWith("src") || rel.startsWith("app")) continue;
            if (f.isDirectory()) walk(root, f, out);
            else if (f.getName().endsWith(".sh")) out.add(f);
        }
    }

    /** the functions every script in a set defines (scripts source each other: install.sh, lib.sh) */
    public static Set<String> functions(List<File> scripts) throws Exception {
        Set<String> f = new HashSet<String>();
        for (File s : scripts) f.addAll(functions(Engine.read(s)));
        return f;
    }

    public static void main(String[] a) throws Exception {
        Set<String> unit = list(new File(a[0]));
        int bad = 0;
        for (int i = 1; i < a.length; i++) {
            File f = new File(a[i]);
            List<File> files = f.isDirectory() ? scripts(f) : Arrays.asList(f);
            List<File> set = new ArrayList<File>(files);
            if (!f.isDirectory()) {   // a single script: its folder's scripts may define what it calls
                File[] sib = f.getAbsoluteFile().getParentFile().listFiles();
                for (File x : sib == null ? new File[0] : sib) if (x.getName().endsWith(".sh")) set.add(x);
            }
            Set<String> fn = functions(set);
            for (File s : files) {
                List<String> m = missing(Engine.read(s), unit, fn);
                for (String x : m) System.out.println("FAIL  " + s.getPath() + ": " + x);
                if (m.isEmpty()) System.out.println("ok    " + s.getPath());
                bad += m.size();
            }
        }
        System.exit(bad == 0 ? 0 : 1);
    }
}
