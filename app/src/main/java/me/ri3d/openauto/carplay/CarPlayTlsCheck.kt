package me.ri3d.openauto.carplay

import com.shilapi.xcertplay.transport.LockdownTlsProvider
import com.shilapi.xcertplay.transport.LockdownCertificateCompatibility
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import com.shilapi.xcertplay.transport.LockdownPairRecordGenerator
import com.shilapi.xcertplay.transport.TlsDuplexChannel
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

import java.net.SocketTimeoutException
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.jce.provider.BouncyCastleProvider

import org.bouncycastle.util.encoders.Base64


/**
 * LOCAL SYNTHETIC ONLY. This never uses provisioned MFi credentials, a radio, USB or an iPhone.
 * Run off the UI thread. The production Lockdown TLS client talks to a pinned local TLS server,
 * so a shrunk API16 APK actually executes provider lookup, ECDHE, RSA and AES-GCM primitives.
 */
object CarPlayTlsCheck {
    private const val CIPHER = "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"
    private const val TOTAL_MILLIS = 30_000L
    private val request = "OpenAuto synthetic client application bytes".toByteArray(Charsets.US_ASCII)
    private val response = "OpenAuto synthetic server application bytes".toByteArray(Charsets.US_ASCII)

    @JvmStatic
    fun run(): String {
        val started = System.nanoTime()
        fun remaining(): Int {
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            if (elapsed >= TOTAL_MILLIS) throw IOException("LOCAL SYNTHETIC TLS deadline exceeded")
            return (TOTAL_MILLIS - elapsed).coerceAtLeast(1).toInt()
        }
        val serverFailure = AtomicReference<Throwable?>()
        val accepted = AtomicReference<SSLSocket?>()
        val completed = CountDownLatch(1)
        val transferred = CountDownLatch(1)
        val releaseServer = CountDownLatch(1)
        var server: SSLServerSocket? = null
        var clientSocket: Socket? = null
        var channel: TlsDuplexChannel? = null
        var serverThread: Thread? = null
        var stage = "synthetic identity generation"
        try {
            // Providers are explicit instances: no process-wide Security.addProvider mutation.
            val bc = BouncyCastleProvider()
            val jsse = LockdownTlsProvider.create(bc)
            val device = KeyPairGenerator.getInstance("RSA", bc).apply { initialize(2048) }.generateKeyPair()
            val pkcs1 = SubjectPublicKeyInfo.getInstance(device.public.encoded).parsePublicKey().encoded
            val devicePem = ("-----BEGIN RSA PUBLIC KEY-----\n" + Base64.toBase64String(pkcs1) +
                "\n-----END RSA PUBLIC KEY-----\n").toByteArray(Charsets.US_ASCII)
            val record = try {
                LockdownPairRecordGenerator.generate(devicePem, "02:00:00:00:00:02", "synthetic-host", "synthetic-buid")
            } finally { pkcs1.fill(0); devicePem.fill(0) }
            remaining()
            fun certificate(pem: ByteArray): X509Certificate = try {
                LockdownCertificateCompatibility.withPinnedProfile {
                    LockdownTlsProvider.normalize(CertificateFactory.getInstance("X.509", bc).generateCertificate(ByteArrayInputStream(pem)) as X509Certificate)
                }
            } finally { pem.fill(0) }
            val deviceCertificate = certificate(record.deviceCertificatePem)
            val rootCertificate = certificate(record.rootCertificatePem)
            val serverContext = SSLContext.getInstance("TLS", jsse).apply {
                init(arrayOf(ServerIdentity(device.private, arrayOf(deviceCertificate, rootCertificate))),
                    arrayOf(ClientPin(rootCertificate)), SecureRandom())
            }
            stage = "server TLS configuration"
            val loopback = InetAddress.getByName("127.0.0.1")
            val bound = serverContext.serverSocketFactory.createServerSocket(0, 1, loopback) as SSLServerSocket
            server = bound
            check(bound.supportedCipherSuites.contains(CIPHER)) { "Required synthetic TLS cipher is unavailable" }
            bound.enabledProtocols = arrayOf("TLSv1.2")
            bound.enabledCipherSuites = arrayOf(CIPHER)
            bound.needClientAuth = true
            bound.soTimeout = minOf(12_000, remaining())
            serverThread = Thread({
                try {
                    val socket = bound.accept() as SSLSocket
                    accepted.set(socket)
                    socket.soTimeout = minOf(12_000, remaining())
                    LockdownCertificateCompatibility.withPinnedProfile { socket.startHandshake() }
                    check(socket.session.protocol == "TLSv1.2") { "Synthetic TLS negotiated an unexpected protocol" }
                    check(socket.session.cipherSuite == CIPHER) { "Synthetic TLS negotiated an unexpected cipher" }
                    val input = DataInputStream(socket.inputStream)
                    val received = ByteArray(request.size)
                    input.readFully(received)
                    check(MessageDigest.isEqual(request, received)) { "Synthetic client application bytes changed" }
                    received.fill(0)
                    socket.outputStream.write(response)
                    socket.outputStream.flush()
                    check(input.read() == 0x5a) { "Synthetic application acknowledgement missing" }
                    transferred.countDown()
                    if (!releaseServer.await(remaining().toLong(), TimeUnit.MILLISECONDS))
                        throw IOException("Synthetic TLS close confirmation timed out")
                } catch (failure: Throwable) {
                    serverFailure.set(failure)
                } finally {
                    closeQuietly(accepted.get())
                    completed.countDown()
                }
            }, "carplay-local-synthetic-tls").apply { isDaemon = true; start() }
            stage = "production client handshake"
            val socket = Socket()
            clientSocket = socket
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(loopback, bound.localPort), minOf(5_000, remaining()))
            channel = TlsDuplexChannel.open(SocketStream(socket, ::remaining), record,
                handshakeTimeoutMillis = minOf(12_000, remaining()).toLong())
            stage = "authenticated bidirectional application data"
            channel.send(request)
            val received = ByteArray(response.size)
            var offset = 0
            while (offset < received.size) {
                val chunk = channel.recv(received.size - offset, minOf(5_000, remaining()).toLong())
                    ?: throw IOException("Synthetic TLS application receive timed out")
                if (chunk.isEmpty()) throw IOException("Synthetic TLS ended before application data")
                if (chunk.size > received.size - offset) throw IOException("Synthetic TLS exceeded its receive bound")
                chunk.copyInto(received, offset)
                offset += chunk.size
            }
            check(MessageDigest.isEqual(response, received)) { "Synthetic server application bytes changed" }
            received.fill(0)
            channel.send(byteArrayOf(0x5a))
            if (!transferred.await(minOf(5_000, remaining()).toLong(), TimeUnit.MILLISECONDS))
                throw IOException("Synthetic TLS server did not finish")
            serverFailure.get()?.let { throw IOException("Synthetic TLS server failed", it) }
            stage = "production direct close"
            val closeStarted = System.nanoTime()
            channel.close()
            channel = null
            check(socket.isClosed) { "Production TLS close did not close its owned socket" }
            check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - closeStarted) < 1_000) {
                "Production TLS close exceeded one second"
            }
            releaseServer.countDown()
            if (!completed.await(minOf(5_000, remaining()).toLong(), TimeUnit.MILLISECONDS))
                throw IOException("Synthetic TLS server close did not finish")
            serverFailure.get()?.let { throw IOException("Synthetic TLS server failed", it) }
            return "LOCAL SYNTHETIC ONLY: TLSv1.2 $CIPHER; mutual pinned identities; application bytes both ways; direct close passed"
        } catch (failure: Throwable) {
            val cause = serverFailure.get() ?: failure
            throw IllegalStateException("LOCAL SYNTHETIC TLS failed during $stage (${cause.javaClass.simpleName})", cause)
        } finally {
            releaseServer.countDown()
            closeQuietly(channel)
            closeQuietly(clientSocket)
            closeQuietly(accepted.get())
            closeQuietly(server)
            serverThread?.let { worker ->
                try { worker.join(1_000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                if (worker.isAlive) worker.interrupt()
            }
        }
    }

    private class SocketStream(private val socket: Socket, private val remaining: () -> Int) : BlockingDuplexByteStream {
        private val input = socket.getInputStream()
        private val output = socket.getOutputStream()
        override fun send(data: ByteArray) {
            remaining()
            require(data.size <= 65_536) { "Synthetic TLS write exceeds its bound" }
            output.write(data)
            output.flush()
        }
        override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
            require(maxBytes > 0 && timeoutMillis > 0)
            socket.soTimeout = minOf(timeoutMillis, remaining().toLong(), 12_000L).toInt()
            val bytes = ByteArray(minOf(maxBytes, 16_384))
            return try {
                val count = input.read(bytes)
                if (count < 0) ByteArray(0) else bytes.copyOf(count)
            } catch (_: SocketTimeoutException) { null }
        }
        override fun close() = socket.close()
    }

    /** X509KeyManager is available on API16; X509ExtendedKeyManager is deliberately avoided. */
    private class ServerIdentity(private val key: PrivateKey, private val chain: Array<X509Certificate>) : X509KeyManager {
        private val traceBudget = AtomicInteger(12)
        // BC's legacy TLS1.2 RSA signatures request ECDHE_RSA; RSA-PSS signatures request RSA.
        // Both select this same RSA identity, while EC/DSA/other credential requests remain rejected.
        private fun accepts(keyType: String?): Boolean = keyType == "RSA" || keyType == "ECDHE_RSA"
        private fun keyTrace(operation: String, detail: String) {
            if (traceBudget.getAndDecrement() > 0) trace("key-manager $operation $detail")
        }
        override fun getClientAliases(keyType: String?, issuers: Array<out java.security.Principal>?): Array<String>? = null
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out java.security.Principal>?, socket: Socket?): String? = null
        override fun getServerAliases(keyType: String?, issuers: Array<out java.security.Principal>?): Array<String>? {
            keyTrace("getServerAliases", "keyType=$keyType accepted=${accepts(keyType)}")
            return if (accepts(keyType)) arrayOf("synthetic-device") else null
        }
        override fun chooseServerAlias(keyType: String?, issuers: Array<out java.security.Principal>?, socket: Socket?): String? {
            keyTrace("chooseServerAlias", "keyType=$keyType accepted=${accepts(keyType)}")
            return if (accepts(keyType)) "synthetic-device" else null
        }
        override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
            if (alias == "synthetic-device") chain.copyOf() else null
        override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == "synthetic-device") key else null
    }

    private class ClientPin(private val certificate: X509Certificate) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            if (chain.isNullOrEmpty() || !MessageDigest.isEqual(certificate.publicKey.encoded, chain[0].publicKey.encoded))
                throw CertificateException("Synthetic TLS client key does not match its pair record")
        }
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("Synthetic server trust manager cannot authenticate a server")
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf(certificate)
    }

    private fun trace(message: String) {
        try { android.util.Log.i("CarPlayTlsCheck", "LOCAL SYNTHETIC ONLY: $message") }
        catch (_: Throwable) { /* Host JVM Android stubs have no logging implementation. */ }
    }
    // Android API16 Socket and ServerSocket do not implement java.io.Closeable.
    private fun closeQuietly(socket: Socket?) {
        try { socket?.close() } catch (_: Throwable) { }
    }
    private fun closeQuietly(server: ServerSocket?) {
        try { server?.close() } catch (_: Throwable) { }
    }
    private fun closeQuietly(resource: Closeable?) {
        try { resource?.close() } catch (_: Throwable) { /* Preserve the diagnostic's original failure. */ }
    }
}
