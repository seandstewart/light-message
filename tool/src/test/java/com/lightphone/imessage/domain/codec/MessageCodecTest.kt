package com.lightphone.imessage.domain.codec

import com.lightphone.imessage.domain.crypto.CryptoEngine
import java.security.cert.X509Certificate
import java.util.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Comprehensive unit tests for MessageCodec. Tests envelope encryption/decryption with AES-256-GCM,
 * RSA-2048-OAEP key wrapping, and ECDSA signing. Target: 100% code coverage.
 */
class MessageCodecTest {
    private lateinit var messageCodec: MessageCodec
    private lateinit var cryptoEngine: CryptoEngine

    @Before
    fun setUp() {
        cryptoEngine = CryptoEngine()
        val plistCodec = PlistCodec()
        messageCodec = MessageCodec(plistCodec, cryptoEngine)
    }

    // ========== Basic Envelope Encode/Decode ==========

    @Test
    fun testEncodeDecodeEnvelopeSimple() {
        val (recipientPublicKey, recipientPrivateKey) = cryptoEngine.generateRsaKeyPair()
        val (senderPublicKey, senderPrivateKey) = cryptoEngine.generateEcdsaKeyPair()
        val senderCert = createTestCertificate(senderPublicKey, senderPrivateKey)

        val payload =
                MessagePayload(
                        messageId = "msg-001",
                        sender = "alice@example.com",
                        recipients = listOf("bob@example.com"),
                        timestamp = System.currentTimeMillis(),
                        body = "Hello, Bob!"
                )

        val encodeResult =
                messageCodec.encodeEnvelope(payload, recipientPublicKey, senderPrivateKey)
        assertTrue("Encoding must succeed", encodeResult.isSuccess)

        val envelope = encodeResult.getOrThrow()
        assertNotNull("Envelope must not be null", envelope)
        assertFalse("Envelope must not be empty", envelope.isEmpty())

        val decodeResult = messageCodec.decodeEnvelope(envelope, recipientPrivateKey, senderCert)
        assertTrue("Decoding must succeed", decodeResult.isSuccess)

        val decodedPayload = decodeResult.getOrThrow()
        assertEquals("Message ID must match", payload.messageId, decodedPayload.messageId)
        assertEquals("Sender must match", payload.sender, decodedPayload.sender)
        assertEquals("Recipients must match", payload.recipients, decodedPayload.recipients)
        assertEquals("Body must match", payload.body, decodedPayload.body)
    }

    @Test
    fun testEncodeDecodeEnvelopeWithMetadata() {
        val (recipientPublicKey, recipientPrivateKey) = cryptoEngine.generateRsaKeyPair()
        val (senderPublicKey, senderPrivateKey) = cryptoEngine.generateEcdsaKeyPair()
        val senderCert = createTestCertificate(senderPublicKey, senderPrivateKey)

        val payload =
                MessagePayload(
                        messageId = "msg-002",
                        sender = "alice@example.com",
                        recipients = listOf("bob@example.com", "carol@example.com"),
                        timestamp = System.currentTimeMillis(),
                        body = "Message with metadata",
                        metadata = mapOf("priority" to "high", "thread-id" to "thread-123")
                )

        val encodeResult =
                messageCodec.encodeEnvelope(payload, recipientPublicKey, senderPrivateKey)
        assertTrue("Encoding must succeed", encodeResult.isSuccess)

        val decodeResult =
                messageCodec.decodeEnvelope(
                        encodeResult.getOrThrow(),
                        recipientPrivateKey,
                        senderCert
                )
        assertTrue("Decoding must succeed", decodeResult.isSuccess)

        val decodedPayload = decodeResult.getOrThrow()
        assertEquals("Metadata must match", payload.metadata, decodedPayload.metadata)
    }

