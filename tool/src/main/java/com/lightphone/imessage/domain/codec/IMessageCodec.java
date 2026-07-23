package com.lightphone.imessage.domain.codec;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import com.lightphone.imessage.domain.codec.MessagePayload;

/**
 * Interface for encoding and decoding iMessage envelopes.
 * Handles encryption/decryption and signing/verification of message payloads.
 */
public interface IMessageCodec {
    /**
     * Encodes a message payload into an encrypted, signed envelope.
     *
     * @param payload Message payload to encode
     * @param recipientKey RSA-2048 public key of recipient
     * @param senderKey ECDSA P-256 private key of sender
     * @return Result containing binary envelope or failure
     */
    Result<byte[]> encodeEnvelope(
        MessagePayload payload,
        PublicKey recipientKey,
        PrivateKey senderKey
    );

    /**
     * Decodes and verifies an encrypted, signed envelope into a message payload.
     *
     * @param envelope Binary envelope
     * @param recipientKey RSA-2048 private key of recipient
     * @param senderCert X509 certificate of sender
     * @return Result containing MessagePayload or failure
     */
    Result<MessagePayload> decodeEnvelope(
        byte[] envelope,
        PrivateKey recipientKey,
        X509Certificate senderCert
    );

    /**
     * Encodes a Plist value to binary Plist bytes.
     *
     * @param value PlistValue to encode
     * @return Result containing binary Plist bytes or failure
     */
    Result<byte[]> encodePlist(PlistValue value);

    /**
     * Decodes binary Plist bytes into a PlistValue.
     *
     * @param bytes Binary Plist data
     * @return Result containing PlistValue or failure
     */
    Result<PlistValue> decodePlist(byte[] bytes);
}
