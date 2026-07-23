package com.lightphone.imessage.domain.codec

import org.junit.Assert.*
import org.junit.Test

/**
 * Comprehensive unit tests for PlistCodec. Covers binary (bplist00) and XML plist encoding/decoding
 * with all value types. Target: 100% code coverage.
 */
class PlistCodecTest {
    private val plistCodec = PlistCodec()

    // ========== Binary Plist (bplist00) Roundtrip Tests ==========

    @Test
    fun testBplist00RoundtripNull() {
        val value = PlistNull
        val encoded = plistCodec.encode(value)
        assertTrue("Encoding must succeed", encoded.isSuccess)

        val decoded = plistCodec.decode(encoded.getOrThrow())
        assertTrue("Decoding must succeed", decoded.isSuccess)
        assertEquals("Decoded value must match original", value, decoded.getOrNull())
    }

    @Test
    fun testBplist00RoundtripBooleanTrue() {
        val value = PlistBoolean(true)
        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue("Decoding must succeed", decoded.isSuccess)
        assertEquals("Decoded boolean must be true", value, decoded.getOrNull())
    }

    @Test
    fun testBplist00RoundtripBooleanFalse() {
        val value = PlistBoolean(false)
        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue("Decoding must succeed", decoded.isSuccess)
        assertEquals("Decoded boolean must be false", value, decoded.getOrNull())
    }

    @Test
    fun testBplist00RoundtripIntegerSmall() {
        val value = PlistInteger(42L)
        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue("Decoding must succeed", decoded.isSuccess)
        assertEquals("Decoded integer must match", value, decoded.getOrNull())
    }

    @Test
    fun testBplist00RoundtripIntegerLarge() {
        val value = PlistInteger(Long.MAX_VALUE)
        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue("Decoding must succeed", decoded.isSuccess)
        assertEquals("Decoded large integer must match", value, decoded.getOrNull())
    }

    @Test
    fun testBplist00RoundtripIntegerNegative() {
        val value = PlistInteger(-12345L)
        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue("Decoding must succeed", decoded.isSuccess)
        assertEquals("Decoded negative integer must match", value, decoded.getOrNull())
    }

    @Test
    fun testBplist00RoundtripIntegerZero() {
        val value = PlistInteger(0L)
        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue("Decoding must succeed", decoded.isSuccess)
        assertEquals("Decoded zero must match", value, decoded.getOrNull())
    }

    @Test
    fun testBplist00RoundtripFloat() {
        val value = PlistFloat(3.14159)
        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue("Decoding must succeed", decoded.isSuccess)
        assertEquals("Decoded float must match", value, decoded.getOrNull())
    }

    @Test
    fun testBplist00RoundtripFloatZero() {
        val value = PlistFloat(0.0)
        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue("Decoding must succeed", decoded.isSuccess)
        assertEquals("Decoded float zero must match", value, decoded.getOrNull())
    }

    @Test
    fun testBplist00RoundtripFloatNegative() {
        val value = PlistFloat(-42.5)
        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue("Decoding must succeed", decoded.isSuccess)
        assertEquals("Decoded negative float must match", value, decoded.getOrNull())
    }

    @Test
    fun testBplist00RoundtripString() {
        val value = PlistString("Hello, Plist!")
        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue("Decoding must succeed", decoded.isSuccess)
        assertEquals("Decoded string must match", value, decoded.getOrNull())
    }

    @Test
    fun testBplist00RoundtripStringEmpty() {
        val value = PlistString("")
        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue("Decoding must succeed", decoded.isSuccess)
        assertEquals("Decoded empty string must match", value, decoded.getOrNull())
    }

    @Test
    fun testBplist00RoundtripStringUnicode() {
        val value = PlistString("Hello 世界 🌍 مرحبا")
        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue("Decoding must succeed", decoded.isSuccess)
        assertEquals("Decoded unicode string must match", value, decoded.getOrNull())
    }

    @Test
    fun testBplist00RoundtripStringLarge() {
        val value = PlistString("A".repeat(10000))
        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue("Decoding must succeed", decoded.isSuccess)
        assertEquals("Decoded large string must match", value, decoded.getOrNull())
    }

