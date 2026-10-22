package me.ri3d.openauto.carplay

import android.content.*
import android.hardware.usb.*
import android.os.IBinder
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.mfi.*
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.transport.*
import java.io.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One cancellable USB/Lockdown/iAP2/NCM attempt. All blocking work stays off the UI thread. */
class CarPlayUsbConnector(
    context: Context,
    private val scope: AttemptScope,
    private val config: AirPlayConfig,
    private val identity: AirPlayIdentity,
    private val pairings: PairingStore,
    private val mfi: MfiAuthenticator,
    private val media: AirPlayMediaHandler,
    private val listener: AirPlaySessionListener,
    private val progress: (String) -> Unit,
    private val onSession: (AirPlaySession) -> Unit,
) : Closeable {
    private val context = context.applicationContext
    private val resources = AttemptScope()
    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val lock = Object()
    private val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val host = IphoneUsbHost(context, usb, IphoneUsbMatcher.appleVendor(), onDiagnostic = ::report)
    private var permission: IphoneUsbHost.PermissionResult? = null
    private var bound = false
    private var service: CarPlayVpnService? = null
    private val serviceReady = CountDownLatch(1)
    private var deviceName: String? = null
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            synchronized(lock) {
                if (open()) service = (binder as CarPlayVpnService.LocalBinder).service
                serviceReady.countDown()
            }
        }
        override fun onServiceDisconnected(name: ComponentName) { fail(IOException("CarPlay VPN service disconnected")) }
    }

    init { scope.own(this) }

    fun start() {
        check(started.compareAndSet(false, true)) { "USB connector already started" }
        if (!open()) return
        resources.own(host.registerPermissionReceiver { result -> synchronized(lock) { permission = result; lock.notifyAll() } })
        resources.own(host.registerAttachReceiver { synchronized(lock) { lock.notifyAll() } })
        val detached = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                @Suppress("DEPRECATION") val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                if (device?.deviceName == deviceName && started.get()) fail(IOException("iPhone USB cable disconnected"))
            }
        }
        @Suppress("DEPRECATION") context.registerReceiver(detached, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED))
        resources.own(Closeable { runCatching { context.unregisterReceiver(detached) } })
        Thread({
            try { connect() } catch (error: Exception) { if (open()) fail(error) }
        }, "carplay-usb-connect").apply { isDaemon = true; start() }
    }

    private fun connect() {
        report("USB: waiting for an iPhone")
        var device = awaitDevice(60_000)
        grant(device)
        val alreadyCarPlay = inspectConfiguration(device)?.let(IphoneCarPlayConfiguration::isCarPlayMode) == true
        if (!alreadyCarPlay) {
            report("USB: requesting CarPlay mode")
            var result: IphoneUsbHost.TransitionResult? = null
            host.requestCarPlayReenumerationAsync(device, Executor { it.run() }) { result = it }
            when (val transition = result) {
                is IphoneUsbHost.TransitionResult.Failed -> {
                    // Some iOS versions reject the vendor request while already exposing NCM.
                    if (inspectConfiguration(device) == null) throw transition.error
                }
                else -> {
                    val oldName = device.deviceName
                    val start = System.nanoTime()
                    while (open()) {
                        val listed = host.discover()
                        val changed = listed.firstOrNull { it.deviceName != oldName }
                        if (changed != null) { device = changed; break }
                        if (elapsedMillis(start) >= 2500) {
                            val same = listed.firstOrNull { it.deviceName == oldName }
                            if (same != null) { device = same; break }
                        }
                        if (elapsedMillis(start) >= 15_000) throw IOException("iPhone did not return after USB mode change")
                        pause(200)
                    }
                    grant(device)
                }
            }
        }
        requireOpen()
        deviceName = device.deviceName
        var opened: IphoneUsbHost.Iap2SessionResult? = null
        host.openIap2UsbSessionAsync(device, Executor { it.run() }) { opened = it }
        val pipe = when (val result = opened) {
            is IphoneUsbHost.Iap2SessionResult.Connected -> resources.own(result.session)
            is IphoneUsbHost.Iap2SessionResult.Failed -> throw result.error
            else -> throw IOException("USBMUX pipe did not open")
        }
        requireOpen()
        report("USB: configuration and USBMUX pipe opened")
        val ncmConnection = usb.openDevice(device) ?: throw IOException("Cannot open independent NCM connection")
        var carPlayInterfaceNumber = -1
        val ncm = try {
            val configuration = IphoneCarPlayConfiguration.find(device, ncmConnection)
                ?: throw IOException("iPhone exposes no verified CarPlay NCM configuration")
            if (UsbPlatformCompatibility.activeConfiguration(ncmConnection) != configuration.id)
                throw IOException("NCM configuration is not the active USB configuration")
            val function = NcmFunctionDiscovery.find(configuration) ?: throw IOException("NCM endpoints unavailable on this Android version")
            carPlayInterfaceNumber = function.control.id
            resources.own(NcmUsbBridge.open(ncmConnection, function, ::report))
        } catch (error: Throwable) { ncmConnection.close(); throw error }
        val mux = resources.own(Iap2UsbMuxHost.open(pipe, onDiagnostic = ::report))
        requireOpen()
        val udid = readPhoneId(mux)
        val store = UsbPairRecordStore(File(context.filesDir, "carplay-usb-pairings"), udid)
        val preferences = context.getSharedPreferences("carplay-usb", Context.MODE_PRIVATE)
        fun stableId(key: String): String = preferences.getString(key, null) ?: UUID.randomUUID().toString().also {
            if (!preferences.edit().putString(key, it).commit()) throw IOException("Cannot persist USB accessory identity")
        }
        val pairing = LockdownPairingClient(mux)
        fun pair(): LockdownPairRecord {
            report("USB: accept the iPhone trust dialog if shown")
            return pairing.pair("OpenAuto", stableId("host-id"), stableId("system-buid"), 5 * 60_000) { !open() }
                .pairRecord.also(store::save)
        }
        var record = store.load()
        val saved = record != null
        if (record == null) record = pair()
        val client = LockdownCarKitClient(mux)
        val carkit = try { client.open(record, "OpenAuto") } catch (error: Exception) {
            if (!saved || !isPairRecordRejected(error)) throw error
            store.clear()
            record = pair()
            client.open(record, "OpenAuto")
        }
        resources.own(carkit)
        val iap = resources.own(Iap2Session.open(carkit, onTrace = listener::onDebugLog))
        val mac = ncm.hostMac ?: byteArrayOf(2, 0, 0, 0, 0, 2)
        val linkLocal = "fe80::2"
        val vpn = awaitService()
        requireOpen()
        when (val result = vpn.attach(ncm, linkLocal, mac, config, identity, pairings, mfi,
            listener, media, onSession = { session -> if (open()) onSession(session) else session.close() })) {
            is CarPlayVpnService.AttachResult.Failed -> throw IOException("USB VPN/NCM attach failed: ${result.message}")
            CarPlayVpnService.AttachResult.AlreadyStarted -> throw IOException("USB VPN transport already belongs to another attempt")
            CarPlayVpnService.AttachResult.Started -> report("USB: NCM/VPN listener established; waiting for iPhone authentication")
        }
        if (!iap.awaitReady(30_000)) throw IOException("Wired iAP2 link negotiation timed out")
        val identification = Iap2IdentificationConfig(config.deviceName, config.model, config.manufacturer,
            config.deviceId, "0.5.0", "1", carPlayUsbInterfaceNumber = carPlayInterfaceNumber)
        val endpoint = Iap2WiredCarPlayEndpoint(listOf(linkLocal), vpn.boundPort() ?: throw IOException("No AirPlay listener"),
            identity.publicKeyHex, config.sourceVersion, mac.joinToString(":") { "%02x".format(it.toInt() and 255) })
        Iap2WiredControlClient(iap, Iap2MfiAuthenticationClient(mfi)).run(identification, endpoint,
            availableCurrentMilliAmps = 0, timeoutMillis = Iap2WiredControlClient.NO_TIMEOUT_MILLIS, onProgress = ::report)
        if (open()) throw IOException("Wired iAP2 control channel closed")
    }

    private fun inspectConfiguration(device: UsbDevice): UsbConfigurationSnapshot? {
        val connection = usb.openDevice(device) ?: return null
        return try { IphoneCarPlayConfiguration.find(device, connection) } finally { connection.close() }
    }

    private fun awaitDevice(timeout: Long): UsbDevice {
        val start = System.nanoTime()
        while (open()) {
            val devices = host.discover()
            if (devices.size > 1) throw IOException("Connect only one Apple USB device for CarPlay")
            devices.singleOrNull()?.let { return it }
            if (elapsedMillis(start) >= timeout) throw IOException("No iPhone attached before USB timeout")
            pause(250)
        }
        throw IOException("USB connection cancelled")
    }

    private fun grant(device: UsbDevice) {
        requireOpen()
        synchronized(lock) { permission = null }
        if (host.requestPermission(device) is IphoneUsbHost.PermissionRequest.AlreadyGranted) return
        report("USB: allow OpenAuto to access the iPhone")
        val start = System.nanoTime()
        while (open()) {
            val result = synchronized(lock) { permission }
            if (result is IphoneUsbHost.PermissionResult.Granted && result.device.deviceName == device.deviceName) return
            if (result is IphoneUsbHost.PermissionResult.Denied && result.device.deviceName == device.deviceName)
                throw IOException("USB permission denied")
            if (elapsedMillis(start) >= 120_000) throw IOException("USB permission request timed out")
            pause(200)
        }
        requireOpen()
    }

    private fun readPhoneId(mux: Iap2UsbMuxHost): String = LockdownPlistChannel(mux.connect()).use { channel ->
        val response = channel.request(LockdownPlistValue.Dictionary(linkedMapOf(
            "Label" to LockdownPlistValue.Text("OpenAuto"), "Request" to LockdownPlistValue.Text("GetValue"),
            "Key" to LockdownPlistValue.Text("UniqueDeviceID"))))
        val id = (response.entries["Value"] as? LockdownPlistValue.Text)?.value
            ?: throw IOException("iPhone did not supply a stable USB pairing identifier")
        require(id.length in 16..128 && id.all { it.isLetterOrDigit() || it == '-' }) { "Invalid USB pairing identifier" }
        id
    }

    private fun awaitService(): CarPlayVpnService {
        requireOpen()
        synchronized(lock) {
            if (!bound) {
                bound = context.bindService(Intent(context, CarPlayVpnService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
                if (!bound) throw IOException("Could not bind CarPlay VPN service")
            }
        }
        if (!serviceReady.await(10, TimeUnit.SECONDS)) throw IOException("CarPlay VPN service bind timed out")
        requireOpen()
        return service ?: throw IOException("CarPlay VPN service unavailable")
    }

    private fun open(): Boolean = !closed.get() && scope.isOpen
    private fun requireOpen() { if (!open()) throw IOException("USB attempt cancelled") }
    private fun pause(millis: Long) { synchronized(lock) { if (open()) lock.wait(millis) } }
    private fun report(message: String) { if (open()) progress(message) }
    private fun fail(error: Exception) { if (open()) { try { listener.onTransportError(error.message ?: error.javaClass.simpleName) } finally { close() } } }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) { lock.notifyAll(); serviceReady.countDown() }
        runCatching { service?.detach() }
        resources.close()
        synchronized(lock) {
            if (bound) { bound = false; runCatching { context.unbindService(serviceConnection) } }
            service = null
        }
    }
    private fun elapsedMillis(start: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
    private fun isPairRecordRejected(error: Throwable): Boolean = generateSequence(error) { it.cause }.take(8).any {
        it.message.orEmpty().contains("InvalidPairRecord", true) || it.message.orEmpty().contains("InvalidHostID", true)
    }
}

