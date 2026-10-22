package com.shilapi.xcertplay.airplay

/**
 * Minimal Apple binary property list (bplist00) codec for the CarPlay control channel.
 *
 * It covers the subset the stack emits and reads: dictionaries, arrays, ASCII and UTF-16
 * strings, raw data, non-negative integers, 32/64-bit reals, and booleans. Dictionary keys are
 * serialized in insertion order, matching the reference implementation this stack targets.
 */
object BplistCodec {
    private const val MAX_BYTES = 1024 * 1024
    private const val MAX_OBJECTS = 8192
    private const val MAX_DEPTH = 32
    private const val MAX_VISITS = 32768
    private val magic = "bplist00".toByteArray(Charsets.US_ASCII)

    fun decode(bytes: ByteArray): Any? {
        require(bytes.size in 40..MAX_BYTES) { "bplist: invalid size" }
        require(bytes.copyOfRange(0, magic.size).contentEquals(magic)) { "bplist: bad magic" }

        val trailer = bytes.size - 32
        val offsetSize = bytes[trailer + 6].toInt() and 0xff
        val refSize = bytes[trailer + 7].toInt() and 0xff
        require(offsetSize in 1..8 && refSize in 1..8) { "bplist: invalid integer width" }
        val objectCount = readBigEndianLong(bytes, (trailer + 8).toLong(), 8)
        val top = readBigEndianLong(bytes, (trailer + 16).toLong(), 8)
        require(objectCount in 1..MAX_OBJECTS.toLong() && top in 0 until objectCount) { "bplist: invalid object table" }
        val numObjects = objectCount.toInt()
        val topObject = top.toInt()
        val offsetTable = readBigEndianLong(bytes, (trailer + 24).toLong(), 8)
        require(offsetTable >= 8 && offsetTable <= trailer.toLong() - numObjects.toLong() * offsetSize) {
            "bplist: invalid offset table"
        }

        val offsets = LongArray(numObjects)
        for (index in offsets.indices) {
            offsets[index] = readBigEndianLong(bytes, offsetTable + index.toLong() * offsetSize, offsetSize)
            require(offsets[index] in 8 until offsetTable) { "bplist: object outside data area" }
        }
        return readObject(bytes, offsets, refSize, topObject, offsetTable.toInt(), BooleanArray(numObjects),
            intArrayOf(0, 0), 0, arrayOfNulls(numObjects), BooleanArray(numObjects))
    }

    fun encode(root: Any?): ByteArray {
        val nodes = ArrayList<Node>()

        fun add(value: Any?, depth: Int = 0): Int {
            require(depth <= MAX_DEPTH && nodes.size < MAX_OBJECTS) { "bplist: excessive encode graph" }
            if (value is ByteArray) require(value.size <= MAX_BYTES) { "bplist: data too large" }
            if (value is String) require(value.length <= MAX_BYTES / 2) { "bplist: string too large" }
            val index = nodes.size
            nodes.add(Leaf(ByteArray(0)))
            when (value) {
                is Boolean -> nodes[index] = Leaf(byteArrayOf(if (value) 0x09 else 0x08))
                is java.math.BigInteger -> {
                    require(value.signum() >= 0) { "bplist: negative integers are not supported" }
                    require(value.bitLength() <= 64) { "bplist: integer exceeds 64 bits" }
                    nodes[index] = Leaf(encodeUnsignedInt(value))
                }
                is Byte, is Short, is Int, is Long -> {
                    val number = (value as Number).toLong()
                    nodes[index] = if (number >= 0) {
                        Leaf(encodeInt(number))
                    } else {
                        Leaf(byteArrayOf(0x23) + bigEndian(number.toDouble().toRawBits(), 8))
                    }
                }
                is Float, is Double -> {
                    val number = (value as Number).toDouble()
                    nodes[index] = Leaf(byteArrayOf(0x23) + bigEndian(number.toRawBits(), 8))
                }
                is String -> nodes[index] = Leaf(encodeString(value))
                is ByteArray -> nodes[index] = Leaf(marker(0x4, value.size) + value)
                is List<*> -> {
                    val refs = value.map { add(it, depth + 1) }.toIntArray()
                    nodes[index] = Container(marker(0xa, value.size), refs)
                }
                is Map<*, *> -> {
                    val entries = value.entries.toList()
                    val keyRefs = entries.map { require(it.key is String) { "bplist: non-string dictionary key" }; add(it.key, depth + 1) }.toIntArray()
                    val valueRefs = entries.map { add(it.value, depth + 1) }.toIntArray()
                    nodes[index] = Container(marker(0xd, entries.size), keyRefs + valueRefs)
                }
                else -> throw IllegalArgumentException("bplist: unsupported value type")
            }
            return index
        }

        val topIndex = add(root)
        val refSize = when {
            nodes.size > 0xffff -> 4
            nodes.size > 0xff -> 2
            else -> 1
        }

        val parts = ArrayList<ByteArray>(nodes.size + 2)
        parts.add(magic)
        var cursor = magic.size
        val offsets = ArrayList<Int>(nodes.size)
        for (node in nodes) {
            offsets.add(cursor)
            val serialized = when (node) {
                is Leaf -> node.body
                is Container -> node.head + refBytes(node.refs, refSize)
            }
            parts.add(serialized)
            cursor += serialized.size
            require(cursor <= MAX_BYTES - 32 - nodes.size * 8) { "bplist: encoded value too large" }
        }

        val offsetTableOffset = cursor
        val offsetSize = when {
            cursor > 0xffff -> 4
            cursor > 0xff -> 2
            else -> 1
        }
        for (offset in offsets) parts.add(bigEndian(offset.toLong(), offsetSize))

        val trailer = ByteArray(32)
        trailer[6] = offsetSize.toByte()
        trailer[7] = refSize.toByte()
        writeBigEndian(trailer, 8, nodes.size.toLong(), 8)
        writeBigEndian(trailer, 16, topIndex.toLong(), 8)
        writeBigEndian(trailer, 24, offsetTableOffset.toLong(), 8)
        parts.add(trailer)
        return concatBytes(*parts.toTypedArray())
    }

