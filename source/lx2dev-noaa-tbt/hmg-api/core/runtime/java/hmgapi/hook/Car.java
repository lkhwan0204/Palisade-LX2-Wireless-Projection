package hmgapi.hook;

import android.content.Context;

/**
 * HMG-API's car API: plain answers about the head unit, built only on what the car
 * already offers (its framework services and build info, through HMG-API's hook
 * points). Mods ask here instead of reaching into the car's classes themselves, so
 * a difference between cars or software is handled once, in HMG-API.
 */
public final class Car {
    private Car() {}

    public static final int REGION_US = 1, REGION_CANADA = 4;
    public static final int DISTANCE_KM = 0, DISTANCE_MILES = 1;

    /** Hyundai/Kia's region code for this head unit (1 US, 4 Canada), or -1 if unknown. */
    public static int region() {
        try {
            return (Integer) Points.get(null, "framework.build_info.region", null);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Is Android Auto guiding (its turn-by-turn is on)? As the car's navigation asks GPM. */
    public static boolean androidAutoGuiding(Context c) {
        if (c == null) return false;
        try {
            Object gpm = Points.newInstance(null, "framework.gpm_manager.new", c);
            return Boolean.TRUE.equals(Points.call(null, "framework.gpm_manager.is_navi_guidance", gpm));
        } catch (Throwable t) {
            return false;
        }
    }

    /** Is CarPlay guiding (DiPO's turn-by-turn mode)? */
    public static boolean carPlayGuiding(Context c) {
        if (c == null) return false;
        try {
            Object dipo = c.getSystemService("dipo");
            return dipo != null && Boolean.TRUE.equals(Points.call(null, "framework.dipo_manager.is_turn_by_turn", dipo));
        } catch (Throwable t) {
            return false;
        }
    }

    /** Is a phone guiding (Android Auto or CarPlay)? */
    public static boolean phoneGuiding(Context c) {
        return androidAutoGuiding(c) || carPlayGuiding(c);
    }

    /**
     * Is the phone's assistant open (Android Auto's, or Siri), with no phone call? As the
     * car's voice button decides (ModeKeyDispatcher): then the button goes to the phone only,
     * a short press ends the assistant and a long press starts it again.
     */
    public static boolean phoneAssistantOpen(Context c) {
        if (c == null) return false;
        try {
            Object dipo = c.getSystemService("dipo");
            if (dipo != null && Integer.valueOf(1).equals(Points.call(null, "framework.dipo_manager.current_stat", dipo))) {
                Object mode = Points.call(null, "framework.dipo_manager.current_mode", dipo);
                return mode != null && Integer.valueOf(0).equals(Points.call(null, "framework.dipo_mode.phone_call", mode))
                        && Integer.valueOf(1).equals(Points.call(null, "framework.dipo_mode.speech", mode));
            }
            Object gpm = Points.newInstance(null, "framework.gpm_manager.new", c);
            return Boolean.TRUE.equals(Points.call(null, "framework.gpm_manager.is_activated", gpm))
                    && !Boolean.TRUE.equals(Points.call(null, "framework.gpm_manager.is_call", gpm))
                    && Boolean.TRUE.equals(Points.call(null, "framework.gpm_manager.is_vr", gpm));
        } catch (Throwable t) {
            return false;
        }
    }

    /** The owner's distance unit (Settings, through the car's USM service): DISTANCE_KM, DISTANCE_MILES, or -1. */
    public static int distanceUnit(Context c) {
        if (c == null) return -1;
        try {
            Object usm = Points.newInstance(null, "framework.usm_manager.new", c);
            return (Integer) Points.call(null, "framework.usm_manager.distance_speed_unit", usm);
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * The car's ro.product.opt.stepofspec: the feature level the Hyundai/Kia apps check
     * (US/Canada: 105; many projection features are coded for 999). Read only: HMG-API
     * never changes it, mods turn on single features with hooks instead. -1 if unknown.
     */
    public static int stepOfSpec() {
        try {
            return (Integer) Points.call(null, "framework.system_properties.get_int", null, "ro.product.opt.stepofspec", -1);
        } catch (Throwable t) {
            return -1;
        }
    }
}
