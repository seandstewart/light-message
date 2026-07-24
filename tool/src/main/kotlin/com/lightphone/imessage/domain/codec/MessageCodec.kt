package com.lightphone.imessage.domain.codec

import com.lightphone.imessage.domain.codec.MessagePayload.AttachmentInfo
import com.lightphone.imessage.domain.crypto.CryptoEngine
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.PrivateKey
import java.security.PublicKey
import java.security.cert.X509Certificate

/**
 * Implements [IMessageCodec] for envelope encoding/decoding with AES-256-GCM encryption,
 * RSA-2048-OAEP key wrapping, and ECDSA P-256 signing/verification.
 *
 * Outer envelope Plist keys (v1):
 * - `v`: version (PlistInteger) = 1
 * - `c`: ciphertext (PlistData) — AES-GCM ciphertext (without tag)
 * - `k`: wrapped key (PlistData) — RSA-OAEP-SHA256 wrapped AES key
 * - `s`: signature (PlistData) — ECDSA(SHA-256) signature over canonical bytes (see below)
 * - `i`: IV (PlistData) — 12-byte GCM IV
 * - `t`: auth tag (PlistData) — 16-byte GCM tag
 *
 * Signature covers a length-delimited canonicalization of `version || wrappedKey || iv ||
 * ciphertext || authTag` — see [canonicalSignedBytes]. This binds all authenticated fields
 * including the IV and version, preventing cross-envelope reuse and downgrade attacks.
 */
