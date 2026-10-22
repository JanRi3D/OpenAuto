package me.ri3d.openauto.carplay.iap2;

import java.util.Set;

/**
 * The IdentificationInformation an accessory sends, and the only corrections it is prepared to make
 * when the phone rejects some of its parameters.
 */
public interface IdentificationPayload {
    /** The complete IdentificationInformation message (0x1D01). */
    CsmMessage message();

    /** Top-level parameter IDs whose values are themselves parameter lists. */
    Set<Integer> groupIds();

    /**
     * The payload to send after the phone rejected these top-level parameters. A correction changes
     * exactly those parameters, each to a different value or by leaving it out, and nothing else.
     *
     * @throws Uncorrectable if a named parameter has no supported correction
     */
    IdentificationPayload correctedFor(Set<Integer> rejectedIds) throws Uncorrectable;

    final class Uncorrectable extends Exception {
        public Uncorrectable(String message) {
            super(message);
        }
    }
}
