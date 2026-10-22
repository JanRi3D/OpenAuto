package me.ri3d.openauto.carplay.iap2;

import java.io.IOException;

/**
 * Where {@link ControlSession} hands its messages down. Two different limits meet here: a control
 * session message may be up to {@link CsmMessage#MAX_LENGTH} bytes, while a link packet may be only
 * as long as the link negotiated at synchronization. Splitting a message over several packets and
 * joining them on the other side is the link's job ({@link Iap2LinkLayer}); the session only ever
 * sees whole messages going down and its own byte stream coming up.
 */
public interface Iap2Link {
    /**
     * Queues one complete control session message, of any length up to {@link CsmMessage#MAX_LENGTH}.
     *
     * @throws IOException if the link is not up, went down while sending, or its bounded send queue is full
     */
    void sendControl(byte[] message) throws IOException;
}
