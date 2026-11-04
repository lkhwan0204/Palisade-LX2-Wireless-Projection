package hmgapi.hook;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mods' button bindings (mod.json "input", hmgapi.engine.Keys), in system_server.
 *
 * The car's buttons (head unit, steering wheel, overhead) all pass
 * WindowManagerService.interceptKey (HKMC's entry: the key policy's dispatch step
 * calls it, and it hands the key to the car's ModeKeyDispatcher) before the car acts
 * on them; -1 there uses a key up. A held-back key goes back into Android's input
 * (InputManager.injectInputEvent) and passes the same way as a new press, which
 * HMG-API lets through; calling the car's dispatcher again doesn't do its action.
 * HMG-API watches every key there once, for all mods; KeyPresses works out the
 * press. A held-back key reaches the car's own key handling later (after release,
 * or after the multi-press window) by calling that method again, which runs what
 * the car does for its own buttons; for keys the car passes on to the app on
 * screen (volume, TUNE in a list) a held-back press doesn't reach that app.
 *
 * Bindings come from public/state.json "input" (settled by the engine) and are
 * reloaded on hmgapi.intent.action.STATE_CHANGED. A matched binding broadcasts its
 * action to the mod's apps (extras hmgapi.key, hmgapi.press) and/or opens its
 * activity.
 */
final class Input {
    private Input() {}

    static final class Binding {
        String mod, key, press, broadcast, open;
        int sendCode, releaseCode;   // 0: none
        boolean sendHold, releaseHold;
        List<String> packages = new ArrayList<String>();
    }

    static final class Key implements KeyPresses.Out {
        final List<Binding> bindings = new ArrayList<Binding>();
        final KeyPresses presses = new KeyPresses(this);

        KeyEvent last;   // the press being handled: its device for the presses a binding sends

        @Override
        public void fire(String press) {
            if ("release".equals(press)) {
                for (Binding b : bindings) {
                    if ("hold".equals(b.press) && b.releaseCode != 0) {
                        HmgApi.i("input: " + b.key + " let go -> the car gets " + KeyEvent.keyCodeToString(b.releaseCode) + (b.releaseHold ? " hold" : " short"));
                        send(b.releaseCode, b.releaseHold, last);
                    }
                }
                return;
            }
            for (Binding b : bindings) if (b.press.equals(press)) Input.fire(b, press, last);
        }

        @Override
        public void replay(List<Object> events) {
            Input.replay(events);
        }

        @Override
        public void later(Runnable r, long ms) {
            handler.postDelayed(r, ms);
        }

        @Override
        public void cancel(Runnable r) {
            handler.removeCallbacks(r);
        }
    }

    static volatile Map<Integer, Key> keys = new HashMap<Integer, Key>();
    static Handler handler;
    static Context context;
    static boolean installed;
    /** every key the hook sees, logged: setprop debug.hmgapi.keys 1, then a state change (or restart) */
    static volatile boolean debug;

    /** In the zygote: when system_server loads automotive-service.jar, hook its key entry. */
    static void zygote() {
        if (!Points.needAll("buttons", "framework.hkmc_loader.automotive_service", "framework.hkmc_loader.class_loader",
                "framework.window_manager_service.intercept_key")) return;
        Points.hook(null, "framework.hkmc_loader.automotive_service", new MethodHook() {
            @Override
            protected void afterHookedMethod(Param p) {
                if (installed || p.getResult() == null) return;
                try {
                    ClassLoader cl = (ClassLoader) Points.get(null, "framework.hkmc_loader.class_loader", p.getResult());
                    install(cl, (Context) p.args[0]);
                } catch (Throwable t) {
                    HmgApi.e("input: key hooks", t);
                }
            }
        });
    }

