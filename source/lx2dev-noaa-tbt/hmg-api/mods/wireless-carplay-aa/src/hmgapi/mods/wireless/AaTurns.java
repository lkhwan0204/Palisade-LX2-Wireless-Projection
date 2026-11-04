package hmgapi.mods.wireless;

import android.content.Context;
import android.os.Handler;
import android.text.format.DateFormat;
import android.util.Log;

import java.util.Calendar;

import hmgapi.hook.Car;
import hmgapi.hook.Cluster;

/**
 * Android Auto turn-by-turn on the cluster and HUD, for one Android Auto connection.
 * GPM has its own (GpmNaviTBTManager), but makes it only when stepofspec is 999 or
 * more, and that one also claims the cluster (alive + status), which these cars never
 * give back. This is the turn-by-turn build's manager in Java, fed by the AA library's
 * callbacks (gpm.navigation_status.*) and sending through HMG-API's Cluster API: turn,
 * distance, HUD turn list and distance, lanes, the next turns, remaining distance and
 * arrival time, destination name. It never sends alive or operation status.
 *
 * The AA library (the Wireless mod's library delta) packs more than stock into the
 * callbacks: next-turn events 32 (destination name; number = destination point),
 * 33 (debug dump), 34 (lanes, hex string), 64..66 (turns ahead: road, side, angle,
 * number = event | exit << 8), 67 (end of the turns ahead, number = how many); and
 * distance flags 0x200 (a turn ahead, index in bits 10-11) and 0x100 (remaining trip:
 * meters, seconds, unit, 0x800 + point << 12).
 *
 * Everything runs on one thread (the handler's), in the order the callbacks came.
 */
final class AaTurns {
    // AA protocol numbers (Protos.NavigationNextTurnEvent)
    static final int UNKNOWN = 0, DEPART = 1, NAME_CHANGE = 2, SLIGHT_TURN = 3, TURN = 4, SHARP_TURN = 5, U_TURN = 6,
            ON_RAMP = 7, OFF_RAMP = 8, FORK = 9, MERGE = 10, ROUNDABOUT_ENTER = 11, ROUNDABOUT_EXIT = 12,
            ROUNDABOUT_ENTER_AND_EXIT = 13, STRAIGHT = 14, FERRY_BOAT = 16, FERRY_TRAIN = 17, DESTINATION = 19;
    static final int LEFT = 1, RIGHT = 2, UNSPECIFIED = 3;
    static final int METERS = 1, KILOMETERS = 2, KILOMETERS_P1 = 3, MILES = 4, MILES_P1 = 5, FEET = 6, YARDS = 7;
    // the library's extra events and flags
    static final int EV_DEST_NAME = 32, EV_DUMP = 33, EV_LANES = 34, EV_AHEAD = 64, EV_AHEAD_END = 67;
    static final int FLAG_TRIP = 0x100, FLAG_AHEAD = 0x200, FLAG_POINT = 0x800;
    static final byte HUD_LIST = (byte) 0xF2, LANES = (byte) 0xF1;

    final Context context;
    final Cluster cluster;
    final Handler handler;
    final Runnable tick = new Runnable() {
        @Override
        public void run() {
            unitTick();
        }
    };

    // the next turn
    int event, turnSide, turnAngle, turnNumber, displayType, direction, unit;
    String road;
    float distance;
    boolean navLive;
    // destination and the remaining trip
    String destName;
    int estPoint;
    int tripMeters = -1, tripSeconds = -1, tripUnit, tripGen, sentGen;
    int lastUsm = -2;
    // HUD turn list (what was sent last)
    int hudType = -1, hudNext;
    boolean hudFresh;
    String hudRoad, laneKey;
    // the turns ahead (index 0..2)
    final String[] aheadName = new String[3];
    final int[] aheadEv = new int[3], aheadSide = new int[3], aheadAng = new int[3], aheadExit = new int[3];
    final int[] aheadMeters = new int[3], aheadUnit = new int[3], aheadE3 = new int[3];
    final boolean[] aheadOk = new boolean[3];

    AaTurns(Context context, Cluster cluster, Handler handler) {
        this.context = context;
        this.cluster = cluster;
        this.handler = handler;
    }

    // ---- from the AA library

