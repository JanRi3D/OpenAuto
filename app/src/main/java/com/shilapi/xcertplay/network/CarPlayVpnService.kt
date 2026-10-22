// Adapted from DiPlay 143f4674b60b564a16baae5110b3b90fb7277631 (GPL-3.0). See THIRD_PARTY_NOTICES.md.
package com.shilapi.xcertplay.network

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.annotation.TargetApi
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.shilapi.xcertplay.airplay.AirPlayListenerIdentity
import com.shilapi.xcertplay.airplay.AirPlayTcpAccepted
import com.shilapi.xcertplay.airplay.isInternalAirPlayPeer
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.transport.NcmUsbBridge
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hosts the AirPlay TCP listener for both NCM/VPN and local-only Wi-Fi transports.
 *
 * The wired path also owns the Android VPN tunnel and NCM IPv6 bridge. VPN consent is requested
 * with [prepare] before binding.
 */
class CarPlayVpnService : VpnService() {
    inner class LocalBinder : Binder() {
        val service: CarPlayVpnService get() = this@CarPlayVpnService
    }

    sealed class AttachResult {
        data object Started : AttachResult()
        data object AlreadyStarted : AttachResult()
        data class Failed(val message: String) : AttachResult()
    }

    private data class AirPlayAttachment(
        val address: InetAddress,
        val config: AirPlayConfig,
        val identity: AirPlayIdentity,
        val pairings: PairingStore,
        val mfi: MfiAuthenticator?,
        val listener: AirPlaySessionListener,
        val media: AirPlayMediaHandler,
        val additionalAddresses: List<InetAddress> = emptyList(),
        val listenerIdentity: AirPlayListenerIdentity? = null,
        val onSession: (AirPlaySession) -> Unit = {},
    )

    private val binder = LocalBinder()
    private val active = AtomicBoolean(false)
    private val sessionsLock = Any()
    private val sessions = mutableSetOf<AirPlaySession>()
    @Volatile private var attachment: AirPlayAttachment? = null
    private var serverSocket: ServerSocket? = null
    private var additionalServers: List<ServerSocket> = emptyList()
    private var bridge: Ipv6NcmBridge? = null
    private var tun: ParcelFileDescriptor? = null
    private var attachGeneration = 0

