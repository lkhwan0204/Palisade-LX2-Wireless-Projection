package hmgapi.hook;

/**
 * Base class for a mod's hook code (the "entry" named in its mod.json).
 * HMG-API loads the mod's hooks.jar in the zygote and creates one instance.
 * Like an Xposed module: hook framework classes in {@link #zygoteInit}, and an
 * app's own classes in {@link #appLoaded} once its class loader exists.
 * Exceptions are caught and logged with the mod's id; they don't affect other mods.
 */
public abstract class ModEntry {
    /** Once, in the zygote, before any app starts. */
    public void zygoteInit(ModContext mod) throws Throwable {}

    /** In each app process, after its Application is created. */
    public void appLoaded(ModContext mod, LoadedApp app) throws Throwable {}

    /**
     * The owner changed this mod's settings (mod.setting() has the new values), in each
     * running app process. Without this, a setting change applies at the next start.
     */
    public void onSettingsChanged(ModContext mod) throws Throwable {}

    /** The mod was turned on or off while apps run (its hooks already pass through while off). */
    public void onEnabled(ModContext mod, boolean enabled) throws Throwable {}
}
