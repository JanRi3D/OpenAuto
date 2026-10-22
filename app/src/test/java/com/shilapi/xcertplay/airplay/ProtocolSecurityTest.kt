package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

/** Protocol regressions for the API 16 adaptation of pinned DiPlay. */
class ProtocolSecurityTest {
    @Test(timeout = 3000) fun infoNeverExpandsSharedReferenceGraphsIntoDiagnosticStrings() {
        val logs = mutableListOf<String>()
        val session = AirPlaySession(java.net.Socket(), AirPlayConfig("Test", "00:11:22:33:44:55", "", "1",
            AirPlayDisplayConfig(800, 480)), AirPlayIdentity.generate(), PairingStore(), null,
            object : AirPlaySessionListener { override fun onDebugLog(message: String) { logs.add(message) } }, object : AirPlayMediaHandler {})
        try {
            val output = java.io.ByteArrayOutputStream()
            output.write("bplist00".toByteArray())
            val offsets = mutableListOf<Int>()
            repeat(16) { index -> offsets.add(output.size()); output.write(byteArrayOf(0xa2.toByte(), (index + 1).toByte(), (index + 1).toByte())) }
            offsets.add(output.size()); output.write(byteArrayOf(0x54) + "leaf".toByteArray())
            val table = output.size(); offsets.forEach { output.write(it) }
            val trailer = ByteArray(32); trailer[6] = 1; trailer[7] = 1; trailer[15] = 17; trailer[31] = table.toByte()
            output.write(trailer)
            val handle = AirPlaySession::class.java.getDeclaredMethod("handle", RtspMessage.Request::class.java).apply { isAccessible = true }
            val response = handle.invoke(session, RtspMessage.Request("POST", "/info", "RTSP/1.0", emptyMap(), output.toByteArray())) as RtspMessage.Response
            assertEquals(200, response.status ?: 200)
            assertTrue("peer graph expanded in diagnostic log", logs.all { it.length < 1024 })
        } finally { session.close() }
    }
    @Test fun rtspRetainsSplitBodyAndParsesFollowingRequest() {
        val first = "POST /pair RTSP/1.0\r\nCSeq: 3\r\nContent-Length: 4\r\n\r\nab".toByteArray()
        val partial = RtspMessage.parseMessages(first)
        assertTrue(partial.messages.isEmpty())
        assertArrayEquals(first, partial.rest)
        val parsed = RtspMessage.parseMessages(partial.rest + "cdGET /info RTSP/1.0\r\n\r\n".toByteArray())
        assertEquals(2, parsed.messages.size)
        assertArrayEquals("abcd".toByteArray(), parsed.messages[0].body)
        assertTrue(parsed.rest.isEmpty())
    }

    @Test fun rtspRejectsInvalidAndExcessiveLengthsBeforeAllocating() {
        for (length in listOf("-1", "2147483647", "wat")) {
            rejects { RtspMessage.parseMessages("POST / RTSP/1.0\r\nContent-Length: $length\r\n\r\n".toByteArray()) }
        }
        rejects { RtspMessage.parseMessages(ByteArray(32769) { 65 }) }
    }

    @Test fun plistRoundTripsUnicodeAndRejectsInvalidObjectCountAndCycle() {
        val plist = BplistCodec.encode(linkedMapOf("name" to "Straße", "values" to listOf(1L, true)))
        assertEquals("Straße", (BplistCodec.decode(plist) as Map<*, *>)["name"])
        val huge = plist.copyOf()
        for (i in 8..15) huge[huge.size - 32 + i] = 0x7f
        rejects { BplistCodec.decode(huge) }
        // Single array object referring to itself, then offset table and trailer.
        val cycle = ByteArray(43)
        "bplist00".toByteArray().copyInto(cycle)
        cycle[8] = 0xa1.toByte(); cycle[9] = 0; cycle[10] = 8
        cycle[17] = 1; cycle[18] = 1; cycle[26] = 1; cycle[42] = 10
        rejects { BplistCodec.decode(cycle) }
    }

    @Test fun plistUnsignedValuesWithUnpaddedBigIntegerWidthsRoundTrip() {
        for (value in listOf("65536", "16777216", "4294967296", "18446744073709551615")) {
            val encoded = BplistCodec.encode(java.math.BigInteger(value))
            val decoded = BplistCodec.decode(encoded) as Long
            assertEquals(value, unsignedPlistDecimal(decoded))
        }
    }

    @Test fun plistSixteenByteUnsignedIntegersFromApple() {
        // A top-level 0x14 integer, as CoreFoundation writes a UInt64 of 2^63 or more.
        fun plist(high: Long, low: Long): ByteArray {
            val body = java.nio.ByteBuffer.allocate(8 + 17 + 1 + 32)
            body.put("bplist00".toByteArray()).put(0x14).putLong(high).putLong(low).put(8)
            body.position(body.position() + 6)
            body.put(1).put(1).putLong(1).putLong(0).putLong(25)
            return body.array()
        }
        assertEquals("18446744073709551615", unsignedPlistDecimal(BplistCodec.decode(plist(0, -1))))
        assertEquals("9223372036854775808", unsignedPlistDecimal(BplistCodec.decode(plist(0, Long.MIN_VALUE))))
        rejects { BplistCodec.decode(plist(1, 0)) }
    }

    @Test fun tlvFragmentsRoundTripAndTruncationIsRejected() {
        val value = ByteArray(600) { it.toByte() }
        assertArrayEquals(value, Tlv8Codec.decode(Tlv8Codec.encode(listOf(Tlv8Item(3, value))))[3])
        rejects { Tlv8Codec.decode(byteArrayOf(3, 5, 1)) }
        rejects { Tlv8Codec.decode(byteArrayOf(3)) }
    }