    private fun readObject(bytes: ByteArray, offsets: LongArray, refSize: Int, index: Int,
        dataEnd: Int, visiting: BooleanArray, visits: IntArray, depth: Int,
        cache: Array<Any?>, decoded: BooleanArray): Any? {
        require(index in offsets.indices && depth <= MAX_DEPTH && ++visits[0] <= MAX_VISITS) { "bplist: excessive graph" }
        if (decoded[index]) return cache[index]
        require(!visiting[index]) { "bplist: cyclic object graph" }
        visiting[index] = true
        var position = offsets[index].toInt()
        fun available(count: Int) {
            require(count >= 0 && position <= dataEnd - count) { "bplist: truncated object" }
        }
        fun reserve(count: Int) {
            require(count >= 0 && visits[1] <= MAX_BYTES * 4 - count) { "bplist: decoded allocation limit" }
            visits[1] += count
        }
        available(1)
        val markerByte = bytes[position].toInt() and 0xff
        val type = markerByte ushr 4
        val nibble = markerByte and 0x0f
        position++

        fun readCount(): Int {
            if (nibble != 0x0f) return nibble
            available(1)
            val sizeMarker = bytes[position].toInt() and 0xff
            position++
            require(sizeMarker ushr 4 == 1 && (sizeMarker and 0x0f) <= 3) { "bplist: invalid count" }
            val intBytes = 1 shl (sizeMarker and 0x0f)
            available(intBytes)
            val countLong = readBigEndianLong(bytes, position.toLong(), intBytes)
            require(countLong in 0..MAX_BYTES.toLong()) { "bplist: excessive count" }
            val count = countLong.toInt()
            position += intBytes
            return count
        }

        fun reference(at: Long): Int {
            require(at >= 8 && at <= dataEnd.toLong() - refSize) { "bplist: invalid reference position" }
            val ref = readBigEndianLong(bytes, at, refSize)
            require(ref in offsets.indices.first.toLong()..offsets.indices.last.toLong()) { "bplist: invalid reference" }
            return ref.toInt()
        }
        fun child(ref: Int) = readObject(bytes, offsets, refSize, ref, dataEnd, visiting, visits, depth + 1, cache, decoded)
        val value = when (type) {
            0x0 -> when (nibble) {
                0x00 -> null
                0x08 -> false
                0x09 -> true
                else -> throw IllegalArgumentException("bplist: unsupported primitive 0x0$nibble")
            }
            0x1 -> {
                val size = 1 shl nibble
                require(nibble <= 4) { "bplist: integer exceeds 128 bits" }
                available(size)
                if (size == 16) {
                    // Apple writes unsigned 64-bit values from 2^63 up as 16-byte integers. The low 64 bits
                    // are kept as a Long; unsignedPlistDecimal reads them back as unsigned.
                    require(readBigEndianLong(bytes, position.toLong(), 8) == 0L) { "bplist: integer exceeds 64 bits" }
                    readBigEndianLong(bytes, position.toLong() + 8, 8)
                } else {
                    readBigEndianLong(bytes, position.toLong(), size)
                }
            }
            0x2 -> {
                val size = 1 shl nibble
                require(nibble in 2..3) { "bplist: invalid real width" }
                available(size)
                when (size) {
                    4 -> Float.fromBits(readBigEndianLong(bytes, position.toLong(), size).toInt())
                    8 -> Double.fromBits(readBigEndianLong(bytes, position.toLong(), size))
                    else -> throw IllegalArgumentException("bplist: unsupported real size $size")
                }
            }
            // Date: 8-byte real, seconds since 2001-01-01 UTC.
            0x3 -> { require(nibble == 3) { "bplist: invalid date width" }; available(8); java.util.Date(
                Math.round((Double.fromBits(readBigEndianLong(bytes, position.toLong(), 8)) + APPLE_EPOCH_SECONDS) * 1000),
            ) }
            0x4 -> {
                val count = readCount()
                available(count)
                reserve(count)
                bytes.copyOfRange(position, position + count)
            }
            // UID (keyed archives): its integer value.
            0x8 -> { require(nibble < 8); available(nibble + 1); readBigEndianLong(bytes, position.toLong(), nibble + 1) }
            0x5 -> {
                val count = readCount()
                available(count)
                reserve(count * 2)
                String(bytes, position, count, Charsets.US_ASCII)
            }
            0x6 -> {
                val count = readCount()
                available(count * 2)
                reserve(count * 2)
                String(bytes, position, count * 2, Charsets.UTF_16BE)
            }
            0xa, 0xc -> {
                val count = readCount()
                require(count <= MAX_OBJECTS) { "bplist: excessive array" }
                available(count * refSize)
                reserve(count * 8)
                val array = ArrayList<Any?>(count)
                for (i in 0 until count) {
                    array.add(child(reference(position + i.toLong() * refSize)))
                }
                array
            }
            0xd -> {
                val count = readCount()
                require(count <= MAX_OBJECTS) { "bplist: excessive dictionary" }
                available(count * refSize * 2)
                reserve(count * 64)
                val dict = LinkedHashMap<String, Any?>(count)
                for (i in 0 until count) {
                    val keyReference = reference(position + i.toLong() * refSize)
                    val valueReference =
                        reference(position + (count + i).toLong() * refSize)
                    val key = child(keyReference)
                    require(key is String) { "bplist: non-string dictionary key" }
                    dict[key] = child(valueReference)
                }
                dict
            }
            else -> throw IllegalArgumentException("bplist: unsupported object type 0x${type.toString(16)}")
        }
        visiting[index] = false
        cache[index] = value
        decoded[index] = true
        return value
    }

