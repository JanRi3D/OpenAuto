package me.ri3d.openauto.carplay.iap2;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import me.ri3d.openauto.carplay.auth.MfiAuthenticator;
import me.ri3d.openauto.carplay.iap2.CsmMessage.Parameter;

/**
 * Regression from an independent review: two different groups whose parts once looked alike when
 * joined as text, so a correction of a rejected Name could change an unrejected group unnoticed.
 * The payloads here are synthetic; the Bluetooth profile itself only ever changes the Name, and no
 * phone has been seen sending such a rejection.
 */
public class IdentificationCorrectionRegressionTest {
    private static final Set<Integer> GROUPS = Collections.singleton(17);

    @Test
    public void groupsThatOnceLookedAlikeAreDifferent() {
        assertEquals(Collections.singleton(17), ControlSession.changedIds(info("name", group(text(0, "a"), text(1, "b"))),
                info("name", group(text(0, "a, 1=b"))), GROUPS));
    }

    @Test
    public void orderIsIgnoredButMultiplicityIsNot() {
        CsmMessage base = info("name", group(text(0, "a"), text(1, "b")));
        assertEquals(Collections.<Integer>emptySet(), ControlSession.changedIds(base, info("name", group(text(1, "b"), text(0, "a"))), GROUPS));
        assertEquals("a repeated part counts", Collections.singleton(17),
                ControlSession.changedIds(info("name", group(text(0, "a"))), info("name", group(text(0, "a"), text(0, "a"))), GROUPS));
        CsmMessage once = new CsmMessage(ControlSession.IDENTIFICATION_INFORMATION, text(13, "en"));
        CsmMessage twice = new CsmMessage(ControlSession.IDENTIFICATION_INFORMATION, text(13, "en"), text(13, "en"));
        assertEquals("a repeated parameter counts", Collections.singleton(13), ControlSession.changedIds(once, twice, GROUPS));
    }

    /** The whole rejection path: Name rejected, the offered correction also rewrites group 17. */
    @Test
    public void correctionThatAlsoChangesAnUnrejectedGroupIsRefused() {
        Outcome o = rejectNameAndOffer(info("OpenAuto", group(text(0, "a, 1=b"))));
        assertEquals(ControlSession.Failure.IDENTIFICATION_REJECTED, o.session.failure());
        assertEquals("nothing resent", 1, o.sent.size());
    }

    /** Control: the same correction with group 17 only reordered goes out. */
    @Test
    public void correctionWithTheGroupOnlyReorderedIsSent() {
        CsmMessage offered = info("OpenAuto", group(text(1, "b"), text(0, "a")));
        Outcome o = rejectNameAndOffer(offered);
        assertNull(o.session.failureDetail(), o.session.failure());
        assertEquals(2, o.sent.size());
        assertArrayEquals(offered.encode(), o.sent.get(1));
    }

    private static Outcome rejectNameAndOffer(CsmMessage offered) {
        CsmMessage base = info("name", group(text(0, "a"), text(1, "b")));
        Outcome o = new Outcome();
        IdentificationPayload correction = payload(offered, null);
        o.session = new ControlSession(o.sent::add, NO_SIGNER, payload(base, correction), 5_000, 0);
        deliver(o.session, new CsmMessage(ControlSession.START_IDENTIFICATION));
        deliver(o.session, new CsmMessage(ControlSession.IDENTIFICATION_REJECTED, new Parameter(0, new byte[0])));
        return o;
    }

    private static final class Outcome {
        final List<byte[]> sent = new ArrayList<>();
        ControlSession session;
    }

    private static void deliver(ControlSession s, CsmMessage m) {
        byte[] b = m.encode();
        s.receive(b, 0, b.length, 0);
    }

    private static IdentificationPayload payload(CsmMessage message, IdentificationPayload correction) {
        return new IdentificationPayload() {
            @Override
            public CsmMessage message() {
                return message;
            }

            @Override
            public Set<Integer> groupIds() {
                return GROUPS;
            }

            @Override
            public IdentificationPayload correctedFor(Set<Integer> rejectedIds) throws Uncorrectable {
                if (correction == null || !rejectedIds.equals(new TreeSet<>(Collections.singleton(0)))) throw new Uncorrectable("not offered");
                return correction;
            }
        };
    }

    private static CsmMessage info(String name, Parameter group) {
        return new CsmMessage(ControlSession.IDENTIFICATION_INFORMATION, text(0, name), group);
    }

    private static Parameter group(Parameter... parts) {
        return new Parameter(17, CsmMessage.encodeParameters(Arrays.asList(parts)));
    }

    private static Parameter text(int id, String value) {
        return new Parameter(id, value.getBytes(StandardCharsets.UTF_8));
    }

    private static final MfiAuthenticator NO_SIGNER = new MfiAuthenticator() {
        @Override
        public int protocolMajor() {
            return 3;
        }

        @Override
        public byte[] readCertificate(int maximumOutputLength) {
            throw new UnsupportedOperationException("identification only");
        }

        @Override
        public byte[] signDigest(byte[] digest) {
            throw new UnsupportedOperationException("identification only");
        }
    };
}