    @Test fun controlFramesSurviveFragmentationAndRejectTampering() {
        val key = ByteArray(32) { it.toByte() }
        val data = ByteArray(40000) { (it * 7).toByte() }
        val sealed = ControlCipher(key, key).encrypt(data)
        val receiver = ControlCipher(key, key)
        val partial = receiver.decrypt(sealed.copyOfRange(0, 8))
        assertTrue(partial.data.isEmpty())
        assertArrayEquals(data, receiver.decrypt(partial.rest + sealed.copyOfRange(8, sealed.size)).data)
        val damaged = ControlCipher(key, key).encrypt(data).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        rejects { ControlCipher(key, key).decrypt(damaged) }
    }

    @Test fun rfc8439TagAndPairVerifyUnknownOrZeroKeyAreRejected() {
        val key = hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
        val nonce = hex("070000004041424344454647")
        val aad = hex("50515253c0c1c2c3c4c5c6c7")
        val text = ("Ladies and Gentlemen of the class of '99: If I could offer you only one tip " +
            "for the future, sunscreen would be it.").toByteArray()
        val sealed = AirPlayCrypto.chachaSeal(key, nonce, text, aad)
        assertArrayEquals(hex("1ae10b594f09e26a7e902ecbd0600691"), sealed.takeLast(16).toByteArray())
        rejects { AirPlayCrypto.chachaOpen(key, nonce, sealed, byteArrayOf(1)) }
        val verifier = PairVerify(AirPlayIdentity.generate(), PairingStore())
        val response = Tlv8Codec.decode(verifier.handle(Tlv8Codec.encode(listOf(Tlv8Item(6, byteArrayOf(1)), Tlv8Item(3, ByteArray(32))))))
        assertNotNull(response[7]); assertFalse(verifier.isVerified)
    }

    @Test fun screenEncryptedFrameRequiresAnAuthenticationTag() {
        rejects { ScreenCodec.decryptFrame(ByteArray(32), 0, ByteArray(128), byteArrayOf(1, 2)) }
    }

    @Test fun unknownNegotiatedAudioFormatIsRejected() {
        rejects { AudioStreamCodec.fromFormatBits(0, 100) }
        rejects { AudioStreamCodec.fromFormatBits(0x100000000L, 100) }
        rejects { AudioStreamCodec.fromFormatBits(0x800004L, 100) }
    }

    @Test fun streamSetupRequiresVerifiedController() {
        val session = AirPlaySession(java.net.Socket(), AirPlayConfig("Test", "11:22:33:44:55:66", "", "1",
            AirPlayDisplayConfig(800, 480)), AirPlayIdentity.generate(), PairingStore(), null,
            object : AirPlaySessionListener {}, object : AirPlayMediaHandler {})
        try {
            val handle = AirPlaySession::class.java.getDeclaredMethod("handle", RtspMessage.Request::class.java)
            handle.isAccessible = true
            val response = handle.invoke(session, RtspMessage.Request("SETUP", "/", "RTSP/1.0", emptyMap(),
                BplistCodec.encode(mapOf("streams" to emptyList<Any>())))) as RtspMessage.Response
            assertEquals(403, response.status)
            val disguised = handle.invoke(session, RtspMessage.Request("SETUP", "/info", "RTSP/1.0", emptyMap(),
                BplistCodec.encode(mapOf("streams" to emptyList<Any>())))) as RtspMessage.Response
            assertEquals(403, disguised.status)
        } finally { session.close() }
    }

    @Test fun pairVerifyAuthenticatesStoredControllerAndRejectsChangedSignature() {
        for (tamper in listOf(false, true)) {
            val store = PairingStore()
            val controller = AirPlayCrypto.ed25519Generate()
            val id = "test-controller".toByteArray()
            store.save(String(id), controller.publicKey)
            val accessory = AirPlayIdentity.generate()
            val responder = PairVerify(accessory, store)
            val ephemeral = AirPlayCrypto.x25519Generate()
            val reply = Tlv8Codec.decode(responder.handle(Tlv8Codec.encode(listOf(
                Tlv8Item(6, byteArrayOf(1)), Tlv8Item(3, ephemeral.publicKey),
            ))))
            val serverKey = reply[3]!!
            val shared = AirPlayCrypto.x25519Shared(ephemeral.privateKey, serverKey)
            val encryptionKey = AirPlayCrypto.hkdfSha512(shared,
                "Pair-Verify-Encrypt-Salt".toByteArray(), "Pair-Verify-Encrypt-Info".toByteArray())
            val signature = AirPlayCrypto.ed25519Sign(controller.privateKey, ephemeral.publicKey + id + serverKey)
            if (tamper) signature[0] = (signature[0].toInt() xor 1).toByte()
            val sub = Tlv8Codec.encode(listOf(Tlv8Item(1, id), Tlv8Item(10, signature)))
            val sealed = AirPlayCrypto.chachaSeal(encryptionKey, AirPlayCrypto.nonceLabel("PV-Msg03"), sub)
            val result = Tlv8Codec.decode(responder.handle(Tlv8Codec.encode(listOf(
                Tlv8Item(6, byteArrayOf(3)), Tlv8Item(5, sealed),
            ))))
            assertEquals(!tamper, responder.isVerified)
            assertEquals(tamper, result.containsKey(7))
            if (!tamper) {
                assertEquals(String(id), responder.verifiedControllerId)
                assertArrayEquals(AirPlayCrypto.hkdfSha512(shared, "Control-Salt".toByteArray(),
                    "Control-Write-Encryption-Key".toByteArray()), responder.controlKeys!!.readKey)
            }
        }
    }

    private fun hex(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun rejects(block: () -> Any?) {
        try { block() } catch (_: Exception) { return }
        fail("malformed or unauthenticated peer input was accepted")
    }
}
