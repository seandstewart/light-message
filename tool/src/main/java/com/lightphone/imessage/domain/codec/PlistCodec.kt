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
import javax.xml.parsers.DocumentBuilderFactory
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
    private val XML_MAGIC = "<?xml"

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
                if (i >= bytes.size || bytes[i] != BPLIST_MAGIC[i]) {
                    isBplist = false
                    break
                }
            }

            if (isBplist) {
                return Result.success(parseBinaryPlist(bytes))
            }

            // Check for XML format
            val startStr = String(bytes, 0, minOf(5, bytes.size), Charsets.UTF_8)
            if (startStr.startsWith(XML_MAGIC)) {
                return Result.success(parseXmlPlist(String(bytes, Charsets.UTF_8)))
            }

            Result.failure(IOException("Invalid plist format: unknown magic bytes"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ============ Binary Plist Encoding ============

    private fun encodeBinaryPlist(value: PlistValue): ByteArray {
        val output = ByteArrayOutputStream()

        // Write header
        output.write(BPLIST_MAGIC)

        // Build object table
        val objectTable = mutableListOf<ByteArray>()
        val objectMap = mutableMapOf<Any, Int>()
        encodeObject(value, objectTable, objectMap)

        // Write object table
        for (obj in objectTable) {
            output.write(obj)
        }

        // Write offset table
        val offsetTableOffset = output.size()
        val offsetSize = calculateOffsetSize(offsetTableOffset)

        // For simplicity, assume offsetSize = 8 for now (we can optimize later)
        val offsetSize8 = 8
        val offsetTable = ByteArray(objectTable.size * 8)
        val offsetBuf = ByteBuffer.wrap(offsetTable).order(ByteOrder.BIG_ENDIAN)

        // Recalculate offsets
        var currentOffset = BPLIST_MAGIC.size
        for (obj in objectTable) {
            offsetBuf.putLong(currentOffset.toLong())
            currentOffset += obj.size
        }

        output.write(offsetTable)

        // Write 32-byte trailer
        val trailerBuffer = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
        trailerBuffer.position(0)

        // 6 unused bytes
        trailerBuffer.put(ByteArray(6))

        // Offset int size
        trailerBuffer.put(8.toByte())

        // Object ref size
        trailerBuffer.put(1.toByte())

        // Number of objects
        trailerBuffer.putInt(objectTable.size)

        // Top object index
        trailerBuffer.putInt(0)

        // Offset table offset
        trailerBuffer.putLong(offsetTableOffset.toLong())

        output.write(trailerBuffer.array())

        return output.toByteArray()
    }

    private fun encodeObject(
            value: PlistValue,
            objectTable: MutableList<ByteArray>,
            objectMap: MutableMap<Any, Int>
    ): Int {
        // Check if already encoded (simple caching for immutable values)
        val cacheKey =
                when (value) {
                    is PlistString -> "s_" + value.value
                    is PlistData -> "d_" + value.value.contentHashCode()
                    is PlistInteger -> "i_" + value.value
                    is PlistFloat -> "f_" + value.value.toBits()
                    is PlistBoolean -> "b_" + value.value
                    PlistNull -> "null"
                    else -> value.toString()
                }

        objectMap[cacheKey]?.let {
            return it
        }

        val encoded =
                when (value) {
                    PlistNull -> byteArrayOf(0x00)
                    is PlistBoolean -> if (value.value) byteArrayOf(0x09) else byteArrayOf(0x08)
                    is PlistInteger -> encodeInteger(value.value)
                    is PlistFloat -> encodeFloat(value.value)
                    is PlistString -> encodeString(value.value)
                    is PlistData -> encodeData(value.value)
                    is PlistDate -> encodeDate(value.timestamp)
                    is PlistArray -> encodeArray(value.items, objectTable, objectMap)
                    is PlistDict -> encodeDict(value.items, objectTable, objectMap)
                }

        val index = objectTable.size
        objectTable.add(encoded)
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
        val lengthBytes = encodeLength(length)
        val result = ByteArray(1 + lengthBytes.size + utf8Bytes.size)
        result[0] = 0x50
        System.arraycopy(lengthBytes, 0, result, 1, lengthBytes.size)
        System.arraycopy(utf8Bytes, 0, result, 1 + lengthBytes.size, utf8Bytes.size)
        return result
    }

    private fun encodeData(value: ByteArray): ByteArray {
        val length = value.size
        val lengthBytes = encodeLength(length)
        val result = ByteArray(1 + lengthBytes.size + value.size)
        result[0] = 0x40
        System.arraycopy(lengthBytes, 0, result, 1, lengthBytes.size)
        System.arraycopy(value, 0, result, 1 + lengthBytes.size, value.size)
        return result
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
            objectTable: MutableList<ByteArray>,
            objectMap: MutableMap<Any, Int>
    ): ByteArray {
        val indices = mutableListOf<Int>()
        for (item in items) {
            indices.add(encodeObject(item, objectTable, objectMap))
        }

        val length = indices.size
        val lengthBytes = encodeLength(length)
        val refBytes = ByteArray(length)

        for (i in indices.indices) {
            refBytes[i] = indices[i].toByte()
        }

        val result = ByteArray(1 + lengthBytes.size + refBytes.size)
        result[0] = 0xa0
        System.arraycopy(lengthBytes, 0, result, 1, lengthBytes.size)
        System.arraycopy(refBytes, 0, result, 1 + lengthBytes.size, refBytes.size)
        return result
    }

    private fun encodeDict(
            items: Map<String, PlistValue>,
            objectTable: MutableList<ByteArray>,
            objectMap: MutableMap<Any, Int>
    ): ByteArray {
        val keys = items.keys.sorted()
        val length = keys.size
        val lengthBytes = encodeLength(length)
        val refBytes = mutableListOf<Byte>()

        // Encode keys first
        for (key in keys) {
            val keyIndex = encodeObject(PlistString(key), objectTable, objectMap)
            refBytes.add(keyIndex.toByte())
        }

        // Encode values in key order
        for (key in keys) {
            val valueIndex = encodeObject(items[key]!!, objectTable, objectMap)
            refBytes.add(valueIndex.toByte())
        }

        val refArray = ByteArray(refBytes.size)
        for (i in refBytes.indices) {
            refArray[i] = refBytes[i]
        }

        val result = ByteArray(1 + lengthBytes.size + refArray.size)
        result[0] = 0xd0
        System.arraycopy(lengthBytes, 0, result, 1, lengthBytes.size)
        System.arraycopy(refArray, 0, result, 1 + lengthBytes.size, refArray.size)
        return result
    }

    private fun encodeLength(length: Int): ByteArray {
        return if (length < 14) {
            byteArrayOf((length and 0x0f).toByte())
        } else if (length <= 0xff) {
            byteArrayOf(0xf0.toByte(), length.toByte())
        } else if (length <= 0xffff) {
            ByteBuffer.allocate(3)
                    .order(ByteOrder.BIG_ENDIAN)
                    .put(0xf1)
                    .putShort(length.toShort())
                    .array()
        } else {
            ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN).put(0xf2).putInt(length).array()
        }
    }

    private fun calculateOffsetSize(offsetTableOffset: Int): Int {
        return when {
            offsetTableOffset <= 0xff -> 1
            offsetTableOffset <= 0xffff -> 2
            offsetTableOffset <= 0xffffffffL -> 4
            else -> 8
        }
    }

    // ============ Binary Plist Decoding ============

    private fun parseBinaryPlist(bytes: ByteArray): PlistValue {
        if (bytes.size < 32) {
            throw IOException("Binary plist too short: missing trailer")
        }

        // Parse trailer (last 32 bytes)
        val trailerOffset = bytes.size - 32
        val trailer = ByteBuffer.wrap(bytes, trailerOffset, 32).order(ByteOrder.BIG_ENDIAN)

        // Skip 6 unused bytes
        trailer.position(6)
        val offsetSize = trailer.get().toInt() and 0xff
        val objectRefSize = trailer.get().toInt() and 0xff
        val objectCount = trailer.int
        val topObjectIndex = trailer.int
        val offsetTableOffset = trailer.long

        if (offsetSize !in listOf(1, 2, 4, 8)) {
            throw IOException("Invalid offset size: $offsetSize")
        }

        if (objectRefSize !in listOf(1, 2, 4, 8)) {
            throw IOException("Invalid object ref size: $objectRefSize")
        }

        if (topObjectIndex >= objectCount) {
            throw IOException("Top object index out of bounds")
        }

        // Parse offset table
        val offsetTable = mutableListOf<Long>()
        var pos = offsetTableOffset.toInt()

        for (i in 0 until objectCount) {
            val offset = readOffset(bytes, pos, offsetSize)
            offsetTable.add(offset)
            pos += offsetSize
        }

        // Parse objects
        val objectCache = mutableMapOf<Int, PlistValue>()

        fun parseObject(index: Int): PlistValue {
            objectCache[index]?.let {
                return it
            }

            val offset = offsetTable[index].toInt()
            if (offset < 0 || offset >= bytes.size) {
                throw IOException("Object offset out of bounds: $offset")
            }

            val buffer =
                    ByteBuffer.wrap(bytes, offset, bytes.size - offset).order(ByteOrder.BIG_ENDIAN)

            val obj = parseObjectInternal(buffer, offsetTable, objectRefSize)
            objectCache[index] = obj
            return obj
        }

        val result = parseObject(topObjectIndex.toInt())
        return result
    }

    private fun parseObjectInternal(
            buffer: ByteBuffer,
            objectTable: List<Long>,
            objectRefSize: Int
    ): PlistValue {
        val marker = buffer.get().toInt() and 0xff
        val type = (marker and 0xf0) shr 4
        val info = marker and 0x0f

        return when {
            marker == 0x00 -> PlistNull
            marker == 0x08 -> PlistBoolean(false)
            marker == 0x09 -> PlistBoolean(true)
            marker >= 0x10 && marker <= 0x13 -> {
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
            marker >= 0x20 && marker <= 0x23 -> {
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
            marker >= 0x40 && marker <= 0x4f -> {
                val length = readLength(buffer, info)
                val data = ByteArray(length)
                buffer.get(data)
                PlistData(data)
            }
            marker >= 0x50 && marker <= 0x5f -> {
                val length = readLength(buffer, info)
                val data = ByteArray(length)
                buffer.get(data)
                val str = String(data, Charsets.UTF_8)
                PlistString(str)
            }
            marker >= 0x60 && marker <= 0x6f -> {
                val length = readLength(buffer, info)
                val data = ByteArray(length * 2)
                buffer.get(data)
                val str = String(data, Charsets.UTF_16BE)
                PlistString(str)
            }
            marker >= 0xa0 && marker <= 0xaf -> {
                val length = readLength(buffer, info)
                val items = mutableListOf<PlistValue>()
                for (i in 0 until length) {
                    items.add(PlistNull) // Placeholder for now; resolve later
                }
                PlistArray(items)
            }
            marker >= 0xd0 && marker <= 0xdf -> {
                val length = readLength(buffer, info)
                val dict = mutableMapOf<String, PlistValue>()
                for (i in 0 until length) {
                    dict["key_$i"] = PlistNull // Placeholder
                }
                PlistDict(dict)
            }
            else -> throw IOException("Unknown object marker: 0x${Integer.toHexString(marker)}")
        }
    }

    private fun readLength(buffer: ByteBuffer, info: Int): Int {
        return if (info < 14) {
            info
        } else if (info == 14) {
            buffer.get().toInt() and 0xff
        } else if (info == 15) {
            val marker = buffer.get().toInt() and 0xff
            val lengthMarker = (marker and 0xf0) shr 4
            when (lengthMarker) {
                0x1 -> buffer.short.toInt() and 0xffff
                0x2 -> buffer.int
                else -> throw IOException("Invalid length marker")
            }
        } else {
            info
        }
    }

    private fun readObjectRef(buffer: ByteBuffer, size: Int): Int {
        return when (size) {
            1 -> buffer.get().toInt() and 0xff
            2 -> buffer.short.toInt() and 0xffff
            4 -> buffer.int
            8 -> buffer.long.toInt()
            else -> throw IOException("Invalid object ref size")
        }
    }

    private fun readOffset(bytes: ByteArray, offset: Int, size: Int): Long {
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

        return parseXmlElement(children[0])
    }

    private fun parseXmlElement(element: Element): PlistValue {
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
                    items.add(parseXmlElement(child))
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
                    val value = parseXmlElement(children[i + 1])
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