    void nextTurn(String str, int side, int ev, int angle, int number) {
        if (ev == EV_DUMP) {
            Log.d("NAVDUMP", String.valueOf(str));
            hmgapi.hook.ProjectionHooks.navigationDump(str);   // to mods that asked for projection events (EGMP's route data)
            return;
        }
        if (ev == EV_LANES) {
            sendLanes(str);
            return;
        }
        if (ev == EV_DEST_NAME) {
            if (str == null || str.length() == 0) {
                destName = "";
                sendDestName();
                destName = null;
                return;
            }
            if (number >= 0) estPoint = number;
            if (!str.equals(destName)) {
                destName = str;
                sendDestName();
            }
            return;
        }
        if (ev == EV_AHEAD_END) {
            if (number > 0) sendAhead(Math.min(number, 3));
            return;
        }
        if (ev >= EV_AHEAD && ev < EV_AHEAD_END) {
            int k = ev - EV_AHEAD;
            aheadName[k] = str == null ? "" : str;
            aheadSide[k] = side;
            aheadAng[k] = angle;
            aheadEv[k] = number & 0xff;
            aheadExit[k] = (number >> 8) & 0xff;
            aheadOk[k] = false;
            return;
        }
        hudFresh = false;
        boolean first = road == null;
        event = ev;
        road = str;
        turnSide = side;
        turnAngle = angle;
        turnNumber = number;
        switch (ev) {
            case DEPART:
                if (first) {
                    cluster.destinationName(0, road);
                    distance = -1f;
                } else {
                    cluster.crossroadName(road);
                    setTurnType(ev, turnSide);
                }
                break;
            case NAME_CHANGE: case SLIGHT_TURN: case TURN: case SHARP_TURN: case STRAIGHT: case MERGE:
                cluster.crossroadName(road);
                setTurnType(ev, turnSide);
                break;
            case U_TURN:
                if (side == UNSPECIFIED) turnSide = LEFT;
                cluster.crossroadName(road);
                setTurnType(ev, turnSide);
                break;
            case ON_RAMP: case OFF_RAMP: case FORK: case FERRY_BOAT:
                cluster.crossroadName(road);
                setRampType(ev, turnSide);
                break;
            case ROUNDABOUT_EXIT: case ROUNDABOUT_ENTER_AND_EXIT:
                cluster.crossroadName(road);
                setRoundaboutType(ev, turnSide, turnAngle);
                break;
            case DESTINATION:
                displayType = 3;
                break;
            case UNKNOWN:
                displayType = 64;
                break;
            default:
                return;   // ROUNDABOUT_ENTER, FERRY_TRAIN: not for the cluster
        }
        sendTurn();
    }

    void nextTurnDistance(int meters, int seconds, int e3, int unitFlags) {
        if ((unitFlags & FLAG_AHEAD) != 0) {
            int k = (unitFlags >> 10) & 3;
            if (k > 2) return;
            aheadMeters[k] = meters;
            aheadE3[k] = e3;
            aheadUnit[k] = unitFlags & 0xff;
            aheadOk[k] = true;
            return;
        }
        if ((unitFlags & FLAG_TRIP) != 0) {
            if ((unitFlags & FLAG_POINT) != 0) {
                int p = (unitFlags >> 12) & 15;
                estPoint = p > 4 ? 0 : p;
            }
            tripMeters = meters;
            tripSeconds = seconds;
            tripUnit = unitFlags & 0xff;
            tripGen++;
            sendRemain();
            return;
        }
        setDistance(meters, e3, unitFlags);
        setTurnType(event, turnSide);
        sendTurn();
    }

    void status(int s) {
        if (s == 1 || s == 3) {   // active, rerouting
            navLive = true;
            return;
        }
        handler.removeCallbacks(tick);
        destName = null;
        navLive = false;
        tripMeters = -1;
        tripSeconds = -1;
        estPoint = 0;
        release();
    }

    /** The car's navigation took over (focus native): forget the turn. */
    void stop() {
        event = 0;
        road = null;
        turnSide = 0;
        turnAngle = 0;
        turnNumber = 0;
        unit = 0;
        displayType = 0;
        direction = 0;
    }

    /** The connection ended: clear what we showed, if a route was on. */
    void end() {
        handler.removeCallbacks(tick);
        if (navLive) status(2);
    }

    // ---- the next turn

    void sendTurn() {
        if (!navLive) return;
        int bar = 32, barErr = 0;
        if (distance < 0f) {
            bar = 254;
            barErr = -2;
            if (displayType < 64) displayType = 2;
        }
        cluster.tbtInfo(displayType, 65535, direction, distance, 0, 0, unit, bar, barErr);
        sendHudList();
        sendHudDistance();
    }

