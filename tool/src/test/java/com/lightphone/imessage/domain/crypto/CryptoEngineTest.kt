package com.lightphone.imessage.domain.crypto

import java.math.BigInteger
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.util.Date
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.*
import org.junit.Test

/**
 * Comprehensive unit tests for CryptoEngine. Covers AES-256-GCM encryption/decryption,
 * RSA-2048-OAEP key wrapping, and ECDSA P-256 signing/verification. Target: 100% code coverage.
 */
class CryptoEngineTest {
    private val cryptoEngine = CryptoEngine()

    // ========== AES-256-GCM Key Generation ==========

    @Test
    fun testGenerateAesKey() {
        val key = cryptoEngine.generateAesKey()
        assertNotNull("Key must not be null", key)
        assertEquals("Key algorithm must be AES", "AES", key.algorithm)
        assertEquals("Key size must be 256 bits (32 bytes)", 256, key.encoded.size * 8)
    }

    @Test
    fun testGenerateAesKeyUniqueness() {
        val key1 = cryptoEngine.generateAesKey()
        val key2 = cryptoEngine.generateAesKey()
        assertFalse("Generated keys must be unique", key1.encoded.contentEquals(key2.encoded))
    }

    // ========== AES-256-GCM Encryption/Decryption ==========

    @Test
    fun testAesGcmEncryptDecryptSimple() {
        val key = cryptoEngine.generateAesKey()
        val plaintext = "Hello, World!".toByteArray(Charsets.UTF_8)

        val encryptResult = cryptoEngine.aesGcmEncrypt(plaintext, key, null).getOrThrow()
        assertNotNull("Encryption result must not be null", encryptResult)
        assertEquals("IV must be 12 bytes", 12, encryptResult.iv.size)
        assertEquals("Auth tag must be 16 bytes", 16, encryptResult.authTag.size)

        val decryptResult =
                cryptoEngine.aesGcmDecrypt(
                        encryptResult.ciphertext,
                        key,
                        encryptResult.iv,
                        encryptResult.authTag,
                        null,
                )
        assertTrue("Decryption must succeed", decryptResult.isSuccess)
        assertArrayEquals(
                "Decrypted plaintext must match original",
                plaintext,
                decryptResult.getOrNull(),
        )
    }

    @Test
    fun testAesGcmEncryptDecryptRandomPlaintext() {
        val key = cryptoEngine.generateAesKey()
        val plaintext = ByteArray(256) { it.toByte() }

        val encryptResult = cryptoEngine.aesGcmEncrypt(plaintext, key, null).getOrThrow()
        val decryptResult =
                cryptoEngine.aesGcmDecrypt(
                        encryptResult.ciphertext,
                        key,
                        encryptResult.iv,
                        encryptResult.authTag,
                        null,
                )

        assertTrue("Decryption must succeed", decryptResult.isSuccess)
        assertArrayEquals(
                "Decrypted plaintext must match original",
                plaintext,
                decryptResult.getOrNull(),
        )
    }

    @Test
    fun testAesGcmEncryptEmptyPlaintext() {
        val key = cryptoEngine.generateAesKey()
        val plaintext = ByteArray(0)

        val encryptResult = cryptoEngine.aesGcmEncrypt(plaintext, key, null).getOrThrow()
        val decryptResult =
                cryptoEngine.aesGcmDecrypt(
                        encryptResult.ciphertext,
                        key,
                        encryptResult.iv,
                        encryptResult.authTag,
                        null,
                )

        assertTrue("Decryption of empty plaintext must succeed", decryptResult.isSuccess)
        assertEquals("Decrypted empty plaintext must match", 0, decryptResult.getOrNull()?.size)
    }

