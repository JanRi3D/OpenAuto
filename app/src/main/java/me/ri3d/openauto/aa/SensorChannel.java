package me.ri3d.openauto.aa;

import java.io.IOException;

import me.ri3d.openauto.proto.ProtoReader;
import me.ri3d.openauto.proto.ProtoWriter;

/** Sensor channel (2): driving status (unrestricted) and night mode (§5, §8.2 step 6). */
public final class SensorChannel extends Channel {
    private final ProtoWriter w = new ProtoWriter(32);
    private final ProtoReader r = new ProtoReader();
    private boolean nightStarted;
    private boolean night;

    SensorChannel(Session s) {
        super(s, Wire.CH_SENSOR);
        night = s.cfg.night;
    }

    @Override
    void describe(ProtoWriter d) {
        ProtoWriter sc = new ProtoWriter(16)
                .message(1, new ProtoWriter(4).uint(1, Wire.SENSOR_DRIVING_STATUS))
                .message(1, new ProtoWriter(4).uint(1, Wire.SENSOR_NIGHT_DATA));
        d.message(2, sc);
    }

    @Override
    protected void handle(int msgId, byte[] data, int off, int len) throws IOException {
        if (msgId != Wire.SENSOR_START_REQUEST) {
            s.log("sensor: unhandled message 0x" + Integer.toHexString(msgId));
            return;
        }
        int type = 0;
        r.reset(data, off, len);
        while (r.next()) {
            if (r.field() == 1) type = r.int32(); else r.skip();
        }
        w.reset().uint(1, Wire.STATUS_OK);
        s.send(id, false, Wire.SENSOR_START_RESPONSE, w);
        if (type == Wire.SENSOR_DRIVING_STATUS) {
            w.reset().message(13, new ProtoWriter(4).uint(1, Wire.DRIVING_UNRESTRICTED));
            s.send(id, false, Wire.SENSOR_EVENT, w);
        } else if (type == Wire.SENSOR_NIGHT_DATA) {
            nightStarted = true;
            sendNight();
        }
        s.log("sensor: start type " + type);
    }

    private void sendNight() throws IOException {
        w.reset().message(10, new ProtoWriter(4).bool(1, night));
        s.send(id, false, Wire.SENSOR_EVENT, w);
    }

    /** Pushes a night-mode change to the phone if it subscribed. */
    public void setNight(boolean isNight) {
        if (isNight == night) return;
        night = isNight;
        if (!nightStarted) return;
        try {
            sendNight();
        } catch (IOException e) {
            s.close("sensor send failed: " + e.getMessage());
        }
    }
}