    static void install(ClassLoader cl, Context c) throws Exception {
        installed = true;
        context = c;
        HandlerThread t = new HandlerThread("hmgapi-keys");
        t.start();
        handler = new Handler(t.getLooper());
        // every key, once Android is up (ModeManagerService.interceptKeyBefore* aren't called on 260507)
        Points.hook(null, "framework.window_manager_service.intercept_key", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                if (p.args[0] == null) return;
                final KeyEvent e = new KeyEvent((KeyEvent) p.args[0]);
                final int code = e.getKeyCode(), action = e.getAction(), repeat = e.getRepeatCount();
                if (passOnce(code)) return;   // one HMG-API put back
                if (debug) HmgApi.i("input: key " + code + " action " + action + " repeat " + repeat);
                if (sent(code)) return;   // Android's long press repeat of a press HMG-API sent: the car's
                final Key k = keys.get(code);
                if (k == null) return;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        k.last = e;
                        if (action == KeyEvent.ACTION_DOWN) k.presses.down(repeat, e);
                        else if (action == KeyEvent.ACTION_UP) k.presses.up(e);
                    }
                });
                if (k.presses.holdsBack()) p.setResult(-1);   // the car gets it later, or never if a binding takes it
            }
        });
        handler.post(new Runnable() {
            @Override
            public void run() {
                load();
            }
        });
        c.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent i) {
                load();
            }
        }, new IntentFilter(Live.ACTION), null, handler);
        HmgApi.i("input: watching the car's keys");
        try {
            LightRelay.start(c, handler);   // same process and context; not about keys
        } catch (Throwable x) {
            HmgApi.e("light relay", x);
        }
    }

    /** public/state.json "input" -> keys by code; codes this car's KeyEvent doesn't have are skipped */
    static void load() {
        Map<Integer, Key> m = new HashMap<Integer, Key>();
        try {
            JSONArray l = new JSONObject(Mods.read(Live.STATE)).optJSONArray("input");
            for (int i = 0; l != null && i < l.length(); i++) {
                JSONObject o = l.getJSONObject(i);
                int code;
                try {
                    code = KeyEvent.class.getField(o.getString("keycode")).getInt(null);
                } catch (NoSuchFieldException e) {
                    continue;   // a button this car doesn't have
                }
                Binding b = new Binding();
                b.mod = o.getString("mod");
                b.key = o.getString("key");
                b.press = o.getString("press");
                b.broadcast = o.optString("broadcast", null);
                b.open = o.optString("open", null);
                b.sendCode = code(o.optString("send_keycode", ""));
                b.sendHold = o.optString("send", "").endsWith(" hold");
                b.releaseCode = code(o.optString("release_keycode", ""));
                b.releaseHold = o.optString("release", "").endsWith(" hold");
                JSONArray ps = o.optJSONArray("packages");
                for (int j = 0; ps != null && j < ps.length(); j++) b.packages.add(ps.getString(j));
                Key k = m.get(code);
                if (k == null) m.put(code, k = new Key());
                k.bindings.add(b);
                k.presses.bind(b.press, "replace".equals(o.optString("stock")));
            }
        } catch (Exception e) {
            // no state yet: nothing bound
        }
        keys = m;
        try {
            debug = "1".equals(Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class)
                    .invoke(null, "debug.hmgapi.keys", ""));
        } catch (Exception e) {
            debug = false;
        }
        HmgApi.i("input: " + m.size() + " key(s) bound" + (debug ? ", logging every key" : ""));
    }

    /** keys put back and not seen again yet, by key code */
    static final Map<Integer, Integer> passing = new HashMap<Integer, Integer>();

    static boolean passOnce(int code) {
        synchronized (passing) {
            Integer n = passing.get(code);
            if (n == null) return false;
            if (n <= 1) passing.remove(code);
            else passing.put(code, n - 1);
            return true;
        }
    }

    /** the held-back key events, in order, back into Android's input: the car gets them as a press */
    static void replay(List<Object> events) {
        for (Object o : events) {
            KeyEvent e = (KeyEvent) o;
            inject(KeyEvent.changeTimeRepeat(e, android.os.SystemClock.uptimeMillis(), e.getRepeatCount()));
        }
    }

    /** one key event into Android's input, let through HMG-API's own hook */
    static void inject(KeyEvent e) {
        try {
            Object im = Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null);
            Method inject = im.getClass().getMethod("injectInputEvent", android.view.InputEvent.class, int.class);
            synchronized (passing) {
                Integer n = passing.get(e.getKeyCode());
                passing.put(e.getKeyCode(), n == null ? 1 : n + 1);
            }
            inject.invoke(im, e, 0);   // async
        } catch (Throwable t) {
            HmgApi.e("input: handing a key to the car", t);
        }
    }

    /** how long a sent hold stays down: past the car's own long press (500 ms for the voice button) */
    static final long SEND_HOLD_MS = 700;

    /**
     * Keys HMG-API sent that are still down, by key code. Android repeats an injected key
     * too (the first repeat, at 500 ms, carries FLAG_LONG_PRESS); the car needs it to see a
     * long press (the voice button's goes to Android Auto/CarPlay), so it passes untouched.
     */
    static final Map<Integer, Integer> sending = new HashMap<Integer, Integer>();

    static boolean sent(int code) {
        synchronized (sending) {
            return sending.containsKey(code);
        }
    }

    static void sending(int code, int d) {
        synchronized (sending) {
            Integer n = sending.get(code);
            int m = (n == null ? 0 : n) + d;
            if (m > 0) sending.put(code, m);
            else sending.remove(code);
        }
    }

    /**
     * Makes the car see a press of another button (a binding's "send"): down, then up
     * at once (short) or after SEND_HOLD_MS (hold), from the device of the press that
     * asked (the car's key dispatcher only takes keys from a real device).
     */
    static void send(final int code, boolean hold, KeyEvent from) {
        final int device = from == null ? 0 : from.getDeviceId(), source = from == null ? android.view.InputDevice.SOURCE_KEYBOARD : from.getSource();
        final long t = android.os.SystemClock.uptimeMillis();
        sending(code, 1);
        inject(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, 0, device, 0, 0, source));
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                inject(new KeyEvent(t, android.os.SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, 0, device, 0, 0, source));
                sending(code, -1);
            }
        }, hold ? SEND_HOLD_MS : 50);
    }

    static int code(String keycode) {
        if (keycode.isEmpty()) return 0;
        try {
            return KeyEvent.class.getField(keycode).getInt(null);
        } catch (Exception e) {
            return 0;   // a button this car doesn't have
        }
    }

    static void fire(Binding b, String press, KeyEvent from) {
        // a sent voice button hold while the phone's assistant is open would start it again;
        // the car's own short press ends it, as without HMG-API
        boolean hold = b.sendHold && !(b.sendCode == code("KEYCODE_SHORTCUT_PTT") && Car.phoneAssistantOpen(context));
        HmgApi.i("input: " + b.key + " " + press + " -> " + b.mod + (b.sendCode != 0 ? " (the car gets " + KeyEvent.keyCodeToString(b.sendCode) + (hold ? " hold)" : " short)") : ""));
        try {
            if (b.sendCode != 0) send(b.sendCode, hold, from);
            if (b.open != null) {
                context.startActivity(new Intent(Intent.ACTION_MAIN).setComponent(ComponentName.unflattenFromString(b.open))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra("hmgapi.key", b.key).putExtra("hmgapi.press", press));
            }
            if (b.broadcast != null) {
                for (String pkg : b.packages) {
                    context.sendBroadcast(new Intent(b.broadcast).setPackage(pkg).addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                            .putExtra("hmgapi.key", b.key).putExtra("hmgapi.press", press));
                }
            }
        } catch (Throwable t) {
            HmgApi.e("input: " + b.mod + " " + b.key + " " + press, t);
        }
    }
}
