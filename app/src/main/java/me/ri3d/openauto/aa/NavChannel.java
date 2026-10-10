package me.ri3d.openauto.aa;

import me.ri3d.openauto.proto.ProtoReader;
import me.ri3d.openauto.proto.ProtoWriter;

/**
 * Navigation status channel (9): the next turn Google Maps gives an instrument cluster. Message layouts
 * from aasdk (openDsh fork): NavigationStatus, NavigationTurnEvent, NavigationDistanceEvent. Declared
 * with type IMAGE, so turn events carry a 256 x 256 PNG of the arrow. The fields hold the latest
 * values and are only touched on the reader thread; listeners read them inside
 * {@link Session.Listener#onNavigation}.
 */
public final class NavChannel extends Channel {
    private final ProtoReader r = new ProtoReader();
    private String logged = "";

    /** Wire.NAV_ACTIVE / NAV_INACTIVE / NAV_REROUTING (0 = unavailable). */
    public int status = Wire.NAV_INACTIVE;
    public String road = "";
    /** aasdk ManeuverType and ManeuverDirection; exit = roundabout exit number. */
    public int maneuver, direction, exit;
    public byte[] image;
    /** Distance to the turn in metres, seconds to it, and the distance as Maps displays it (value x 1000 in unit, aasdk DistanceUnit); -1 = none yet. */
    public int meters = -1, seconds = -1, displayMillis, unit;

    NavChannel(Session s) {
        super(s, Wire.CH_NAVIGATION);
    }

    @Override
    void describe(ProtoWriter d) {
        // openDsh/openauto's values: an update a second at most, 256 x 256 images at 16 bits; field 4 is unnamed, 255.
        ProtoWriter options = new ProtoWriter(16).uint(1, 256).uint(2, 256).uint(3, 16).uint(4, 255);
        d.message(8, new ProtoWriter(24).uint(1, 1000).uint(2, Wire.NAV_TYPE_IMAGE).message(3, options));
    }

    @Override
    protected void handle(int msgId, byte[] data, int off, int len) {
        try {
            r.reset(data, off, len);
            switch (msgId) {
                case Wire.NAV_STATUS:
                    while (r.next()) if (r.field() == 1) status = r.int32(); else r.skip();
                    if (status != Wire.NAV_ACTIVE && status != Wire.NAV_REROUTING) { image = null; meters = -1; seconds = -1; }
                    break;
                case Wire.NAV_TURN:
                    road = "";
                    image = null;
                    maneuver = direction = exit = 0;
                    while (r.next()) {
                        switch (r.field()) {
                            case 1: road = r.string(); break;
                            case 2: direction = r.int32(); break;
                            case 3: maneuver = r.int32(); break;
                            case 4: image = r.bytes(); break;
                            case 5: exit = r.int32(); break;
                            default: r.skip();
                        }
                    }
                    break;
                case Wire.NAV_DISTANCE:
                    while (r.next()) {
                        switch (r.field()) {
                            case 1: meters = r.int32(); break;
                            case 2: seconds = r.int32(); break;
                            case 3: displayMillis = r.int32(); break;
                            case 4: unit = r.int32(); break;
                            default: r.skip();
                        }
                    }
                    break;
                default:
                    s.log("navigation: unhandled message 0x" + Integer.toHexString(msgId));
                    return;
            }
        } catch (RuntimeException e) { // a malformed nav message must not end the projection
            s.log("navigation: bad message 0x" + Integer.toHexString(msgId) + ": " + e.getMessage());
            return;
        }
        // The phone repeats the turn every second: log only what changed (metres excluded, they count down).
        String line = "navigation: status " + status + ", turn " + maneuver + "/" + direction + " exit " + exit + " '" + road
                + "', picture " + (image == null ? "none" : image.length + " bytes") + ", distance " + displayMillis + " unit " + unit;
        if (!line.equals(logged)) s.log(logged = line);
        s.navigationChanged();
    }
}