    @Test
    fun testEncodeDecodeEnvelopeWithAttachments() {
        val (recipientPublicKey, recipientPrivateKey) = cryptoEngine.generateRsaKeyPair()
        val (senderPublicKey, senderPrivateKey) = cryptoEngine.generateEcdsaKeyPair()
        val senderCert = createTestCertificate(senderPublicKey, senderPrivateKey)

        val attachmentKey = ByteArray(32) { it.toByte() }
        val attachment =
                MessagePayload.AttachmentInfo(
                        id = "att-001",
                        mimeType = "image/png",
                        url = "https://example.com/image.png",
                        size = 10240,
                        encryptionKey = attachmentKey
                )

        val payload =
                MessagePayload(
                        messageId = "msg-003",
                        sender = "alice@example.com",
                        recipients = listOf("bob@example.com"),
                        timestamp = System.currentTimeMillis(),
                        body = "Message with attachment",
                        attachments = listOf(attachment)
                )

        val encodeResult =
                messageCodec.encodeEnvelope(payload, recipientPublicKey, senderPrivateKey)
        assertTrue("Encoding must succeed", encodeResult.isSuccess)

        val decodeResult =
                messageCodec.decodeEnvelope(
                        encodeResult.getOrThrow(),
                        recipientPrivateKey,
                        senderCert
                )
        assertTrue("Decoding must succeed", decodeResult.isSuccess)

        val decodedPayload = decodeResult.getOrThrow()
        assertEquals(
                "Attachments must match",
                payload.attachments.size,
                decodedPayload.attachments.size
        )

        val decodedAttachment = decodedPayload.attachments[0]
        assertEquals("Attachment ID must match", attachment.id, decodedAttachment.id)
        assertEquals(
                "Attachment MIME type must match",
                attachment.mimeType,
                decodedAttachment.mimeType
        )
        assertEquals("Attachment URL must match", attachment.url, decodedAttachment.url)
        assertEquals("Attachment size must match", attachment.size, decodedAttachment.size)
        assertArrayEquals(
                "Attachment encryption key must match",
                attachment.encryptionKey,
                decodedAttachment.encryptionKey
        )
    }

    @Test
    fun testEncodeDecodeEnvelopeMultipleRecipients() {
        val (recipientPublicKey, recipientPrivateKey) = cryptoEngine.generateRsaKeyPair()
        val (senderPublicKey, senderPrivateKey) = cryptoEngine.generateEcdsaKeyPair()
        val senderCert = createTestCertificate(senderPublicKey, senderPrivateKey)

        val payload =
                MessagePayload(
                        messageId = "msg-004",
                        sender = "alice@example.com",
                        recipients =
                                listOf("bob@example.com", "carol@example.com", "dave@example.com"),
                        timestamp = System.currentTimeMillis(),
                        body = "Group message"
                )

        val encodeResult =
                messageCodec.encodeEnvelope(payload, recipientPublicKey, senderPrivateKey)
        assertTrue("Encoding must succeed", encodeResult.isSuccess)

        val decodeResult =
                messageCodec.decodeEnvelope(
                        encodeResult.getOrThrow(),
                        recipientPrivateKey,
                        senderCert
                )
        assertTrue("Decoding must succeed", decodeResult.isSuccess)

        val decodedPayload = decodeResult.getOrThrow()
        assertEquals("Recipients count must match", 3, decodedPayload.recipients.size)
        assertTrue(
                "Recipients must contain all addresses",
                decodedPayload.recipients.contains("bob@example.com")
        )
        assertTrue(
                "Recipients must contain all addresses",
                decodedPayload.recipients.contains("carol@example.com")
        )
        assertTrue(
                "Recipients must contain all addresses",
                decodedPayload.recipients.contains("dave@example.com")
        )
    }

    // ========== Signature Verification ==========

