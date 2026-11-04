package hmgapi.hook;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Apps a mod marks "not_while_driving" (mod.json): they follow the car's own driving
 * restriction, as the stock video apps do. While it's on, the app's screens close
 * and the car shows its own popup; they can't be opened again until it's off. The
 * car decides when the restriction applies (speed, region; the dealer setting that
 * turns it off is respected), not HMG-API.
 */
final class Driving {
    private Driving() {}

    static final String ENTER = "android.mobis.regulation.ACTION_ENTER_REGULATION";

    static Activity resumed;

    /** In every app's process: is it one a mod (on) marked, per public/state.json? */
    static void appLoaded(LoadedApp app) {
        if (!(app.context instanceof Application) || !marked(app.packageName)) return;
        final Application a = (Application) app.context;
        a.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(Activity act) {
                resumed = act;
                if (restricted(act)) close(act);
            }

            @Override
            public void onActivityPaused(Activity act) {
                if (resumed == act) resumed = null;
            }

            @Override
            public void onActivityCreated(Activity act, Bundle b) {}

            @Override
            public void onActivityStarted(Activity act) {}

            @Override
            public void onActivityStopped(Activity act) {}

            @Override
            public void onActivitySaveInstanceState(Activity act, Bundle b) {}

            @Override
            public void onActivityDestroyed(Activity act) {}
        });
        a.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                Activity act = resumed;
                if (act != null) close(act);
            }
        }, new IntentFilter(ENTER));
        HmgApi.i("driving: " + app.packageName + " follows the car's driving restriction");
    }

    static boolean marked(String pkg) {
        try {
            JSONObject mods = new JSONObject(Mods.read(Live.STATE)).optJSONObject("mods");
            if (mods == null) return false;
            for (java.util.Iterator<?> it = mods.keys(); it.hasNext(); ) {
                JSONObject m = mods.optJSONObject(String.valueOf(it.next()));
                if (m == null || !m.optBoolean("enabled")) continue;
                JSONArray l = m.optJSONArray("not_while_driving");
                for (int i = 0; l != null && i < l.length(); i++) if (pkg.equals(l.optString(i))) return true;
            }
        } catch (Exception e) {
            // no state yet: nothing marked
        }
        return false;
    }

    static Object manager(Context c) {
        return Points.call(null, "framework.regulation_manager.get_instance", null, c.getApplicationContext());
    }

    static boolean restricted(Context c) {
        try {
            return Integer.valueOf(1).equals(Points.call(null, "framework.regulation_manager.status", manager(c)));
        } catch (Throwable t) {
            HmgApi.e("driving: restriction status", t);
            return false;
        }
    }

    static void close(Activity act) {
        try {
            CharSequence label = act.getApplicationInfo().loadLabel(act.getPackageManager());
            Points.call(null, "framework.regulation_manager.show_popup", manager(act), label + " isn't available while driving.");
        } catch (Throwable t) {
            HmgApi.e("driving: popup", t);
        }
        act.finishAffinity();
        HmgApi.i("driving: closed " + act.getPackageName() + " (driving restriction)");
    }
}
