package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.mfi.MfiAuthenticator
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.asn1.x9.ECNamedCurveTable
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.junit.Assert.*
import org.junit.Test

/** Real loopback TCP, synthetic keys only. This provides no iPhone or MFi acceptance evidence. */
class AirPlaySyntheticPeerTest {
    @Test fun independentControllerPairsAuthenticatesStreamsAndRejectsDamagedControl() {
        val accessory = AirPlayIdentity.generate()
        val controller = AirPlayCrypto.ed25519Generate()
        val controllerId = "synthetic-controller".toByteArray()
        val store = PairingStore()
        val signer = SyntheticAccessory()
        val active = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val streams = mutableListOf<Int>()
        val sessions = mutableListOf<AirPlaySession>()
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
            Socket(listener.inetAddress, listener.localPort).use { socket ->
                socket.soTimeout = 5000
                val session = AirPlaySession(listener.accept(), AirPlayConfig("Synthetic receiver", "12:34:56:78:90:ab",
                    "12:34:56:78:90:ab", "1.0", AirPlayDisplayConfig(800, 480, fps = 30)), accessory, store, signer,
                    object : AirPlaySessionListener {
                        override fun onSessionActive(session: AirPlaySession) { active.countDown() }
                        override fun onSessionEnded(session: AirPlaySession) { ended.countDown() }
                    }, object : AirPlayMediaHandler {
                        override fun onScreen(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Int {
                            synchronized(streams) { streams.add(type) }
                            return 12345
                        }
                        override fun onSessionClosed(session: AirPlaySession) { synchronized(sessions) { sessions.add(session) } }
                    })
                try {
                    session.start()
                    val peer = Peer(socket)
                    assertEquals(403, peer.request("SETUP", "/", BplistCodec.encode(mapOf("streams" to listOf(mapOf("type" to 110))))).status)
                    assertTrue(streams.isEmpty())
                    val secret = pairSetup(peer, accessory, controller, controllerId)
                    assertTrue(secret.isNotEmpty())
                    assertArrayEquals(controller.publicKey, store.get(String(controllerId)))
                    pairVerify(peer, accessory, controller, controllerId)

                    val authEphemeral = AirPlayCrypto.x25519Generate()
                    val auth = peer.request("POST", "/auth-setup", byteArrayOf(1) + authEphemeral.publicKey)
                    assertEquals(200, auth.status)
                    val authPublic = auth.body.copyOfRange(0, 32)
                    val certLength = u32(auth.body, 32)
                    assertArrayEquals(signer.certificate, auth.body.copyOfRange(36, 36 + certLength))
                    val signatureLength = u32(auth.body, 36 + certLength)
                    val shared = AirPlayCrypto.x25519Shared(authEphemeral.privateKey, authPublic)
                    val decrypt = Cipher.getInstance("AES/CTR/NoPadding")
                    decrypt.init(Cipher.DECRYPT_MODE, SecretKeySpec(hash("SHA-1", "AES-KEY".toByteArray(), shared).copyOf(16), "AES"),
                        IvParameterSpec(hash("SHA-1", "AES-IV".toByteArray(), shared).copyOf(16)))
                    val signature = decrypt.doFinal(auth.body.copyOfRange(40 + certLength, 40 + certLength + signatureLength))
                    assertTrue(signer.verify(hash("SHA-256", authPublic, authEphemeral.publicKey), signature))

                    val info = peer.request("GET", "/info")
                    assertEquals(200, info.status)
                    assertEquals("Synthetic receiver", (BplistCodec.decode(info.body) as Map<*, *>)["name"])
                    val setup = peer.request("SETUP", "/", BplistCodec.encode(mapOf("streams" to listOf(mapOf("type" to 110)))))
                    assertEquals(200, setup.status)
                    val declared = ((BplistCodec.decode(setup.body) as Map<*, *>)["streams"] as List<*>)[0] as Map<*, *>
                    assertEquals(12345L, declared["dataPort"])
                    assertEquals(listOf(110), streams)
                    assertEquals(200, peer.request("RECORD", "/").status)
                    assertTrue(active.await(5, TimeUnit.SECONDS))

                    peer.sendDamagedRequest()
                    assertTrue("bad authentication tag did not close control", ended.await(5, TimeUnit.SECONDS))
                    assertEquals(-1, socket.getInputStream().read())
                    assertTrue(session.isClosed)
                    assertEquals(listOf(session), sessions)
                } finally { session.close() }
            }
        }
    }