/** Bounded, app-private record files keyed by a hash of the actual Lockdown UDID. */
internal class UsbPairRecordStore(private val directory: File, udid: String) {
    private val file = File(directory, MessageDigest.getInstance("SHA-256").digest(udid.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) } + ".pair")
    @Synchronized fun load(): LockdownPairRecord? {
        val backup = File(file.path + ".bak")
        if (!file.exists() && backup.exists()) backup.renameTo(file)
        if (!file.exists()) return null
        if (file.length() !in 1..262144) throw IOException("Saved USB pairing record is malformed")
        return DataInputStream(FileInputStream(file)).use { input ->
            if (input.readInt() != 1) throw IOException("Unsupported USB pairing record")
            val host = input.readUTF(); val buid = input.readUTF(); val wifi = input.readUTF()
            fun bytes(): ByteArray {
                val size = input.readInt()
                if (size !in 1..32768) throw IOException("Invalid USB pairing material length")
                return ByteArray(size).also(input::readFully)
            }
            val record = LockdownPairRecord.restore(host, buid, wifi, bytes(), bytes(), bytes(), bytes(), bytes(), bytes())
            if (input.read() != -1) throw IOException("Unexpected USB pairing record data")
            record
        }
    }
    @Synchronized fun save(record: LockdownPairRecord) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create USB pairing directory")
        val temp = File(file.path + ".tmp"); val backup = File(file.path + ".bak")
        try {
            FileOutputStream(temp).use { stream ->
                val output = DataOutputStream(stream)
                output.writeInt(1); output.writeUTF(record.hostId); output.writeUTF(record.systemBuid); output.writeUTF(record.wifiMacAddress)
                listOf(record.devicePublicKeyPem, record.deviceCertificatePem, record.hostPrivateKeyPem,
                    record.hostCertificatePem, record.rootPrivateKeyPem, record.rootCertificatePem).forEach { value ->
                    try { require(value.size in 1..32768); output.writeInt(value.size); output.write(value) } finally { value.fill(0) }
                }
                output.flush(); stream.fd.sync()
            }
            if (backup.exists() && !backup.delete()) throw IOException("Cannot rotate USB pairing backup")
            if (file.exists() && !file.renameTo(backup)) throw IOException("Cannot back up USB pairing record")
            if (!temp.renameTo(file)) { backup.renameTo(file); throw IOException("Cannot commit USB pairing record") }
            backup.delete()
        } finally { temp.delete() }
    }
    @Synchronized fun clear() { file.delete(); File(file.path + ".bak").delete() }
}