    /** Distance to the next turn, in the owner's unit (Settings) where the phone's differs. */
    void setDistance(int meters, int e3, int u) {
        int usm = Car.distanceUnit(context);
        if (meters >= 0) {
            if (usm == Car.DISTANCE_KM) {
                if (u >= MILES) {
                    if (meters < 1000) {
                        u = METERS;
                    } else {
                        e3 = meters;
                        u = KILOMETERS;
                    }
                }
            } else if (usm == Car.DISTANCE_MILES) {
                if (meters >= 161) {
                    if (u < MILES) {
                        e3 = meters * 1000 / 1609;
                        u = MILES_P1;
                    }
                } else if (u != FEET && u != YARDS) {
                    e3 = meters * 3281;
                    u = FEET;
                }
            }
        }
        distance = (float) (e3 / 1000.0d);
        if (u == METERS) {
            if (meters >= 1000) {
                distance = (float) (meters / 1000.0d);
                unit = Cluster.UNIT_KM;
            } else {
                distance = meters;
                unit = Cluster.UNIT_M;
            }
        } else if (u == KILOMETERS || u == KILOMETERS_P1) {
            unit = Cluster.UNIT_KM;
        } else if (u == MILES || u == MILES_P1) {
            unit = Cluster.UNIT_MI;
        } else if (u == FEET) {
            unit = Cluster.UNIT_FT;
        } else if (u == YARDS) {
            unit = Cluster.UNIT_YD;
        } else {
            unit = 15;
        }
    }

    void setTurnType(int ev, int side) {
        switch (ev) {
            case STRAIGHT: case MERGE:
                displayType = 65;
                direction = 0;
                break;
            case SLIGHT_TURN:
                set(side, 66, 30, 71, 330);
                break;
            case TURN:
                set(side, 67, 90, 70, 270);
                break;
            case SHARP_TURN:
                set(side, 68, 135, 69, 225);
                break;
            case U_TURN:
                displayType = side == RIGHT ? 73 : 72;
                direction = 270;
                break;
            case NAME_CHANGE:
                if (side == RIGHT || side == LEFT) {
                    set(side, 67, 90, 70, 270);
                } else {
                    displayType = 65;
                    direction = 0;
                }
                break;
            default:
                break;   // ramps, roundabouts, destination: set by their own
        }
    }

    /** right type/direction, left type/direction, otherwise 64 (unknown) straight */
    private void set(int side, int rType, int rDir, int lType, int lDir) {
        if (side == RIGHT) {
            displayType = rType;
            direction = rDir;
        } else if (side == LEFT) {
            displayType = lType;
            direction = lDir;
        } else {
            displayType = 64;
            direction = 0;
        }
    }

    void setRampType(int ev, int side) {
        if (ev == ON_RAMP) {
            if (side == RIGHT) {
                displayType = 173;
                direction = 30;
            } else if (side == LEFT) {
                displayType = 174;
                direction = 330;
            } else {
                displayType = 172;
                direction = 0;
            }
        } else if (ev == OFF_RAMP) {
            if (side == RIGHT) {
                displayType = 81;
                direction = 30;
            } else if (side == LEFT) {
                displayType = 82;
                direction = 330;
            } else {
                displayType = 86;
                direction = 0;
            }
        } else if (ev == FORK) {
            if (side == RIGHT) displayType = 173;
            else if (side == LEFT) displayType = 174;
            direction = 0;
        } else if (ev == FERRY_BOAT) {
            displayType = 176;
            direction = 0;
        }
    }

    /** Roundabouts: the exit's angle picks the icon (the cluster draws them for right-hand traffic). */
    void setRoundaboutType(int ev, int side, int a) {
        if (ev == ROUNDABOUT_EXIT) {
            if (side == RIGHT) {
                displayType = 32;
            } else if (side == LEFT) {
                displayType = 33;
            } else {
                displayType = 64;
                direction = 0;
            }
            return;
        }
        if (a == 180) { displayType = 96; direction = 0; }
        else if (a >= 135 && a < 180) { displayType = 97; direction = 45; }
        else if (a >= 90 && a < 135) { displayType = 99; direction = 90; }
        else if (a >= 45 && a < 90) { displayType = 100; direction = 135; }
        else if ((a >= 0 && a < 45) || (a > 315 && a <= 360)) { displayType = 102; direction = 180; }
        else if (a > 270 && a <= 315) { displayType = 103; direction = 225; }
        else if (a > 225 && a <= 270) { displayType = 105; direction = 270; }
        else if (a > 180 && a <= 225) { displayType = 106; direction = 315; }
        else { displayType = 64; direction = 0; }
    }

    // ---- HUD