    @Test
    fun testAesGcmEncryptLargePlaintext() {
        val key = cryptoEngine.generateAesKey()
        val plaintext = ByteArray(1024 * 100) { (it % 256).toByte() } // 100 KB

        val encryptResult = cryptoEngine.aesGcmEncrypt(plaintext, key, null).getOrThrow()
        val decryptResult =
                cryptoEngine.aesGcmDecrypt(
                        encryptResult.ciphertext,
                        key,
                        encryptResult.iv,
                        encryptResult.authTag,
                        null,
                )

        assertTrue("Decryption of large plaintext must succeed", decryptResult.isSuccess)
        assertArrayEquals(
                "Decrypted large plaintext must match original",
                plaintext,
                decryptResult.getOrNull(),
        )
    }

    @Test
    fun testAesGcmEncryptWithAAD() {
        val key = cryptoEngine.generateAesKey()
        val plaintext = "Secret message".toByteArray(Charsets.UTF_8)
        val aad = "Additional authenticated data".toByteArray(Charsets.UTF_8)

        val encryptResult = cryptoEngine.aesGcmEncrypt(plaintext, key, aad).getOrThrow()
        val decryptResult =
                cryptoEngine.aesGcmDecrypt(
                        encryptResult.ciphertext,
                        key,
                        encryptResult.iv,
                        encryptResult.authTag,
                        aad,
                )

        assertTrue("Decryption with matching AAD must succeed", decryptResult.isSuccess)
        assertArrayEquals(
                "Decrypted plaintext must match original",
                plaintext,
                decryptResult.getOrNull(),
        )
    }

    // ========== AES-GCM Authentication Tag Validation ==========

    @Test
    fun testAesGcmAuthTagValidation_CorruptedTag() {
        val key = cryptoEngine.generateAesKey()
        val plaintext = "Secret".toByteArray(Charsets.UTF_8)

        val encryptResult = cryptoEngine.aesGcmEncrypt(plaintext, key, null).getOrThrow()

        // Corrupt the auth tag
        val corruptedTag = encryptResult.authTag.copyOf()
        corruptedTag[0] = (corruptedTag[0].toInt() xor 0xFF).toByte()

        val decryptResult =
                cryptoEngine.aesGcmDecrypt(
                        encryptResult.ciphertext,
                        key,
                        encryptResult.iv,
                        corruptedTag,
                        null,
                )

        assertTrue("Decryption with corrupted tag must fail", decryptResult.isFailure)
    }

    @Test
    fun testAesGcmAuthTagValidation_WrongAAD() {
        val key = cryptoEngine.generateAesKey()
        val plaintext = "Secret".toByteArray(Charsets.UTF_8)
        val correctAAD = "Correct AAD".toByteArray(Charsets.UTF_8)
        val wrongAAD = "Wrong AAD".toByteArray(Charsets.UTF_8)

        val encryptResult = cryptoEngine.aesGcmEncrypt(plaintext, key, correctAAD).getOrThrow()
        val decryptResult =
                cryptoEngine.aesGcmDecrypt(
                        encryptResult.ciphertext,
                        key,
                        encryptResult.iv,
                        encryptResult.authTag,
                        wrongAAD,
                )

        assertTrue("Decryption with wrong AAD must fail", decryptResult.isFailure)
    }

    @Test
    fun testAesGcmAuthTagValidation_CorruptedCiphertext() {
        val key = cryptoEngine.generateAesKey()
        val plaintext = "Secret".toByteArray(Charsets.UTF_8)

        val encryptResult = cryptoEngine.aesGcmEncrypt(plaintext, key, null).getOrThrow()

        // Corrupt the ciphertext
        val corruptedCiphertext = encryptResult.ciphertext.copyOf()
        if (corruptedCiphertext.isNotEmpty()) {
            corruptedCiphertext[0] = (corruptedCiphertext[0].toInt() xor 0xFF).toByte()
        }

        val decryptResult =
                cryptoEngine.aesGcmDecrypt(
                        corruptedCiphertext,
                        key,
                        encryptResult.iv,
                        encryptResult.authTag,
                        null,
                )

        assertTrue("Decryption with corrupted ciphertext must fail", decryptResult.isFailure)
    }