    private fun pairSetup(peer: Peer, accessory: AirPlayIdentity, controller: AirPlayCrypto.Ed25519KeyPair, id: ByteArray): ByteArray {
        val m2 = tlv(peer.request("POST", "/pair-setup", Tlv8Codec.encode(listOf(Tlv8Item(6, byteArrayOf(1)), Tlv8Item(0, byteArrayOf(0))))))
        assertArrayEquals(byteArrayOf(2), m2[6])
        val salt = m2[2]!!
        // Independent controller-side SRP arithmetic; only the public RFC group constant is shared.
        val n = Srp6a.modulus()
        val g = BigInteger.valueOf(5)
        val a = BigInteger(256, SecureRandom()).add(BigInteger.ONE)
        val aPublic = g.modPow(a, n)
        val bPublic = BigInteger(1, m2[3]!!)
        val k = BigInteger(1, hash("SHA-512", pad(n), pad(g)))
        val x = BigInteger(1, hash("SHA-512", salt, hash("SHA-512", "Pair-Setup:3939".toByteArray())))
        val u = BigInteger(1, hash("SHA-512", pad(aPublic), pad(bPublic)))
        val shared = bPublic.subtract(k.multiply(g.modPow(x, n))).mod(n).modPow(a.add(u.multiply(x)), n)
        val key = hash("SHA-512", unsigned(shared))
        val hn = hash("SHA-512", unsigned(n)); val hg = hash("SHA-512", unsigned(g))
        val xor = ByteArray(hn.size) { (hn[it].toInt() xor hg[it].toInt()).toByte() }
        val proof = hash("SHA-512", xor, hash("SHA-512", "Pair-Setup".toByteArray()), salt, pad(aPublic), pad(bPublic), key)
        val m4 = tlv(peer.request("POST", "/pair-setup", Tlv8Codec.encode(listOf(Tlv8Item(6, byteArrayOf(3)),
            Tlv8Item(3, pad(aPublic)), Tlv8Item(4, proof)))))
        assertArrayEquals(byteArrayOf(4), m4[6])
        assertArrayEquals(hash("SHA-512", pad(aPublic), proof, key), m4[4])
        val signKey = derive(key, "Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info")
        val signature = AirPlayCrypto.ed25519Sign(controller.privateKey, signKey + id + controller.publicKey)
        val sub = Tlv8Codec.encode(listOf(Tlv8Item(1, id), Tlv8Item(3, controller.publicKey), Tlv8Item(10, signature)))
        val encryptionKey = derive(key, "Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info")
        val m6 = tlv(peer.request("POST", "/pair-setup", Tlv8Codec.encode(listOf(Tlv8Item(6, byteArrayOf(5)),
            Tlv8Item(5, AirPlayCrypto.chachaSeal(encryptionKey, AirPlayCrypto.nonceLabel("PS-Msg05"), sub))))))
        assertArrayEquals(byteArrayOf(6), m6[6])
        val response = Tlv8Codec.decode(AirPlayCrypto.chachaOpen(encryptionKey, AirPlayCrypto.nonceLabel("PS-Msg06"), m6[5]!!))
        assertArrayEquals(accessory.publicKey, response[3])
        assertArrayEquals(accessory.pairingId.toByteArray(), response[1])
        val serverSignKey = derive(key, "Pair-Setup-Accessory-Sign-Salt", "Pair-Setup-Accessory-Sign-Info")
        assertTrue(AirPlayCrypto.ed25519Verify(accessory.publicKey, serverSignKey + response[1]!! + response[3]!!, response[10]!!))
        return key
    }

    private fun pairVerify(peer: Peer, accessory: AirPlayIdentity, controller: AirPlayCrypto.Ed25519KeyPair, id: ByteArray) {
        val ephemeral = AirPlayCrypto.x25519Generate()
        val m2 = tlv(peer.request("POST", "/pair-verify", Tlv8Codec.encode(listOf(Tlv8Item(6, byteArrayOf(1)), Tlv8Item(3, ephemeral.publicKey)))))
        val public = m2[3]!!
        val shared = AirPlayCrypto.x25519Shared(ephemeral.privateKey, public)
        val encryptionKey = derive(shared, "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info")
        val sub = Tlv8Codec.decode(AirPlayCrypto.chachaOpen(encryptionKey, AirPlayCrypto.nonceLabel("PV-Msg02"), m2[5]!!))
        assertArrayEquals(accessory.pairingId.toByteArray(), sub[1])
        assertTrue(AirPlayCrypto.ed25519Verify(accessory.publicKey, public + sub[1]!! + ephemeral.publicKey, sub[10]!!))
        val signature = AirPlayCrypto.ed25519Sign(controller.privateKey, ephemeral.publicKey + id + public)
        val sealed = AirPlayCrypto.chachaSeal(encryptionKey, AirPlayCrypto.nonceLabel("PV-Msg03"),
            Tlv8Codec.encode(listOf(Tlv8Item(1, id), Tlv8Item(10, signature))))
        val m4 = tlv(peer.request("POST", "/pair-verify", Tlv8Codec.encode(listOf(Tlv8Item(6, byteArrayOf(3)), Tlv8Item(5, sealed)))))
        assertArrayEquals(byteArrayOf(4), m4[6])
        peer.readKey = derive(shared, "Control-Salt", "Control-Read-Encryption-Key")
        peer.writeKey = derive(shared, "Control-Salt", "Control-Write-Encryption-Key")
    }

    private class Peer(private val socket: Socket) {
        var readKey: ByteArray? = null
        var writeKey: ByteArray? = null
        private var incoming = ByteArray(0)
        private var position = 0
        private var readCounter = 0L
        private var writeCounter = 0L
        private var sequence = 0
        data class Response(val status: Int, val body: ByteArray)

