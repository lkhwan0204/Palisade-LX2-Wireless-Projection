package hmgapi.hook;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.IBinder;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Mods add choices to the stock ★-key settings lists; the owner picks one there.
 *
 * ButtonSettings has a list screen per key: CustomButtonSetting (head unit,
 * "Nav" ★) and CustomButtonSettingSWRC (steering wheel ★). Both keep
 * mMenuList (CustomItem(titleId, summaryId, iconId, action)), build the visible
 * rows in initList() from hard-coded title-id checks (unknown ids are skipped),
 * and on a tap save getShortCut(titleId) -- the CustomItem's action -- with
 * setCustomKey / setKey. If no row matches the saved action, initList ticks
 * "None" and initCustomKey() resets the key.
 *
 * HMG-API, in the ButtonSettings process: before initList, adds a CustomItem
 * per mod action (action hmgapi.star.<mod>.<id>, made-up ids 0x7f7d00xx); after
 * it, appends the rows with the screen's own getListItem and fixes the tick;
 * and skips initCustomKey while the saved action belongs to an installed mod.
 * Resources.getText/getDrawable answer the made-up ids (HMG-API's resource hooks).
 *
 * On a press, ModeManagerService sends the saved action as startService + an
 * ordered broadcast; HMG-API's receiver in the launcher process (always
 * running) runs the mod's action. At launcher start, a key whose saved action
 * belongs to a removed mod is set back to the stock "None".
 */
final class StarKey {
    private StarKey() {}

    static final String PREFIX = "hmgapi.star.";
    static final String WHEEL_NONE = "com.mobis.intent.action.SWRC_CUSTOM_SHORTCUT";
    static final String HEAD_NONE = "com.mobis.intent.action.CUSTOM_SHORTCUT";

    static Mods.StarAction forAction(String action) {
        if (action == null || !action.startsWith(PREFIX)) return null;
        for (Mods.StarAction a : Mods.starActions) {
            if (a.action.equals(action)) return a;
        }
        return null;
    }

    static Mods.StarAction forResource(int id) {
        if ((id & 0xffff0000) != 0x7f7d0000) return null;
        for (Mods.StarAction a : Mods.starActions) {
            if (id == a.titleId || id == a.summaryId || id == a.iconId) return a;
        }
        return null;
    }

    // ---- launcher process: receive presses, clean up after removed mods ----