    @Test
    fun testAesGcmAuthTagValidation_CorruptedIV() {
        val key = cryptoEngine.generateAesKey()
        val plaintext = "Secret".toByteArray(Charsets.UTF_8)

        val encryptResult = cryptoEngine.aesGcmEncrypt(plaintext, key, null).getOrThrow()

        // Corrupt the IV
        val corruptedIV = encryptResult.iv.copyOf()
        corruptedIV[0] = (corruptedIV[0].toInt() xor 0xFF).toByte()

        val decryptResult =
                cryptoEngine.aesGcmDecrypt(
                        encryptResult.ciphertext,
                        key,
                        corruptedIV,
                        encryptResult.authTag,
                        null,
                )

        assertTrue("Decryption with corrupted IV must fail", decryptResult.isFailure)
    }

    // ========== RSA-2048-OAEP Key Wrapping ==========

    @Test
    fun testRsaOaepWrapUnwrapSimple() {
        val aesKey = cryptoEngine.generateAesKey()
        val (publicKey, privateKey) = cryptoEngine.generateRsaKeyPair()

        val wrapResult = cryptoEngine.rsaOaepWrap(aesKey, publicKey)
        assertTrue("Wrapping must succeed", wrapResult.isSuccess)

        val wrappedKey = wrapResult.getOrNull()
        assertNotNull("Wrapped key must not be null", wrappedKey)
        assertFalse("Wrapped key must not be empty", wrappedKey?.isEmpty() ?: true)

        val unwrapResult = cryptoEngine.rsaOaepUnwrap(wrappedKey!!, privateKey)
        assertTrue("Unwrapping must succeed", unwrapResult.isSuccess)

        val unwrappedKey = unwrapResult.getOrNull()
        assertNotNull("Unwrapped key must not be null", unwrappedKey)
        assertArrayEquals(
                "Unwrapped key must match original",
                aesKey.encoded,
                unwrappedKey?.encoded,
        )
    }

    @Test
    fun testRsaOaepWrapUnwrapMultipleKeys() {
        val (publicKey, privateKey) = cryptoEngine.generateRsaKeyPair()

        val key1 = cryptoEngine.generateAesKey()
        val key2 = cryptoEngine.generateAesKey()

        val wrap1 = cryptoEngine.rsaOaepWrap(key1, publicKey)
        val wrap2 = cryptoEngine.rsaOaepWrap(key2, publicKey)

        assertTrue("First wrapping must succeed", wrap1.isSuccess)
        assertTrue("Second wrapping must succeed", wrap2.isSuccess)

        val unwrap1 = cryptoEngine.rsaOaepUnwrap(wrap1.getOrThrow(), privateKey)
        val unwrap2 = cryptoEngine.rsaOaepUnwrap(wrap2.getOrThrow(), privateKey)

        assertTrue("First unwrapping must succeed", unwrap1.isSuccess)
        assertTrue("Second unwrapping must succeed", unwrap2.isSuccess)

        assertArrayEquals("First key must match", key1.encoded, unwrap1.getOrNull()?.encoded)
        assertArrayEquals("Second key must match", key2.encoded, unwrap2.getOrNull()?.encoded)
    }

    @Test
    fun testRsaOaepWrapUnwrapWrongPrivateKey() {
        val aesKey = cryptoEngine.generateAesKey()
        val (publicKey1, _) = cryptoEngine.generateRsaKeyPair()
        val (_, wrongPrivateKey) = cryptoEngine.generateRsaKeyPair()

        val wrappedKey = cryptoEngine.rsaOaepWrap(aesKey, publicKey1).getOrThrow()
        val unwrapResult = cryptoEngine.rsaOaepUnwrap(wrappedKey, wrongPrivateKey)

        assertTrue("Unwrapping with wrong private key must fail", unwrapResult.isFailure)
    }