    @Test
    fun testSignatureVerification() {
        val (recipientPublicKey, recipientPrivateKey) = cryptoEngine.generateRsaKeyPair()
        val (senderPublicKey, senderPrivateKey) = cryptoEngine.generateEcdsaKeyPair()
        val senderCert = createTestCertificate(senderPublicKey, senderPrivateKey)

        val payload =
                MessagePayload(
                        messageId = "msg-005",
                        sender = "alice@example.com",
                        recipients = listOf("bob@example.com"),
                        timestamp = System.currentTimeMillis(),
                        body = "Signed message"
                )

        val encodeResult =
                messageCodec.encodeEnvelope(payload, recipientPublicKey, senderPrivateKey)
        val envelope = encodeResult.getOrThrow()

        val decodeResult = messageCodec.decodeEnvelope(envelope, recipientPrivateKey, senderCert)
        assertTrue("Decoding with correct sender cert must succeed", decodeResult.isSuccess)
    }

    @Test
    fun testSignatureVerificationWithWrongKey() {
        val (recipientPublicKey, recipientPrivateKey) = cryptoEngine.generateRsaKeyPair()
        val (senderPublicKey, senderPrivateKey) = cryptoEngine.generateEcdsaKeyPair()
        val (wrongPublicKey, _) = cryptoEngine.generateEcdsaKeyPair()

        val senderCert = createTestCertificate(senderPublicKey, senderPrivateKey)
        val wrongCert =
                createTestCertificate(wrongPublicKey, cryptoEngine.generateEcdsaKeyPair().second)

        val payload =
                MessagePayload(
                        messageId = "msg-006",
                        sender = "alice@example.com",
                        recipients = listOf("bob@example.com"),
                        timestamp = System.currentTimeMillis(),
                        body = "Message"
                )

        val envelope =
                messageCodec
                        .encodeEnvelope(payload, recipientPublicKey, senderPrivateKey)
                        .getOrThrow()
        val decodeResult = messageCodec.decodeEnvelope(envelope, recipientPrivateKey, wrongCert)

        assertTrue("Decoding with wrong sender cert must fail", decodeResult.isFailure)
    }

    // ========== Tampering Detection ==========

    @Test
    fun testRejectTamperedCiphertext() {
        val (recipientPublicKey, recipientPrivateKey) = cryptoEngine.generateRsaKeyPair()
        val (senderPublicKey, senderPrivateKey) = cryptoEngine.generateEcdsaKeyPair()
        val senderCert = createTestCertificate(senderPublicKey, senderPrivateKey)

        val payload =
                MessagePayload(
                        messageId = "msg-007",
                        sender = "alice@example.com",
                        recipients = listOf("bob@example.com"),
                        timestamp = System.currentTimeMillis(),
                        body = "Tamper test"
                )

        val encodeResult =
                messageCodec.encodeEnvelope(payload, recipientPublicKey, senderPrivateKey)
        val envelope = encodeResult.getOrThrow()

        // Tamper with the envelope
        val tamperedEnvelope = envelope.copyOf()
        if (tamperedEnvelope.size > 100) {
            tamperedEnvelope[100] = (tamperedEnvelope[100].toInt() xor 0xFF).toByte()
        }

        val decodeResult =
                messageCodec.decodeEnvelope(tamperedEnvelope, recipientPrivateKey, senderCert)
        assertTrue("Decoding tampered envelope must fail", decodeResult.isFailure)
    }

    @Test
    fun testRejectInvalidSignature() {
        val (recipientPublicKey, recipientPrivateKey) = cryptoEngine.generateRsaKeyPair()
        val (senderPublicKey, senderPrivateKey) = cryptoEngine.generateEcdsaKeyPair()
        val (otherPublicKey, otherPrivateKey) = cryptoEngine.generateEcdsaKeyPair()

        val senderCert = createTestCertificate(senderPublicKey, senderPrivateKey)

        val payload =
                MessagePayload(
                        messageId = "msg-008",
                        sender = "alice@example.com",
                        recipients = listOf("bob@example.com"),
                        timestamp = System.currentTimeMillis(),
                        body = "Invalid signature test"
                )

        // Encode with one key
        val envelope =
                messageCodec
                        .encodeEnvelope(payload, recipientPublicKey, senderPrivateKey)
                        .getOrThrow()

        // Try to decode with different sender certificate
        val wrongCert = createTestCertificate(otherPublicKey, otherPrivateKey)
        val decodeResult = messageCodec.decodeEnvelope(envelope, recipientPrivateKey, wrongCert)

        assertTrue("Decoding with mismatched signature must fail", decodeResult.isFailure)
    }