    /** The HUD's turn list: this turn's icon, the next one's (if known) and the road name, when one of them changed. */
    void sendHudList() {
        int type = displayType;
        if (type < 64) return;
        int next = 0;
        if (aheadOk[0]) {
            next = aheadType(aheadEv[0], aheadSide[0]);
            hudFresh = true;
        } else if (hudFresh) {
            next = hudNext;
        }
        String r = road == null ? "" : road;
        if (hudType == type && hudNext == next && r.equals(hudRoad)) return;
        byte[] name;
        try {
            name = r.getBytes("UTF-16LE");
        } catch (java.io.UnsupportedEncodingException e) {
            return;
        }
        int n = Math.min(name.length, 78);
        byte[] f = new byte[n + 5];
        f[0] = HUD_LIST;
        f[1] = (byte) (next != 0 ? 2 : 1);
        f[2] = (byte) type;
        f[3] = (byte) next;
        System.arraycopy(name, 0, f, 5, n);
        cluster.turnListNew(f);
        hudType = type;
        hudNext = next;
        hudRoad = r;
    }

    /** The HUD's distance: whole number (up to 4095) and tenths for km/mi. */
    void sendHudDistance() {
        float d = distance;
        int u = unit;
        if (d < 0f || u < 0 || u > 4) return;
        int whole = Math.min((int) d, 4095);
        int tenths = 0;
        if (u == Cluster.UNIT_KM || u == Cluster.UNIT_MI) tenths = Math.max(0, Math.min(9, (int) ((d - whole) * 10f + 0.5f)));
        cluster.tbtSubInfo2C(u, tenths, whole, 0, 0, 0, 0, 0, 0);
    }

    void hudBlank() {
        laneKey = "";
        cluster.tbtSubInfo2C(0, 0, 0, 0, 0, 0, 0, 0, 0);
        byte[] lanes = new byte[81];
        lanes[0] = LANES;
        cluster.laneInfo(lanes);
        hudType = -1;
        hudNext = 0;
        hudFresh = false;
        hudRoad = null;
        cluster.turnListNew(new byte[] { HUD_LIST });
        cluster.turnListNew(new byte[] { HUD_LIST });
    }

    /** Clears the turn, the remaining trip, the destination and the HUD. */
    void release() {
        destName = "";
        sendDestName();
        destName = null;
        cluster.tbtInfo(0, 0, 0f, 0f, -1, 0, 0, 0, -1);
        cluster.tbtSubInfo(0f, -1, 0, 0, 0, 0, 0, 0f, -1, 0);
        hudBlank();
    }

    void sendDestName() {
        if (destName != null) cluster.destinationName(0, destName);
    }

    // ---- lanes: "nn" lane count, then per lane 8 hex digits (type, then 3 arrow bytes)

    void sendLanes(String s) {
        if (s == null) s = "";
        if (s.equals(laneKey)) return;
        int n = 0;
        if (s.length() >= 2) {
            n = hexByte(s, 0);
            if (n < 0 || n > 8) return;
        }
        byte[] f = new byte[81];
        f[0] = LANES;
        f[1] = (byte) n;
        for (int k = 0; k < n; k++) {
            int at = k * 8 + 2;
            int b = hexByte(s, at);
            if (b < 0) return;
            f[k + 3] = (byte) b;
            for (int j = 0; j < 3; j++) {
                b = hexByte(s, at + 2 + j * 2);
                if (b < 0) return;
                f[k * 3 + 19 + j] = (byte) b;
            }
        }
        cluster.laneInfo(f);
        laneKey = s;
    }

    static int hexByte(String s, int at) {
        if (at + 1 >= s.length()) return -1;
        int hi = hex(s.charAt(at)), lo = hex(s.charAt(at + 1));
        return hi < 0 || lo < 0 ? -1 : hi << 4 | lo;
    }