    @Test
    fun testRsaOaepWrapUnwrapCorruptedWrappedKey() {
        val aesKey = cryptoEngine.generateAesKey()
        val (publicKey, privateKey) = cryptoEngine.generateRsaKeyPair()

        val wrappedKey = cryptoEngine.rsaOaepWrap(aesKey, publicKey).getOrThrow()

        // Corrupt the wrapped key
        val corruptedWrappedKey = wrappedKey.copyOf()
        if (corruptedWrappedKey.isNotEmpty()) {
            corruptedWrappedKey[0] = (corruptedWrappedKey[0].toInt() xor 0xFF).toByte()
        }

        val unwrapResult = cryptoEngine.rsaOaepUnwrap(corruptedWrappedKey, privateKey)
        assertTrue("Unwrapping corrupted key must fail", unwrapResult.isFailure)
    }

    // ========== ECDSA P-256 Signing/Verification ==========

    @Test
    fun testEcdsaSignVerifySimple() {
        val data = "Hello, ECDSA!".toByteArray(Charsets.UTF_8)
        val (publicKey, privateKey) = cryptoEngine.generateEcdsaKeyPair()

        val signResult = cryptoEngine.ecdsaSign(data, privateKey)
        assertTrue("Signing must succeed", signResult.isSuccess)

        val signature = signResult.getOrNull()
        assertNotNull("Signature must not be null", signature)
        assertFalse("Signature must not be empty", signature?.isEmpty() ?: true)

        // Create a self-signed certificate for verification
        val cert = createSelfSignedEcdsaCertificate(publicKey)

        val verifyResult = cryptoEngine.ecdsaVerify(data, signature!!, cert)
        assertTrue("Verification must succeed", verifyResult.isSuccess)
    }

    @Test
    fun testEcdsaSignVerifyRandomData() {
        val data = ByteArray(256) { it.toByte() }
        val (publicKey, privateKey) = cryptoEngine.generateEcdsaKeyPair()

        val signature = cryptoEngine.ecdsaSign(data, privateKey).getOrThrow()
        val cert = createSelfSignedEcdsaCertificate(publicKey)
        val verifyResult = cryptoEngine.ecdsaVerify(data, signature, cert)

        assertTrue("Verification of random data must succeed", verifyResult.isSuccess)
    }

    @Test
    fun testEcdsaSignVerifyEmptyData() {
        val data = ByteArray(0)
        val (publicKey, privateKey) = cryptoEngine.generateEcdsaKeyPair()

        val signature = cryptoEngine.ecdsaSign(data, privateKey).getOrThrow()
        val cert = createSelfSignedEcdsaCertificate(publicKey)
        val verifyResult = cryptoEngine.ecdsaVerify(data, signature, cert)

        assertTrue("Verification of empty data must succeed", verifyResult.isSuccess)
    }

    @Test
    fun testEcdsaSignVerifyLargeData() {
        val data = ByteArray(1024 * 100) { (it % 256).toByte() } // 100 KB
        val (publicKey, privateKey) = cryptoEngine.generateEcdsaKeyPair()

        val signature = cryptoEngine.ecdsaSign(data, privateKey).getOrThrow()
        val cert = createSelfSignedEcdsaCertificate(publicKey)
        val verifyResult = cryptoEngine.ecdsaVerify(data, signature, cert)

        assertTrue("Verification of large data must succeed", verifyResult.isSuccess)
    }

    @Test
    fun testEcdsaSignVerifyInvalidSignature() {
        val data = "Original data".toByteArray(Charsets.UTF_8)
        val (publicKey, privateKey) = cryptoEngine.generateEcdsaKeyPair()

        val signature = cryptoEngine.ecdsaSign(data, privateKey).getOrThrow()

        // Corrupt the signature
        val corruptedSignature = signature.copyOf()
        if (corruptedSignature.isNotEmpty()) {
            corruptedSignature[0] = (corruptedSignature[0].toInt() xor 0xFF).toByte()
        }

        val cert = createSelfSignedEcdsaCertificate(publicKey)
        val verifyResult = cryptoEngine.ecdsaVerify(data, corruptedSignature, cert)

        assertTrue("Verification of corrupted signature must fail", verifyResult.isFailure)
    }

