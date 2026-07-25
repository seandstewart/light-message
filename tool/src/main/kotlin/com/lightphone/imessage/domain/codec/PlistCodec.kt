package com.lightphone.imessage.domain.codec

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * Codec for encoding and decoding Plist values in binary (bplist00) and XML formats. Prefer binary
 * format for efficiency; can decode both formats.
 */
class PlistCodec {
    private val dateFormatter = DateTimeFormatter.ISO_INSTANT.withZone(ZoneId.of("UTC"))
    private val BPLIST_MAGIC =
        byteArrayOf(0x62, 0x70, 0x6c, 0x69, 0x73, 0x74, 0x30, 0x30) // "bplist00"
    private val XML_MAGIC_BYTES = "<?xml".toByteArray(Charsets.US_ASCII)

    companion object {
        // Binary plist type markers
        private const val NULL_TYPE = 0x00
        private const val FALSE_TYPE = 0x08
        private const val TRUE_TYPE = 0x09
        private const val INT_TYPE = 0x10
        private const val REAL_TYPE = 0x20
        private const val DATE_TYPE = 0x33
        private const val DATA_TYPE = 0x40
        private const val STRING_TYPE = 0x50
        private const val UNICODE_STRING_TYPE = 0x60
        private const val UID_TYPE = 0x80
        private const val ARRAY_TYPE = 0xa0
        private const val SET_TYPE = 0xc0
        private const val DICT_TYPE = 0xd0

        // Constants for binary plist format
        private const val EPOCH_OFFSET = 978307200L // Seconds from Unix epoch (1970) to 2001-01-01

        // Max recursion depth guard against cyclic references / stack-blowing input.
        private const val MAX_DEPTH = 100
    }