    /** The exception chain behind the latest [AttachResult.Failed], for the diagnostic log. */
    @Volatile var lastAttachFailure: String? = null
        private set

    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == SERVICE_INTERFACE) super.onBind(intent) else binder

    @Synchronized
    fun attach(
        ncm: NcmUsbBridge,
        linkLocal: String,
        hostMac: ByteArray,
        config: AirPlayConfig,
        identity: AirPlayIdentity,
        pairings: PairingStore,
        mfi: MfiAuthenticator?,
        listener: AirPlaySessionListener,
        media: AirPlayMediaHandler,
        onSession: (AirPlaySession) -> Unit = {},
    ): AttachResult {
        if (active.get()) {
            Log.i(TAG, "replacing stale NCM/VPN attachment")
            releaseLocked()
        }
        active.set(true)
        val generation = ++attachGeneration
        return try {
            val address = InetAddress.getByName(linkLocal)
            if (address !is Inet6Address || !address.isLinkLocalAddress) {
                throw IllegalArgumentException("linkLocal must be a link-local IPv6 literal")
            }
            require(hostMac.size == 6) { "hostMac must be 6 bytes" }

            val builder = Builder()
                .addAddress(linkLocal, LINK_PREFIX)
                .addRoute(LINK_LOCAL_ROUTE, LINK_PREFIX)
                .setSession(SESSION_NAME)
                .setMtu(TUN_MTU)
            if (Build.VERSION.SDK_INT >= 21) Api21.scopeAndBlock(builder, packageName)
            else listener.onDebugLog("API 16-20 USB VPN routes only fe80::/64; per-app VPN scoping is unavailable")
            val tunFd = builder.establish() ?: throw IOException("VpnService.establish returned null")
            tun = tunFd

            val ipv6Bridge = Ipv6NcmBridge(ncm, tunFd, hostMac) { error ->
                onTransportError(generation, listener, error)
            }
            ipv6Bridge.start()
            bridge = ipv6Bridge

            startAirPlayServer(
                generation,
                AirPlayAttachment(scopedToTunnel(address, listener), config, identity, pairings, mfi, listener, media, onSession = onSession),
            )
            AttachResult.Started
        } catch (error: Exception) {
            releaseLocked()
            lastAttachFailure = attachFailure(error)
            AttachResult.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    /**
     * Starts the AirPlay listener on the local-only Wi-Fi AP address without establishing a VPN or
     * NCM bridge.
     */
    @Synchronized
    fun attachWireless(
        bindAddress: InetAddress,
        config: AirPlayConfig,
        identity: AirPlayIdentity,
        pairings: PairingStore,
        mfi: MfiAuthenticator?,
        listener: AirPlaySessionListener,
        media: AirPlayMediaHandler,
        additionalBindAddresses: List<InetAddress> = emptyList(),
        listenerIdentity: AirPlayListenerIdentity? = null,
    ): AttachResult {
        if (active.get()) {
            Log.i(TAG, "replacing stale local-only Wi-Fi attachment")
            releaseLocked()
        }
        active.set(true)
        val generation = ++attachGeneration
        return try {
            startAirPlayServer(
                generation,
                AirPlayAttachment(bindAddress, config, identity, pairings, mfi, listener, media,
                    additionalBindAddresses, listenerIdentity),
            )
            AttachResult.Started
        } catch (error: Exception) {
            releaseLocked()
            lastAttachFailure = attachFailure(error)
            AttachResult.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    /** Releases the active AirPlay listener and whichever VPN/NCM transport resources are active. */
    @Synchronized
    fun detach() {
        releaseLocked()
    }

    @Synchronized
    fun detachWireless(owner: AirPlayListenerIdentity) {
        if (attachment?.listenerIdentity === owner) releaseLocked()
    }

    fun isAttached(): Boolean = active.get() && attachment != null

    /** Port the AirPlay listener actually bound, which may differ from the configured port. */
    fun boundPort(): Int? = attachment?.config?.port

    override fun onRevoke() {
        val current = attachment?.listener
        detach()
        current?.onTransportError("CarPlay VPN consent was revoked")
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        detach()
        super.onDestroy()
    }

    /**
     * Linux rejects bind() to a link-local IPv6 address without a scope id (EINVAL). Android 8+
     * IoBridge.bind looks the scope up itself, but Android 7.x binds natively in
     * PlainSocketImpl.socketBind and never derives one, so the unscoped wired listener failed with
     * "Invalid argument". Scope it to the tunnel that owns the address on every version; when no
     * interface owns it, fail the attachment explicitly.
     */
    private fun scopedToTunnel(address: Inet6Address, listener: AirPlaySessionListener): InetAddress {
        if (address.scopeId != 0) return address
        var interfaces = emptyList<TunnelCandidate>()
        repeat(TUN_LOOKUP_ATTEMPTS) { attempt ->
            if (attempt > 0) Thread.sleep(TUN_LOOKUP_DELAY_MILLIS)
            interfaces = tunnelCandidates()
            selectTunnelInterface(address, interfaces)?.let { owner ->
                runCatching { listener.onDebugLog("wired AirPlay listener scope iface=${owner.name} index=${owner.index}") }
                return Inet6Address.getByAddress(null, address.address, owner.index)
            }
        }
        val tunnels = interfaces.filter { it.name.startsWith("tun") }.joinToString(",") { it.name }.ifEmpty { "none" }
        runCatching { listener.onDebugLog("wired AirPlay listener scope iface=not_found tun=$tunnels; listener scope unavailable") }
        throw IOException("Cannot find the VPN interface owning the wired IPv6 address")
    }

    private fun tunnelCandidates(): List<TunnelCandidate> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.let { Collections.list(it) }.orEmpty().map { networkInterface ->
            val addresses = Collections.list(networkInterface.inetAddresses)
            val index = if (Build.VERSION.SDK_INT >= 19) Api19.index(networkInterface) else
                addresses.filterIsInstance<Inet6Address>().firstOrNull { it.scopeId > 0 }?.scopeId
                    ?: runCatching { java.io.File("/sys/class/net/${networkInterface.name}/ifindex").readText().trim().toInt() }.getOrDefault(0)
            TunnelCandidate(networkInterface.name, index, addresses)
        }
    }.getOrDefault(emptyList())

    private fun startAirPlayServer(
        generation: Int,
        replacement: AirPlayAttachment,
    ) {
        val servers = if (replacement.additionalAddresses.isEmpty()) {
            listOf(PortSelector.bind(replacement.address, replacement.config.port) { busy, bound ->
                Log.w(TAG, "AirPlay port $busy is in use; listening on $bound instead")
            })
        } else {
            PortSelector.bindAll(listOf(replacement.address) + replacement.additionalAddresses,
                replacement.config.port) { busy, bound ->
                Log.w(TAG, "AirPlay port $busy is in use; listening on $bound instead")
            }
        }
        val server = servers.first()
        attachment = replacement.copy(config = replacement.config.copy(port = server.localPort))
        serverSocket = server
        additionalServers = servers.drop(1)
        servers.forEach { bound ->
            runCatching { replacement.listener.onDebugLog(
                "airplay listener ready family=${if (bound.inetAddress is Inet6Address) "IPv6" else "IPv4"} port=${bound.localPort}",
            ) }
            Thread(
                { acceptLoop(generation, bound) },
                "airplay-accept",
            ).apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun acceptLoop(
        generation: Int,
        server: ServerSocket,
    ) {
        try {
            while (active.get()) {
                val socket: Socket = server.accept()
                val acceptedAtNanos = System.nanoTime()
                Log.i(TAG, "airplay connection accepted from ${socket.remoteSocketAddress}")
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.setSoLinger(true, 0)
                val session = synchronized(this) {
                    if (!active.get() || generation != attachGeneration ||
                        (serverSocket !== server && additionalServers.none { it === server })) {
                        socket.close()
                        return
                    }
                    val current = attachment
                    if (current == null) {
                        socket.close()
                        return
                    }
                    if (synchronized(sessionsLock) { sessions.size >= 4 }) {
                        socket.close()
                        throw IOException("Too many concurrent CarPlay control connections")
                    }
                    val internalPeer = isInternalAirPlayPeer(socket.inetAddress, socket.localAddress)
                    current.listenerIdentity?.let { owner ->
                        current.listener.onTcpAccepted(AirPlayTcpAccepted(
                            owner, internalPeer, acceptedAtNanos,
                        ))
                    }
                    runCatching { current.listener.onDebugLog(
                        "airplay TCP accepted family=${if (socket.inetAddress is Inet6Address) "IPv6" else "IPv4"}",
                    ) }
                    AirPlaySession(
                        socket = socket,
                        config = current.config,
                        identity = current.identity,
                        pairings = current.pairings,
                        mfi = current.mfi,
                        listener = object : AirPlaySessionListener by current.listener {
                            override fun onSessionActive(session: AirPlaySession) {
                                if (active.get() && generation == attachGeneration && (current.listenerIdentity == null || !internalPeer)) current.listener.onSessionActive(session)
                            }

                            override fun onRemoteControlMessage(
                                session: AirPlaySession,
                                streamId: Long,
                                message: Map<String, Any?>,
                            ) {
                                current.listener.onRemoteControlMessage(session, streamId, message)
                            }

                            override fun onVideoPlaybackUiRequested(session: AirPlaySession) {
                                current.listener.onVideoPlaybackUiRequested(session)
                            }

                            override fun onSessionEnded(session: AirPlaySession) {
                                removeSession(session)
                                if (active.get() && generation == attachGeneration && (current.listenerIdentity == null || !internalPeer)) current.listener.onSessionEnded(session)
                            }
                        },
                        media = current.media,
                    ).also {
                        addSession(it)
                        try { current.onSession(it) } catch (error: Exception) {
                            it.close(); removeSession(it)
                            throw IOException("CarPlay session callback failed", error)
                        }
                    }
                }
                if (active.get() && generation == attachGeneration) session.start() else session.close()
            }
        } catch (error: Exception) {
            if (active.get()) {
                attachment?.listener?.let { onTransportError(generation, it, error) }
            }
        }
    }

    private fun addSession(session: AirPlaySession) {
        synchronized(sessionsLock) { sessions.add(session) }
    }

    private fun removeSession(session: AirPlaySession?) {
        if (session == null) return
        synchronized(sessionsLock) { sessions.remove(session) }
    }

    private fun closeSessionsLocked() {
        synchronized(sessionsLock) {
            sessions.toList().forEach { session ->
                try {
                    session.close()
                } catch (error: Exception) {
                    Log.w(TAG, "AirPlay session replacement failed", error)
                }
            }
            sessions.clear()
        }
    }

    private fun onTransportError(
        generation: Int,
        listener: AirPlaySessionListener,
        error: Throwable,
    ) {
        val message = error.message ?: error.javaClass.simpleName
        Log.e(TAG, "CarPlay transport stopped: $message", error)
        Thread(
            {
                val releasedGeneration = synchronized(this) {
                    if (generation != attachGeneration) return@Thread
                    releaseLocked()
                    attachGeneration
                }
                listener.onTransportError(message)
                synchronized(this) {
                    if (attachGeneration == releasedGeneration && !active.get()) stopSelf()
                }
            },
            "airplay-teardown",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /** Caller must hold this service's monitor. Closes only resources active for this attachment. */
    private fun releaseLocked() {
        attachGeneration += 1
        active.set(false)
        attachment = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        additionalServers.forEach { runCatching { it.close() } }
        additionalServers = emptyList()
        runCatching { closeSessionsLocked() }
        runCatching { bridge?.close() }
        bridge = null
        runCatching { tun?.close() }
        tun = null
    }

    @TargetApi(19) private object Api19 {
        fun index(networkInterface: NetworkInterface): Int = networkInterface.index
    }
    @TargetApi(21) private object Api21 {
        fun scopeAndBlock(builder: Builder, packageName: String) {
            builder.setBlocking(true)
            builder.addAllowedApplication(packageName)
        }
    }
    private object PortSelector {
        fun bind(address: InetAddress, port: Int, onFallback: (Int, Int) -> Unit): ServerSocket =
            bindAll(listOf(address), port, onFallback).single()
        fun bindAll(addresses: List<InetAddress>, preferred: Int, onFallback: (Int, Int) -> Unit): List<ServerSocket> {
            for (port in (listOf(preferred) + (7001..7010).toList() + listOf(0)).distinct()) {
                val servers = mutableListOf<ServerSocket>()
                try {
                    for (address in addresses.distinct()) {
                        val server = ServerSocket()
                        try { server.bind(java.net.InetSocketAddress(address, servers.firstOrNull()?.localPort ?: port), 4) }
                        catch (error: Exception) { server.close(); throw error }
                        servers.add(server)
                    }
                    if (preferred != 0 && servers.first().localPort != preferred) onFallback(preferred, servers.first().localPort)
                    return servers
                } catch (error: Exception) {
                    servers.forEach { runCatching { it.close() } }
                    if (error !is java.net.BindException) throw error
                }
            }
            throw java.net.BindException("No AirPlay listener port available")
        }
    }
    companion object {
        private const val TAG = "xcertplay-usb"
        private const val LINK_PREFIX = 64
        private const val LINK_LOCAL_ROUTE = "fe80::"
        private const val SESSION_NAME = "xcertplay CarPlay"
        private const val TUN_MTU = 1500
        private const val TUN_LOOKUP_ATTEMPTS = 10
        private const val TUN_LOOKUP_DELAY_MILLIS = 50L

        internal data class TunnelCandidate(val name: String, val index: Int, val addresses: List<InetAddress>)

        /**
         * Picks the interface that owns [address]. A tun interface wins when another interface
         * happens to carry the same link-local address.
         */
        internal fun selectTunnelInterface(address: Inet6Address, interfaces: List<TunnelCandidate>): TunnelCandidate? {
            val owners = interfaces.filter { candidate ->
                candidate.index > 0 && candidate.addresses.any { owned ->
                    owned is Inet6Address && owned.address.contentEquals(address.address)
                }
            }
            return owners.firstOrNull { it.name.startsWith("tun") } ?: owners.firstOrNull()
        }

        /** Returns the VPN consent intent, or null when consent is already granted. */
        fun prepare(context: Context): Intent? = VpnService.prepare(context)

        /**
         * Android 7.1 reported only "Invalid argument" when establish failed (#557); name the
         * exception chain and the frame that threw, so the rejected VPN setting can be identified.
         */
        internal fun attachFailure(error: Throwable): String {
            val chain = generateSequence(error) { it.cause }.take(4).joinToString(" <- ") { failure ->
                "${failure.javaClass.simpleName}: ${failure.message ?: "no message"}"
            }
            val origin = error.stackTrace.firstOrNull()
                ?.let { " at ${it.className.substringAfterLast('.')}.${it.methodName}" }.orEmpty()
            return chain + origin
        }
    }
}
