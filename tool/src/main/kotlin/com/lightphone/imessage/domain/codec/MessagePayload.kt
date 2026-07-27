package com.lightphone.imessage.domain.codec

/**
 * Represents a complete iMessage payload to be encrypted inside an envelope.
 *
 * Contains message content, metadata, and optional attachment references.
 *
 * Notes on design:
 * - This is intentionally a regular `class` (not `data class`) so we can defensively copy the
 * list/map inputs in an `init` block. Callers may pass mutable collections; we snapshot them via
 * `toList()`/`toMap()` to prevent external aliasing / mutation.
 * - [equals], [hashCode], [toString], and [copy] are provided explicitly to mimic data-class
 * semantics while keeping the defensive copy.
 * - [toString] redacts [body] (sensitive content) by exposing only its length.
 */
class MessagePayload(
        val messageId: String,
        val sender: String,
        recipients: List<String>,
        val body: String,
        metadata: Map<String, String> = emptyMap(),
        attachments: List<AttachmentInfo> = emptyList(),
) {
    val recipients: List<String>
    val metadata: Map<String, String>
    val attachments: List<AttachmentInfo>

    init {
        // Defensive snapshots — break external aliasing so later mutation by the
        // caller cannot silently corrupt an already-constructed payload.
        this.recipients = recipients.toList()
        this.metadata = LinkedHashMap(metadata)
        this.attachments = attachments.toList()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MessagePayload) return false
        return messageId == other.messageId &&
                sender == other.sender &&
                recipients == other.recipients &&
                body == other.body &&
                metadata == other.metadata &&
                attachments == other.attachments
    }

    override fun hashCode(): Int {
        var result = messageId.hashCode()
        result = 31 * result + sender.hashCode()
        result = 31 * result + recipients.hashCode()
        result = 31 * result + body.hashCode()
        result = 31 * result + metadata.hashCode()
        result = 31 * result + attachments.hashCode()
        return result
    }

    /** Redacts [body] content — logs must not leak plaintext. */
    override fun toString(): String =
            "MessagePayload(" +
                    "messageId='$messageId', " +
                    "sender='$sender', " +
                    "recipients=$recipients, " +
                    "body=<${body.length} chars>, " +
                    "metadata=$metadata, " +
                    "attachments=$attachments" +
                    ")"

    fun copy(
            messageId: String = this.messageId,
            sender: String = this.sender,
            recipients: List<String> = this.recipients,
            body: String = this.body,
            metadata: Map<String, String> = this.metadata,
            attachments: List<AttachmentInfo> = this.attachments,
    ): MessagePayload = MessagePayload(messageId, sender, recipients, body, metadata, attachments)

    /**
     * Represents an attachment reference within a message payload.
     *
     * [encryptionKey] is the raw symmetric key used to decrypt the fetched blob at [url]. Overrides
     * [equals]/[hashCode] to use `contentEquals`/`contentHashCode` on the ByteArray field
     * (data-class defaults would compare by reference identity). [toString] redacts the key
     * material, exposing only its byte length.
     */
    data class AttachmentInfo(
            val id: String,
            val mimeType: String,
            val url: String,
            val encryptionKey: ByteArray,
            val size: Long,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AttachmentInfo) return false
            return id == other.id &&
                    mimeType == other.mimeType &&
                    url == other.url &&
                    size == other.size &&
                    encryptionKey.contentEquals(other.encryptionKey)
        }

        override fun hashCode(): Int {
            var result = id.hashCode()
            result = 31 * result + mimeType.hashCode()
            result = 31 * result + url.hashCode()
            result = 31 * result + size.hashCode()
            result = 31 * result + encryptionKey.contentHashCode()
            return result
        }

        /** Redacts [encryptionKey] — logs must not leak key material. */
        override fun toString(): String =
                "AttachmentInfo(" +
                        "id='$id', " +
                        "mimeType='$mimeType', " +
                        "url='$url', " +
                        "encryptionKey=<${encryptionKey.size}B>, " +
                        "size=$size" +
                        ")"
    }
}
