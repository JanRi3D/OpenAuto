package com.shilapi.xcertplay.transport

import org.junit.Assert.*
import org.junit.Test

class LegacyUsbDescriptorPolicyTest {
    private fun descriptors(endpoint: Int = 0x85): ByteArray = byteArrayOf(
        9, 2, 32, 0, 1, 6, 0, 0x80.toByte(), 50,
        9, 4, 1, 0, 2, 0xff.toByte(), 0xfe.toByte(), 2, 0,
        7, 5, 4, 2, 0, 2, 0,
        7, 5, endpoint.toByte(), 2, 0, 2, 0,
    )

    @Test fun verifiesExactFrameworkEndpointDescriptors() {
        val parsed = LegacyUsbDescriptorPolicy.parse(descriptors())
        assertEquals(6, parsed.single().id)
        assertEquals(1, parsed.single().interfaces.single().id)
        val iface = parsed.single().interfaces.single()
        assertTrue(LegacyUsbDescriptorPolicy.matches(iface, 1, 0xff, 0xfe, 2,
            listOf(LegacyUsbDescriptorPolicy.Endpoint(4, 2, 512), LegacyUsbDescriptorPolicy.Endpoint(0x85, 2, 512))))
        assertFalse(LegacyUsbDescriptorPolicy.matches(iface, 1, 0xff, 0xfe, 2,
            listOf(LegacyUsbDescriptorPolicy.Endpoint(4, 2, 512), LegacyUsbDescriptorPolicy.Endpoint(0x86, 2, 512))))
    }

    @Test fun malformedDescriptorCannotProduceUsableConfiguration() {
        for (bytes in listOf(descriptors().copyOf(29), descriptors().also { it[18] = 0 },
            descriptors().also { it[2] = 31 })) {
            assertThrows(java.io.IOException::class.java) { LegacyUsbDescriptorPolicy.parse(bytes) }
        }
    }

    @Test fun nonzeroAlternateMustBeExplicitlySelectedAndVerified() {
        val iface = LegacyUsbDescriptorPolicy.parse(descriptors().also { it[12] = 1 })
            .single().interfaces.single()
        assertEquals(1, iface.alternateSetting)
        assertFalse(LegacyUsbDescriptorPolicy.matches(iface, 1, 0xff, 0xfe, 2,
            listOf(LegacyUsbDescriptorPolicy.Endpoint(4, 2, 64), LegacyUsbDescriptorPolicy.Endpoint(0x85, 2, 512))))
    }
}
