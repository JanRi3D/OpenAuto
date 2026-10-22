package com.shilapi.xcertplay.iap2
import com.shilapi.xcertplay.iap2.trace.Iap2FrameFormatter
import com.shilapi.xcertplay.iap2.trace.Iap2TraceDirection
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import org.junit.Assert.*
import org.junit.Test

class CredentialTraceTest {
    @Test fun allCredentialContainersAreRedactedEvenWhenTheirPayloadIsMalformed() {
        val secret="test-wifi-password-sentinel"
        for(id in listOf(0x4301,0x5701,0x5703,0xaa01,0xaa02,0xaa03)) {
            val trace=Iap2FrameFormatter.format(Iap2TraceDirection.TX,"test",Iap2Frame(id,secret.toByteArray()))
            assertFalse("credential text exposed for id=$id",trace.contains(secret))
            assertFalse("credential bytes exposed for id=$id",trace.contains("raw-body"))
            assertTrue("credential container not redacted id=$id",trace.contains("redacted"))
        }
    }
}
