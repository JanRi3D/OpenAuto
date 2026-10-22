package me.ri3d.openauto.carplay.network

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Bounded DNS-SD wire codec. Compression pointers are followed with cycle and length checks. */
object DnsWire {
    data class Record(val name: String, val type: Int, val data: ByteArray, val target: String? = null,
                      val port: Int = 0, val ttl: Long = 0)
    data class Message(val response: Boolean, val questions: List<String>, val records: List<Record>)
    fun query(name: String, type:Int=12): ByteArray = bytes { out ->
        require(type in listOf(1,12,28,255))
        out.writeShort(0); out.writeShort(0); out.writeShort(1)
        repeat(3) { out.writeShort(0) }
        writeName(out, name); out.writeShort(type); out.writeShort(1)
    }

    fun advertisement(instance: String, host: String, port: Int, address: ByteArray,
                      txt: Map<String, String>, ttl: Int): ByteArray {
        require(port in 1..65535 && address.size in listOf(4, 16) && ttl in 0..3600)
        val service = "$instance._airplay._tcp.local."
        val ptr = bytes { writeName(it, service) }
        val srv = bytes { it.writeShort(0); it.writeShort(0); it.writeShort(port); writeName(it, host) }
        val text = bytes { out -> txt.forEach { (k,v) ->
            require(k.isNotEmpty() && '=' !in k && k.length < 64)
            val entry = "$k=$v".toByteArray(Charsets.UTF_8)
            require(entry.size <= 255); out.writeByte(entry.size); out.write(entry)
        } }
        return bytes { out ->
            out.writeShort(0); out.writeShort(0x8400); out.writeShort(0); out.writeShort(4)
            out.writeShort(0); out.writeShort(0)
            fun rr(name: String, type: Int, data: ByteArray, unique: Boolean) {
                writeName(out,name); out.writeShort(type); out.writeShort(if(unique) 0x8001 else 1)
                out.writeInt(ttl); out.writeShort(data.size); out.write(data)
            }
            rr("_airplay._tcp.local.",12,ptr,false); rr(service,33,srv,true)
            rr(service,16,text,true); rr(host,if(address.size==4) 1 else 28,address,true)
        }
    }

    fun read(packet: ByteArray): Message {
        require(packet.size in 12..9000) { "Invalid DNS packet length" }
        fun u16(p: Int): Int {
            require(p >= 0 && p + 2 <= packet.size)
            return ((packet[p].toInt() and 255) shl 8) or (packet[p+1].toInt() and 255)
        }
        var cursor = 12
        fun name(start: Int): Pair<String,Int> {
            var p = start; var end = -1; var size = 0; var hops = 0
            val labels = ArrayList<String>(); val visited = HashSet<Int>()
            while(true) {
                require(p in packet.indices && visited.add(p) && ++hops <= 128) { "Invalid DNS name pointer" }
                val n = packet[p].toInt() and 255
                if(n == 0) { if(end<0) end=p+1; break }
                if(n and 0xc0 == 0xc0) {
                    val next = u16(p) and 0x3fff
                    if(end<0) end=p+2
                    p=next; continue
                }
                require(n in 1..63 && p+1+n <= packet.size)
                size += n+1; require(size<=255)
                labels.add(String(packet,p+1,n,Charsets.UTF_8)); p += 1+n
            }
            return (if(labels.isEmpty()) "." else labels.joinToString(".")+".") to end
        }
        val q = u16(4); val count = u16(6)+u16(8)+u16(10)
        require(q<=64 && count<=128) { "Too many DNS records" }
        val questions = ArrayList<String>()
        repeat(q) { val (n,end)=name(cursor); cursor=end; require(cursor+4<=packet.size); cursor+=4; questions.add(n) }
        val records = ArrayList<Record>()
        repeat(count) {
            val (n,end)=name(cursor); cursor=end
            require(cursor+10<=packet.size)
            val type=u16(cursor); val ttl=(u16(cursor+4).toLong() shl 16) or u16(cursor+6).toLong()
            val size=u16(cursor+8); cursor+=10; require(cursor+size<=packet.size)
            val data=packet.copyOfRange(cursor,cursor+size)
            val target = when(type) {12,5 -> name(cursor).first; 33 -> {require(size>=7);name(cursor+6).first};else -> null}
            val port = if(type==33) u16(cursor+4) else 0
            records.add(Record(n,type,data,target,port,ttl)); cursor+=size
        }
        return Message(u16(2) and 0x8000 != 0,questions,records)
    }

    private fun writeName(out: DataOutputStream, name: String) {
        val labels=name.trimEnd('.').split('.')
        require(labels.isNotEmpty() && name.toByteArray(Charsets.UTF_8).size<=255)
        labels.forEach { val b=it.toByteArray(Charsets.UTF_8); require(b.size in 1..63);out.writeByte(b.size);out.write(b) }
        out.writeByte(0)
    }
    private fun bytes(write: (DataOutputStream)->Unit): ByteArray {
        val b=ByteArrayOutputStream(); DataOutputStream(b).use(write); return b.toByteArray()
    }
}
