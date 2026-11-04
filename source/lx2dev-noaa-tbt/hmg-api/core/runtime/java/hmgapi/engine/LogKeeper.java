package hmgapi.engine;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * HMG-API's log keeper, run by hmgapid as HMG-API's own service "log" from boot: its
 * output is HMG-API's log tag (every process: the zygote's hooks, system_server, apps,
 * mods' "[mod] ..." lines) as logcat sees it, which hmgapid keeps in
 * /data/hmgapi/logs/hmgapi/log.log (256 KB, then .1). logcat itself holds only seconds
 * on these units (the CAN status lines fill it), so this is what the app's Logs tab
 * and the Car Report read. Lines are kept as they are: the Car Report scrubs what it
 * takes from them.
 */
public final class LogKeeper {
    private LogKeeper() {}

    public static void main(String[] a) throws Exception {
        Process p = new ProcessBuilder("/system/bin/logcat", "-v", "time", "-s", "HMG-API:V").redirectErrorStream(true).start();
        BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
        String l;
        while ((l = in.readLine()) != null) {
            if (l.startsWith("---------")) continue;   // logcat's buffer headers
            System.out.println(l);
        }
        System.exit(p.waitFor());   // restarted by hmgapid
    }
}