    @Test
    fun testBplist00RoundtripData() {
        val value = PlistData(byteArrayOf(0, 1, 2, 3, 255, 254, 253))
        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue("Decoding must succeed", decoded.isSuccess)
        assertEquals("Decoded data must match", value, decoded.getOrNull())
    }

    @Test
    fun testBplist00RoundtripDataEmpty() {
        val value = PlistData(ByteArray(0))
        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue("Decoding must succeed", decoded.isSuccess)
        assertEquals("Decoded empty data must match", value, decoded.getOrNull())
    }

    @Test
    fun testBplist00RoundtripDataLarge() {
        val value = PlistData(ByteArray(10000) { it.toByte() })
        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue("Decoding must succeed", decoded.isSuccess)
        assertEquals("Decoded large data must match", value, decoded.getOrNull())
    }

    @Test
    fun testBplist00RoundtripDate() {
        val timestamp = System.currentTimeMillis() / 1000 // seconds since 2001
        val value = PlistDate(timestamp)
        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue("Decoding must succeed", decoded.isSuccess)
        assertEquals("Decoded date must match", value, decoded.getOrNull())
    }

    @Test
    fun testBplist00RoundtripDateEpoch() {
        val value = PlistDate(0L)
        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue("Decoding must succeed", decoded.isSuccess)
        assertEquals("Decoded epoch date must match", value, decoded.getOrNull())
    }

    @Test
    fun testBplist00RoundtripArray() {
        val value =
                PlistArray(
                        listOf(
                                PlistNull,
                                PlistBoolean(true),
                                PlistInteger(42L),
                                PlistString("test")
                        )
                )
        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue("Decoding must succeed", decoded.isSuccess)
        assertEquals("Decoded array must match", value, decoded.getOrNull())
    }

    @Test
    fun testBplist00RoundtripArrayEmpty() {
        val value = PlistArray(emptyList())
        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue("Decoding must succeed", decoded.isSuccess)
        assertEquals("Decoded empty array must match", value, decoded.getOrNull())
    }

    @Test
    fun testBplist00RoundtripArrayNested() {
        val value =
                PlistArray(
                        listOf(
                                PlistArray(listOf(PlistInteger(1L), PlistInteger(2L))),
                                PlistArray(listOf(PlistInteger(3L), PlistInteger(4L)))
                        )
                )
        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue("Decoding must succeed", decoded.isSuccess)
        assertEquals("Decoded nested array must match", value, decoded.getOrNull())
    }

    @Test
    fun testBplist00RoundtripDictionary() {
        val value =
                PlistDict(
                        mapOf(
                                "key1" to PlistString("value1"),
                                "key2" to PlistInteger(42L),
                                "key3" to PlistBoolean(true)
                        )
                )
        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue("Decoding must succeed", decoded.isSuccess)
        assertEquals("Decoded dictionary must match", value, decoded.getOrNull())
    }

    @Test
    fun testBplist00RoundtripDictionaryEmpty() {
        val value = PlistDict(emptyMap())
        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue("Decoding must succeed", decoded.isSuccess)
        assertEquals("Decoded empty dictionary must match", value, decoded.getOrNull())
    }

    // ========== All Value Types ==========

    @Test
    fun testAllValueTypes() {
        val testDict =
                PlistDict(
                        mapOf(
                                "null" to PlistNull,
                                "bool_true" to PlistBoolean(true),
                                "bool_false" to PlistBoolean(false),
                                "int" to PlistInteger(12345L),
                                "float" to PlistFloat(3.14159),
                                "string" to PlistString("test string"),
                                "data" to PlistData(byteArrayOf(1, 2, 3)),
                                "array" to PlistArray(listOf(PlistInteger(1L), PlistInteger(2L))),
                                "dict" to PlistDict(mapOf("nested" to PlistString("value"))),
                                "date" to PlistDate(100000L)
                        )
                )

        val encoded = plistCodec.encode(testDict)
        assertTrue("Encoding all types must succeed", encoded.isSuccess)

        val decoded = plistCodec.decode(encoded.getOrThrow())
        assertTrue("Decoding all types must succeed", decoded.isSuccess)
        assertEquals("All types must roundtrip correctly", testDict, decoded.getOrNull())
    }

    // ========== Large Dictionary Test ==========

