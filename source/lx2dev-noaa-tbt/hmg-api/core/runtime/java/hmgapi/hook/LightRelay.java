package hmgapi.hook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Handler;

import java.io.OutputStream;

/**
 * In system_server: passes the ambient light's status (the system service's
 * SML_STATUS_CHANGED broadcast, sent after every change and on request) to the
 * vehicle service, which can't hear broadcasts itself (app_process, no app). The
 * vehicle service keeps the owner's light with it, to put it back when a mod lets go.
 */
final class LightRelay {
    private LightRelay() {}

    static final String ACTION = "com.mobis.intent.action.SML_STATUS_CHANGED", EXTRA = "com.mobis.intent.extra.SML_STATUS";
    static final String SOCKET = "hmgapi-vehicle";
    static String lastFailure;

    static void start(Context c, Handler h) {
        c.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent i) {
                byte[] s = i.getByteArrayExtra(EXTRA);
                if (s != null) send(s);
            }
        }, new IntentFilter(ACTION), null, h);
        HmgApi.i("light: passing the ambient light's status to the vehicle service");
    }

    static void send(byte[] s) {
        StringBuilder hex = new StringBuilder("light ");
        for (byte b : s) hex.append(String.format("%02x", b & 255));
        LocalSocket sock = new LocalSocket();
        try {
            sock.connect(new LocalSocketAddress(SOCKET));
            OutputStream o = sock.getOutputStream();
            o.write((hex + "\n").getBytes("UTF-8"));
            o.flush();
            lastFailure = null;
        } catch (Exception e) {
            String why = String.valueOf(e);
            if (!why.equals(lastFailure)) HmgApi.i("light: vehicle service not reached: " + why);   // not running: nothing to keep
            lastFailure = why;
        } finally {
            try {
                sock.close();
            } catch (Exception x) {
                // closed already
            }
        }
    }
}
