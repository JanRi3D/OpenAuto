package me.ri3d.openauto.carplay

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.mfi.*
import com.shilapi.xcertplay.transport.*
import me.ri3d.openauto.Perms
import me.ri3d.openauto.ProjectionService
import me.ri3d.openauto.carplay.media.CarPlayDeviceMedia
import me.ri3d.openauto.carplay.network.CarPlayDiscovery
import me.ri3d.openauto.settings.Prefs
import me.ri3d.openauto.wireless.Hotspot
import java.io.Closeable
import java.io.IOException
import java.net.*
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** One cancellable CarPlay attempt. Network work never runs on the Android main thread. */
class CarPlayManager private constructor(private val context:Context) {
    interface Listener { fun onCarPlayChanged() }
    private val main=Handler(Looper.getMainLooper())
    private val listeners=LinkedHashSet<Listener>()
    private val log=ArrayDeque<String>()
    private val prefs=Prefs(context)
    @Volatile private var attempt:AttemptScope?=null
    @Volatile private var closing=false
    @Volatile private var deviceMedia:CarPlayDeviceMedia?=null
    private val sessions=PrimarySessionRegistry<AirPlaySession>({it.mainScreenSessionToken()},{it.isClosed})
    @Volatile private var surface:Surface?=null
    @Volatile var status:String="Ready for CarPlay"; private set
    @Volatile var projecting=false; private set
    @Volatile var displayWidth=800; private set
    @Volatile var displayHeight=480; private set
    val busy:Boolean get()=attempt!=null
    val settings get()=context.getSharedPreferences("carplay",Context.MODE_PRIVATE)

    fun addListener(listener:Listener) {listeners.add(listener);listener.onCarPlayChanged()}
    fun removeListener(listener:Listener) {listeners.remove(listener)}
    @Synchronized fun logLines():List<String> = ArrayList(log)
    private fun announce(owner:AttemptScope?,message:String) {
        main.post {
            if(owner!=null && attempt!==owner)return@post
            status=message
            synchronized(this) {if(log.size>=96)log.removeFirst();log.addLast(message)}
            listeners.toList().forEach {it.onCarPlayChanged()}
        }
    }
    fun setSurface(value:Surface?) {if(value==null)touch(emptyList());surface=value;deviceMedia?.setSurface(value)}
    fun touch(contacts:List<AirPlayContact>) {if(sessions.primary()==null)return
        synchronized(inputLock) {
            // Preserve down/up ordering. Only successive moves with identical contact state coalesce.
            val previous=pendingTouches.peekLast()
            if(previous!=null && previous.map{it.id to it.down}==contacts.map{it.id to it.down})pendingTouches.removeLast()
            if(pendingTouches.size>=64){pendingTouches.clear();pendingTouches.addLast(emptyList())}
            pendingTouches.addLast(contacts.toList());inputLock.notifyAll()
        }
    }
    private val inputLock=Object()
    private val pendingTouches=ArrayDeque<List<AirPlayContact>>()
    private var pendingSiri=false
    fun siri() {synchronized(inputLock){pendingSiri=true;inputLock.notifyAll()}}

    fun startWireless(ssid:String,password:String,phoneAddress:String,startHotspot:Boolean,width:Int,height:Int) {
        require(ssid.toByteArray(Charsets.UTF_8).size in 1..32) {"Enter a Wi-Fi name (up to 32 bytes)"}
        require(password.isEmpty() || password.toByteArray(Charsets.UTF_8).size in 8..63) {"Wi-Fi password must contain 8 to 63 bytes"}
        require(BluetoothAdapter.checkBluetoothAddress(phoneAddress)) {"Pair and select your iPhone in Android Bluetooth settings"}
        val owner=begin(width,height) ?: return
        Thread({try {runWireless(owner,ssid,password,phoneAddress,startHotspot)}catch(e:Exception){fail(owner,e)}},"carplay-connect").start()
    }
    fun startWired(width:Int,height:Int) {
        val owner=begin(width,height) ?: return
        Thread({try {
            val store=CarPlayIdentityStore(context);val mfi=store.accessory();val identity=store.airPlay()
            val pairings=store.pairings();val media=deviceMedia ?: throw IOException("Media owner unavailable")
            val config=config(identity,media)
            val engine=CarPlayMediaEngine(media,config.microphone)
            owner.own(Closeable {engine.close()})
            val listener=listener(owner)
            owner.own(CarPlayUsbConnector(context,owner,config,identity,pairings,mfi,engine,listener,
                {announce(owner,it)},{accepted->if(attempt===owner && owner.isOpen)sessions.add(accepted)})).start()
        }catch(e:Exception){fail(owner,e)}},"carplay-usb-start").start()
    }

