package hmgapi.engine;

import java.io.File;
import java.io.IOException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mod identity and signatures. Plain Java 7 (Android 4.4 and desktop JDKs:
 * ECDSA P-256 / SHA-256 is built into both), so the authoring tool and the head
 * unit run the same code.
 *
 *   keygen <key file>                    a new author key (private, PKCS#8) + <key file>.pub
 *   uuid <key file or .pub>              the mod UUID this key gives
 *   sign <mod folder> <key file>         writes META-HMGAPI/manifest.json and META-HMGAPI/signature
 *   sign-hmgapi <payload folder> <key>   the same for a HMG-API release (id "hmgapi", version from VERSION)
 *   verify <mod folder> [<expected uuid>] checks every file, the signature, and the UUID
 *
 * A mod's UUID is derived from its author's public key (SHA-256 of the encoded
 * key, first 16 bytes, as a version-8 UUID), so only the key holder can sign a
 * mod with that UUID. HMG-API installs a first version after the owner confirms
 * its UUID, and an update only if it carries the same UUID and verifies.
 *
 * manifest.json: {"format": 1, "id", "version", "uuid", "key": <base64 X.509
 * public key>, "files": {"<path>": "<sha256>", ...}}. signature: base64 ECDSA
 * (DER) over the manifest's exact bytes.
 */
public final class Signing {
    private Signing() {}

    static final String DIR = "META-HMGAPI";

    public static void main(String[] a) throws Exception {
        if (a.length >= 2 && "keygen".equals(a[0])) {
            File priv = new File(a[1]);
            if (priv.exists()) throw new IOException(priv + " exists; not overwriting a key");
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair kp = g.generateKeyPair();
            Engine.write(priv, b64(kp.getPrivate().getEncoded()) + "\n");
            Engine.write(new File(a[1] + ".pub"), b64(kp.getPublic().getEncoded()) + "\n");
            System.out.println("key: " + priv + " (keep it private and backed up: losing it means updates can't be signed)");
            System.out.println("uuid: " + uuid(kp.getPublic().getEncoded()));
        } else if (a.length >= 2 && "uuid".equals(a[0])) {
            System.out.println(uuid(publicOf(new File(a[1])).getEncoded()));
        } else if (a.length >= 3 && "sign".equals(a[0])) {
            System.out.println(sign(new File(a[1]), new File(a[2])));
        } else if (a.length >= 3 && "sign-hmgapi".equals(a[0])) {
            File d = new File(a[1]);
            System.out.println(signAs(d, new File(a[2]), "hmgapi", Engine.read(new File(d, "VERSION")).trim()));
        } else if (a.length >= 2 && "verify".equals(a[0])) {
            Result r = verify(new File(a[1]), a.length > 2 ? a[2] : null);
            System.out.println(r.ok ? "ok: " + r.id + " " + r.version + " uuid " + r.uuid : "FAILED: " + r.error);
            System.exit(r.ok ? 0 : 1);
        } else {
            System.err.println("usage: keygen <key> | uuid <key|pub> | sign <mod dir> <key> | verify <mod dir> [uuid]");
            System.exit(2);
        }
    }

    // ---- keys and UUIDs

    public static String uuid(byte[] publicKey) throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-256").digest(publicKey);
        h[6] = (byte) ((h[6] & 0x0f) | 0x80);   // version 8 (custom)
        h[8] = (byte) ((h[8] & 0x3f) | 0x80);   // RFC 4122 variant
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            if (i == 4 || i == 6 || i == 8 || i == 10) s.append('-');
            s.append(String.format("%02x", h[i] & 0xff));
        }
        return s.toString();
    }

    static PublicKey publicOf(File f) throws Exception {
        String t = Engine.read(f).trim();
        KeyFactory kf = KeyFactory.getInstance("EC");
        if (f.getName().endsWith(".pub")) return kf.generatePublic(new X509EncodedKeySpec(unb64(t)));
        File pub = new File(f.getPath() + ".pub");
        if (pub.isFile()) return kf.generatePublic(new X509EncodedKeySpec(unb64(Engine.read(pub).trim())));
        throw new IOException("no " + pub);
    }

    // ---- sign

    @SuppressWarnings("unchecked")
    public static String sign(File mod, File keyFile) throws Exception {
        Map<String, Object> mj = Json.object(Engine.read(new File(mod, "mod.json")));
        return signAs(mod, keyFile, String.valueOf(mj.get("id")), String.valueOf(mj.get("version")));
    }

    /** Signs a folder as <id> <version>: a mod (from its mod.json) or a HMG-API release ("hmgapi"). */
    public static String signAs(File mod, File keyFile, String id, String version) throws Exception {
        KeyFactory kf = KeyFactory.getInstance("EC");
        PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(unb64(Engine.read(keyFile).trim())));
        PublicKey pub = publicOf(keyFile);
        Map<String, Object> man = new LinkedHashMap<String, Object>();
        man.put("format", 1L);
        man.put("id", id);
        man.put("version", version);
        man.put("uuid", uuid(pub.getEncoded()));
        man.put("key", b64(pub.getEncoded()));
        Map<String, Object> files = new LinkedHashMap<String, Object>();
        for (String p : list(mod)) files.put(p, sha256(new File(mod, p)));
        man.put("files", files);
        byte[] bytes = Json.write(man).getBytes("UTF-8");
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(priv);
        s.update(bytes);
        File d = new File(mod, DIR);
        d.mkdirs();
        Engine.write(new File(d, "manifest.json"), new String(bytes, "UTF-8"));
        Engine.write(new File(d, "signature"), b64(s.sign()) + "\n");
        return "signed " + id + " " + version + ": " + files.size() + " files, uuid " + man.get("uuid");
    }

    // ---- verify

    public static final class Result {
        public boolean ok;
        public String id, version, uuid, error;
    }

    @SuppressWarnings("unchecked")
    public static Result verify(File mod, String expectUuid) {
        Result r = new Result();
        try {
            File mf = new File(mod, DIR + "/manifest.json"), sf = new File(mod, DIR + "/signature");
            if (!mf.isFile() || !sf.isFile()) return fail(r, "not signed (no " + DIR + "/manifest.json and signature)");
            byte[] bytes = Engine.readAll(new java.io.FileInputStream(mf));
            Map<String, Object> man = Json.object(new String(bytes, "UTF-8"));
            r.id = String.valueOf(man.get("id"));
            r.version = String.valueOf(man.get("version"));
            byte[] key = unb64(String.valueOf(man.get("key")));
            r.uuid = uuid(key);
            if (!r.uuid.equals(man.get("uuid"))) return fail(r, "manifest uuid doesn't belong to its key");
            if (expectUuid != null && !expectUuid.equals(r.uuid)) {
                return fail(r, "signed by a different author (uuid " + r.uuid + ", installed mod has " + expectUuid + ")");
            }
            PublicKey pub = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(key));
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initVerify(pub);
            s.update(bytes);
            if (!s.verify(unb64(Engine.read(sf).trim()))) return fail(r, "signature doesn't match the manifest");
            Map<String, Object> files = (Map<String, Object>) man.get("files");
            List<String> actual = list(mod);
            for (String p : actual) {
                if (!files.containsKey(p)) return fail(r, "file not in the manifest: " + p);
            }
            for (Map.Entry<String, Object> e : files.entrySet()) {
                File f = new File(mod, e.getKey());
                if (!f.isFile()) return fail(r, "missing file: " + e.getKey());
                if (!sha256(f).equals(e.getValue())) return fail(r, "file changed after signing: " + e.getKey());
            }
            File mjf = new File(mod, "mod.json");
            if (mjf.isFile()) {
                Map<String, Object> mj = Json.object(Engine.read(mjf));
                if (!r.id.equals(mj.get("id")) || !r.version.equals(String.valueOf(mj.get("version")))) {
                    return fail(r, "mod.json id/version differ from the signed manifest");
                }
            } else if (!"hmgapi".equals(r.id)) {
                return fail(r, "no mod.json");
            }
            r.ok = true;
        } catch (Exception e) {
            fail(r, String.valueOf(e));
        }
        return r;
    }

    static Result fail(Result r, String why) {
        r.ok = false;
        r.error = why;
        return r;
    }

    // ---- helpers

    /** Every file under the mod, except META-HMGAPI/ and HMG-API's own markers. */
    static List<String> list(File mod) {
        List<String> out = new ArrayList<String>();
        walk(mod, "", out);
        Collections.sort(out);
        return out;
    }

    static void walk(File d, String rel, List<String> out) {
        File[] fs = d.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            String p = rel.isEmpty() ? f.getName() : rel + "/" + f.getName();
            if (p.equals(DIR) || p.equals(".disabled")) continue;
            if (f.isDirectory()) walk(f, p, out);
            else out.add(p);
        }
    }

    static String sha256(File f) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        java.io.InputStream in = new java.io.FileInputStream(f);
        try {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) d.update(b, 0, n);
        } finally {
            in.close();
        }
        StringBuilder s = new StringBuilder();
        for (byte x : d.digest()) s.append(String.format("%02x", x & 0xff));
        return s.toString();
    }

    // Base64 by hand: android.util.Base64 isn't on desktop JVMs, java.util.Base64 isn't on Android 4.4.
    static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    static String b64(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < b.length; i += 3) {
            int n = (b[i] & 0xff) << 16 | (i + 1 < b.length ? (b[i + 1] & 0xff) << 8 : 0) | (i + 2 < b.length ? b[i + 2] & 0xff : 0);
            s.append(B64[n >> 18 & 63]).append(B64[n >> 12 & 63]);
            s.append(i + 1 < b.length ? B64[n >> 6 & 63] : '=').append(i + 2 < b.length ? B64[n & 63] : '=');
        }
        return s.toString();
    }

    static byte[] unb64(String s) {
        s = s.replaceAll("[^A-Za-z0-9+/]", "");
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        int buf = 0, bits = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int v = c >= 'A' && c <= 'Z' ? c - 'A' : c >= 'a' && c <= 'z' ? c - 'a' + 26 : c >= '0' && c <= '9' ? c - '0' + 52 : c == '+' ? 62 : 63;
            buf = buf << 6 | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                o.write(buf >> bits & 0xff);
            }
        }
        return o.toByteArray();
    }
}