    /**
     * Wrapper for `PlistData.value` byte arrays used as dedup map key so equality is by content,
     * not by reference. Fixes hash-collision-induced dedup bugs from using `contentHashCode()` as a
     * plain map key.
     */
    private class BytesKey(val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean =
            this === other || (other is BytesKey && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    /**
     * Encodes a PlistValue to bytes in binary plist00 format.
     *
     * @param value The PlistValue to encode
     * @return Result containing ByteArray with bplist00 data, or failure with exception
     */
    fun encode(value: PlistValue): Result<ByteArray> {
        return try {
            val encoded = encodeBinaryPlist(value)
            Result.success(encoded)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Decodes bytes in binary plist00 or XML format to a PlistValue. Automatically detects format
     * by magic bytes.
     *
     * @param bytes ByteArray containing plist data
     * @return Result containing decoded PlistValue, or failure with exception
     */
    fun decode(bytes: ByteArray): Result<PlistValue> {
        return try {
            if (bytes.size < 8) {
                return Result.failure(IOException("Plist data too short"))
            }

            // Check for bplist00 magic bytes
            var isBplist = true
            for (i in 0 until 8) {
                if (bytes[i] != BPLIST_MAGIC[i]) {
                    isBplist = false
                    break
                }
            }

            if (isBplist) {
                return Result.success(parseBinaryPlist(bytes))
            }

            // Check for XML format by comparing raw bytes (no UTF-8 decode of prefix).
            if (bytes.size >= XML_MAGIC_BYTES.size &&
                XML_MAGIC_BYTES.indices.all { bytes[it] == XML_MAGIC_BYTES[it] }
            ) {
                return Result.success(parseXmlPlist(String(bytes, Charsets.UTF_8)))
            }

            Result.failure(IOException("Invalid plist format: unknown magic bytes"))
        } catch (e: Exception) {
            // Preserve original cause; do not swap for a generic wrapper.
            Result.failure(e)
        }
    }

    // ============ Binary Plist Encoding ============

    /**
     * Intermediate object representation. We build a list of these in a first pass so we know the
     * total object count (and therefore `objectRefSize`) before we serialize refs and offsets.
     */
    private sealed class Pending {
        abstract fun serialize(refSize: Int): ByteArray
    }

    private class PendingRaw(val bytes: ByteArray) : Pending() {
        override fun serialize(refSize: Int): ByteArray = bytes
    }

    private inner class PendingArray(val refs: IntArray) : Pending() {
        override fun serialize(refSize: Int): ByteArray {
            val length = refs.size
            val refBytes = ByteArray(length * refSize)
            for (i in 0 until length) {
                writeRefBigEndian(refBytes, i * refSize, refs[i], refSize)
            }
            if (length < 14) {
                val result = ByteArray(1 + refBytes.size)
                result[0] = (0xa0 or length).toByte()
                System.arraycopy(refBytes, 0, result, 1, refBytes.size)
                return result
            } else {
                val lengthBytes = encodeLength(length)
                val result = ByteArray(1 + lengthBytes.size + refBytes.size)
                result[0] = 0xaf.toByte()
                System.arraycopy(lengthBytes, 0, result, 1, lengthBytes.size)
                System.arraycopy(refBytes, 0, result, 1 + lengthBytes.size, refBytes.size)
                return result
            }
        }
    }

    private inner class PendingDict(val keyRefs: IntArray, val valRefs: IntArray) : Pending() {
        override fun serialize(refSize: Int): ByteArray {
            val length = keyRefs.size
            val refBytes = ByteArray(2 * length * refSize)
            for (i in 0 until length) {
                writeRefBigEndian(refBytes, i * refSize, keyRefs[i], refSize)
            }
            for (i in 0 until length) {
                writeRefBigEndian(refBytes, (length + i) * refSize, valRefs[i], refSize)
            }
            if (length < 14) {
                val result = ByteArray(1 + refBytes.size)
                result[0] = (0xd0 or length).toByte()
                System.arraycopy(refBytes, 0, result, 1, refBytes.size)
                return result
            } else {
                val lengthBytes = encodeLength(length)
                val result = ByteArray(1 + lengthBytes.size + refBytes.size)
                result[0] = 0xdf.toByte()
                System.arraycopy(lengthBytes, 0, result, 1, lengthBytes.size)
                System.arraycopy(refBytes, 0, result, 1 + lengthBytes.size, refBytes.size)
                return result
            }
        }
    }

    private fun encodeBinaryPlist(value: PlistValue): ByteArray {
        val output = ByteArrayOutputStream()

        // Write header
        output.write(BPLIST_MAGIC)

        // Build object table as Pending items so we can defer ref serialization until we know the
        // final objectRefSize (which depends on total object count).
        val objectTable = mutableListOf<Pending>()
        val objectMap = mutableMapOf<Any, Int>()
        val topObjectIndex = encodeObject(value, objectTable, objectMap)

        // Compute objectRefSize now that we know how many objects there are.
        val objectRefSize =
            when {
                objectTable.size < 256 -> 1
                objectTable.size < 65536 -> 2
                else -> 4
            }

        // Materialize each Pending using the chosen objectRefSize.
        val objectBytes = ArrayList<ByteArray>(objectTable.size)
        var runningOffset = BPLIST_MAGIC.size.toLong()
        val offsetsForObjects = LongArray(objectTable.size)
        for ((idx, obj) in objectTable.withIndex()) {
            val serialized = obj.serialize(objectRefSize)
            objectBytes.add(serialized)
            offsetsForObjects[idx] = runningOffset
            runningOffset += serialized.size.toLong()
        }

        // Write object bytes
        for (obj in objectBytes) {
            output.write(obj)
        }

        // Offset table starts here.
        val offsetTableOffset = output.size().toLong()

        // Compute offsetSize based on the largest offset value we need to encode.
        val maxOffset = maxOf(offsetTableOffset, offsetsForObjects.maxOrNull() ?: 0L)
        val offsetSize =
            when {
                maxOffset <= 0xffL -> 1
                maxOffset <= 0xffffL -> 2
                maxOffset <= 0xffffffffL -> 4
                else -> 8
            }

        val offsetTable = ByteArray(objectTable.size * offsetSize)
        for (i in offsetsForObjects.indices) {
            writeOffsetBigEndian(offsetTable, i * offsetSize, offsetsForObjects[i], offsetSize)
        }
        output.write(offsetTable)

        // Write 32-byte trailer.
        // Layout preserved from prior implementation for backward compat with our own decoder:
        //   bytes 0..5   unused
        //   byte  6      offsetIntSize
        //   byte  7      objectRefSize
        //   bytes 8..11  numObjects (int, big-endian)
        //   bytes 12..15 topObjectIndex (int, big-endian)
        //   bytes 16..23 offsetTableOffset (long, big-endian)
        //   bytes 24..31 padding
        val trailerBuffer = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
        trailerBuffer.position(0)
        trailerBuffer.put(ByteArray(6)) // 6 unused bytes
        trailerBuffer.put(offsetSize.toByte()) // offset int size
        trailerBuffer.put(objectRefSize.toByte()) // object ref size
        trailerBuffer.putInt(objectTable.size) // number of objects
        trailerBuffer.putInt(topObjectIndex) // top object index
        trailerBuffer.putLong(offsetTableOffset) // offset table offset

        output.write(trailerBuffer.array())

        return output.toByteArray()
    }

    private fun encodeObject(
        value: PlistValue,
        objectTable: MutableList<Pending>,
        objectMap: MutableMap<Any, Int>,
    ): Int {
        // Deduplicate immutable scalar values. For PlistData use a content-equality wrapper so
        // distinct byte arrays with colliding hash codes do NOT dedupe as the same object.
        val cacheKey: Any =
            when (value) {
                is PlistString -> "s_" + value.value
                is PlistData -> BytesKey(value.value)
                is PlistInteger -> "i_" + value.value
                is PlistFloat -> "f_" + value.value.toBits()
                is PlistBoolean -> "b_" + value.value
                is PlistDate -> "t_" + value.timestamp
                PlistNull -> "null"
                is PlistArray -> Any() // containers not deduped
                is PlistDict -> Any()
            }

        objectMap[cacheKey]?.let {
            return it
        }

        val pending: Pending =
            when (value) {
                PlistNull -> PendingRaw(byteArrayOf(0x00))
                is PlistBoolean ->
                    PendingRaw(if (value.value) byteArrayOf(0x09) else byteArrayOf(0x08))

                is PlistInteger -> PendingRaw(encodeInteger(value.value))
                is PlistFloat -> PendingRaw(encodeFloat(value.value))
                is PlistString -> PendingRaw(encodeString(value.value))
                is PlistData -> PendingRaw(encodeData(value.value))
                is PlistDate -> PendingRaw(encodeDate(value.timestamp))
                is PlistArray -> encodeArray(value.items, objectTable, objectMap)
                is PlistDict -> encodeDict(value.items, objectTable, objectMap)
            }

        val index = objectTable.size
        objectTable.add(pending)
        objectMap[cacheKey] = index
        return index
    }

    private fun encodeInteger(value: Long): ByteArray {
        return when {
            value >= -128 && value <= 127 -> byteArrayOf(0x10, value.toByte())
            value >= -32768 && value <= 32767 ->
                ByteBuffer.allocate(3)
                    .order(ByteOrder.BIG_ENDIAN)
                    .put(0x11)
                    .putShort(value.toShort())
                    .array()

            value >= -2147483648L && value <= 2147483647L ->
                ByteBuffer.allocate(5)
                    .order(ByteOrder.BIG_ENDIAN)
                    .put(0x12)
                    .putInt(value.toInt())
                    .array()

            else ->
                ByteBuffer.allocate(9)
                    .order(ByteOrder.BIG_ENDIAN)
                    .put(0x13)
                    .putLong(value)
                    .array()
        }
    }

    private fun encodeFloat(value: Double): ByteArray {
        return ByteBuffer.allocate(9).order(ByteOrder.BIG_ENDIAN).put(0x23).putDouble(value).array()
    }

    private fun encodeString(value: String): ByteArray {
        val utf8Bytes = value.toByteArray(Charsets.UTF_8)
        val length = utf8Bytes.size
        if (length < 14) {
            val result = ByteArray(1 + utf8Bytes.size)
            result[0] = (0x50 or length).toByte()
            System.arraycopy(utf8Bytes, 0, result, 1, utf8Bytes.size)
            return result
        } else {
            val lengthBytes = encodeLength(length)
            val result = ByteArray(1 + lengthBytes.size + utf8Bytes.size)
            result[0] = 0x5f.toByte()
            System.arraycopy(lengthBytes, 0, result, 1, lengthBytes.size)
            System.arraycopy(utf8Bytes, 0, result, 1 + lengthBytes.size, utf8Bytes.size)
            return result
        }
    }

    private fun encodeData(value: ByteArray): ByteArray {
        val length = value.size
        if (length < 14) {
            val result = ByteArray(1 + value.size)
            result[0] = (0x40 or length).toByte()
            System.arraycopy(value, 0, result, 1, value.size)
            return result
        } else {
            val lengthBytes = encodeLength(length)
            val result = ByteArray(1 + lengthBytes.size + value.size)
            result[0] = 0x4f.toByte()
            System.arraycopy(lengthBytes, 0, result, 1, lengthBytes.size)
            System.arraycopy(value, 0, result, 1 + lengthBytes.size, value.size)
            return result
        }
    }

    private fun encodeDate(timestamp: Long): ByteArray {
        val seconds = timestamp.toDouble()
        return ByteBuffer.allocate(9)
            .order(ByteOrder.BIG_ENDIAN)
            .put(0x33)
            .putDouble(seconds)
            .array()
    }

    private fun encodeArray(
        items: List<PlistValue>,
        objectTable: MutableList<Pending>,
        objectMap: MutableMap<Any, Int>,
    ): Pending {
        val indices = IntArray(items.size)
        for ((i, item) in items.withIndex()) {
            indices[i] = encodeObject(item, objectTable, objectMap)
        }
        return PendingArray(indices)
    }

    private fun encodeDict(
        items: Map<String, PlistValue>,
        objectTable: MutableList<Pending>,
        objectMap: MutableMap<Any, Int>,
    ): Pending {
        val keys = items.keys.sorted()
        val keyRefs = IntArray(keys.size)
        val valRefs = IntArray(keys.size)
        for ((i, key) in keys.withIndex()) {
            keyRefs[i] = encodeObject(PlistString(key), objectTable, objectMap)
        }
        for ((i, key) in keys.withIndex()) {
            valRefs[i] = encodeObject(items[key]!!, objectTable, objectMap)
        }
        return PendingDict(keyRefs, valRefs)
    }

    private fun encodeLength(length: Int): ByteArray {
        return if (length < 14) {
            byteArrayOf((length and 0x0f).toByte())
        } else if (length <= 0xff) {
            byteArrayOf(0xf0.toByte(), length.toByte())
        } else if (length <= 0xffff) {
            ByteBuffer.allocate(3)
                .order(ByteOrder.BIG_ENDIAN)
                .put(0xf1.toByte())
                .putShort(length.toShort())
                .array()
        } else {
            ByteBuffer.allocate(5)
                .order(ByteOrder.BIG_ENDIAN)
                .put(0xf2.toByte())
                .putInt(length)
                .array()
        }
    }

    private fun writeRefBigEndian(out: ByteArray, pos: Int, ref: Int, size: Int) {
        when (size) {
            1 -> out[pos] = ref.toByte()
            2 -> {
                out[pos] = ((ref ushr 8) and 0xff).toByte()
                out[pos + 1] = (ref and 0xff).toByte()
            }

            4 -> {
                out[pos] = ((ref ushr 24) and 0xff).toByte()
                out[pos + 1] = ((ref ushr 16) and 0xff).toByte()
                out[pos + 2] = ((ref ushr 8) and 0xff).toByte()
                out[pos + 3] = (ref and 0xff).toByte()
            }

            else -> throw IOException("Invalid ref size: $size")
        }
    }

    private fun writeOffsetBigEndian(out: ByteArray, pos: Int, offset: Long, size: Int) {
        when (size) {
            1 -> out[pos] = offset.toByte()
            2 -> {
                out[pos] = ((offset ushr 8) and 0xff).toByte()
                out[pos + 1] = (offset and 0xff).toByte()
            }

            4 -> {
                out[pos] = ((offset ushr 24) and 0xff).toByte()
                out[pos + 1] = ((offset ushr 16) and 0xff).toByte()
                out[pos + 2] = ((offset ushr 8) and 0xff).toByte()
                out[pos + 3] = (offset and 0xff).toByte()
            }

            8 -> {
                for (b in 0 until 8) {
                    out[pos + b] = ((offset ushr ((7 - b) * 8)) and 0xff).toByte()
                }
            }

            else -> throw IOException("Invalid offset size: $size")
        }
    }

    // ============ Binary Plist Decoding ============

    private fun parseBinaryPlist(bytes: ByteArray): PlistValue {
        if (bytes.size < 32) {
            throw IOException("Binary plist too short: missing trailer")
        }

        // Parse trailer (last 32 bytes). Use absolute positioning against the whole `bytes`
        // buffer — `ByteBuffer.wrap(bytes, off, len)` sets capacity to `bytes.size`, so relative
        // seeks like `position(6)` would land in the wrong region.
        val trailerOffset = bytes.size - 32
        val trailer = ByteBuffer.wrap(bytes, trailerOffset, 32).order(ByteOrder.BIG_ENDIAN)
        trailer.position(trailerOffset + 6)
        val offsetSize = trailer.get().toInt() and 0xff
        val objectRefSize = trailer.get().toInt() and 0xff
        val objectCount = trailer.int
        val topObjectIndex = trailer.int
        val offsetTableOffsetLong = trailer.long

        if (offsetSize !in listOf(1, 2, 4, 8)) {
            throw IOException("Invalid offset size: $offsetSize")
        }
        if (objectRefSize !in listOf(1, 2, 4, 8)) {
            throw IOException("Invalid object ref size: $objectRefSize")
        }

        // Guard against unbounded allocation from adversarial trailer values.
        require(objectCount in 0..(bytes.size / offsetSize)) {
            "objectCount out of range: $objectCount (bytes=${bytes.size}, offsetSize=$offsetSize)"
        }
        require(topObjectIndex in 0 until objectCount) {
            "topObjectIndex out of bounds: $topObjectIndex (count=$objectCount)"
        }
        require(offsetTableOffsetLong in 0..Int.MAX_VALUE.toLong()) {
            "offsetTableOffset out of range: $offsetTableOffsetLong"
        }
        val offsetTableOffset = offsetTableOffsetLong.toInt()
        require(offsetTableOffset + objectCount.toLong() * offsetSize <= bytes.size) {
            "offset table would exceed input size"
        }

        // Parse offset table into a fixed-size LongArray.
        val offsetTable = LongArray(objectCount)
        var pos = offsetTableOffset
        for (i in 0 until objectCount) {
            offsetTable[i] = readOffset(bytes, pos, offsetSize)
            pos += offsetSize
        }

        return readObjectAt(bytes, offsetTable, topObjectIndex, objectRefSize, 0)
    }

    /**
     * Recursively resolves the object at the given index. Guards against cyclic references and
     * pathologically deep input via a hard `MAX_DEPTH` limit.
     */
    private fun readObjectAt(
        bytes: ByteArray,
        offsetTable: LongArray,
        index: Int,
        objectRefSize: Int,
        depth: Int,
    ): PlistValue {
        if (depth > MAX_DEPTH) {
            throw IllegalArgumentException("plist nested too deep (>$MAX_DEPTH)")
        }
        require(index in offsetTable.indices) {
            "object index out of bounds: $index (count=${offsetTable.size})"
        }
        val offsetLong = offsetTable[index]
        require(offsetLong in 0..Int.MAX_VALUE.toLong()) {
            "object offset out of range: $offsetLong"
        }
        val offset = offsetLong.toInt()
        if (offset < 0 || offset >= bytes.size) {
            throw IOException("Object offset out of bounds: $offset")
        }

        val buffer = ByteBuffer.wrap(bytes, offset, bytes.size - offset).order(ByteOrder.BIG_ENDIAN)

        val marker = buffer.get().toInt() and 0xff
        val info = marker and 0x0f

        return when {
            marker == 0x00 -> PlistNull
            marker == 0x08 -> PlistBoolean(false)
            marker == 0x09 -> PlistBoolean(true)
            marker in 0x10..0x13 -> {
                val value =
                    when (info) {
                        0x0 -> buffer.get().toLong()
                        0x1 -> buffer.short.toLong()
                        0x2 -> buffer.int.toLong()
                        0x3 -> buffer.long
                        else -> throw IOException("Invalid integer type")
                    }
                PlistInteger(value)
            }

            marker in 0x20..0x23 -> {
                val value =
                    when (info) {
                        0x2 -> buffer.float.toDouble()
                        0x3 -> buffer.double
                        else -> throw IOException("Invalid float type")
                    }
                PlistFloat(value)
            }

            marker == 0x33 -> {
                val timestamp = buffer.double.toLong()
                PlistDate(timestamp)
            }

            marker in 0x40..0x4f -> {
                val length = readLength(buffer, info)
                require(length in 0..buffer.remaining()) {
                    "data length invalid: $length (remaining=${buffer.remaining()})"
                }
                val data = ByteArray(length)
                buffer.get(data)
                PlistData(data)
            }

            marker in 0x50..0x5f -> {
                val length = readLength(buffer, info)
                require(length in 0..buffer.remaining()) {
                    "ascii string length invalid: $length (remaining=${buffer.remaining()})"
                }
                val data = ByteArray(length)
                buffer.get(data)
                // Note: uses lenient UTF-8 decoding (replacement chars on malformed bytes) to
                // preserve backward compatibility. Callers needing strict decoding should
                // validate PlistString content out-of-band.
                val str = String(data, Charsets.UTF_8)
                PlistString(str)
            }

            marker in 0x60..0x6f -> {
                val length = readLength(buffer, info)
                require(length >= 0 && length <= Int.MAX_VALUE / 2) {
                    "utf16 string length invalid: $length"
                }
                val byteLength = Math.multiplyExact(length, 2)
                require(byteLength in 0..buffer.remaining()) {
                    "utf16 string byte length invalid: $byteLength"
                }
                val data = ByteArray(byteLength)
                buffer.get(data)
                val str = String(data, Charsets.UTF_16BE)
                PlistString(str)
            }

            marker in 0xa0..0xaf -> {
                val length = readLength(buffer, info)
                require(length >= 0) { "array length negative: $length" }
                require(length.toLong() * objectRefSize <= buffer.remaining()) {
                    "array refs would exceed buffer"
                }
                val refIndices = IntArray(length)
                for (i in 0 until length) {
                    refIndices[i] = readObjectRef(buffer, objectRefSize)
                }
                val items = ArrayList<PlistValue>(length)
                for (ref in refIndices) {
                    items.add(readObjectAt(bytes, offsetTable, ref, objectRefSize, depth + 1))
                }
                PlistArray(items)
            }

            marker in 0xd0..0xdf -> {
                val length = readLength(buffer, info)
                require(length >= 0) { "dict length negative: $length" }
                require(length.toLong() * 2L * objectRefSize <= buffer.remaining()) {
                    "dict refs would exceed buffer"
                }
                val keyRefs = IntArray(length)
                val valRefs = IntArray(length)
                for (i in 0 until length) {
                    keyRefs[i] = readObjectRef(buffer, objectRefSize)
                }
                for (i in 0 until length) {
                    valRefs[i] = readObjectRef(buffer, objectRefSize)
                }
                val dict = LinkedHashMap<String, PlistValue>(length)
                for (i in 0 until length) {
                    val keyValue =
                        readObjectAt(bytes, offsetTable, keyRefs[i], objectRefSize, depth + 1)
                    if (keyValue !is PlistString) {
                        throw IOException(
                            "Dictionary key must be a string, got ${keyValue::class.simpleName}"
                        )
                    }
                    val valueValue =
                        readObjectAt(bytes, offsetTable, valRefs[i], objectRefSize, depth + 1)
                    dict[keyValue.value] = valueValue
                }
                PlistDict(dict)
            }

            else -> throw IOException("Unknown object marker: 0x${Integer.toHexString(marker)}")
        }
    }

    private fun readLength(
        buffer: ByteBuffer,
        info: Int,
    ): Int {
        return if (info < 14) {
            info
        } else if (info == 14) {
            buffer.get().toInt() and 0xff
        } else if (info == 15) {
            val marker = buffer.get().toInt() and 0xff
            when (marker) {
                0xf0 -> buffer.get().toInt() and 0xff
                0xf1 -> buffer.short.toInt() and 0xffff
                0xf2 -> buffer.int
                else -> throw IOException("Invalid length marker: 0x${marker.toString(16)}")
            }
        } else {
            info
        }
    }

    private fun readObjectRef(
        buffer: ByteBuffer,
        size: Int,
    ): Int {
        return when (size) {
            1 -> buffer.get().toInt() and 0xff
            2 -> buffer.short.toInt() and 0xffff
            4 -> {
                val v = buffer.int.toLong() and 0xffffffffL
                require(v in 0..Int.MAX_VALUE.toLong()) { "object ref out of range: $v" }
                v.toInt()
            }

            8 -> {
                val v = buffer.long
                require(v in 0..Int.MAX_VALUE.toLong()) { "object ref out of range: $v" }
                v.toInt()
            }

            else -> throw IOException("Invalid object ref size")
        }
    }

    private fun readOffset(
        bytes: ByteArray,
        offset: Int,
        size: Int,
    ): Long {
        val buffer = ByteBuffer.wrap(bytes, offset, size).order(ByteOrder.BIG_ENDIAN)
        return when (size) {
            1 -> (bytes[offset].toInt() and 0xff).toLong()
            2 -> (buffer.short.toInt() and 0xffff).toLong()
            4 -> (buffer.int.toLong()) and 0xffffffffL
            8 -> buffer.long
            else -> throw IOException("Invalid offset size")
        }
    }

    // ============ XML Plist Decoding ============

    private fun parseXmlPlist(xml: String): PlistValue {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false

        // Harden against XXE / DoS. Some features may not be supported by the underlying parser;
        // catch ParserConfigurationException per-feature so we still apply the ones that ARE
        // supported. The essential ones (disallow-doctype-decl, no external entities) are set
        // first — if any of those fail we still fail closed thanks to secure processing.
        trySetFeature(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true)
        trySetFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true)
        trySetFeature(factory, "http://xml.org/sax/features/external-general-entities", false)
        trySetFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false)
        trySetFeature(
            factory,
            "http://apache.org/xml/features/nonvalidating/load-external-dtd",
            false,
        )
        factory.isXIncludeAware = false
        factory.isExpandEntityReferences = false

        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        val root = doc.documentElement

        if (root.tagName != "plist") {
            throw IOException("Root element must be 'plist', got '${root.tagName}'")
        }

        // Get first child element (the actual value)
        val children = getElementChildren(root)
        if (children.isEmpty()) {
            return PlistNull
        }

        return parseXmlElement(children[0], 0)
    }

    private fun trySetFeature(factory: DocumentBuilderFactory, name: String, value: Boolean) {
        try {
            factory.setFeature(name, value)
        } catch (_: ParserConfigurationException) {
            // Feature not supported by this parser — continue. FEATURE_SECURE_PROCESSING and the
            // other set features still provide defense in depth.
        }
    }

    private fun parseXmlElement(element: Element, depth: Int): PlistValue {
        if (depth > MAX_DEPTH) {
            throw IllegalArgumentException("plist nested too deep (>$MAX_DEPTH)")
        }
        return when (element.tagName) {
            "null" -> PlistNull
            "true" -> PlistBoolean(true)
            "false" -> PlistBoolean(false)
            "integer" -> PlistInteger(element.textContent.trim().toLong())
            "real" -> PlistFloat(element.textContent.trim().toDouble())
            "string" -> PlistString(element.textContent)
            "data" -> {
                val base64 = element.textContent.trim().replace("\\s+".toRegex(), "")
                val bytes = Base64.getDecoder().decode(base64)
                PlistData(bytes)
            }

            "date" -> {
                val dateStr = element.textContent.trim()
                try {
                    val instant = Instant.parse(dateStr)
                    val secondsSinceEpoch = instant.epochSecond
                    val secondsSince2001 = secondsSinceEpoch - EPOCH_OFFSET
                    PlistDate(secondsSince2001)
                } catch (e: Exception) {
                    throw IOException("Invalid date format: $dateStr", e)
                }
            }

            "array" -> {
                val children = getElementChildren(element)
                val items = mutableListOf<PlistValue>()
                for (child in children) {
                    items.add(parseXmlElement(child, depth + 1))
                }
                PlistArray(items)
            }

            "dict" -> {
                val children = getElementChildren(element)
                val dict = mutableMapOf<String, PlistValue>()
                var i = 0
                while (i < children.size) {
                    if (children[i].tagName != "key") {
                        throw IOException("Dictionary must have alternating key/value elements")
                    }
                    val key = children[i].textContent
                    if (i + 1 >= children.size) {
                        throw IOException("Dictionary key without value")
                    }
                    val value = parseXmlElement(children[i + 1], depth + 1)
                    dict[key] = value
                    i += 2
                }
                PlistDict(dict)
            }

            else -> throw IOException("Unknown XML element: ${element.tagName}")
        }
    }

    private fun getElementChildren(parent: Element): List<Element> {
        val children = mutableListOf<Element>()
        for (i in 0 until parent.childNodes.length) {
            val node = parent.childNodes.item(i)
            if (node.nodeType == Node.ELEMENT_NODE) {
                children.add(node as Element)
            }
        }
        return children
    }
}
