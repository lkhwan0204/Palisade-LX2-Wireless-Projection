package hmgapi.hook;

import java.util.ArrayList;
import java.util.List;

/**
 * One button's presses (plain Java, so the logic is tested off the car): which of
 * short, hold and down it was, and what the car gets. No multi-press: telling a
 * double press from two single ones means holding every press back for the
 * window, or letting the car act on each one (a menu opened three times). Its user (Input) feeds
 * it events on one thread and does what Out says.
 *
 * A key with a "replace" binding is held back: its events are kept until the press
 * is known, then dropped (a replacing binding matched) or handed to the car in
 * order (none did). Keys with only "keep" bindings are never held back.
 */
final class KeyPresses {
    static final long HOLD_MS = 800;
    /**
     * A taken short press's long press goes on to the car this soon, so the car's own
     * long press (500 ms after it sees the key, for the voice button) lands near where
     * it would without HMG-API
     */
    static final long PASS_MS = 300;

    interface Out {
        void fire(String press);

        /** hand these held-back events to the car, in order */
        void replay(List<Object> events);

        void later(Runnable r, long ms);

        void cancel(Runnable r);
    }

    /** the presses bound on this key, and which of them replace the car's own action */
    final List<String> presses = new ArrayList<String>(), replacing = new ArrayList<String>();
    final Out out;

    int count;
    boolean down, holdFired, passing, wasLong, heldDown;
    final List<Object> held = new ArrayList<Object>();
    Runnable hold, longPress;

    KeyPresses(Out out) {
        this.out = out;
    }

    void bind(String press, boolean replace) {
        if (!presses.contains(press)) presses.add(press);
        if (replace && !replacing.contains(press)) replacing.add(press);
    }

    boolean holdsBack() {
        return !replacing.isEmpty();
    }

    /** a key down (repeat 0), an auto-repeat (repeat > 0), or a key up; event: what replay hands back */
    void down(int repeat, Object event) {
        if (passing) {   // a long press of a key whose short press is taken: the car has it
            if (presses.contains("down")) out.fire("down");
            held.add(event);
            replay();
            return;
        }
        if (repeat > 0) {
            if (presses.contains("down")) out.fire("down");
            if (holdsBack() && !holdFired) held.add(event);
            return;
        }
        down = true;
        holdFired = false;
        wasLong = false;
        count++;
        if (holdsBack()) held.add(event);
        if (presses.contains("down")) out.fire("down");
        if (presses.contains("hold")) {
            final int at = count;
            hold = new Runnable() {
                @Override
                public void run() {
                    hold = null;
                    if (!down || count != at || holdFired) return;
                    holdFired = true;
                    heldDown = true;
                    count = 0;
                    out.fire("hold");
                    if (replacing.contains("hold")) held.clear();   // the car never sees this press
                    else replay();
                }
            };
            out.later(hold, HOLD_MS);
        } else if (presses.contains("short")) {
            // no hold binding: a long press isn't a short press. A taken short press passes the
            // long one to the car at 0.3 s (the voice button's long press is Android Auto's/CarPlay's)
            final boolean taken = replacing.contains("short");
            final int at = count;
            longPress = new Runnable() {
                @Override
                public void run() {
                    longPress = null;
                    if (!down || count != at) return;
                    count = 0;
                    if (!replacing.contains("short")) {
                        wasLong = true;   // the car has it all along; it just isn't a short press
                        return;
                    }
                    passing = true;
                    replay();
                }
            };
            out.later(longPress, taken ? PASS_MS : HOLD_MS);
        }
    }

    void up(Object event) {
        down = false;
        if (hold != null) {
            out.cancel(hold);
            hold = null;
        }
        if (longPress != null) {
            out.cancel(longPress);
            longPress = null;
        }
        if (passing) {
            passing = false;
            held.add(event);
            replay();
            return;
        }
        if (wasLong) {
            wasLong = false;
            return;
        }
        if (holdFired) {   // the hold already counted; its release goes where the press went
            holdFired = false;
            if (heldDown) {
                heldDown = false;
                out.fire("release");   // a hold binding's "release" (e.g. ends a push-to-talk assistant)
            }
            if (holdsBack() && !replacing.contains("hold")) {
                held.add(event);
                replay();
            } else {
                held.clear();
            }
            return;
        }
        if (holdsBack()) held.add(event);
        decide();
    }

    /** a press is over (not a hold): a short binding fires; the car gets it unless that replaces */
    void decide() {
        int n = count;
        count = 0;
        if (n == 0) return;
        String press = "short";
        boolean taken = false;
        if (presses.contains(press)) {
            out.fire(press);
            taken = replacing.contains(press);
        }
        if (taken) held.clear();
        else replay();
    }

    void replay() {
        if (held.isEmpty()) return;
        List<Object> l = new ArrayList<Object>(held);
        held.clear();
        out.replay(l);
    }
}
