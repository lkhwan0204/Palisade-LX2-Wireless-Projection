package hmgapi.vehicle;

/** What the MICOM watch never writes: runs of 8 or more printable characters (VIN, serials, numbers, names). */
final class Scrub {
    private Scrub() {}

    static final int MIN = 8;

    /** frame bytes as hex, text runs as "__" */
    static String hex(byte[] d) {
        if (d == null) return "";
        boolean[] t = new boolean[d.length];
        int start = -1;
        for (int i = 0; i <= d.length; i++) {
            boolean p = i < d.length && d[i] >= 0x20 && d[i] <= 0x7e;
            if (p && start < 0) start = i;
            if (!p && start >= 0) {
                if (i - start >= MIN) for (int k = start; k < i; k++) t[k] = true;
                start = -1;
            }
        }
        StringBuilder b = new StringBuilder(d.length * 2);
        for (int i = 0; i < d.length; i++) b.append(t[i] ? "__" : String.format("%02x", d[i] & 0xff));
        return b.toString();
    }
}
