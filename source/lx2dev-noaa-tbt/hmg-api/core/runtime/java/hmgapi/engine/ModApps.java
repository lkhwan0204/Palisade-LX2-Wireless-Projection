package hmgapi.engine;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * engine apps: the mods' own APKs (mod.json "apps") brought in line with the mods.
 *
 *   enabled mod   installed (or updated when its APK changed), turned on again if HMG-API turned it off
 *   mod off       pm disable: hidden, its data kept
 *   mod removed   pm uninstall
 *
 * An app that was installed some other way (by hand, an older NEMESIS package) is
 * left alone and reported, unless hmgapi.json overrides.replace_apps lists its
 * package: then the mod takes it over (pm install -r keeps its data).
 *
 * Needs Android up; hmgapid runs this once Android is up and whenever the list
 * changes (state/apps.txt). pm only takes these signatures while sys.boot_completed
 * is 0 (OEM allowlist), so it's set for each pm install only, as for HMG-API's app.
 * Record: state/apps.json {"<package>": {"mod", "md5", "off"}}, and state/apps.installed
 * (one package per line, for HMG-API's uninstaller, which turns them off and leaves
 * state/apps.alloff, so a reinstall turns them on again).
 */
final class ModApps {
    private ModApps() {}

    @SuppressWarnings("unchecked")
    static int sync(Engine e) throws Exception {
        if (!e.host && !"1".equals(e.getprop("sys.boot_completed")) || e.host && "1".equals(Engine.env("HMGAPI_ANDROID_DOWN", ""))) {
            e.out.add("apps: waiting for Android (hmgapid runs this again once it's up)");
            return 0;
        }
        File lock = new File(e.stateDir, "apps.lock"), again = new File(e.stateDir, "apps.again");
        if (!lockFree(e, lock)) {
            again.createNewFile();   // the running one goes round once more
            e.out.add("apps: already running; it will pick this up");
            return 0;
        }
        Engine.write(lock, pid());
        try {
            do {
                again.delete();
                once(e);
            } while (again.exists());
        } finally {
            lock.delete();
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    static void once(Engine e) throws Exception {
        Set<String> on = new LinkedHashSet<String>();
        Map<String, Map<String, Object>> json = LiveState.mods(e, on);
        File recFile = new File(e.stateDir, "apps.json");
        Map<String, Object> rec = recFile.isFile() ? Json.object(Engine.read(recFile)) : new LinkedHashMap<String, Object>();
        Set<String> installed = installed(e);
        File allOff = new File(e.stateDir, "apps.alloff");   // HMG-API's uninstall turned them all off
        if (allOff.exists()) {
            for (Object r : rec.values()) ((Map<String, Object>) r).put("off", true);
            allOff.delete();
        }
        Set<String> wanted = new LinkedHashSet<String>();
        for (Map.Entry<String, Map<String, Object>> m : json.entrySet()) {
            File dir = new File(e.data, "mods/" + m.getKey());
            for (ModRuntime.App a : ModRuntime.of(m.getKey(), m.getValue(), dir).apps) {
                if (!wanted.add(a.pkg)) continue;   // two mods, one app: the first (LiveState reports it)
                Map<String, Object> r = (Map<String, Object>) rec.get(a.pkg);
                boolean have = installed.contains(a.pkg);
                if (!on.contains(a.mod)) {
                    if (r != null && have && !Boolean.TRUE.equals(r.get("off"))) {
                        if (pm(e, "disable", a.pkg)) {
                            r.put("off", true);
                            e.out.add("apps: " + a.pkg + " turned off (" + a.mod + " is off; its data stays)");
                        }
                    }
                    continue;
                }
                if (have && r == null && !replaceAllowed(e, a.pkg)) {
                    e.out.add("apps: " + a.pkg + " (" + a.mod + ") is already installed some other way: left alone"
                            + " (hmgapi.json overrides.replace_apps can list it to let the mod take it over; its data stays)");
                    continue;
                }
                String md5 = Engine.md5(new File(dir, a.apk));
                if (!have || r == null || !md5.equals(r.get("md5"))) {
                    String why = install(e, new File(dir, a.apk), a.pkg);
                    if (why != null) {
                        e.out.add("apps: WARNING: " + a.pkg + " (" + a.mod + ") didn't install: " + why);
                        continue;
                    }
                    r = new LinkedHashMap<String, Object>();
                    r.put("mod", a.mod);
                    r.put("md5", md5);
                    rec.put(a.pkg, r);
                    e.out.add("apps: " + a.pkg + " " + (have ? "updated" : "installed") + " (" + a.mod + ")");
                }
                if (Boolean.TRUE.equals(r.get("off")) && pm(e, "enable", a.pkg)) {
                    r.remove("off");
                    e.out.add("apps: " + a.pkg + " turned on (" + a.mod + ")");
                }
            }
        }
        for (String pkg : new LinkedHashSet<String>(rec.keySet())) {
            if (wanted.contains(pkg)) continue;
            if (!installed.contains(pkg) || pm(e, "uninstall", pkg)) {
                e.out.add("apps: " + pkg + " removed (its mod no longer has it)");
                rec.remove(pkg);
            }
        }
        Engine.write(recFile, Json.write(rec));
        StringBuilder b = new StringBuilder();
        for (String p : rec.keySet()) b.append(p).append('\n');
        Engine.write(new File(e.stateDir, "apps.installed"), b.toString());
    }

    /** null when installed, else why not */
    static String install(Engine e, File apk, String pkg) throws Exception {
        if (e.host) {
            e.hostAction("pm install -r " + apk.getName() + " " + pkg);
            if ("1".equals(Engine.env("HMGAPI_PM_FAIL", ""))) return "Failure [TEST]";
            File pl = packagesList(e);
            Engine.write(pl, (pl.isFile() ? Engine.read(pl) : "") + pkg + " 10" + (100 + Math.abs(pkg.hashCode() % 800)) + " 0 /data/data/" + pkg + " default none\n");
            return null;
        }
        // the package manager reads it as system: a copy it may read
        File pub = new File(e.data, "public/apps");
        pub.mkdirs();
        LiveState.traversable(e.data);
        new File(e.data, "public").setExecutable(true, false);
        pub.setExecutable(true, false);
        File copy = new File(pub, pkg + ".apk");
        Engine.copy(apk, copy);
        copy.setReadable(true, false);
        try {
            Engine.exec("/system/bin/setprop", "sys.boot_completed", "0");
            Engine.Run r;
            try {
                r = Engine.exec("/system/bin/pm", "install", "-r", copy.getPath());
            } finally {
                Engine.exec("/system/bin/setprop", "sys.boot_completed", "1");
            }
            return r.text.contains("Success") ? null : r.text.trim();
        } finally {
            copy.delete();
        }
    }

    static boolean pm(Engine e, String verb, String pkg) throws Exception {
        if (e.host) {
            e.hostAction("pm " + verb + " " + pkg);
            if ("uninstall".equals(verb)) {
                File pl = packagesList(e);
                StringBuilder b = new StringBuilder();
                for (String l : Engine.read(pl).split("\n")) if (!l.isEmpty() && !l.startsWith(pkg + " ")) b.append(l).append('\n');
                Engine.write(pl, b.toString());
            }
            return true;
        }
        Engine.Run r = Engine.exec("/system/bin/pm", verb, pkg);
        boolean ok = r.code == 0 && !r.text.contains("Failure") && !r.text.contains("Error");
        if (!ok) e.out.add("apps: pm " + verb + " " + pkg + ": " + r.text.trim());
        return ok;
    }

    static File packagesList(Engine e) {
        return new File(e.root + "/data/system/packages.list");
    }

    static Set<String> installed(Engine e) throws Exception {
        Set<String> out = new LinkedHashSet<String>();
        File pl = packagesList(e);
        if (!pl.isFile()) return out;
        for (String l : Engine.read(pl).split("\n")) {
            int sp = l.indexOf(' ');
            if (sp > 0) out.add(l.substring(0, sp));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static boolean replaceAllowed(Engine e, String pkg) {
        Object o = e.hmgapi.get("overrides");
        Object l = o instanceof Map ? ((Map<String, Object>) o).get("replace_apps") : null;
        return l instanceof java.util.List && ((java.util.List<Object>) l).contains(pkg);
    }

    /** a stale lock after a restart may name any process: it counts only if that's the engine */
    static boolean lockFree(Engine e, File lock) {
        try {
            if (!lock.isFile()) return true;
            String p = Engine.read(lock).trim();
            if (e.host) return p.isEmpty() || !new File("/proc/" + p).exists();
            File cmd = new File("/proc/" + p + "/cmdline");
            return !cmd.isFile() || !Engine.read(cmd).contains("app_process");
        } catch (Exception x) {
            return true;
        }
    }

    static String pid() {
        try {
            return new File("/proc/self").getCanonicalFile().getName();
        } catch (Exception x) {
            return "";
        }
    }
}