    private fun encodeString(value: String): ByteArray {
        val ascii = value.all { it.code <= 0x7f }
        return if (ascii) {
            marker(0x5, value.length) + value.toByteArray(Charsets.US_ASCII)
        } else {
            marker(0x6, value.length) + value.toByteArray(Charsets.UTF_16BE)
        }
    }

    private fun encodeInt(number: Long): ByteArray {
        val size = when {
            number > 0xffffffffL -> 8
            number > 0xffffL -> 4
            number > 0xffL -> 2
            else -> 1
        }
        val log = when (size) {
            8 -> 3
            4 -> 2
            2 -> 1
            else -> 0
        }
        return byteArrayOf((0x10 or log).toByte()) + bigEndian(number, size)
    }

    private fun encodeUnsignedInt(number: java.math.BigInteger): ByteArray {
        val size = when {
            number.bitLength() > 32 -> 8
            number.bitLength() > 16 -> 4
            number.bitLength() > 8 -> 2
            else -> 1
        }
        val log = when (size) {
            8 -> 3
            4 -> 2
            2 -> 1
            else -> 0
        }
        val raw = number.toByteArray()
        val padded = ByteArray(size)
        val count = minOf(size, raw.size)
        raw.copyInto(padded, size - count, raw.size - count, raw.size)
        return byteArrayOf((0x10 or log).toByte()) + padded
    }

    private fun marker(type: Int, count: Int): ByteArray {
        if (count < 0x0f) return byteArrayOf(((type shl 4) or count).toByte())
        val size = when {
            count > 0xffff -> 4
            count > 0xff -> 2
            else -> 1
        }
        val log = when (size) {
            4 -> 2
            2 -> 1
            else -> 0
        }
        return byteArrayOf(((type shl 4) or 0x0f).toByte(), (0x10 or log).toByte()) +
            bigEndian(count.toLong(), size)
    }

    private fun refBytes(refs: IntArray, refSize: Int): ByteArray {
        val output = ByteArray(refs.size * refSize)
        for (index in refs.indices) writeBigEndian(output, index * refSize, refs[index].toLong(), refSize)
        return output
    }

    private fun bigEndian(value: Long, size: Int): ByteArray {
        val output = ByteArray(size)
        writeBigEndian(output, 0, value, size)
        return output
    }

    private fun writeBigEndian(target: ByteArray, offset: Int, value: Long, size: Int) {
        var current = value
        for (index in size - 1 downTo 0) {
            target[offset + index] = (current and 0xff).toByte()
            current = current ushr 8
        }
    }

    private fun readBigEndianLong(bytes: ByteArray, offset: Long, size: Int): Long {
        require(size in 1..8 && offset >= 0 && offset <= bytes.size.toLong() - size) { "bplist: invalid integer range" }
        var value = 0L
        var base = offset.toInt()
        for (index in 0 until size) {
            value = (value shl 8) or (bytes[base + index].toLong() and 0xffL)
        }
        return value
    }

    private sealed class Node
    private class Leaf(val body: ByteArray) : Node()
    private class Container(val head: ByteArray, val refs: IntArray) : Node()

    private const val APPLE_EPOCH_SECONDS = 978_307_200.0
}
