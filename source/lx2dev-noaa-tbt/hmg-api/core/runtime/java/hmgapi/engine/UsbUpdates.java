package hmgapi.engine;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Updates from a USB stick: zips in HMGAPI/updates/ on the stick, mod packages
 * (signed by their author) and HMG-API releases (signed with HMG-API's release key).
 *
 *   engine usb-scan                    what's on the stick and what each zip would do (JSON, for the app)
 *   engine update-hmgapi <zip> [--force]   check a HMG-API release and run its installer
 *
 * Mod packages install with `engine install <zip> [--uuid <uuid>]` (Packages).
 * A stick can't be read while ADB over USB is on: the port is in device mode then.
 */
final class UsbUpdates {
    private UsbUpdates() {}

    /** HMG-API's release key: only releases signed with it update HMG-API. */
    static final String RELEASE_UUID = "7c8adfcb-bf60-88ac-9389-62e9d43728e1";
    static final String[] MOUNTS = { "/storage/usb0", "/storage/usb1", "/storage/usb2",
        "/mnt/media_rw/usb0", "/mnt/media_rw/usb1", "/mnt/media_rw/usb2" };

    static String releaseUuid(Engine e) {
        String t = Engine.env("HMGAPI_TEST_RELEASE_UUID", null);
        return e.host && t != null ? t : RELEASE_UUID;
    }

    static List<File> dirs(Engine e) {
        List<File> out = new ArrayList<File>();
        String test = Engine.env("HMGAPI_USB_DIRS", null);
        for (String d : e.host && test != null ? test.split(":") : MOUNTS) {
            File f = new File(e.host ? d : e.root + d);
            if (new File(f, "HMGAPI/updates").isDirectory()) out.add(f);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static int scan(Engine e) throws Exception {
        Map<String, Object> rep = new LinkedHashMap<String, Object>();
        List<Object> sticks = new ArrayList<Object>(), pkgs = new ArrayList<Object>();
        Set<String> seen = new LinkedHashSet<String>();   // one stick can show up under two mount points
        for (File d : dirs(e)) {
            sticks.add(d.getPath());
            File[] zs = new File(d, "HMGAPI/updates").listFiles();
            if (zs == null) continue;
            Arrays.sort(zs);
            for (File z : zs) {
                if (!z.isFile() || !z.getName().toLowerCase().endsWith(".zip")) continue;
                if (!seen.add(z.getName() + " " + z.length())) continue;
                pkgs.add(inspect(e, z));
            }
        }
        rep.put("sticks", sticks);
        rep.put("packages", pkgs);
        rep.put("hmgapi", installedVersion(e));
        if (sticks.isEmpty()) {
            rep.put("hint", "No USB stick with a HMGAPI/updates folder. If ADB over USB is on, the port is in computer mode"
                    + " and can't read a stick: turn it off in HMG-API (ADB mod) first.");
        }
        e.out.add(Json.write(rep));
        return 0;
    }

    static Map<String, Object> inspect(Engine e, File zip) {
        Map<String, Object> p = new LinkedHashMap<String, Object>();
        p.put("file", zip.getPath());
        File stage = new File(e.stageDir, "scan");
        Engine.rm(stage);
        stage.mkdirs();
        try {
            Packages.unzip(zip, stage);
            File dir = Packages.root(stage);
            if (new File(dir, "mod.json").isFile()) {
                Packages.Verdict v = Packages.check(e, dir, null, false, false);
                p.put("kind", "mod");
                p.put("id", v.id);
                p.put("name", v.name);
                p.put("version", v.version);
                p.put("description", v.description);
                p.put("installed", v.installedVersion);
                p.put("status", v.status);
                if (v.uuid != null) p.put("uuid", v.uuid);
                if (v.author != null) p.put("author", v.author);
                if (v.reason != null) p.put("reason", v.reason);
                p.put("notes", new ArrayList<Object>(v.notes));
                if (v.otherCar != null && "refused".equals(v.status)) {
                    // only the car is wrong: what "Install anyway" (--any-car) would do
                    Packages.Verdict a = Packages.check(e, dir, null, false, true);
                    if (a.code == 0) {
                        p.put("any_car", a.status);
                        if (a.author != null) p.put("author", a.author);
                        if (a.installedVersion != null) p.put("installed", a.installedVersion);
                    }
                }
            } else if (new File(dir, "install.sh").isFile() && new File(dir, "VERSION").isFile()) {
                Release r = release(e, dir);
                p.put("kind", "hmgapi");
                p.put("id", "hmgapi");
                p.put("name", "HMG-API");
                p.put("version", r.version);
                p.put("installed", installedVersion(e));
                p.put("status", r.status);
                if (r.reason != null) p.put("reason", r.reason);
            } else {
                p.put("kind", "unknown");
                p.put("status", "refused");
                p.put("reason", "not a HMG-API mod package or release");
            }
        } catch (Exception x) {
            p.put("kind", "unknown");
            p.put("status", "refused");
            p.put("reason", "can't read it: " + x.getMessage());
        } finally {
            Engine.rm(stage);
        }
        return p;
    }

    static final class Release {
        String version, status, reason;
    }

    /** A HMG-API release folder: signed with the release key, and how it compares with what's installed. */
    static Release release(Engine e, File dir) throws Exception {
        Release r = new Release();
        r.version = Engine.read(new File(dir, "VERSION")).trim();
        Signing.Result sig = Signing.verify(dir, releaseUuid(e));
        if (!sig.ok) {
            r.status = "refused";
            r.reason = "not a genuine HMG-API release: " + sig.error;
            return r;
        }
        if (!"hmgapi".equals(sig.id) || !r.version.equals(sig.version)) {
            r.status = "refused";
            r.reason = "the signed release says " + sig.id + " " + sig.version;
            return r;
        }
        int cmp = new Version(r.version).compareTo(new Version(installedVersion(e)));
        r.status = cmp > 0 ? "update" : cmp == 0 ? "same" : "older";
        return r;
    }

    static String installedVersion(Engine e) {
        try {
            for (String l : Engine.read(new File(e.data, "installed.txt")).split("\n")) {
                if (l.startsWith("version ")) return l.substring(8).trim();
            }
        } catch (Exception ignored) {
            // not installed by the installer (dev setup): this engine's own version
        }
        return Engine.HMGAPI_VERSION;
    }

    static int updateHmgApi(Engine e, String path, boolean force) throws Exception {
        File zip = new File(path);
        if (!zip.isFile()) {
            e.out.add("no release at " + path);
            return 2;
        }
        File stage = new File(e.stageDir, "hmgapi-update");
        Engine.rm(stage);
        stage.mkdirs();
        try {
            Packages.unzip(zip, stage);
            File dir = Packages.root(stage);
            if (!new File(dir, "install.sh").isFile() || !new File(dir, "VERSION").isFile()) {
                e.out.add("refused: not a HMG-API release");
                return 3;
            }
            Release r = release(e, dir);
            if ("refused".equals(r.status) || ("older".equals(r.status) && !force)) {
                e.out.add("refused: " + (r.reason != null ? r.reason
                        : "HMG-API " + r.version + " is older than the installed " + installedVersion(e) + " (--force installs it anyway)"));
                return 3;
            }
            e.out.add("HMG-API " + r.version + " (" + r.status + "), signed with the release key; running its installer");
            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", new File(dir, "install.sh").getPath(), dir.getPath());
            if (e.host) {
                pb.command("sh", new File(dir, "install.sh").getPath(), dir.getPath());
                pb.environment().put("ROOT", e.root);
                pb.environment().put("CAPCHECK", "0");
            }
            pb.redirectErrorStream(true);
            Process pr = pb.start();
            String text = new String(Engine.readAll(pr.getInputStream()), "UTF-8");
            int rc = pr.waitFor();
            for (String l : text.split("\n")) if (!l.isEmpty()) e.out.add(l);
            return rc;
        } finally {
            Engine.rm(stage);
        }
    }
}
