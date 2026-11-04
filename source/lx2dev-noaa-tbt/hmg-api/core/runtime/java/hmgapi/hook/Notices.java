package hmgapi.hook;

import android.content.Context;
import android.content.Intent;

/**
 * The car's own on-screen notices, for mods: the same toast the stock apps show
 * (over any app, CarPlay and Android Auto included). Any process may send it.
 * A mod's own app uses hmgapi.client.HmgApi.toast, which does the same.
 */
public final class Notices {
    private Notices() {}

    public static final String ACTION = "com.hkmc.intent.action.TOAST";

    public static void toast(Context ctx, String text) {
        ctx.sendBroadcast(new Intent(ACTION)
                .putExtra("com.hkmc.intent.extra.TOAST_MESSAGE", text)
                .putExtra("com.hkmc.intent.extra.TOAST_POPUPSHOWING", true));
    }
}
