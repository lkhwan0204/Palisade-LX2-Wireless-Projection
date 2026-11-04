package hmgapi.hook;

import java.io.File;
import java.io.IOException;

/** Dalvik names optimized files by JAR basename, so mods cannot share a directory. */
final class ModDexCache {
    private ModDexCache() {}

    static File directory(File parent, String id) throws IOException {
        if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IOException("invalid mod cache id: " + id);
        }
        File dir = new File(parent, id);
        if (!parent.getCanonicalFile().equals(dir.getCanonicalFile().getParentFile())) {
            throw new IOException("mod cache outside its parent: " + id);
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("can't create mod cache: " + dir);
        }
        return dir;
    }
}