    private fun begin(width:Int,height:Int):AttemptScope? {
        check(Looper.myLooper()==Looper.getMainLooper()) {"Start projection from the main thread"}
        if(busy)return null
        if(!ProjectionOwnership.acquire("carplay")){announce(null,"Disconnect Android Auto before starting CarPlay");return null}
        val owner=AttemptScope();attempt=owner;closing=false;projecting=false;sessions.clear()
        try {
            val h=minOf(prefs.res(),height.coerceAtLeast(240),720)
            val scale=minOf(1.0,1280.0/(width.toDouble()/height.coerceAtLeast(1)*h))
            displayHeight=(h*scale/16).toInt().coerceAtLeast(15)*16
            displayWidth=(displayHeight.toDouble()*width.coerceAtLeast(320)/height.coerceAtLeast(240)/16).toInt().coerceIn(20,80)*16
            val media=owner.own(CarPlayDeviceMedia(context,prefs));deviceMedia=media
            media.setDisplay(displayWidth,displayHeight,30);media.setSurface(surface)
            media.diagnostic={announce(owner,it)}
            media.onFirstFrame={main.post {if(attempt===owner && owner.isOpen){projecting=true;announce(owner,"CarPlay connected")}}}
            ProjectionService.start(context)
            startInputWorker(owner)
            announce(owner,"Preparing CarPlay")
            return owner
        }catch(e:Exception){fail(owner,e);return null}
    }

    private fun config(identity:AirPlayIdentity,media:CarPlayDeviceMedia):AirPlayConfig {
        val bytes=identity.publicKey.takeLast(6).toByteArray();bytes[0]=(bytes[0].toInt() and 0xfe or 2).toByte()
        val id=bytes.joinToString(":") {"%02X".format(it.toInt() and 255)}
        return AirPlayConfig("OpenAuto",id,"", "377.25.6",
            AirPlayDisplayConfig(displayWidth,displayHeight,fps=30),
            manufacturer="OpenAuto",model="OpenAuto",oemLabel="OpenAuto",hevc=false,
            microphone=prefs.mic() && Perms.hasMic(context),microphoneOpus=media.microphoneOpusSupported,
            mainBufferedAudio=false,disableAudioOutput=!prefs.audioMedia() && !prefs.audioSpeech())
    }