    @Test
    fun testEcdsaSignVerifyDataTampered() {
        val originalData = "Original data".toByteArray(Charsets.UTF_8)
        val tamperedData = "Tampered data".toByteArray(Charsets.UTF_8)
        val (publicKey, privateKey) = cryptoEngine.generateEcdsaKeyPair()

        val signature = cryptoEngine.ecdsaSign(originalData, privateKey).getOrThrow()
        val cert = createSelfSignedEcdsaCertificate(publicKey)
        val verifyResult = cryptoEngine.ecdsaVerify(tamperedData, signature, cert)

        assertTrue("Verification with tampered data must fail", verifyResult.isFailure)
    }

    @Test
    fun testEcdsaVerifyWithWrongKey() {
        val data = "Test data".toByteArray(Charsets.UTF_8)
        val (publicKey1, privateKey1) = cryptoEngine.generateEcdsaKeyPair()
        val (publicKey2, _) = cryptoEngine.generateEcdsaKeyPair()

        val signature = cryptoEngine.ecdsaSign(data, privateKey1).getOrThrow()

        val cert = createSelfSignedEcdsaCertificate(publicKey2)
        val verifyResult = cryptoEngine.ecdsaVerify(data, signature, cert)

        assertTrue("Verification with wrong public key must fail", verifyResult.isFailure)
    }

    @Test
    fun testEcdsaSignVerifyMultipleSignatures() {
        val data = "Data to sign".toByteArray(Charsets.UTF_8)
        val (publicKey, privateKey) = cryptoEngine.generateEcdsaKeyPair()

        val signature1 = cryptoEngine.ecdsaSign(data, privateKey).getOrThrow()
        val signature2 = cryptoEngine.ecdsaSign(data, privateKey).getOrThrow()

        // ECDSA signatures are non-deterministic (random nonce), so signatures should differ
        assertFalse(
                "Multiple signatures of same data should differ (ECDSA uses random nonce)",
                signature1.contentEquals(signature2),
        )

        val cert = createSelfSignedEcdsaCertificate(publicKey)

        val verify1 = cryptoEngine.ecdsaVerify(data, signature1, cert)
        val verify2 = cryptoEngine.ecdsaVerify(data, signature2, cert)

        assertTrue("First signature must verify", verify1.isSuccess)
        assertTrue("Second signature must verify", verify2.isSuccess)
    }

    // ========== Helper Functions ==========

    /**
     * Creates a self-signed X509 certificate wrapping [publicKey] for testing ECDSA verify.
     *
     * Only the public key inside the cert is inspected by `CryptoEngine.ecdsaVerify`; the cert's
     * own signature is not validated. We generate a fresh signing keypair so the cert is well-
     * formed but the signature itself is not asserted to chain back to [publicKey].
     *
     * Uses BouncyCastle (testImpl only, not a production dep) because the previous impl relied on
     * `sun.security.x509.*` internals that are not on the compile classpath.
     */
    private fun createSelfSignedEcdsaCertificate(publicKey: PublicKey): X509Certificate {
        val (_, signingPrivateKey) = cryptoEngine.generateEcdsaKeyPair()

        val notBefore = Date()
        val notAfter = Date(notBefore.time + 365L * 24 * 60 * 60 * 1000)
        val name = X500Name("CN=test,O=test,C=US")
        val serial = BigInteger.valueOf(System.currentTimeMillis())

        val builder =
                JcaX509v3CertificateBuilder(name, serial, notBefore, notAfter, name, publicKey)
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(signingPrivateKey)
        val holder = builder.build(signer)
        return JcaX509CertificateConverter().getCertificate(holder)
    }
}
