package hmgapi.engine;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * A native patch applied in memory: an lpatch recipe (tools/lpatch) put on a library
 * after it's loaded in an app, instead of on its file. The file stays stock, so mods
 * patching the same library mix as long as their regions don't overlap, and turning a
 * mod off needs only an app restart.
 *
 * The lpatch lines, offsets in the library file (the code segment maps at file offset 0):
 *   id <name>, file <path>
 *   native <md5> [note]       this library (whole-file md5) has it already: nothing to do
 *   verify <off> <len> <md5 before> <md5 after | ->
 *   patch <off> <hex>
 * Memory only, for code that doesn't fit in place:
 *   segment <preferred off> <hex>   code mapped near the library (preferably at that offset)
 *   rel32 <seg off> <seg anchor> <lib off>   32-bit at seg off := address(lib off) - address(seg anchor)
 *   branch <off> <seg off>          a Thumb-2 b.w from the library into the segment (within 16 MB)
 * A verify region holding a branch has no fixed "after" ("-"): it depends on where the
 * segment lands.
 *
 * Nothing is written unless every verify region is the stock bytes; the segment goes in
 * first, then patches, then branches, so a jump only goes live once its target is there.
 */
public final class MemRecipe {
    /** the process's memory (on the car: hmgapi.hook.NativePatch; in tests: a byte array) */
    public interface Memory {
        byte[] peek(long addr, int len);

        boolean poke(long addr, byte[] data);

        /** an anonymous code page run at hint if possible (any address otherwise), 0 if none */
        long mapCode(long hint, int len);
    }

    public static final class Verify {
        public final long off;
        public final int len;
        public final String before, after;

        Verify(long off, int len, String before, String after) {
            this.off = off;
            this.len = len;
            this.before = before;
            this.after = after;
        }
    }

    public String id = "", file = "";
    public final List<String> natives = new ArrayList<String>();
    public final List<Verify> verifies = new ArrayList<Verify>();
    public final List<long[]> patchAt = new ArrayList<long[]>();   // {off}
    public final List<byte[]> patchBytes = new ArrayList<byte[]>();
    public long segPreferred = -1;
    public byte[] segment;
    public final List<long[]> rel32 = new ArrayList<long[]>();     // {seg off, anchor, lib off}
    public final List<long[]> branches = new ArrayList<long[]>();  // {lib off, seg off}

    public static MemRecipe parse(String text) {
        MemRecipe r = new MemRecipe();
        int n = 0;
        for (String line : text.split("\n")) {
            n++;
            int hash = line.indexOf('#');
            String l = (hash >= 0 ? line.substring(0, hash) : line).trim();
            if (l.isEmpty()) continue;
            String[] w = l.split("\\s+");
            try {
                if (w[0].equals("id")) r.id = w[1];
                else if (w[0].equals("file")) r.file = w[1];
                else if (w[0].equals("native")) r.natives.add(w[1]);
                else if (w[0].equals("verify")) r.verifies.add(new Verify(num(w[1]), (int) num(w[2]), w[3], w[4]));
                else if (w[0].equals("patch")) {
                    r.patchAt.add(new long[] { num(w[1]) });
                    r.patchBytes.add(hex(w[2]));
                } else if (w[0].equals("segment")) {
                    r.segPreferred = num(w[1]);
                    r.segment = hex(w[2]);
                } else if (w[0].equals("rel32")) r.rel32.add(new long[] { num(w[1]), num(w[2]), num(w[3]) });
                else if (w[0].equals("branch")) r.branches.add(new long[] { num(w[1]), num(w[2]) });
                else throw new IllegalArgumentException("unknown line");
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("recipe line " + n + ": " + l + " (" + e.getMessage() + ")");
            }
        }
        if (r.verifies.isEmpty() && r.natives.isEmpty()) throw new IllegalArgumentException("recipe has no verify or native lines");
        if ((!r.rel32.isEmpty() || !r.branches.isEmpty()) && r.segment == null) throw new IllegalArgumentException("rel32/branch without a segment");
        for (long[] p : r.patchAt) {
            if (!r.covered(p[0], r.patchBytes.get(r.patchAt.indexOf(p)).length)) throw new IllegalArgumentException("patch at 0x" + Long.toHexString(p[0]) + " outside every verify region");
        }
        for (long[] b : r.branches) {
            if (!r.covered(b[0], 4)) throw new IllegalArgumentException("branch at 0x" + Long.toHexString(b[0]) + " outside every verify region");
        }
        return r;
    }

    boolean covered(long off, int len) {
        for (Verify v : verifies) {
            if (off >= v.off && off + len <= v.off + v.len) return true;
        }
        return false;
    }

    static long num(String s) {
        return s.startsWith("0x") ? Long.parseLong(s.substring(2), 16) : Long.parseLong(s);
    }

