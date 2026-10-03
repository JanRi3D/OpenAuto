package me.ri3d.openauto.aa;

import java.util.List;

import me.ri3d.openauto.proto.ProtoWriter;

/** Encoders for the larger control messages (protocol-reference §2.4). */
final class Messages {
    private Messages() {}

    /** ServiceDiscoveryResponse with one ChannelDescriptor per declared channel. */
    static ProtoWriter discovery(Session s, List<Channel> channels) {
        Session.Config c = s.cfg;
        ProtoWriter resp = new ProtoWriter(512);
        for (Channel ch : channels) {
            ProtoWriter d = new ProtoWriter(96).uint(1, ch.id);
            ch.describe(d);
            resp.message(1, d);
        }
        resp.string(2, c.headUnitName)
                .string(3, c.carModel)
                .string(4, c.carYear)
                .string(5, c.carSerial)
                .bool(6, c.leftHandDrive)
                .string(7, c.manufacturer)
                .string(8, c.model)
                .string(9, c.swBuild)
                .string(10, c.swVersion)
                .bool(11, false)
                .bool(12, false);
        return resp;
    }
}
