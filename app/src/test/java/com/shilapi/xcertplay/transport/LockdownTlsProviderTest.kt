package com.shilapi.xcertplay.transport

import org.junit.Assert.*
import org.junit.Test

class LockdownTlsProviderTest {
    @Test fun canonicalizesOnlyVerifiedStrongPkcs1SignatureOids() {
        assertEquals("SHA256withRSA", LockdownTlsProvider.signatureName("1.2.840.113549.1.1.11", "SHA256WithRSAEncryption", null))
        assertEquals("SHA384withRSA", LockdownTlsProvider.signatureName("1.2.840.113549.1.1.12", "SHA384WithRSAEncryption", byteArrayOf(5, 0)))
        assertEquals("SHA512withRSA", LockdownTlsProvider.signatureName("1.2.840.113549.1.1.13", "SHA512WithRSAEncryption", null))
    }

    @Test fun leavesPssWeakUnknownAndMismatchedOidNamesUntouched() {
        assertEquals("RSASSA-PSS", LockdownTlsProvider.signatureName("1.2.840.113549.1.1.10", "RSASSA-PSS", byteArrayOf(0x30, 0)))
        assertEquals("MD5WithRSAEncryption", LockdownTlsProvider.signatureName("1.2.840.113549.1.1.4", "MD5WithRSAEncryption", null))
        assertEquals("unrecognized", LockdownTlsProvider.signatureName("1.2.3.4", "unrecognized", null))
    }

    @Test fun rejectsUnexpectedPkcs1SignatureParameters() {
        assertThrows(IllegalArgumentException::class.java) {
            LockdownTlsProvider.signatureName("1.2.840.113549.1.1.11", "SHA256WithRSAEncryption", byteArrayOf(0x30, 0))
        }
    }
}