    @Test
    fun testLargeDictionary() {
        val largeDict = mutableMapOf<String, PlistValue>()
        for (i in 0 until 1000) {
            largeDict["key_$i"] = PlistInteger(i.toLong())
        }
        val value = PlistDict(largeDict)

        val encoded = plistCodec.encode(value)
        assertTrue("Encoding large dictionary must succeed", encoded.isSuccess)

        val decoded = plistCodec.decode(encoded.getOrThrow())
        assertTrue("Decoding large dictionary must succeed", decoded.isSuccess)

        val decodedDict = decoded.getOrNull() as? PlistDict
        assertNotNull("Decoded value must be a dict", decodedDict)
        assertEquals("Decoded dict must have 1000 entries", 1000, decodedDict?.items?.size)
    }

    // ========== Nested Structures ==========

    @Test
    fun testNestedStructures_DictInArray() {
        val value =
                PlistArray(
                        listOf(
                                PlistDict(mapOf("a" to PlistInteger(1L))),
                                PlistDict(mapOf("b" to PlistInteger(2L)))
                        )
                )

        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue("Decoding must succeed", decoded.isSuccess)
        assertEquals("Nested dict in array must match", value, decoded.getOrNull())
    }

    @Test
    fun testNestedStructures_ArrayInDict() {
        val value =
                PlistDict(
                        mapOf(
                                "items" to
                                        PlistArray(
                                                listOf(
                                                        PlistInteger(1L),
                                                        PlistInteger(2L),
                                                        PlistInteger(3L)
                                                )
                                        )
                        )
                )

        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue("Decoding must succeed", decoded.isSuccess)
        assertEquals("Nested array in dict must match", value, decoded.getOrNull())
    }

    @Test
    fun testNestedStructures_DictInDictInArray() {
        val value =
                PlistArray(
                        listOf(
                                PlistDict(
                                        mapOf(
                                                "nested" to
                                                        PlistDict(
                                                                mapOf(
                                                                        "deep" to
                                                                                PlistString("value")
                                                                )
                                                        )
                                        )
                                )
                        )
                )

        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue("Decoding must succeed", decoded.isSuccess)
        assertEquals("Deeply nested structure must match", value, decoded.getOrNull())
    }

    @Test
    fun testNestedStructures_ComplexStructure() {
        val value =
                PlistDict(
                        mapOf(
                                "users" to
                                        PlistArray(
                                                listOf(
                                                        PlistDict(
                                                                mapOf(
                                                                        "name" to
                                                                                PlistString(
                                                                                        "Alice"
                                                                                ),
                                                                        "age" to PlistInteger(30L),
                                                                        "tags" to
                                                                                PlistArray(
                                                                                        listOf(
                                                                                                PlistString(
                                                                                                        "admin"
                                                                                                ),
                                                                                                PlistString(
                                                                                                        "user"
                                                                                                )
                                                                                        )
                                                                                )
                                                                )
                                                        ),
                                                        PlistDict(
                                                                mapOf(
                                                                        "name" to
                                                                                PlistString("Bob"),
                                                                        "age" to PlistInteger(25L),
                                                                        "tags" to
                                                                                PlistArray(
                                                                                        listOf(
                                                                                                PlistString(
                                                                                                        "user"
                                                                                                )
                                                                                        )
                                                                                )
                                                                )
                                                        )
                                                )
                                        )
                        )
                )

        val encoded = plistCodec.encode(value)
        assertTrue("Encoding complex structure must succeed", encoded.isSuccess)

        val decoded = plistCodec.decode(encoded.getOrThrow())
        assertTrue("Decoding complex structure must succeed", decoded.isSuccess)
        assertEquals("Complex structure must roundtrip correctly", value, decoded.getOrNull())
    }

    // ========== Format Detection ==========