    @SuppressLint("MissingPermission") // Activity requests Bluetooth before calling start; checked here as well.
    private fun runWireless(owner:AttemptScope,ssid:String,password:String,phoneAddress:String,startHotspot:Boolean) {
        val store=CarPlayIdentityStore(context);val mfi=store.accessory();val identity=store.airPlay();val pairings=store.pairings()
        check(Perms.hasBluetooth(context)) {"Bluetooth permission is required"}
        val adapter=BluetoothAdapter.getDefaultAdapter() ?: throw IOException("Android exposes no Bluetooth adapter. Try the USB connection.")
        if(!adapter.isEnabled)throw IOException("Switch Bluetooth on and pair the iPhone in Android settings")
        val phone=adapter.bondedDevices.firstOrNull {it.address.equals(phoneAddress,true)} ?: throw IOException("The selected iPhone is no longer paired")
        val btMac=adapter.address
        require(!btMac.isNullOrBlank() && btMac!="02:00:00:00:00:00" && btMac!="00:00:00:00:00:00") {"Android has not exposed its Bluetooth address. USB can be used instead."}
        val network=if(startHotspot)startHotspot(owner,ssid,password) else currentNetwork(ssid,password)
        if(!owner.isOpen)return
        val media=deviceMedia ?: throw IOException("Media owner unavailable")
        val baseConfig=config(identity,media).copy(btMac=btMac)
        val server=ServerSocket()
        owner.own(Closeable {server.close()})
        server.reuseAddress=true;server.bind(InetSocketAddress(network.address,7000),4)
        val config=baseConfig.copy(port=server.localPort)
        val engine=CarPlayMediaEngine(media,config.microphone)
        owner.own(Closeable {engine.close()})
        val bootstrap=java.util.concurrent.atomic.AtomicReference<Iap2Session?>()
        val runtime=java.util.concurrent.atomic.AtomicReference<Iap2Session?>()
        val runtimeGeneration=AtomicInteger()
        val runtimeReady=AtomicBoolean(false)
        val handoffRequested=AtomicBoolean(false)
        // As in DiPlay: Bluetooth closes once the iPhone has asked for it and the Wi-Fi control link is ready.
        fun completeHandoff()=synchronized(runtime) {
            if(owner.isOpen && handoffRequested.get() && runtimeReady.get())bootstrap.getAndSet(null)?.let {runCatching{it.close()};owner.forget(it)}
        }
        // Losing Bluetooth is no failure once the iPhone is moving CarPlay to Wi-Fi.
        val handedOff={handoffRequested.get() || runtime.get()?.isClosed==false || sessions.primary()!=null}
        val wirelessId=Iap2WirelessIdentification(btMac,network.ssid)
        val identification=Iap2IdentificationConfig("OpenAuto","OpenAuto","OpenAuto",identity.pairingId,"0.6.0","1",wirelessId)
        val endpoint=Iap2WirelessCarPlayEndpoint(network.ssid,network.password,0,
            if(network.password.isEmpty())Iap2WirelessSecurity.NONE else Iap2WirelessSecurity.WPA_WPA2,
            listOf(network.address.hostAddress!!),server.localPort,config.deviceId,identity.publicKeyHex,config.sourceVersion)
        engine.setIapTunnelHandler {stream ->
            if(!owner.isOpen)false else {
                var previous:Iap2Session?=null
                val generation=synchronized(runtime) {
                    runtimeReady.set(false);previous=runtime.getAndSet(null);runtimeGeneration.incrementAndGet()
                }
                previous?.close()
                Thread({try {
                    val channel=owner.own(Iap2Session.openTunnel(stream,onTrace={announce(owner,it)}))
                    synchronized(runtime) {
                        if(!owner.isOpen || runtimeGeneration.get()!=generation) {channel.close();owner.forget(channel);return@Thread}
                        runtime.set(channel)
                    }
                    Iap2WirelessControlClient(channel,Iap2MfiAuthenticationClient(mfi)).run(
                        identification.forWirelessLink(Iap2WirelessLinkRole.RUNTIME_TUNNEL,wirelessId),endpoint,
                        timeoutMillis=Iap2WirelessControlClient.NO_TIMEOUT_MILLIS,
                        onReady={synchronized(runtime){if(owner.isOpen && runtimeGeneration.get()==generation && runtime.get()===channel){
                            runtimeReady.set(true);announce(owner,"CarPlay control connected");completeHandoff()}}},
                        onProgress={if(runtimeGeneration.get()==generation)announce(owner,it)})
                    channel.close();owner.forget(channel)
                    if(owner.isOpen && runtimeGeneration.get()==generation)throw IOException("iPhone CarPlay control disconnected")
                }catch(e:Exception){if(runtimeGeneration.get()==generation)fail(owner,e)}},"carplay-iap-tunnel").start()
                true
            }
        }
        startAccept(owner,server,config,identity,pairings,mfi,engine) {type->
            if(type.equals("disableBluetooth",true) || type.equals("disable-bluetooth",true)){handoffRequested.set(true);completeHandoff()}
        }
        owner.own(CarPlayDiscovery(context,network.address,config,identity){announce(owner,it)})
        announce(owner,"Connecting to the paired iPhone")
        val socket=phone.createRfcommSocketToServiceRecord(java.util.UUID.fromString("00000000-deca-fade-deca-deafdecacafe"))
        owner.own(Closeable {socket.close()})
        main.postDelayed({if(attempt===owner && owner.isOpen && !socket.isConnected)runCatching{socket.close()}},20000)
        socket.connect()
        val stream=owner.own(BluetoothRfcommDuplexStream(socket){announce(owner,it)})
        val channel=owner.own(Iap2Session.openWireless(stream,onTrace={announce(owner,it)}));bootstrap.set(channel)
        WirelessBootstrap.run(owner,channel,handedOff) {
            val result=Iap2WirelessControlClient(channel,Iap2MfiAuthenticationClient(mfi)).run(
                identification.forWirelessLink(Iap2WirelessLinkRole.BLUETOOTH_BOOTSTRAP,wirelessId),endpoint,
                timeoutMillis=CONTROL_TIMEOUT_MILLIS,keepAliveAfterDeadline={owner.isOpen && sessions.primary()!=null},
                onProgress={announce(owner,it)})
            if(owner.isOpen && !handedOff())throw IOException("Bluetooth CarPlay setup ended before the Wi-Fi connection (${result.stage})")
        }
        bootstrap.compareAndSet(channel,null)
        stream.close();owner.forget(stream)
    }

