package com.lightphone.imessage.domain.codec;

import com.lightphone.imessage.domain.crypto.CryptoEngine;
import com.lightphone.imessage.domain.crypto.AesGcmResult;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import javax.crypto.SecretKey;
import java.util.*;

/**
 * Implements IMessageCodec for envelope encoding/decoding with AES-256-GCM encryption,
 * RSA-2048-OAEP key wrapping, and ECDSA signing/verification.
 *
 * Envelope format (outer Plist):
 * - v: version (Long) = 1
 * - c: ciphertext (Data) = AES-256-GCM ciphertext
 * - k: wrapped key (Data) = RSA-OAEP wrapped AES key
 * - s: signature (Data) = ECDSA signature
 * - i: IV (Data) = 12-byte AES-GCM initialization vector
 * - t: auth tag (Data) = 16-byte AES-GCM authentication tag
 */
public class MessageCodec implements IMessageCodec {

    private final PlistCodec plistCodec;
    private final CryptoEngine cryptoEngine;

    public MessageCodec(PlistCodec plistCodec, CryptoEngine cryptoEngine) {
        this.plistCodec = plistCodec;
        this.cryptoEngine = cryptoEngine;
    }

    /**
     * Encodes a message payload into an encrypted, signed envelope.
     */
    @Override
    public Result<byte[]> encodeEnvelope(
            MessagePayload payload,
            PublicKey recipientKey,
            PrivateKey senderKey
    ) {
        try {
            // Step 1: Convert payload to Plist dict
            PlistValue innerPlist = messagePayloadToPlist(payload);

            // Step 2: Encode inner Plist to binary bytes
            Result<byte[]> innerPlistBytesResult = plistCodec.encode(innerPlist);
            if (innerPlistBytesResult.isFailure) {
                return Result.failure(innerPlistBytesResult.exceptionOrNull());
            }
            byte[] innerPlistBytes = innerPlistBytesResult.getOrThrow();

            // Step 3: Generate AES-256 key and encrypt
            SecretKey aesKey = cryptoEngine.generateAesKey();
            AesGcmResult aesGcmResult = cryptoEngine.aesGcmEncrypt(innerPlistBytes, aesKey, null);

            // Step 4: Wrap AES key with recipient's public key using RSA-OAEP
            Result<byte[]> wrappedKeyResult = cryptoEngine.rsaOaepWrap(aesKey, recipientKey);
            if (wrappedKeyResult.isFailure) {
                return Result.failure(wrappedKeyResult.exceptionOrNull());
            }
            byte[] wrappedKey = wrappedKeyResult.getOrThrow();

            // Step 5: Concatenate wrappedKey || ciphertext || authTag for signing
            byte[] dataToSign = concatenateByteArrays(
                    wrappedKey,
                    aesGcmResult.ciphertext,
                    aesGcmResult.authTag
            );

            // Step 6: ECDSA sign the concatenation
            Result<byte[]> signatureResult = cryptoEngine.ecdsaSign(dataToSign, senderKey);
            if (signatureResult.isFailure) {
                return Result.failure(signatureResult.exceptionOrNull());
            }
            byte[] signature = signatureResult.getOrThrow();

            // Step 7: Build outer Plist envelope dict
            Map<String, PlistValue> envelopeDict = new LinkedHashMap<>();
            envelopeDict.put("v", new PlistInteger(1L));
            envelopeDict.put("c", new PlistData(aesGcmResult.ciphertext));
            envelopeDict.put("k", new PlistData(wrappedKey));
            envelopeDict.put("s", new PlistData(signature));
            envelopeDict.put("i", new PlistData(aesGcmResult.iv));
            envelopeDict.put("t", new PlistData(aesGcmResult.authTag));
            PlistValue envelopePlist = new PlistDict(envelopeDict);

            // Step 8: Encode outer Plist to binary
            return plistCodec.encode(envelopePlist);
        } catch (Exception e) {
            return Result.failure(e);
        }
    }

