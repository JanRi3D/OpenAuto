package me.ri3d.openauto.carplay.auth;

import static me.ri3d.openauto.carplay.auth.LocalMfiAuthenticatorTest.hex;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.security.SignatureException;

/** Fixed DER and raw vectors for P-256 signatures, written out by hand. */
public class RawEcdsaSignatureTest {
    private static final String N = "FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551";
    private static final String N_MINUS_1 = "FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632550";

    @Test
    public void convertsCanonicalDer() throws Exception {
        // r has its top bit set and needs a sign-padding zero in DER; s does not
        String r = "80" + "11".repeat(31), s = "7F" + "22".repeat(31);
        roundTrip("3045" + "022100" + r + "0220" + s, r + s);
        // short integers are left-padded to 32 bytes
        roundTrip("3007" + "020101" + "02020100", "00".repeat(31) + "01" + "00".repeat(30) + "0100");
        // the largest values, the longest encoding (72 bytes)
        roundTrip("3046" + "022100" + N_MINUS_1 + "022100" + N_MINUS_1, N_MINUS_1 + N_MINUS_1);
        assertEquals(RawEcdsaSignature.MAX_DER_LENGTH, hex("3046" + "022100" + N_MINUS_1 + "022100" + N_MINUS_1).length);
    }

    @Test
    public void rejectsInvalidIntegers() {
        derRejected("negative", "3006" + "0201FF" + "020101");
        derRejected("negative without sign padding", "3044" + "0220" + "80" + "11".repeat(31) + "0220" + "7F" + "22".repeat(31));
        derRejected("zero r", "3006" + "020100" + "020101");
        derRejected("zero s", "3006" + "020101" + "020100");
        derRejected("r = n", "3026" + "022100" + N + "020101");
        derRejected("r = 2^256", "3026" + "022101" + "00".repeat(32) + "020101");
        derRejected("needless leading zero", "3007" + "02020001" + "020101");
        derRejected("empty integer", "3005" + "0200" + "020101");
    }

    @Test
    public void rejectsMalformedStructure() {
        derRejected("sequence longer than the data", "3007" + "020101" + "020101");
        derRejected("sequence shorter than the data", "3005" + "020101" + "020101");
        derRejected("integer runs past the end", "3006" + "020501" + "020101");
        derRejected("non-minimal length", "308106" + "020101" + "020101");
        derRejected("indefinite length", "3080" + "020101" + "020101" + "0000");
        derRejected("trailing data", "3006" + "020101" + "020101" + "00");
        derRejected("SET instead of SEQUENCE", "3106" + "020101" + "020101");
        derRejected("one integer", "3003" + "020101");
        derRejected("three integers", "3009" + "020101" + "020101" + "020101");
        derRejected("empty", "");
        derRejected("longer than any P-256 signature", "3047" + "022100" + N_MINUS_1 + "022200" + "00" + N_MINUS_1);
        try {
            RawEcdsaSignature.fromDer(null);
            fail("null accepted");
        } catch (SignatureException expected) {
            // nothing to convert
        }
    }

    @Test
    public void rejectsInvalidRaw() {
        rawRejected("zero r", "00".repeat(32) + "01".repeat(32));
        rawRejected("zero s", "01".repeat(32) + "00".repeat(32));
        rawRejected("r = n", N + "01".repeat(32));
        rawRejected("s all ones", "01".repeat(32) + "FF".repeat(32));
        rawRejected("63 bytes", "01".repeat(63));
        rawRejected("65 bytes", "01".repeat(65));
        rawRejected("empty", "");
        try {
            RawEcdsaSignature.toDer(null);
            fail("null accepted");
        } catch (SignatureException expected) {
            // nothing to convert
        }
    }

    private static void roundTrip(String der, String raw) throws SignatureException {
        assertArrayEquals(hex(raw), RawEcdsaSignature.fromDer(hex(der)));
        assertArrayEquals(hex(der), RawEcdsaSignature.toDer(hex(raw)));
    }

    private static void derRejected(String what, String der) {
        try {
            RawEcdsaSignature.fromDer(hex(der));
            fail(what + ": accepted");
        } catch (SignatureException expected) {
            // the only failure fromDer reports
        }
    }

    private static void rawRejected(String what, String raw) {
        try {
            RawEcdsaSignature.toDer(hex(raw));
            fail(what + ": accepted");
        } catch (SignatureException expected) {
            // the only failure toDer reports
        }
    }
}
