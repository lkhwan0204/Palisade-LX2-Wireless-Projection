package hmgapi.hook;

/**
 * The parent of a mod's app part (ModContext.appPart): every class but the part's own
 * package comes from the mod's main loader (so the part shares the mod's classes and
 * statics, and sees HMG-API's API), then from the app's class loader (the app's own
 * classes and the libraries it declares, e.g. com.tcc.iap2). The part's package itself
 * is never found here, so the DexClassLoader under this one defines it from the mod's
 * jar, linked against the app.
 */
final class AppPartLoader extends ClassLoader {
    final String prefix;
    final ClassLoader mod, app;

    AppPartLoader(String pkg, ClassLoader mod, ClassLoader app) {
        super(mod);   // never asked (loadClass below decides); Dalvik refuses a null parent
        this.prefix = pkg + ".";
        this.mod = mod;
        this.app = app;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (name.startsWith(prefix)) throw new ClassNotFoundException(name);
        try {
            return mod.loadClass(name);
        } catch (ClassNotFoundException e) {
            return app.loadClass(name);
        }
    }
}