    static void start(Context ctx) {
        Context app = ctx.getApplicationContext();
        cleanUp(app);
        if (Mods.starActions.isEmpty()) return;
        IntentFilter f = new IntentFilter();
        for (Mods.StarAction a : Mods.starActions) f.addAction(a.action);
        app.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                Mods.StarAction a = forAction(intent.getAction());
                if (a != null) run(c, a);
            }
        }, f);
        HmgApi.i("star key: " + Mods.starActions.size() + " mod actions offered in button settings");
    }

    static void cleanUp(Context ctx) {
        try {
            String wheel = prop("persist.sys.key.swrccustom", "");
            if (wheel.startsWith(PREFIX) && forAction(wheel) == null) {
                setProp("persist.sys.key.swrccustom", WHEEL_NONE);
                HmgApi.i("star key (wheel): " + wheel + " belongs to a removed mod, set to None");
            }
            String head = (String) mode("getCustomKeyAction");
            if (head != null && head.startsWith(PREFIX) && forAction(head) == null) {
                mode("setCustomKey", HEAD_NONE);
                HmgApi.i("star key (head unit): " + head + " belongs to a removed mod, set to None");
            }
        } catch (Throwable t) {
            HmgApi.e("star key clean-up", t);
        }
    }

    static void run(Context c, Mods.StarAction a) {
        try {
            if (a.className != null) {
                Intent i = new Intent(Intent.ACTION_MAIN);
                i.setComponent(new ComponentName(a.packageName, a.className));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
            } else {
                c.sendBroadcast(new Intent(a.broadcast).addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES));
            }
            Bridge.log(4, "[" + a.mod + "] star key: " + a.id);
        } catch (Throwable t) {
            HmgApi.e("[" + a.mod + "] star key " + a.id, t);
        }
    }

    // ---- ButtonSettings process: the choices in the two list screens ----

    static void settingsScreens(ClassLoader cl) throws Exception {
        if (Mods.starActions.isEmpty()) return;
        Constructor<?> newItem = Points.ctor(cl, "buttonsettings.item.new");
        for (String name : new String[] { "buttonsettings.head_screen", "buttonsettings.wheel_screen" }) {
            try {
                screen(cl, name, newItem);
            } catch (Throwable t) {
                HmgApi.e("star key screen " + name, t);
            }
        }
        HmgApi.hookEntryResources();
    }

    /** One of the two ★ key screens ("buttonsettings.head_screen" / "buttonsettings.wheel_screen"). */
    static void screen(final ClassLoader cl, final String s, final Constructor<?> newItem) throws Exception {
        final Field menu = Points.field(cl, s + ".menu_list"), cur = Points.field(cl, s + ".cur_list"),
                titles = Points.field(cl, s + ".title_list"), checked = Points.field(cl, s + ".checked_list"),
                adapter = Points.field(cl, s + ".adapter"), keycode = Points.field(cl, s + ".keycode");
        final Method listItem = Points.method(cl, s + ".list_item");
        final Method select = Points.method(cl, s + ".set_selected");
        final String name = s;

        Points.hook(cl, s + ".init_list", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) throws Throwable {
                HmgApi.appContext = (Context) p.thisObject;
                @SuppressWarnings("unchecked")
                List<Object> items = (List<Object>) menu.get(p.thisObject);
                for (Mods.StarAction a : available((Context) p.thisObject)) {
                    if (indexOf(cl, items, a) < 0) items.add(newItem.newInstance(a.titleId, a.summaryId, a.iconId, a.action));
                }
            }

            @Override
            protected void afterHookedMethod(Param p) throws Throwable {
                Object self = p.thisObject;
                @SuppressWarnings("unchecked")
                List<Object> items = (List<Object>) menu.get(self);
                @SuppressWarnings("unchecked")
                List<Object> rows = (List<Object>) cur.get(self);
                int[] t = (int[]) titles.get(self);
                int ours = -1;
                List<Mods.StarAction> offered = available((Context) self);
                for (Mods.StarAction a : offered) {
                    int row = rows.size();
                    rows.add(listItem.invoke(self, a.titleId, a.action, indexOf(cl, items, a)));
                    if (row >= t.length) {
                        int[] n = new int[row + 1];
                        System.arraycopy(t, 0, n, 0, t.length);
                        t = n;
                    }
                    t[row] = a.titleId;
                    if (a.action.equals(keycode.get(null))) ours = row;
                }
                titles.set(self, t);
                boolean[] c = new boolean[rows.size()];
                for (int i = 0; i < rows.size(); i++) {
                    Object r = rows.get(i);
                    if (ours >= 0) Points.call(cl, "buttonsettings.row.set_check", r, i == ours);   // initList ticked "None"
                    c[i] = (Boolean) Points.call(cl, "buttonsettings.row.is_check", r);
                }
                checked.set(self, c);
                Object ad = adapter.get(self);
                Points.call(cl, "buttonsettings.adapter.set_boolean_list", ad, (Object) c);
                Points.call(cl, "buttonsettings.adapter.set_list_items", ad, rows);
                ((android.widget.BaseAdapter) ad).notifyDataSetChanged();   // Android's own method
                if (ours >= 0) select.invoke(self, ours);
                HmgApi.i("star key: " + name + " shows " + offered.size() + " mod actions"
                        + (ours >= 0 ? ", selected: " + keycode.get(null) : ""));
            }
        });

        Points.hook(cl, s + ".init_custom_key", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) throws Throwable {
                Object k = keycode.get(null);
                if (forAction(k instanceof String ? (String) k : null) != null) {
                    p.setResult(null);   // a mod's action is selected: don't reset the key to default
                }
            }
        });
    }

    /** Actions whose activity is installed (broadcast actions always are). */
    static List<Mods.StarAction> available(Context ctx) {
        List<Mods.StarAction> out = new java.util.ArrayList<Mods.StarAction>();
        for (Mods.StarAction a : Mods.starActions) {
            if (a.className == null) {
                out.add(a);
                continue;
            }
            try {
                ctx.getPackageManager().getActivityInfo(new ComponentName(a.packageName, a.className), 0);
                out.add(a);
            } catch (Exception e) {
                // not installed: not offered
            }
        }
        return out;
    }

    static int indexOf(ClassLoader cl, List<Object> items, Mods.StarAction a) {
        for (int i = 0; i < items.size(); i++) {
            if (a.action.equals(Points.call(cl, "buttonsettings.item.get_shortcut", items.get(i)))) return i;
        }
        return -1;
    }

    /** Text for the made-up title and summary ids. */
    static CharSequence text(Mods.StarAction a, int id) {
        return id == a.summaryId ? a.modName : a.title;
    }

    static Drawable icon(Mods.StarAction a) {
        Context ctx = HmgApi.appContext;
        if (ctx != null && a.className != null) {
            try {
                Drawable d = ctx.getPackageManager().getActivityIcon(new ComponentName(a.packageName, a.className));
                Drawable.ConstantState cs = d.getConstantState();
                return cs != null ? cs.newDrawable(ctx.getResources()) : d;
            } catch (Exception e) {
                // fall back below
            }
        }
        return Resources.getSystem().getDrawable(android.R.drawable.sym_def_app_icon);
    }

    // ---- system properties and the mode service ----

    static Object mode(String method, String... args) throws Exception {
        IBinder b = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class)
                .invoke(null, "com.hkmc.micom.mode");
        if (b == null) throw new IllegalStateException("no com.hkmc.micom.mode service");
        Object svc = Class.forName("com.hkmc.mode.IModeManager$Stub").getMethod("asInterface", IBinder.class)
                .invoke(null, b);
        Class<?> api = Class.forName("com.hkmc.mode.IModeManager");   // the proxy class itself is private
        if (args.length == 0) return api.getMethod(method).invoke(svc);
        return api.getMethod(method, String.class).invoke(svc, args[0]);
    }

    static String prop(String key, String def) throws Exception {
        Method m = Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class);
        return (String) m.invoke(null, key, def);
    }

    static void setProp(String key, String value) throws Exception {
        Method m = Class.forName("android.os.SystemProperties").getMethod("set", String.class, String.class);
        m.invoke(null, key, value);
    }
}
