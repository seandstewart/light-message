package com.lightphone.imessage.domain.codec

import java.security.PrivateKey
import java.security.PublicKey
import java.security.cert.X509Certificate

/**
 * Interface for encoding and decoding iMessage envelopes.
 *
 * Handles authenticated encryption (AES-256-GCM), key wrapping (RSA-2048-OAEP-SHA256), and message
 * signing/verification (ECDSA P-256 + SHA-256) of message payloads.
 *
 * All methods return [kotlin.Result] so that callers can distinguish crypto/parse failures from
 * success without exception plumbing.
 */
interface IMessageCodec {
    /**
     * Encode [payload] into an encrypted, signed envelope.
     *
     * @param payload Message payload to encode.
     * @param recipientKey RSA-2048 public key of the recipient (used to wrap the AES key).
     * @param senderKey ECDSA P-256 private key of the sender (used to sign the envelope).
     * @return [Result] containing the binary envelope bytes, or a failure with the underlying
     * cause.
     */
    fun encodeEnvelope(
            payload: MessagePayload,
            recipientKey: PublicKey,
            senderKey: PrivateKey,
    ): Result<ByteArray>

    /**
     * Decode and verify [envelope] into a [MessagePayload].
     *
     * The implementation MUST verify the ECDSA signature BEFORE any AES unwrap/decrypt happens, and
     * MUST reject unsupported envelope versions.
     *
     * @param envelope Binary envelope bytes.
     * @param senderCert X.509 certificate of the sender (public key used to verify signature).
     * @param recipientKey RSA-2048 private key of the recipient (used to unwrap the AES key).
     * @return [Result] containing the decoded [MessagePayload], or a failure with the underlying
     * cause.
     */
    fun decodeEnvelope(
            envelope: ByteArray,
            senderCert: X509Certificate,
            recipientKey: PrivateKey,
    ): Result<MessagePayload>
}
