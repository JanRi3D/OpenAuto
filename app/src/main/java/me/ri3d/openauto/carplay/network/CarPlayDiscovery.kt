package me.ri3d.openauto.carplay.network

import android.content.Context
import android.net.wifi.WifiManager
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import java.io.Closeable
import java.net.*
import java.util.concurrent.atomic.AtomicBoolean

/** Interface-bound IPv4 mDNS, including TXT publication on platforms older than API 21. */
class CarPlayDiscovery(context:Context, private val address:InetAddress, private val config:AirPlayConfig,
                        identity:AirPlayIdentity, private val diagnostic:(String)->Unit):Closeable {
    private val open=AtomicBoolean(true)
    private val group=InetAddress.getByName("224.0.0.251")
    private val iface=NetworkInterface.getByInetAddress(address) ?: throw IllegalStateException("Wi-Fi interface unavailable")
    private val socket=createSocket()
    private val lock=try {
        (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createMulticastLock("OpenAuto-CarPlay")?.apply {setReferenceCounted(false);acquire()}
    }catch(error:Exception){socket.close();throw error}
    private val host="openauto-${config.deviceId.replace(":","").takeLast(8)}.local."
    private val txt=linkedMapOf("deviceid" to config.deviceId,"features" to featureText(AirPlayInfoPlist.features(config)),
        "flags" to "0x4","model" to config.model,"srcvers" to config.sourceVersion,"protovers" to "1.1",
        "pi" to identity.pairingId,"pk" to identity.publicKeyHex)
    private val records=LinkedHashMap<String,DnsWire.Record>()
    private val probes=HashMap<String,Long>()
    private var probeSocket:Socket?=null
    private val worker=Thread(::run,"carplay-mdns").apply {isDaemon=true;start()}

    private fun packet(ttl:Int)=DnsWire.advertisement("OpenAuto",host,config.port,address.address,txt,ttl)
    private fun send(bytes:ByteArray, to:InetAddress=group, port:Int=5353) {socket.send(DatagramPacket(bytes,bytes.size,to,port))}
    private fun run() {
        try {
            send(packet(120));var lastQuery=0L
            while(open.get()) {
                val now=System.nanoTime()/1_000_000
                if(now-lastQuery>=3000) {send(DnsWire.query("_carplay-ctrl._tcp.local."));send(packet(120));lastQuery=now}
                val incoming=DatagramPacket(ByteArray(9000),9000)
                try {socket.receive(incoming)} catch(_:SocketTimeoutException) {continue}
                if(incoming.address==address) continue
                val msg=try {DnsWire.read(incoming.data.copyOf(incoming.length))} catch(_:IllegalArgumentException) {continue}
                if(!msg.response && msg.questions.any { it.equals("_airplay._tcp.local.",true) || it.equals(host,true) ||
                    it.endsWith("._airplay._tcp.local.",true) || it=="_services._dns-sd._udp.local." }) {
                    send(packet(120),if(incoming.port==5353)group else incoming.address,incoming.port)
                }
                msg.records.forEach {
                    if(records.size>=128) records.clear()
                    records["${it.name.lowercase()}:${it.type}"]=it
                }
                // A service target and its address may be announced in different packets.
                records.values.filter {it.type==33 && it.name.endsWith("._carplay-ctrl._tcp.local.",true)}.toList().forEach { srv ->
                    val target=records.values.firstOrNull {it.type==1 && it.name.equals(srv.target,true)}
                    if(target==null) {
                        srv.target?.let { send(DnsWire.query(it,1)) }
                    } else if(target.data.size==4 && srv.port in 1..65535) {
                        val key=target.data.joinToString(".")+":"+srv.port
                        val prior=probes[key] ?: -10000L
                        if(now-prior>=3000) {
                            if(probes.size>=128)probes.remove(probes.keys.first())
                            probes[key]=now
                            val peer=InetAddress.getByAddress(target.data)
                            if(onSelectedLink(peer))probe(peer,srv.port)
                        }
                    }
                }
            }
        } catch(error:Exception) {if(open.get()) diagnostic("Bonjour unavailable: ${error.javaClass.simpleName}")}
    }
    private fun probe(peer:InetAddress,port:Int) {
        val s=Socket();synchronized(this){if(!open.get()){s.close();return};probeSocket=s}
        try {
            s.bind(InetSocketAddress(address,0));s.connect(InetSocketAddress(peer,port),1500);s.soTimeout=1500
            val request="GET /ctrl-int/1/connect HTTP/1.1\r\nHost: ${peer.hostAddress}:$port\r\n"+
                "User-Agent: AirPlay/${config.sourceVersion}\r\nAirPlay-Receiver-Device-ID: ${config.deviceId.replace(":","")}\r\nConnection: close\r\n\r\n"
            s.getOutputStream().write(request.toByteArray(Charsets.US_ASCII));s.getOutputStream().flush()
            val b=ByteArray(256);val n=s.getInputStream().read(b)
            if(n>0) diagnostic("iPhone CarPlay discovery response received")
        } catch(_:Exception) { } finally{runCatching{s.close()};synchronized(this){if(probeSocket===s)probeSocket=null}}
    }
    private fun createSocket():MulticastSocket {
        val s=MulticastSocket(null)
        try {s.reuseAddress=true;s.bind(InetSocketAddress(5353));s.networkInterface=iface;s.timeToLive=255;s.soTimeout=1000
            s.joinGroup(InetSocketAddress(group,5353),iface);return s
        }catch(error:Exception){s.close();throw error}
    }
    private fun onSelectedLink(peer:InetAddress):Boolean {
        if(peer.isLoopbackAddress || peer.isMulticastAddress || peer.isAnyLocalAddress)return false
        val bytes=peer.address;val local=address.address
        if(bytes.size!=local.size)return false
        val prefix=iface.interfaceAddresses.firstOrNull {it.address==address}?.networkPrefixLength?.toInt() ?: return false
        for(bit in 0 until prefix){val mask=1 shl (7-bit%8)
            if((bytes[bit/8].toInt() and mask)!=(local[bit/8].toInt() and mask))return false}
        return true
    }
    override fun close() {
        if(!open.compareAndSet(true,false))return
        try{send(packet(0))}catch(_:Exception){}
        socket.close();synchronized(this){try{probeSocket?.close()}catch(_:Exception){}}
        if(worker!==Thread.currentThread())worker.join(2000)
        if(lock?.isHeld==true)lock.release()
    }
    private fun featureText(value:Long):String {
        val low="0x"+java.lang.Long.toHexString(value and 0xffffffffL);val high=value ushr 32
        return if(high==0L)low else low+",0x"+java.lang.Long.toHexString(high)
    }
}
