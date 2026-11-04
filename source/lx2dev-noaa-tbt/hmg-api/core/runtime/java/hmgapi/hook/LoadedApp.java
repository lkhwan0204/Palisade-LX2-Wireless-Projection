package hmgapi.hook;

import android.content.Context;

/** An app process that has just started: what {@link ModEntry#appLoaded} gets. */
public final class LoadedApp {
    public final String packageName;
    public final String processName;
    public final ClassLoader classLoader;
    /** The app's Application (null if it has none yet). */
    public final Context context;

    LoadedApp(String packageName, String processName, ClassLoader classLoader, Context context) {
        this.packageName = packageName;
        this.processName = processName;
        this.classLoader = classLoader;
        this.context = context;
    }
}