    static int hex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        return -1;
    }

    // ---- the turns ahead (cluster turn list)

    static int aheadType(int ev, int side) {
        switch (ev) {
            case STRAIGHT: case MERGE: return 65;
            case SLIGHT_TURN: return side == RIGHT ? 66 : side == LEFT ? 71 : 64;
            case TURN: return side == RIGHT ? 67 : side == LEFT ? 70 : 64;
            case SHARP_TURN: return side == RIGHT ? 68 : side == LEFT ? 69 : 64;
            case U_TURN: return side == RIGHT ? 73 : 72;
            default: return 64;
        }
    }

    void sendAhead(int n) {
        int count = 0;
        for (int k = 0; k < n; k++) if (aheadOk[k]) count++;
        if (count == 0) return;
        int usm = Car.distanceUnit(context);
        Cluster.Turn[] list = new Cluster.Turn[count];
        int i = 0;
        for (int k = 0; k < n && i < count; k++) {
            if (!aheadOk[k]) continue;
            Cluster.Turn t = new Cluster.Turn();
            t.type = aheadType(aheadEv[k], aheadSide[k]);
            t.direction = Math.max(aheadAng[k], 0);
            t.traffic = 0;
            t.no = i + 1;
            t.name = aheadName[k] == null ? "" : aheadName[k];
            t.exitNumber = aheadExit[k] == 255 || aheadExit[k] < 0 ? "" : String.valueOf(aheadExit[k]);
            int m = aheadMeters[k], e3 = aheadE3[k], u = aheadUnit[k];
            int cu = Cluster.UNIT_M;
            float d = m;
            if (e3 >= 0) {
                if (u == KILOMETERS || u == KILOMETERS_P1) { cu = Cluster.UNIT_KM; d = e3 / 1000f; }
                else if (u == MILES || u == MILES_P1) { cu = Cluster.UNIT_MI; d = e3 / 1000f; }
                else if (u == FEET) { cu = Cluster.UNIT_FT; d = e3 / 1000f; }
                else if (u == YARDS) { cu = Cluster.UNIT_FT; d = e3 / 1000f * 3f; }
            }
            if (usm == Car.DISTANCE_KM) {
                if (m < 1000) { cu = Cluster.UNIT_M; d = m; }
                else { cu = Cluster.UNIT_KM; d = m / 1000f; }
            } else if (usm == Car.DISTANCE_MILES) {
                if (m < 161) { cu = Cluster.UNIT_FT; d = m * 3.28084f; }
                else { cu = Cluster.UNIT_MI; d = m / 1609f; }
            }
            t.distance = Math.max(0f, Math.min(65535f, d));
            t.unit = cu;
            list[i++] = t;
        }
        cluster.turnList(list);
        if (aheadOk[0]) {
            hudNext = aheadType(aheadEv[0], aheadSide[0]);
            hudFresh = true;
        }
        for (int k = 0; k < 3; k++) aheadOk[k] = false;
    }

    // ---- remaining distance and arrival time

    /** Sends the remaining trip when it changed or the owner changed units; then checks again every 250 ms. */
    void sendRemain() {
        if (!navLive || tripMeters < 0) return;
        if (tripGen == sentGen) {
            int usm = Car.distanceUnit(context);
            if (usm == -1 || usm == lastUsm) return;
        }
        sentGen = tripGen;
        int m = tripMeters, u = tripUnit;
        int usm = Car.distanceUnit(context);
        lastUsm = usm;
        if (usm == Car.DISTANCE_KM) u = m < 1000 ? METERS : KILOMETERS;
        else if (usm == Car.DISTANCE_MILES) u = m < 161 ? FEET : MILES;
        int whole, tenths = 0, cu;
        if (u == KILOMETERS || u == KILOMETERS_P1 || (u != MILES && u != MILES_P1 && u != FEET && u != YARDS && m >= 1000)) {
            whole = m / 1000;
            tenths = m % 1000 / 100;
            cu = Cluster.UNIT_KM;
        } else if (u == MILES || u == MILES_P1) {
            int t = m * 10 / 1609;
            tenths = t % 10;
            whole = t / 10;
            cu = Cluster.UNIT_MI;
        } else if (u == FEET) {
            whole = m * 3281 / 1000;
            cu = Cluster.UNIT_FT;
        } else if (u == YARDS) {
            whole = m * 1094 / 1000;
            cu = Cluster.UNIT_YD;
        } else {
            whole = m;
            cu = Cluster.UNIT_M;
        }
        int hour = -1, minute = -1, timeType = -1, timeFormat = -1;
        int s = tripSeconds;
        Calendar c = null;
        if (s >= 500000000) {   // a clock time (seconds since 1970)
            c = Calendar.getInstance();
            c.setTimeInMillis(s * 1000L);
        } else if (s >= 0 && s <= 604800) {   // seconds from now
            c = Calendar.getInstance();
            c.add(Calendar.SECOND, s);
        }
        if (c != null) {
            hour = c.get(Calendar.HOUR_OF_DAY);
            minute = c.get(Calendar.MINUTE);
            timeType = 1;
            timeFormat = context == null || DateFormat.is24HourFormat(context) ? 2 : 1;
        }
        cluster.tbtSubInfo(whole + tenths / 10f, 0, cu, hour, minute, timeType, timeFormat, 0f, -1, estPoint);
        scheduleTick();
    }

    void scheduleTick() {
        if (tripMeters < 0) return;
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, 250);
    }

    void unitTick() {
        if (tripMeters < 0) return;
        sendRemain();
        scheduleTick();
    }
}