class MessageCodec(
        private val plistCodec: PlistCodec,
        private val cryptoEngine: CryptoEngine,
) : IMessageCodec {

    override fun encodeEnvelope(
            payload: MessagePayload,
            recipientKey: PublicKey,
            senderKey: PrivateKey,
    ): Result<ByteArray> {
        // Step 1: MessagePayload -> inner Plist -> bytes
        val innerPlist = messagePayloadToPlist(payload)
        val innerPlistBytes =
                plistCodec.encode(innerPlist).getOrElse {
                    return Result.failure(it)
                }

        // Step 2: AES-256-GCM encrypt inner plist bytes.
        val aesKey = cryptoEngine.generateAesKey()
        val aesResult =
                cryptoEngine.aesGcmEncrypt(innerPlistBytes, aesKey, null).getOrElse {
                    return Result.failure(it)
                }

        // Step 3: RSA-OAEP wrap the AES key with recipient's public key.
        val wrappedKey =
                cryptoEngine.rsaOaepWrap(aesKey, recipientKey).getOrElse {
                    return Result.failure(it)
                }

        // Step 4: Sign canonical bytes (version + length-delimited fields).
        val canonical =
                canonicalSignedBytes(
                        v = ENVELOPE_VERSION,
                        wrappedKey = wrappedKey,
                        iv = aesResult.iv,
                        ciphertext = aesResult.ciphertext,
                        authTag = aesResult.authTag,
                )
        val signature =
                cryptoEngine.ecdsaSign(canonical, senderKey).getOrElse {
                    return Result.failure(it)
                }

        // Step 5: Assemble outer envelope Plist (LinkedHashMap preserves order).
        val envelopeDict =
                linkedMapOf<String, PlistValue>(
                        "v" to PlistInteger(ENVELOPE_VERSION.toLong()),
                        "c" to PlistData(aesResult.ciphertext),
                        "k" to PlistData(wrappedKey),
                        "s" to PlistData(signature),
                        "i" to PlistData(aesResult.iv),
                        "t" to PlistData(aesResult.authTag),
                )
        return plistCodec.encode(PlistDict(envelopeDict))
    }

    override fun decodeEnvelope(
            envelope: ByteArray,
            senderCert: X509Certificate,
            recipientKey: PrivateKey,
    ): Result<MessagePayload> {
        // Step 1: Parse outer Plist envelope.
        val envelopePlist =
                plistCodec.decode(envelope).getOrElse {
                    return Result.failure(it)
                }
        if (envelopePlist !is PlistDict) {
            return Result.failure(IllegalArgumentException("envelope root must be a Plist dict"))
        }
        val items = envelopePlist.items

        // Step 2: Extract & validate version FIRST (version whitelist).
        val vValue = items["v"]
        if (vValue !is PlistInteger) {
            return Result.failure(
                    IllegalArgumentException("missing or invalid envelope version field")
            )
        }
        val v = vValue.value.toInt()
        if (v != ENVELOPE_VERSION) {
            return Result.failure(IllegalArgumentException("unsupported envelope version: $v"))
        }

        // Step 3: Extract required binary fields.
        val ciphertext =
                extractData(items, "c")
                        ?: return Result.failure(
                                IllegalArgumentException("envelope missing ciphertext ('c')")
                        )
        val wrappedKey =
                extractData(items, "k")
                        ?: return Result.failure(
                                IllegalArgumentException("envelope missing wrapped key ('k')")
                        )
        val signature =
                extractData(items, "s")
                        ?: return Result.failure(
                                IllegalArgumentException("envelope missing signature ('s')")
                        )
        val iv =
                extractData(items, "i")
                        ?: return Result.failure(
                                IllegalArgumentException("envelope missing IV ('i')")
                        )
        val authTag =
                extractData(items, "t")
                        ?: return Result.failure(
                                IllegalArgumentException("envelope missing auth tag ('t')")
                        )

        // Step 4: Verify signature BEFORE any unwrap/decrypt. Reject on failure.
        val canonical = canonicalSignedBytes(v, wrappedKey, iv, ciphertext, authTag)
        cryptoEngine.ecdsaVerify(canonical, signature, senderCert).getOrElse {
            return Result.failure(it)
        }

        // Step 5: RSA-OAEP unwrap AES key with recipient private key.
        val aesKey =
                cryptoEngine.rsaOaepUnwrap(wrappedKey, recipientKey).getOrElse {
                    return Result.failure(it)
                }

        // Step 6: AES-256-GCM decrypt inner plist bytes.
        val innerPlistBytes =
                cryptoEngine.aesGcmDecrypt(ciphertext, aesKey, iv, authTag, null).getOrElse {
                    return Result.failure(it)
                }

        // Step 7: Parse inner Plist and rehydrate into MessagePayload.
        val innerPlist =
                plistCodec.decode(innerPlistBytes).getOrElse {
                    return Result.failure(it)
                }
        return plistToMessagePayload(innerPlist)
    }

    /** Encodes a Plist value to binary Plist bytes. Convenience pass-through used by tests. */
    fun encodePlist(value: PlistValue): Result<ByteArray> = plistCodec.encode(value)

    /** Decodes binary Plist bytes into a PlistValue. Convenience pass-through used by tests. */
    fun decodePlist(bytes: ByteArray): Result<PlistValue> = plistCodec.decode(bytes)

    // ---------- helpers ----------

    /**
     * Canonicalize the bytes covered by the ECDSA signature.
     *
     * Format: `versionBE4 || len(wk)BE4 || wk || len(iv)BE4 || iv || len(ct)BE4 || ct ||
     * len(tag)BE4 || tag`. All lengths are 4-byte big-endian uint32. This is used symmetrically on
     * both encode and decode paths so that any drift will cause signature verification to fail.
     */
    private fun canonicalSignedBytes(
            v: Int,
            wrappedKey: ByteArray,
            iv: ByteArray,
            ciphertext: ByteArray,
            authTag: ByteArray,
    ): ByteArray {
        val out =
                ByteArrayOutputStream(
                        4 +
                                4 +
                                wrappedKey.size +
                                4 +
                                iv.size +
                                4 +
                                ciphertext.size +
                                4 +
                                authTag.size,
                )
        out.write(intToBE4(v))
        out.write(intToBE4(wrappedKey.size))
        out.write(wrappedKey)
        out.write(intToBE4(iv.size))
        out.write(iv)
        out.write(intToBE4(ciphertext.size))
        out.write(ciphertext)
        out.write(intToBE4(authTag.size))
        out.write(authTag)
        return out.toByteArray()
    }

    private fun intToBE4(n: Int): ByteArray =
            ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(n).array()

    /** Convert a MessagePayload to a Plist dict (order-preserved via LinkedHashMap). */
    private fun messagePayloadToPlist(payload: MessagePayload): PlistValue {
        val recipientsList: List<PlistValue> = payload.recipients.map { PlistString(it) }

        val metadataDict = linkedMapOf<String, PlistValue>()
        for ((k, v) in payload.metadata) {
            metadataDict[k] = PlistString(v)
        }

        val attachmentsList: List<PlistValue> =
                payload.attachments.map { att ->
                    val attDict =
                            linkedMapOf<String, PlistValue>(
                                    "id" to PlistString(att.id),
                                    "mimeType" to PlistString(att.mimeType),
                                    "url" to PlistString(att.url),
                                    "size" to PlistInteger(att.size),
                                    "encryptionKey" to PlistData(att.encryptionKey),
                            )
                    PlistDict(attDict)
                }

        val payloadDict =
                linkedMapOf<String, PlistValue>(
                        "messageId" to PlistString(payload.messageId),
                        "sender" to PlistString(payload.sender),
                        "recipients" to PlistArray(recipientsList),
                        "body" to PlistString(payload.body),
                        "metadata" to PlistDict(metadataDict),
                        "attachments" to PlistArray(attachmentsList),
                )
        return PlistDict(payloadDict)
    }

    /** Rehydrate a MessagePayload from an inner Plist dict. */
    private fun plistToMessagePayload(plist: PlistValue): Result<MessagePayload> {
        if (plist !is PlistDict) {
            return Result.failure(IllegalArgumentException("inner payload must be a Plist dict"))
        }
        val dict = plist.items

        val messageId =
                (dict["messageId"] as? PlistString)?.value
                        ?: return Result.failure(
                                IllegalArgumentException("payload missing 'messageId'")
                        )
        val sender =
                (dict["sender"] as? PlistString)?.value
                        ?: return Result.failure(
                                IllegalArgumentException("payload missing 'sender'")
                        )
        val body =
                (dict["body"] as? PlistString)?.value
                        ?: return Result.failure(IllegalArgumentException("payload missing 'body'"))

        val recipients = mutableListOf<String>()
        (dict["recipients"] as? PlistArray)?.items?.forEach { item ->
            if (item is PlistString) recipients.add(item.value)
        }

        val metadata = LinkedHashMap<String, String>()
        (dict["metadata"] as? PlistDict)?.items?.forEach { (k, v) ->
            if (v is PlistString) metadata[k] = v.value
        }

        val attachments = mutableListOf<AttachmentInfo>()
        val attachmentsValue = dict["attachments"]
        if (attachmentsValue is PlistArray) {
            for (item in attachmentsValue.items) {
                if (item !is PlistDict) continue
                val ad = item.items
                val attId = (ad["id"] as? PlistString)?.value
                val mimeType = (ad["mimeType"] as? PlistString)?.value
                val url = (ad["url"] as? PlistString)?.value
                val encKey = (ad["encryptionKey"] as? PlistData)?.value
                val size = (ad["size"] as? PlistInteger)?.value ?: 0L

                if (attId == null || mimeType == null || url == null || encKey == null) {
                    return Result.failure(
                            IllegalArgumentException(
                                    "attachment missing required field(s): " +
                                            "id=${attId != null}, mimeType=${mimeType != null}, " +
                                            "url=${url != null}, encryptionKey=${encKey != null}",
                            ),
                    )
                }

                attachments.add(
                        AttachmentInfo(
                                id = attId,
                                mimeType = mimeType,
                                url = url,
                                encryptionKey = encKey,
                                size = size,
                        ),
                )
            }
        }

        return Result.success(
                MessagePayload(
                        messageId = messageId,
                        sender = sender,
                        recipients = recipients,
                        body = body,
                        metadata = metadata,
                        attachments = attachments,
                ),
        )
    }

    private fun extractData(items: Map<String, PlistValue>, key: String): ByteArray? =
            (items[key] as? PlistData)?.value

    private companion object {
        const val ENVELOPE_VERSION = 1
    }
}
