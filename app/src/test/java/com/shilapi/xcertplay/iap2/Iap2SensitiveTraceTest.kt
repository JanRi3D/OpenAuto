package com.shilapi.xcertplay.iap2

import com.shilapi.xcertplay.iap2.message.Iap2WirelessMessages
import com.shilapi.xcertplay.iap2.trace.Iap2FrameFormatter
import com.shilapi.xcertplay.iap2.trace.Iap2TraceDirection
import org.junit.Assert.*
import org.junit.Test

class Iap2SensitiveTraceTest {
    @Test fun wifiCredentialsAreNeverFormattedAsTextOrHex() {
        val frame = Iap2WirelessMessages.accessoryWiFiConfiguration("private-ssid", "private-password", 3, 0x24)
        val trace = Iap2FrameFormatter.format(Iap2TraceDirection.TX, "test", frame)
        assertFalse(trace.contains("private"))
        assertFalse(trace.contains("70 72 69 76 61 74 65"))
        assertTrue(trace.contains("redacted"))
    }
}