    @Test
    fun testRejectUnsignedMessage() {
        val (recipientPublicKey, recipientPrivateKey) = cryptoEngine.generateRsaKeyPair()
        val (senderPublicKey, senderPrivateKey) = cryptoEngine.generateEcdsaKeyPair()
        val senderCert = createTestCertificate(senderPublicKey, senderPrivateKey)

        // Create a malformed envelope without proper signature
        val malformedEnvelope = byteArrayOf('b', 'p', 'l', 'i', 's', 't', '0', '0') + ByteArray(100)

        val decodeResult =
                messageCodec.decodeEnvelope(malformedEnvelope, recipientPrivateKey, senderCert)
        assertTrue("Decoding unsigned/malformed message must fail", decodeResult.isFailure)
    }

    // ========== Metadata Preservation ==========

    @Test
    fun testMetadataPreservation() {
        val (recipientPublicKey, recipientPrivateKey) = cryptoEngine.generateRsaKeyPair()
        val (senderPublicKey, senderPrivateKey) = cryptoEngine.generateEcdsaKeyPair()
        val senderCert = createTestCertificate(senderPublicKey, senderPrivateKey)

        val metadata =
                mapOf(
                        "priority" to "high",
                        "thread-id" to "thread-456",
                        "custom-field" to "custom-value",
                        "empty" to ""
                )

        val payload =
                MessagePayload(
                        messageId = "msg-009",
                        sender = "alice@example.com",
                        recipients = listOf("bob@example.com"),
                        timestamp = System.currentTimeMillis(),
                        body = "Metadata test",
                        metadata = metadata
                )

        val envelope =
                messageCodec
                        .encodeEnvelope(payload, recipientPublicKey, senderPrivateKey)
                        .getOrThrow()
        val decodedPayload =
                messageCodec.decodeEnvelope(envelope, recipientPrivateKey, senderCert).getOrThrow()

        assertEquals("All metadata must be preserved", metadata, decodedPayload.metadata)
    }

    @Test
    fun testAttachmentMetadata() {
        val (recipientPublicKey, recipientPrivateKey) = cryptoEngine.generateRsaKeyPair()
        val (senderPublicKey, senderPrivateKey) = cryptoEngine.generateEcdsaKeyPair()
        val senderCert = createTestCertificate(senderPublicKey, senderPrivateKey)

        val attachments =
                listOf(
                        MessagePayload.AttachmentInfo(
                                id = "att-001",
                                mimeType = "image/jpeg",
                                url = "https://example.com/photo.jpg",
                                size = 204800,
                                encryptionKey = ByteArray(32)
                        ),
                        MessagePayload.AttachmentInfo(
                                id = "att-002",
                                mimeType = "video/mp4",
                                url = "https://example.com/video.mp4",
                                size = 5242880,
                                encryptionKey = ByteArray(32) { it.toByte() }
                        )
                )

        val payload =
                MessagePayload(
                        messageId = "msg-010",
                        sender = "alice@example.com",
                        recipients = listOf("bob@example.com"),
                        timestamp = System.currentTimeMillis(),
                        body = "Multiple attachments",
                        attachments = attachments
                )

        val envelope =
                messageCodec
                        .encodeEnvelope(payload, recipientPublicKey, senderPrivateKey)
                        .getOrThrow()
        val decodedPayload =
                messageCodec.decodeEnvelope(envelope, recipientPrivateKey, senderCert).getOrThrow()

        assertEquals(
                "All attachments must be preserved",
                attachments.size,
                decodedPayload.attachments.size
        )

        for (i in attachments.indices) {
            assertEquals(
                    "Attachment ID must match",
                    attachments[i].id,
                    decodedPayload.attachments[i].id
            )
            assertEquals(
                    "Attachment MIME type must match",
                    attachments[i].mimeType,
                    decodedPayload.attachments[i].mimeType
            )
            assertEquals(
                    "Attachment URL must match",
                    attachments[i].url,
                    decodedPayload.attachments[i].url
            )
            assertEquals(
                    "Attachment size must match",
                    attachments[i].size,
                    decodedPayload.attachments[i].size
            )
        }
    }

