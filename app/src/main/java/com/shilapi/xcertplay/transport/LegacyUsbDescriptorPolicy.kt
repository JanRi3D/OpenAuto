// Adapted from DiPlay 143f4674b60b564a16baae5110b3b90fb7277631 (GPL-3.0). See THIRD_PARTY_NOTICES.md.
package com.shilapi.xcertplay.transport

import java.io.IOException

/** Bounded descriptor parser used before trusting legacy framework endpoint objects. */
internal object LegacyUsbDescriptorPolicy {
    data class Endpoint(val address: Int, val attributes: Int, val maximumPacketSize: Int)
    data class Interface(val id: Int, val alternateSetting: Int, val interfaceClass: Int,
        val subclass: Int, val protocol: Int, val endpoints: List<Endpoint>)
    data class Configuration(val id: Int, val interfaces: List<Interface>)

    fun parse(bytes: ByteArray): List<Configuration> {
        if (bytes.size > 256 * 1024) throw IOException("USB descriptors exceed defensive limit")
        val result = mutableListOf<Configuration>()
        var offset = 0
        while (offset < bytes.size) {
            val length = u8(bytes, offset)
            if (length < 2 || length > bytes.size - offset) throw IOException("Invalid USB descriptor length")
            if (u8(bytes, offset + 1) != 2) { offset += length; continue }
            if (length < 9) throw IOException("Truncated USB configuration descriptor")
            val total = u8(bytes, offset + 2) or (u8(bytes, offset + 3) shl 8)
            if (total < length || total > bytes.size - offset) throw IOException("Invalid USB configuration length")
            val end = offset + total
            val id = u8(bytes, offset + 5)
            val interfaces = mutableListOf<Interface>()
            var current: Interface? = null
            var declaredEndpoints = 0
            var cursor = offset + length
            fun finish() {
                val iface = current ?: return
                if (iface.endpoints.size != declaredEndpoints) throw IOException("Incomplete USB interface endpoints")
                if (interfaces.any { it.id == iface.id && it.alternateSetting == iface.alternateSetting })
                    throw IOException("Duplicate USB interface setting")
                interfaces.add(iface)
            }
            while (cursor < end) {
                val size = u8(bytes, cursor)
                if (size < 2 || size > end - cursor) throw IOException("Invalid USB child descriptor")
                when (u8(bytes, cursor + 1)) {
                    2 -> throw IOException("Nested USB configuration")
                    4 -> {
                        if (size < 9) throw IOException("Truncated USB interface")
                        finish()
                        declaredEndpoints = u8(bytes, cursor + 4)
                        current = Interface(u8(bytes, cursor + 2), u8(bytes, cursor + 3),
                            u8(bytes, cursor + 5), u8(bytes, cursor + 6), u8(bytes, cursor + 7), emptyList())
                    }
                    5 -> {
                        if (size < 7) throw IOException("Truncated USB endpoint")
                        val iface = current ?: throw IOException("Endpoint without USB interface")
                        val endpoint = Endpoint(u8(bytes, cursor + 2), u8(bytes, cursor + 3),
                            u8(bytes, cursor + 4) or (u8(bytes, cursor + 5) shl 8))
                        if (iface.endpoints.any { it.address == endpoint.address }) throw IOException("Duplicate USB endpoint")
                        current = iface.copy(endpoints = iface.endpoints + endpoint)
                    }
                }
                cursor += size
            }
            finish()
            if (interfaces.map { it.id }.distinct().size != u8(bytes, offset + 4))
                throw IOException("USB interface count does not match descriptors")
            if (result.any { it.id == id }) throw IOException("Duplicate USB configuration")
            result.add(Configuration(id, interfaces))
            offset = end
        }
        return result
    }

    fun matches(iface: Interface, id: Int, interfaceClass: Int, subclass: Int, protocol: Int,
        endpoints: List<Endpoint>): Boolean = iface.id == id && iface.interfaceClass == interfaceClass &&
        iface.subclass == subclass && iface.protocol == protocol &&
        iface.endpoints.sortedBy { it.address } == endpoints.sortedBy { it.address }

    private fun u8(bytes: ByteArray, offset: Int): Int =
        if (offset in bytes.indices) bytes[offset].toInt() and 255 else throw IOException("Truncated USB descriptors")
}
