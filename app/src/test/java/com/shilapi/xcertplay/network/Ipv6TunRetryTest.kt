package com.shilapi.xcertplay.network

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class Ipv6TunRetryTest {
    @Test fun recognizesOnlyActualWouldBlockErrors() {
        assertTrue(Ipv6NcmBridge.isWouldBlock(IOException("read failed: EAGAIN (Try again)")))
        assertTrue(Ipv6NcmBridge.isWouldBlock(IOException("outer", IOException("Resource temporarily unavailable"))))
        assertFalse(Ipv6NcmBridge.isWouldBlock(IOException("Bad file descriptor")))
        assertFalse(Ipv6NcmBridge.isWouldBlock(IOException("Permission denied")))
    }
}