    // ========== Edge Cases ==========

    @Test
    fun testEnvelopeWithEmptyBody() {
        val (recipientPublicKey, recipientPrivateKey) = cryptoEngine.generateRsaKeyPair()
        val (senderPublicKey, senderPrivateKey) = cryptoEngine.generateEcdsaKeyPair()
        val senderCert = createTestCertificate(senderPublicKey, senderPrivateKey)

        val payload =
                MessagePayload(
                        messageId = "msg-011",
                        sender = "alice@example.com",
                        recipients = listOf("bob@example.com"),
                        timestamp = System.currentTimeMillis(),
                        body = ""
                )

        val envelope =
                messageCodec
                        .encodeEnvelope(payload, recipientPublicKey, senderPrivateKey)
                        .getOrThrow()
        val decodedPayload =
                messageCodec.decodeEnvelope(envelope, recipientPrivateKey, senderCert).getOrThrow()

        assertEquals("Empty body must be preserved", "", decodedPayload.body)
    }

    @Test
    fun testEnvelopeWithLargeBody() {
        val (recipientPublicKey, recipientPrivateKey) = cryptoEngine.generateRsaKeyPair()
        val (senderPublicKey, senderPrivateKey) = cryptoEngine.generateEcdsaKeyPair()
        val senderCert = createTestCertificate(senderPublicKey, senderPrivateKey)

        val largeBody = "A".repeat(100000) // 100 KB

        val payload =
                MessagePayload(
                        messageId = "msg-012",
                        sender = "alice@example.com",
                        recipients = listOf("bob@example.com"),
                        timestamp = System.currentTimeMillis(),
                        body = largeBody
                )

        val envelope =
                messageCodec
                        .encodeEnvelope(payload, recipientPublicKey, senderPrivateKey)
                        .getOrThrow()
        val decodedPayload =
                messageCodec.decodeEnvelope(envelope, recipientPrivateKey, senderCert).getOrThrow()

        assertEquals("Large body must be preserved", largeBody, decodedPayload.body)
    }

    @Test
    fun testEnvelopeWithUnicodeBody() {
        val (recipientPublicKey, recipientPrivateKey) = cryptoEngine.generateRsaKeyPair()
        val (senderPublicKey, senderPrivateKey) = cryptoEngine.generateEcdsaKeyPair()
        val senderCert = createTestCertificate(senderPublicKey, senderPrivateKey)

        val unicodeBody = "Hello 世界 🌍 مرحبا שלום"

        val payload =
                MessagePayload(
                        messageId = "msg-013",
                        sender = "alice@example.com",
                        recipients = listOf("bob@example.com"),
                        timestamp = System.currentTimeMillis(),
                        body = unicodeBody
                )

        val envelope =
                messageCodec
                        .encodeEnvelope(payload, recipientPublicKey, senderPrivateKey)
                        .getOrThrow()
        val decodedPayload =
                messageCodec.decodeEnvelope(envelope, recipientPrivateKey, senderCert).getOrThrow()

        assertEquals("Unicode body must be preserved", unicodeBody, decodedPayload.body)
    }

    @Test
    fun testEnvelopeWithNoRecipients() {
        val (recipientPublicKey, recipientPrivateKey) = cryptoEngine.generateRsaKeyPair()
        val (senderPublicKey, senderPrivateKey) = cryptoEngine.generateEcdsaKeyPair()
        val senderCert = createTestCertificate(senderPublicKey, senderPrivateKey)

        val payload =
                MessagePayload(
                        messageId = "msg-014",
                        sender = "alice@example.com",
                        recipients = emptyList(),
                        timestamp = System.currentTimeMillis(),
                        body = "No recipients"
                )

        val envelope =
                messageCodec
                        .encodeEnvelope(payload, recipientPublicKey, senderPrivateKey)
                        .getOrThrow()
        val decodedPayload =
                messageCodec.decodeEnvelope(envelope, recipientPrivateKey, senderCert).getOrThrow()

        assertEquals("Empty recipients list must be preserved", 0, decodedPayload.recipients.size)
    }

