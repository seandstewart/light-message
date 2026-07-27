package com.lightphone.imessage.domain.codec

/**
 * Sealed class representing any value in a Plist (property list). Supports all types needed for
 * binary (bplist00) and XML plist formats.
 */
sealed class PlistValue

/** Represents null value in a Plist. */
object PlistNull : PlistValue()

/** Represents a boolean value in a Plist. */
data class PlistBoolean(val value: Boolean) : PlistValue()

/**
 * Represents an integer value in a Plist. Internally stores as Long to support 1, 2, 4, and 8-byte
 * encodings in binary format.
 */
data class PlistInteger(val value: Long) : PlistValue()

/**
 * Represents a floating-point value in a Plist. Internally stores as Double to support both 4-byte
 * and 8-byte float encodings in binary format.
 */
data class PlistFloat(val value: Double) : PlistValue()

/** Represents a string value in a Plist. UTF-8 in binary format, UTF-16 in XML format. */
data class PlistString(val value: String) : PlistValue()

/**
 * Represents raw binary data in a Plist. Base64-encoded in XML format, raw bytes in binary format.
 */
data class PlistData(val value: ByteArray) : PlistValue() {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PlistData

        return value.contentEquals(other.value)
    }

    override fun hashCode(): Int {
        return value.contentHashCode()
    }
}

/** Represents an array (ordered list) in a Plist. */
data class PlistArray(val items: List<PlistValue>) : PlistValue()

/** Represents a dictionary (key-value map) in a Plist. Keys must be strings. */
data class PlistDict(val items: Map<String, PlistValue>) : PlistValue()

/**
 * Represents a date value in a Plist. Stores as absolute seconds since 2001-01-01 00:00:00 UTC. ISO
 * 8601 formatted in XML, numeric timestamp in binary.
 */
data class PlistDate(val timestamp: Long) : PlistValue()