    private fun startAccept(owner:AttemptScope,server:ServerSocket,config:AirPlayConfig,identity:AirPlayIdentity,
                            pairings:PairingStore,mfi:MfiAuthenticator,engine:CarPlayMediaEngine,command:(String)->Unit) {
        val active=AtomicInteger()
        Thread({try {
            while(owner.isOpen) {
                val socket=server.accept()
                if(!owner.isOpen || active.get()>=4) {socket.close();continue}
                socket.tcpNoDelay=true;socket.keepAlive=true
                val callback=object:AirPlaySessionListener by listener(owner) {
                    override fun onSessionEnded(ended:AirPlaySession) {active.decrementAndGet();listener(owner).onSessionEnded(ended)}
                    override fun onCommand(session:AirPlaySession,type:String,params:Map<String,Any?>) {command(type)}
                }
                try {val accepted=owner.own(AirPlaySession(socket,config,identity,pairings,mfi,callback,engine));sessions.add(accepted);active.incrementAndGet();accepted.start()}
                catch(e:Exception){socket.close();throw e}
            }
        }catch(e:Exception){if(owner.isOpen)fail(owner,e)}},"carplay-airplay-listen").start()
    }
    private fun listener(owner:AttemptScope)=object:AirPlaySessionListener {
        override fun onSessionActive(active:AirPlaySession) {if(attempt===owner && owner.isOpen){sessions.add(active);announce(owner,"CarPlay session active; waiting for picture")
            // Setup stages carry their own time limits; this one starts when the iPhone has connected.
            main.postDelayed({if(attempt===owner && owner.isOpen && !projecting)fail(owner,IOException("The iPhone connected but sent no CarPlay picture"))},90000)}}
        override fun onSessionEnded(ended:AirPlaySession) {
            owner.forget(ended)
            main.post {if(attempt===owner){sessions.remove(ended);if(sessions.primary()==null){projecting=false;announce(owner,"iPhone disconnected")}}}
        }
        override fun onTransportError(message:String) {fail(owner,IOException(message))}
        override fun onDebugLog(message:String) {announce(owner,message)}
        override fun onHostUiRequested(session:AirPlaySession) {announce(owner,"CarPlay is running in the background")}
    }
    private data class Network(val address:InetAddress,val ssid:String,val password:String)
    @SuppressLint("MissingPermission") // Network visibility is checked at startup; no scan is performed.
    private fun currentNetwork(ssid:String,password:String):Network {
        val wifi=context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val ip=wifi?.connectionInfo?.ipAddress ?: 0
        if(ip==0)throw IOException("Connect the radio to the selected Wi-Fi network first, or choose Start radio hotspot")
        val address=InetAddress.getByAddress(byteArrayOf(ip.toByte(),(ip ushr 8).toByte(),(ip ushr 16).toByte(),(ip ushr 24).toByte()))
        require(NetworkInterface.getByInetAddress(address)!=null) {"Selected Wi-Fi interface is unavailable"}
        val actual=wifi?.connectionInfo?.ssid?.trim('"')
        if(actual!=null && actual!="<unknown ssid>" && actual!=ssid)throw IOException("The radio is connected to a different Wi-Fi name")
        return Network(address,ssid,password)
    }
    private fun startHotspot(owner:AttemptScope,ssid:String,password:String):Network {
        require(password.toByteArray(Charsets.UTF_8).size in 8..63) {"Radio hotspot requires a password of at least 8 characters"}
        check(Perms.hasHotspot(context)) {"Hotspot permission is required"}
        val hotspot=Hotspot(context);val complete=CountDownLatch(1)
        var network:Network?=null;var failure:String?=null
        owner.own(Closeable {complete.await(HOTSPOT_TIMEOUT_SECONDS,TimeUnit.SECONDS);main.post {hotspot.stop()}})
        main.post {
            if(!owner.isOpen){complete.countDown();return@post}
            hotspot.start(ssid,password,object:Hotspot.Callback {
                override fun onStarted(name:String?,pass:String?,bssid:String?,ip:String?) {
                    try {
                        if(!owner.isOpen)hotspot.stop() else {
                            val address=InetAddress.getByName(ip ?: throw IOException("Hotspot has no address"))
                            check(NetworkInterface.getByInetAddress(address)!=null) {"Hotspot address is not assigned"}
                            network=Network(address,name ?: ssid,pass ?: password)
                        }
                    }catch(e:Exception){failure=e.message}finally{complete.countDown()}
                }
                override fun onFailed(reason:String?) {failure=reason ?: "Radio hotspot unavailable";complete.countDown();if(network!=null)fail(owner,IOException(failure))}
            })
        }
        if(!complete.await(HOTSPOT_TIMEOUT_SECONDS,TimeUnit.SECONDS))throw IOException("Radio hotspot did not start in time")
        return network ?: throw IOException(failure ?: "Radio hotspot was cancelled")
    }
    private fun startInputWorker(owner:AttemptScope) {
        synchronized(inputLock){pendingTouches.clear();pendingSiri=false}
        owner.own(Closeable{synchronized(inputLock){inputLock.notifyAll()}})
        Thread({while(owner.isOpen) {
            var touch:List<AirPlayContact>?=null;var siri=false
            synchronized(inputLock) {
                if(pendingTouches.isEmpty() && !pendingSiri && owner.isOpen)inputLock.wait(200)
                touch=pendingTouches.pollFirst();siri=pendingSiri;pendingSiri=false
            }
            val active=sessions.primary()
            if(owner.isOpen && active!=null){touch?.let{active.sendTouch(it)};if(siri)active.invokeSiri()}
        }},"carplay-input").apply{isDaemon=true;start()}
    }
    fun stop() {val owner=attempt ?: return;finish(owner,"Disconnected")}
    private fun fail(owner:AttemptScope,error:Exception) {if(attempt===owner && owner.isOpen)finish(owner,error.message ?: error.javaClass.simpleName)}
    private fun finish(owner:AttemptScope,message:String) {
        synchronized(this){if(attempt!==owner || closing)return;closing=true}
        announce(owner,message)
        Thread({owner.close();main.post {
            if(attempt===owner){attempt=null;closing=false;sessions.clear();deviceMedia=null;projecting=false
                ProjectionOwnership.release("carplay");ProjectionService.stop(context);announce(null,message)}
        }},"carplay-disconnect").start()
    }
    companion object {
        // DiPlay's limits: the first connection waits for the iPhone's "Use CarPlay?" prompt.
        private const val CONTROL_TIMEOUT_MILLIS=5*60_000L
        private const val HOTSPOT_TIMEOUT_SECONDS=60L
        @Volatile private var instance:CarPlayManager?=null
        @JvmStatic fun get(context:Context):CarPlayManager=instance ?: synchronized(this) {
            instance ?: CarPlayManager(context.applicationContext).also {instance=it}
        }
    }
}