    // ========== Plist Encoding/Decoding ==========

    @Test
    fun testEncodePlistSimple() {
        val value = PlistDict(mapOf("test" to PlistString("value")))
        val encodeResult = messageCodec.encodePlist(value)

        assertTrue("Encoding must succeed", encodeResult.isSuccess)
        assertFalse("Encoded plist must not be empty", encodeResult.getOrThrow().isEmpty())
    }

    @Test
    fun testDecodePlistSimple() {
        val value = PlistDict(mapOf("test" to PlistString("value")))
        val encoded = messageCodec.encodePlist(value).getOrThrow()
        val decodeResult = messageCodec.decodePlist(encoded)

        assertTrue("Decoding must succeed", decodeResult.isSuccess)
        assertEquals("Decoded plist must match original", value, decodeResult.getOrNull())
    }

    @Test
    fun testEncodePlistAllTypes() {
        val value =
                PlistDict(
                        mapOf(
                                "null" to PlistNull,
                                "bool" to PlistBoolean(true),
                                "int" to PlistInteger(42L),
                                "float" to PlistFloat(3.14),
                                "string" to PlistString("test"),
                                "data" to PlistData(byteArrayOf(1, 2, 3)),
                                "array" to PlistArray(listOf(PlistInteger(1L), PlistInteger(2L))),
                                "dict" to PlistDict(mapOf("nested" to PlistString("value")))
                        )
                )

        val encoded = messageCodec.encodePlist(value).getOrThrow()
        val decoded = messageCodec.decodePlist(encoded).getOrThrow()

        assertEquals("All types must roundtrip", value, decoded)
    }

    // ========== Helper Functions ==========

    private fun createTestCertificate(
            publicKey: java.security.PublicKey,
            privateKey: java.security.PrivateKey
    ): X509Certificate {
        try {
            val certBuilder = sun.security.x509.X509CertInfo()

            certBuilder.set(
                    sun.security.x509.X509CertInfo.VERSION,
                    sun.security.x509.CertificateVersion(sun.security.x509.CertificateVersion.V3)
            )

            certBuilder.set(
                    sun.security.x509.X509CertInfo.SERIAL_NUMBER,
                    sun.security.x509.CertificateSerialNumber(
                            (System.currentTimeMillis() / 1000).toInt()
                    )
            )

            val algorithm =
                    sun.security.x509.AlgorithmId(
                            sun.security.util.ObjectIdentifier("1.2.840.10045.4.3.2")
                    )
            certBuilder.set(
                    sun.security.x509.X509CertInfo.ALGORITHM_ID,
                    sun.security.x509.CertificateAlgorithmId(algorithm)
            )

            val issuer = sun.security.x509.X500Name("CN=test,O=test,C=US")
            certBuilder.set(sun.security.x509.X509CertInfo.ISSUER, issuer)
            certBuilder.set(sun.security.x509.X509CertInfo.SUBJECT, issuer)

            certBuilder.set(
                    sun.security.x509.X509CertInfo.KEY,
                    sun.security.x509.CertificateX509Key(publicKey)
            )

            val now = Date()
            val validity =
                    sun.security.x509.CertificateValidity(
                            now,
                            Date(now.time + 365 * 24 * 60 * 60 * 1000)
                    )
            certBuilder.set(sun.security.x509.X509CertInfo.VALIDITY, validity)

            val cert = sun.security.x509.X509CertImpl(certBuilder)
            cert.sign(privateKey, "SHA256withECDSA")

            return cert
        } catch (e: Exception) {
            throw RuntimeException("Failed to create test certificate: ${e.message}", e)
        }
    }
}
