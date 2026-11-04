package hmgapi.vehicle;

import android.os.IBinder;
import android.os.SystemClock;

import hmgapi.hook.Points;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashSet;
import java.util.Set;

/**
 * The MICOM watch: a recorder for mapping the car's data (owner and developers
 * only: hmgapid micom-watch). It registers with the car's own raw data service
 * (MICOMDATA_SERVICE, IMicomDataService.registerCallback) for the categories asked
 * for and writes every frame it hears to /data/hmgapi/logs/hmgapi/micom.log:
 *
 *   <ms since boot> <cid> <function> <data hex>
 *
 * It only listens: the service's send method isn't mapped. Names come off the car
 * (tools/micom-decode.py with a catalogue from tools/micom-catalogue.py).
 *
 * Per-car identity never reaches the log: the telematics category (TMU, 0xc5:
 * modem, account, phone numbers) is never watched, and in every frame a run of 8 or
 * more printable characters (a VIN, a serial, a phone number, a name) is written
 * as "__" bytes. Numbers, flags and short codes stay.
 * state/micom-watch.txt: "all" or category ids in hex, one line; no file: off.
 */
final class MicomWatch extends com.hkmc.micom.IMicomDataCallback.Stub {
    static final File WANT = new File("/data/hmgapi/state/micom-watch.txt");
    static final File LOG = new File("/data/hmgapi/logs/hmgapi/micom.log");
    static final long MAX = 4L << 20;
    static final String TAG = "hmgapi";
    static final int NEVER = 0xc5;   // TMU: telematics (identity, account, phone numbers)

    final Set<Integer> on = new HashSet<Integer>();
    String wanted = "";
    FileOutputStream out;
    long written;

    /** called every tick: (un)registers to match state/micom-watch.txt */
    synchronized void sync() {
        String want = "";
        try {
            if (WANT.isFile()) want = hmgapi.engine.Engine.read(WANT).trim();
        } catch (Exception e) {
            want = "";
        }
        if (want.equals(wanted)) return;
        wanted = want;
        Set<Integer> next = new HashSet<Integer>();
        if ("all".equals(want)) {
            for (int c = 0x80; c <= 0xcf; c++) next.add(c);
            next.remove(NEVER);
        } else if (!want.isEmpty()) {
            for (String w : want.split("[ ,]+")) {
                try {
                    int c = Integer.parseInt(w.replace("0x", ""), 16);
                    if (c >= 0 && c <= 0xff && c != NEVER) next.add(c);
                } catch (NumberFormatException e) {
                    // not a category
                }
            }
        }
        Object svc = service();
        if (svc == null) {
            wanted = "";   // try again next tick
            return;
        }
        for (Integer c : new HashSet<Integer>(on)) {
            if (next.contains(c)) continue;
            call(svc, "framework.micomdata_service.unregister", c);
            on.remove(c);
        }
        for (Integer c : next) {
            if (on.contains(c)) continue;
            if (Boolean.TRUE.equals(call(svc, "framework.micomdata_service.register", c))) on.add(c);
        }
        line("# watching " + (on.isEmpty() ? "nothing" : on.size() + " categor" + (on.size() == 1 ? "y" : "ies")) + " (" + (want.isEmpty() ? "off" : want) + ")");
        if (on.isEmpty()) close();
        System.out.println("vehicle: MICOM watch " + (want.isEmpty() ? "off" : want) + ": " + on.size() + " registered");
    }

    Object service() {
        try {
            String name = (String) Points.get(null, "framework.car_services.micom_data", null);
            IBinder b = (IBinder) Points.call(null, "framework.service_manager.get_service", null, name);
            return b == null ? null : Points.call(null, "framework.micomdata_service_stub.as_interface", null, b);
        } catch (Throwable t) {
            System.out.println("vehicle: MICOM data service: " + t);
            return null;
        }
    }

    Object call(Object svc, String id, int cid) {
        try {
            return Points.call(svc.getClass().getClassLoader(), id, svc, TAG, (byte) cid, this);
        } catch (Throwable t) {
            System.out.println("vehicle: " + id + " 0x" + Integer.toHexString(cid) + ": " + t);
            return null;
        }
    }

    @Override
    public void onEvent(byte cid, byte funcH, byte funcL, byte[] data) {
        StringBuilder b = new StringBuilder(32 + (data == null ? 0 : data.length * 2));
        b.append(SystemClock.elapsedRealtime()).append(' ').append(String.format("%02x", cid & 0xff)).append(' ')
                .append(String.format("%04x", (funcH & 0xff) << 8 | funcL & 0xff)).append(' ');
        b.append(Scrub.hex(data));
        line(b.toString());
    }

    synchronized void line(String s) {
        try {
            if (out == null) {
                LOG.getParentFile().mkdirs();
                written = LOG.length();
                out = new FileOutputStream(LOG, true);
            }
            byte[] b = (s + "\n").getBytes("UTF-8");
            out.write(b);
            written += b.length;
            if (written > MAX) {
                close();
                LOG.renameTo(new File(LOG.getPath() + ".1"));
            }
        } catch (Exception e) {
            close();
        }
    }

    void close() {
        try {
            if (out != null) out.close();
        } catch (Exception e) {
            // gone
        }
        out = null;
    }
}