        fun request(method: String, path: String, body: ByteArray = ByteArray(0)): Response {
            val bytes = "$method $path RTSP/1.0\r\nCSeq: ${++sequence}\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray() + body
            val output = socket.getOutputStream()
            if (writeKey == null) {
                // Exercise real request fragmentation over TCP.
                output.write(bytes, 0, minOf(13, bytes.size)); output.flush()
                output.write(bytes, minOf(13, bytes.size), bytes.size - minOf(13, bytes.size))
            } else output.write(seal(bytes))
            output.flush()
            val head = ByteArrayOutputStream()
            while (true) {
                head.write(readByte())
                if (head.size() > 32768) error("response header too large")
                val bytesSoFar = head.toByteArray()
                if (bytesSoFar.size >= 4 && String(bytesSoFar.takeLast(4).toByteArray()) == "\r\n\r\n") break
            }
            val lines = String(head.toByteArray()).split("\r\n")
            val size = lines.first { it.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt()
            require(size in 0..1024 * 1024)
            return Response(lines[0].split(' ')[1].toInt(), ByteArray(size) { readByte().toByte() })
        }

        fun sendDamagedRequest() {
            val wire = seal("GET /info RTSP/1.0\r\nContent-Length: 0\r\n\r\n".toByteArray())
            wire[wire.lastIndex] = (wire.last().toInt() xor 1).toByte()
            socket.getOutputStream().write(wire); socket.getOutputStream().flush()
        }

        private fun seal(bytes: ByteArray): ByteArray {
            val head = byteArrayOf(bytes.size.toByte(), (bytes.size ushr 8).toByte())
            return head + AirPlayCrypto.chachaSeal(writeKey!!, AirPlayCrypto.nonce64(writeCounter++), bytes, head)
        }

        private fun readByte(): Int {
            if (readKey == null) return socket.getInputStream().read().also { require(it >= 0) { "peer EOF" } }
            if (position == incoming.size) {
                val input = socket.getInputStream()
                val low = input.read(); val high = input.read()
                require(low >= 0 && high >= 0)
                val size = low or (high shl 8)
                require(size <= 16384)
                val wire = ByteArray(size + 16)
                var read = 0
                while (read < wire.size) {
                    val count = input.read(wire, read, wire.size - read)
                    require(count > 0); read += count
                }
                incoming = AirPlayCrypto.chachaOpen(readKey!!, AirPlayCrypto.nonce64(readCounter++), wire, byteArrayOf(low.toByte(), high.toByte()))
                position = 0
            }
            return incoming[position++].toInt() and 255
        }
    }

    /** Synthetic public-key bytes stand in for a certificate; no licensed credential is used. */
    private class SyntheticAccessory : MfiAuthenticator {
        private val curve = ECNamedCurveTable.getByName("secp256r1")
        private val domain = ECDomainParameters(curve.curve, curve.g, curve.n, curve.h)
        private val scalar = BigInteger(255, SecureRandom()).add(BigInteger.ONE)
        private val public = domain.g.multiply(scalar)
        val certificate: ByteArray = public.getEncoded(false)
        override fun protocolMajor() = 3
        override fun readCertificate(maximumOutputLength: Int) = certificate.copyOf()
        override fun signChallenge(challenge: ByteArray): ByteArray {
            val signer = ECDSASigner()
            signer.init(true, ECPrivateKeyParameters(scalar, domain))
            val signature = signer.generateSignature(challenge)
            return unsigned(signature[0]).let { ByteArray(32 - it.size) + it } +
                unsigned(signature[1]).let { ByteArray(32 - it.size) + it }
        }
        fun verify(challenge: ByteArray, signature: ByteArray): Boolean {
            if (signature.size != 64) return false
            val verifier = ECDSASigner()
            verifier.init(false, ECPublicKeyParameters(public, domain))
            return verifier.verifySignature(challenge, BigInteger(1, signature.copyOfRange(0, 32)), BigInteger(1, signature.copyOfRange(32, 64)))
        }
    }

    companion object {
        private fun unsigned(number: BigInteger): ByteArray = number.toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        private fun pad(number: BigInteger): ByteArray = unsigned(number).let { ByteArray(384 - it.size) + it }
        private fun hash(algorithm: String, vararg parts: ByteArray): ByteArray = MessageDigest.getInstance(algorithm).run { parts.forEach { update(it) }; digest() }
        private fun derive(key: ByteArray, salt: String, info: String) = AirPlayCrypto.hkdfSha512(key, salt.toByteArray(), info.toByteArray())
        private fun u32(bytes: ByteArray, offset: Int): Int = (0..3).fold(0) { value, index -> (value shl 8) or (bytes[offset + index].toInt() and 255) }
        private fun tlv(response: Peer.Response): Map<Int, ByteArray> { assertEquals(200, response.status); return Tlv8Codec.decode(response.body).also { assertNull(it[7]) } }
    }
}