    /**
     * Decodes and verifies an encrypted, signed envelope into a message payload.
     */
    @Override
    public Result<MessagePayload> decodeEnvelope(
            byte[] envelope,
            PrivateKey recipientKey,
            X509Certificate senderCert
    ) {
        try {
            // Step 1: Decode outer Plist
            Result<PlistValue> envelopePlistResult = plistCodec.decode(envelope);
            if (envelopePlistResult.isFailure) {
                return Result.failure(envelopePlistResult.exceptionOrNull());
            }
            PlistValue envelopePlist = envelopePlistResult.getOrThrow();

            // Step 2: Extract envelope fields
            if (!(envelopePlist instanceof PlistDict)) {
                return Result.failure(new IllegalArgumentException("Envelope must be a Plist dict"));
            }
            PlistDict envelopeDict = (PlistDict) envelopePlist;

            PlistValue vValue = envelopeDict.items.get("v");
            if (!(vValue instanceof PlistInteger)) {
                return Result.failure(new IllegalArgumentException("Missing or invalid version field"));
            }
            long version = ((PlistInteger) vValue).value;
            if (version != 1L) {
                return Result.failure(new IllegalArgumentException("Invalid version: " + version));
            }

            byte[] ciphertext = extractData(envelopeDict, "c");
            byte[] wrappedKey = extractData(envelopeDict, "k");
            byte[] signature = extractData(envelopeDict, "s");
            byte[] iv = extractData(envelopeDict, "i");
            byte[] authTag = extractData(envelopeDict, "t");

            if (ciphertext == null || wrappedKey == null || signature == null || iv == null || authTag == null) {
                return Result.failure(new IllegalArgumentException("Missing required envelope field"));
            }

            // Step 3: ECDSA verify signature
            // Signature is computed over: wrappedKey || ciphertext || authTag (concatenated with no delimiters)
            // This ensures integrity of the encrypted message and protects against tampering.
            byte[] dataToVerify = concatenateByteArrays(wrappedKey, ciphertext, authTag);
            Result<Void> verifyResult = cryptoEngine.ecdsaVerify(dataToVerify, signature, senderCert);
            if (verifyResult.isFailure) {
                return Result.failure(verifyResult.exceptionOrNull());
            }

            // Step 4: RSA-OAEP unwrap the AES key
            Result<SecretKey> aesKeyResult = cryptoEngine.rsaOaepUnwrap(wrappedKey, recipientKey);
            if (aesKeyResult.isFailure) {
                return Result.failure(aesKeyResult.exceptionOrNull());
            }
            SecretKey aesKey = aesKeyResult.getOrThrow();

            // Step 5: AES-256-GCM decrypt
            Result<byte[]> decryptedBytesResult = cryptoEngine.aesGcmDecrypt(ciphertext, aesKey, iv, authTag, null);
            if (decryptedBytesResult.isFailure) {
                return Result.failure(decryptedBytesResult.exceptionOrNull());
            }
            byte[] decryptedBytes = decryptedBytesResult.getOrThrow();

            // Step 6: Decode inner Plist
            Result<PlistValue> innerPlistResult = plistCodec.decode(decryptedBytes);
            if (innerPlistResult.isFailure) {
                return Result.failure(innerPlistResult.exceptionOrNull());
            }
            PlistValue innerPlist = innerPlistResult.getOrThrow();

            // Step 7: Convert Plist dict to MessagePayload
            return plistToMessagePayload(innerPlist);
        } catch (Exception e) {
            return Result.failure(e);
        }
    }

    /**
     * Encodes a Plist value to binary Plist bytes.
     */
    @Override
    public Result<byte[]> encodePlist(PlistValue value) {
        return plistCodec.encode(value);
    }

    /**
     * Decodes binary Plist bytes into a PlistValue.
     */
    @Override
    public Result<PlistValue> decodePlist(byte[] bytes) {
        return plistCodec.decode(bytes);
    }

    // ========== Helper Methods ==========

    /**
     * Converts a MessagePayload data class to a Plist dictionary.
     */
    private PlistValue messagePayloadToPlist(MessagePayload payload) {
        List<PlistValue> attachmentsList = new ArrayList<>();
        for (AttachmentInfo attachment : payload.attachments) {
            Map<String, PlistValue> attachDict = new LinkedHashMap<>();
            attachDict.put("id", new PlistString(attachment.id));
            attachDict.put("mimeType", new PlistString(attachment.mimeType));
            attachDict.put("url", new PlistString(attachment.url));
            attachDict.put("size", new PlistInteger(attachment.size));
            attachDict.put("encryptionKey", new PlistData(attachment.encryptionKey));
            attachmentsList.add(new PlistDict(attachDict));
        }

        Map<String, PlistValue> metadataDict = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : payload.metadata.entrySet()) {
            metadataDict.put(entry.getKey(), new PlistString(entry.getValue()));
        }

        List<PlistValue> recipientsList = new ArrayList<>();
        for (String recipient : payload.recipients) {
            recipientsList.add(new PlistString(recipient));
        }

