package hmgapi.hook;

import java.util.ArrayList;
import java.util.List;

/**
 * The parent of a mod's class loader: HMG-API's runtime (hmgapi.hook), then the API
 * packages exported by the mods it requires (mod.json "api": {"packages": [...]}).
 * So a mod uses another mod's API classes directly, the same classes that mod uses,
 * and sees nothing of it but what it exports.
 */
final class ApiLoader extends ClassLoader {
    static final class Export {
        final String mod;
        final List<String> packages;
        final ClassLoader loader;

        Export(String mod, List<String> packages, ClassLoader loader) {
            this.mod = mod;
            this.packages = packages;
            this.loader = loader;
        }
    }

    final List<Export> exports = new ArrayList<Export>();

    ApiLoader(ClassLoader runtime) {
        super(runtime);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        for (Export e : exports) {
            for (String p : e.packages) {
                if (name.startsWith(p + ".")) return e.loader.loadClass(name);
            }
        }
        throw new ClassNotFoundException(name);
    }
}