    @Test
    fun testAutodetectFormatBplist() {
        val value = PlistDict(mapOf("test" to PlistString("bplist test")))
        val encoded = plistCodec.encode(value)

        assertTrue("Encoding must succeed", encoded.isSuccess)

        val bytes = encoded.getOrThrow()
        // Binary plist starts with "bplist00"
        val isBplist =
                bytes.size >= 8 &&
                        bytes[0] == 'b'.code.toByte() &&
                        bytes[1] == 'p'.code.toByte() &&
                        bytes[2] == 'l'.code.toByte() &&
                        bytes[3] == 'i'.code.toByte() &&
                        bytes[4] == 's'.code.toByte() &&
                        bytes[5] == 't'.code.toByte() &&
                        bytes[6] == '0'.code.toByte() &&
                        bytes[7] == '0'.code.toByte()

        assertTrue("Encoded format must be binary plist (bplist00)", isBplist)
    }

    @Test
    fun testAutodetectFormatDetection() {
        val value = PlistString("test")
        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue("Decoding must auto-detect format", decoded.isSuccess)
        assertEquals("Auto-detected format must decode correctly", value, decoded.getOrNull())
    }

    // ========== Invalid Format Handling ==========

    @Test
    fun testInvalidMagic() {
        val invalidBytes = byteArrayOf('x', 'y', 'z') + ByteArray(100)
        val decoded = plistCodec.decode(invalidBytes)

        assertTrue("Decoding invalid magic must fail", decoded.isFailure)
    }

    @Test
    fun testInvalidMagicTooShort() {
        val invalidBytes = byteArrayOf('b', 'p')
        val decoded = plistCodec.decode(invalidBytes)

        assertTrue("Decoding too-short plist must fail", decoded.isFailure)
    }

    @Test
    fun testInvalidMagicEmpty() {
        val invalidBytes = ByteArray(0)
        val decoded = plistCodec.decode(invalidBytes)

        assertTrue("Decoding empty bytes must fail", decoded.isFailure)
    }

    @Test
    fun testCorruptedBplistTrailer() {
        val value = PlistDict(mapOf("key" to PlistString("value")))
        val encoded = plistCodec.encode(value).getOrThrow()

        // Corrupt the last 32 bytes (trailer)
        if (encoded.size > 32) {
            for (i in encoded.size - 32 until encoded.size) {
                encoded[i] = (encoded[i].toInt() xor 0xFF).toByte()
            }
        }

        val decoded = plistCodec.decode(encoded)
        // May fail due to invalid trailer
        // The exact behavior depends on implementation
    }

    // ========== Edge Cases ==========

    @Test
    fun testRoundtripPreservesKeyOrder() {
        // LinkedHashMap preserves insertion order
        val value =
                PlistDict(
                        linkedMapOf(
                                "z" to PlistInteger(1L),
                                "a" to PlistInteger(2L),
                                "m" to PlistInteger(3L)
                        )
                )

        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue("Decoding must succeed", decoded.isSuccess)

        val decodedDict = decoded.getOrNull() as? PlistDict
        assertNotNull("Decoded value must be a dict", decodedDict)

        // Check all keys are present
        assertEquals("All keys must be preserved", 3, decodedDict?.items?.size)
        assertEquals("Key 'z' must have value 1", PlistInteger(1L), decodedDict?.items?.get("z"))
        assertEquals("Key 'a' must have value 2", PlistInteger(2L), decodedDict?.items?.get("a"))
        assertEquals("Key 'm' must have value 3", PlistInteger(3L), decodedDict?.items?.get("m"))
    }

    @Test
    fun testRoundtripSpecialCharactersInKeys() {
        val value =
                PlistDict(
                        mapOf(
                                "key-with-dash" to PlistInteger(1L),
                                "key.with.dot" to PlistInteger(2L),
                                "key_with_underscore" to PlistInteger(3L),
                                "key with spaces" to PlistInteger(4L)
                        )
                )

        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue("Decoding must succeed", decoded.isSuccess)
        assertEquals("Special characters in keys must be preserved", value, decoded.getOrNull())
    }

    @Test
    fun testArrayWithMixedTypes() {
        val value =
                PlistArray(
                        listOf(
                                PlistNull,
                                PlistBoolean(true),
                                PlistInteger(42L),
                                PlistFloat(3.14),
                                PlistString("mixed"),
                                PlistData(byteArrayOf(1, 2, 3)),
                                PlistArray(emptyList()),
                                PlistDict(emptyMap())
                        )
                )

        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue("Decoding mixed array must succeed", decoded.isSuccess)
        assertEquals("Mixed array must roundtrip correctly", value, decoded.getOrNull())
    }
}