        Map<String, PlistValue> payloadDict = new LinkedHashMap<>();
        payloadDict.put("messageId", new PlistString(payload.messageId));
        payloadDict.put("sender", new PlistString(payload.sender));
        payloadDict.put("recipients", new PlistArray(recipientsList));
        payloadDict.put("timestamp", new PlistInteger(payload.timestamp));
        payloadDict.put("body", new PlistString(payload.body));
        payloadDict.put("metadata", new PlistDict(metadataDict));
        payloadDict.put("attachments", new PlistArray(attachmentsList));

        return new PlistDict(payloadDict);
    }

    /**
     * Converts a Plist dictionary to a MessagePayload data class.
     */
    private Result<MessagePayload> plistToMessagePayload(PlistValue plist) {
        try {
            if (!(plist instanceof PlistDict)) {
                return Result.failure(new IllegalArgumentException("Payload must be a Plist dict"));
            }
            PlistDict dict = (PlistDict) plist;

            String messageId = extractString(dict, "messageId");
            String sender = extractString(dict, "sender");

            if (messageId == null || sender == null) {
                return Result.failure(new IllegalArgumentException("Missing required payload fields"));
            }

            List<String> recipients = new ArrayList<>();
            PlistValue recipientsValue = dict.items.get("recipients");
            if (recipientsValue instanceof PlistArray) {
                for (PlistValue item : ((PlistArray) recipientsValue).items) {
                    if (item instanceof PlistString) {
                        recipients.add(((PlistString) item).value);
                    }
                }
            }

            long timestamp = extractLong(dict, "timestamp");
            String body = extractString(dict, "body");

            if (body == null) {
                return Result.failure(new IllegalArgumentException("Missing body"));
            }

            Map<String, String> metadata = new HashMap<>();
            PlistValue metadataValue = dict.items.get("metadata");
            if (metadataValue instanceof PlistDict) {
                for (Map.Entry<String, PlistValue> entry : ((PlistDict) metadataValue).items.entrySet()) {
                    if (entry.getValue() instanceof PlistString) {
                        metadata.put(entry.getKey(), ((PlistString) entry.getValue()).value);
                    }
                }
            }

            List<AttachmentInfo> attachments = new ArrayList<>();
            PlistValue attachmentsValue = dict.items.get("attachments");
            if (attachmentsValue instanceof PlistArray) {
                for (PlistValue item : ((PlistArray) attachmentsValue).items) {
                    if (item instanceof PlistDict) {
                        PlistDict attachDict = (PlistDict) item;
                        String attId = extractString(attachDict, "id");
                        String mimeType = extractString(attachDict, "mimeType");
                        String url = extractString(attachDict, "url");
                        long size = extractLong(attachDict, "size");
                        byte[] encKey = extractData(attachDict, "encryptionKey");

                        if (attId != null && mimeType != null && url != null && encKey != null) {
                            attachments.add(new AttachmentInfo(
                                    attId, mimeType, url, size, encKey
                            ));
                        }
                    }
                }
            }

            MessagePayload payload = new MessagePayload(
                    messageId, sender, recipients, timestamp, body, metadata, attachments
            );
            return Result.success(payload);
        } catch (Exception e) {
            return Result.failure(e);
        }
    }

    /**
     * Extracts a string value from a Plist dictionary.
     */
    private static String extractString(PlistDict dict, String key) {
        PlistValue value = dict.items.get(key);
        if (value instanceof PlistString) {
            return ((PlistString) value).value;
        }
        return null;
    }

    /**
     * Extracts a long value from a Plist dictionary.
     */
    private static long extractLong(PlistDict dict, String key) {
        PlistValue value = dict.items.get(key);
        if (value instanceof PlistInteger) {
            return ((PlistInteger) value).value;
        }
        return 0L;
    }

    /**
     * Extracts binary data from a Plist dictionary.
     */
    private static byte[] extractData(PlistDict dict, String key) {
        PlistValue value = dict.items.get(key);
        if (value instanceof PlistData) {
            return ((PlistData) value).value;
        }
        return null;
    }

    /**
     * Concatenates multiple byte arrays.
     */
    private static byte[] concatenateByteArrays(byte[]... arrays) {
        int totalLength = 0;
        for (byte[] array : arrays) {
            totalLength += array.length;
        }
        byte[] result = new byte[totalLength];
        int offset = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, result, offset, array.length);
            offset += array.length;
        }
        return result;
    }
}
