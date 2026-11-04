package hmgapi.hook;

import android.content.Context;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * HMG-API's cluster API: the instrument cluster and HUD through the car's own
 * NavigationCluster service (framework points framework.navigation_cluster.*), the
 * same calls the car's navigation and the projection apps make. Mods send turn-by-turn
 * here instead of reaching into NavigationCluster themselves.
 *
 *   Cluster c = Cluster.of(context);
 *   c.tbtInfo(67, 65535, 90f, 0.2f, 0, 0, Cluster.UNIT_MI, 32, 0);
 *
 * Every call is a no-op when the service can't be made, and never throws: the cluster
 * is a display, a failed update mustn't take the calling app down.
 */
public final class Cluster {
    /** NavigationCluster's distance units */
    public static final int UNIT_M = 0, UNIT_KM = 1, UNIT_MI = 2, UNIT_FT = 3, UNIT_YD = 4;
    static final String NC = "framework.navigation_cluster";

    private final Object nc;
    private static final Map<String, Method> methods = new HashMap<String, Method>();

    private Cluster(Object nc) {
        this.nc = nc;
    }

    /** The cluster service for this app (null if this car's framework can't make one). */
    public static Cluster of(Context c) {
        try {
            return new Cluster(Points.newInstance(null, NC + ".new", c));
        } catch (Throwable t) {
            HmgApi.i("cluster: no NavigationCluster (" + t + ")");
            return null;
        }
    }

    private void call(String point, Object... args) {
        try {
            Method m;
            synchronized (methods) {
                m = methods.get(point);
                if (m == null) methods.put(point, m = Points.method(null, NC + "." + point));
            }
            m.invoke(nc, args);
        } catch (Throwable t) {
            HmgApi.i("cluster: " + point + " failed (" + t + ")");
        }
    }

    /** NavigationCluster.updateTBTInfo: the next turn (type, side street, direction, distance, ...). */
    public void tbtInfo(int type, int sideStreet, float direction, float distance, int distanceErr, int combiSideStreet,
            int unit, int barGraph, int barGraphErr) {
        call("update_tbt_info", type, sideStreet, direction, distance, distanceErr, combiSideStreet, unit, barGraph, barGraphErr);
    }

    /** NavigationCluster.updateTBTSubInfo (10 values): remaining distance, arrival time, destination point. */
    public void tbtSubInfo(float distance, int distanceErr, int unit, int hour, int minute, int timeType, int timeFormat,
            float direction, int directionErr, int estPoint) {
        call("update_tbt_sub_info_b", distance, distanceErr, unit, hour, minute, timeType, timeFormat, direction, directionErr, estPoint);
    }

    /** NavigationCluster.updateTBTSubInfo2C: the HUD's distance (unit, tenths, whole) and the rest zero. */
    public void tbtSubInfo2C(int a, int b, int c, int d, int e, int f, int g, int h, int i) {
        call("update_tbt_sub_info_2c", a, b, c, d, e, f, g, h, i);
    }

    /** NavigationCluster.updateTPTurnListNew: the HUD's turn list frame (raw bytes, first byte 0xF2). */
    public void turnListNew(byte[] frame) {
        call("update_tp_turn_list_new", (Object) frame);
    }

    /** NavigationCluster.updateLaneInfo: lanes as a raw frame (81 bytes, first byte 0xF1). */
    public void laneInfo(byte[] frame) {
        call("update_lane_info", (Object) frame);
    }

    /** NavigationCluster.updateTPFlexibleDestinationName. */
    public void destinationName(int type, String name) {
        try {
            Object d = Points.newInstance(null, "framework.destination_name.new");
            Points.field(null, "framework.destination_name.type").setInt(d, type);
            Points.field(null, "framework.destination_name.name").set(d, name);
            call("update_tp_destination_name", d);
        } catch (Throwable t) {
            HmgApi.i("cluster: destination name failed (" + t + ")");
        }
    }

    /** NavigationCluster.updateTPCrossroadName. */
    public void crossroadName(String name) {
        try {
            Object d = Points.newInstance(null, "framework.crossroad_name.new");
            Points.field(null, "framework.crossroad_name.name").set(d, name);
            call("update_tp_crossroad_name", d);
        } catch (Throwable t) {
            HmgApi.i("cluster: crossroad name failed (" + t + ")");
        }
    }

    /** One upcoming turn for turnList (NavigationCluster.TurnList). */
    public static final class Turn {
        public int type, direction, traffic, no, unit;
        public String name = "", exitNumber = "";
        public float distance;
    }

    /** NavigationCluster.updateTPTurnList: the cluster's list of upcoming turns. */
    public void turnList(Turn[] turns) {
        try {
            Class<?> tl = Points.cls(null, "framework.turn_list");
            Object a = java.lang.reflect.Array.newInstance(tl, turns.length);
            for (int k = 0; k < turns.length; k++) {
                Turn t = turns[k];
                Object o = Points.newInstance(null, "framework.turn_list.new");
                Points.field(null, "framework.turn_list.type").setInt(o, t.type);
                Points.field(null, "framework.turn_list.direction").setInt(o, t.direction);
                Points.field(null, "framework.turn_list.traffic").setInt(o, t.traffic);
                Points.field(null, "framework.turn_list.no").setInt(o, t.no);
                Points.field(null, "framework.turn_list.name").set(o, t.name);
                Points.field(null, "framework.turn_list.exit_number").set(o, t.exitNumber);
                Points.field(null, "framework.turn_list.distance").setFloat(o, t.distance);
                Points.field(null, "framework.turn_list.unit").setInt(o, t.unit);
                java.lang.reflect.Array.set(a, k, o);
            }
            call("update_tp_turn_list", a);
        } catch (Throwable t) {
            HmgApi.i("cluster: turn list failed (" + t + ")");
        }
    }
}