    static byte[] hex(String s) {
        if (s.length() % 2 != 0) throw new IllegalArgumentException("odd hex");
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    public static String md5(byte[] b) {
        try {
            StringBuilder s = new StringBuilder();
            for (byte x : MessageDigest.getInstance("MD5").digest(b)) s.append(String.format("%02x", x & 0xff));
            return s.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** the library regions this recipe owns (verify regions): [off, off+len) pairs */
    public List<long[]> regions() {
        List<long[]> r = new ArrayList<long[]>();
        for (Verify v : verifies) r.add(new long[] { v.off, v.off + v.len });
        return r;
    }

    /** outcome of apply */
    public static final class Result {
        public static final int APPLIED = 0, NATIVE = 1, ALREADY = 2, MISMATCH = 3, FAILED = 4;
        public final int status;
        public final String message;
        /** where the segment went (0: no segment) */
        public final long segment;

        Result(int status, String message, long segment) {
            this.status = status;
            this.message = message;
            this.segment = segment;
        }

        public boolean ok() {
            return status == APPLIED || status == NATIVE || status == ALREADY;
        }
    }

    static final long BRANCH_RANGE = 16L * 1024 * 1024 - 4;

    /**
     * Puts this recipe on the library mapped at base (address of file offset 0), whose
     * code segment ends at textEnd (file offset). fileMd5: the library file's md5.
     */
    public Result apply(Memory m, long base, long textEnd, String fileMd5) {
        if (fileMd5 != null && natives.contains(fileMd5)) return new Result(Result.NATIVE, id + ": this library has it already", 0);
        int before = 0, after = 0;
        StringBuilder spots = new StringBuilder();
        for (Verify v : verifies) {
            if (v.off + v.len > textEnd) {
                spots.append(" 0x").append(Long.toHexString(v.off)).append(":outside");
                continue;
            }
            String h = md5(m.peek(base + v.off, v.len));
            if (h.equals(v.before)) before++;
            else if (h.equals(v.after)) after++;
            else spots.append(" 0x").append(Long.toHexString(v.off)).append(":other");
        }
        if (after == verifies.size() && !verifies.isEmpty()) return new Result(Result.ALREADY, id + ": already patched", 0);
        if (before != verifies.size()) return new Result(Result.MISMATCH, id + ": not this library's stock bytes (" + spots.toString().trim() + ")", 0);

        long seg = 0;
        if (segment != null) {
            seg = placeSegment(m, base, textEnd);
            if (seg == 0) return new Result(Result.FAILED, id + ": no place for its code within branch range of the library", 0);
            byte[] code = segment.clone();
            for (long[] r : rel32) {
                long v = (base + r[2]) - (seg + r[1]);
                int o = (int) r[0];
                code[o] = (byte) v;
                code[o + 1] = (byte) (v >> 8);
                code[o + 2] = (byte) (v >> 16);
                code[o + 3] = (byte) (v >> 24);
            }
            if (!m.poke(seg, code)) return new Result(Result.FAILED, id + ": couldn't write its code", seg);
        }
        for (int i = 0; i < patchAt.size(); i++) {
            if (!m.poke(base + patchAt.get(i)[0], patchBytes.get(i))) return new Result(Result.FAILED, id + ": couldn't write at 0x" + Long.toHexString(patchAt.get(i)[0]), seg);
        }
        for (long[] b : branches) {
            byte[] bw = branchW(base + b[0], seg + b[1]);
            if (bw == null || !m.poke(base + b[0], bw)) return new Result(Result.FAILED, id + ": couldn't write the branch at 0x" + Long.toHexString(b[0]), seg);
        }
        for (Verify v : verifies) {
            if (!"-".equals(v.after) && !md5(m.peek(base + v.off, v.len)).equals(v.after)) {
                return new Result(Result.FAILED, id + ": 0x" + Long.toHexString(v.off) + " didn't come out as the recipe says", seg);
            }
        }
        return new Result(Result.APPLIED, id + ": applied" + (seg != 0 ? " (code at 0x" + Long.toHexString(seg) + ")" : ""), seg);
    }

    /** a code page run for the segment, every branch into it within range; tries the preferred offset, then 1 MB steps around the library */
    long placeSegment(Memory m, long base, long textEnd) {
        long page = segPreferred >= 0 ? segPreferred & 0xfff : 0;
        int len = (int) (page + segment.length);
        List<Long> hints = new ArrayList<Long>();
        if (segPreferred >= 0) hints.add(base + (segPreferred & ~0xfffL));
        long end = (base + textEnd + 0xfff) & ~0xfffL;
        for (int k = 0; k < 15; k++) {
            hints.add(end + k * 0x100000L);
            if (base - (k + 1) * 0x100000L > 0) hints.add(base - (k + 1) * 0x100000L);
        }
        for (long h : hints) {
            long got = m.mapCode(h, len);
            if (got == 0) continue;
            long seg = got + page;
            boolean inRange = true;
            for (long[] b : branches) {
                long d = (seg + b[1]) - (base + b[0] + 4);
                if (d > BRANCH_RANGE || d < -BRANCH_RANGE) inRange = false;
            }
            if (inRange) return seg;
            // the kernel put it elsewhere: left mapped (a few KB), the next hint is tried
        }
        return 0;
    }

    /** Thumb-2 B.W (T4) at from to target (Thumb code addresses; the low bit is ignored); null if out of range */
    public static byte[] branchW(long from, long target) {
        long off = (target & ~1L) - ((from & ~1L) + 4);
        if (off > BRANCH_RANGE || off < -BRANCH_RANGE - 4 || (off & 1) != 0) return null;
        long s = off < 0 ? 1 : 0;
        long imm11 = (off >> 1) & 0x7ff, imm10 = (off >> 12) & 0x3ff;
        long i1 = (off >> 23) & 1, i2 = (off >> 22) & 1;
        long j1 = (~(i1 ^ s)) & 1, j2 = (~(i2 ^ s)) & 1;
        int hw1 = (int) (0xF000 | (s << 10) | imm10), hw2 = (int) (0x9000 | (j1 << 13) | (j2 << 11) | imm11);
        return new byte[] { (byte) hw1, (byte) (hw1 >> 8), (byte) hw2, (byte) (hw2 >> 8) };
    }
}
