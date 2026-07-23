package com.lightphone.imessage.domain.crypto

import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
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

        val encryptResult = cryptoEngine.aesGcmEncrypt(plaintext, key, null)
        assertNotNull("Encryption result must not be null", encryptResult)
        assertEquals("IV must be 12 bytes", 12, encryptResult.iv.size)
        assertEquals("Auth tag must be 16 bytes", 16, encryptResult.authTag.size)

        val decryptResult =
                cryptoEngine.aesGcmDecrypt(
                        encryptResult.ciphertext,
                        key,
                        encryptResult.iv,
                        encryptResult.authTag,
                        null
                )
        assertTrue("Decryption must succeed", decryptResult.isSuccess)
        assertArrayEquals(
                "Decrypted plaintext must match original",
                plaintext,
                decryptResult.getOrNull()
        )
    }

    @Test
    fun testAesGcmEncryptDecryptRandomPlaintext() {
        val key = cryptoEngine.generateAesKey()
        val plaintext = ByteArray(256) { it.toByte() }

        val encryptResult = cryptoEngine.aesGcmEncrypt(plaintext, key, null)
        val decryptResult =
                cryptoEngine.aesGcmDecrypt(
                        encryptResult.ciphertext,
                        key,
                        encryptResult.iv,
                        encryptResult.authTag,
                        null
                )

        assertTrue("Decryption must succeed", decryptResult.isSuccess)
        assertArrayEquals(
                "Decrypted plaintext must match original",
                plaintext,
                decryptResult.getOrNull()
        )
    }

    @Test
    fun testAesGcmEncryptEmptyPlaintext() {
        val key = cryptoEngine.generateAesKey()
        val plaintext = ByteArray(0)

        val encryptResult = cryptoEngine.aesGcmEncrypt(plaintext, key, null)
        val decryptResult =
                cryptoEngine.aesGcmDecrypt(
                        encryptResult.ciphertext,
                        key,
                        encryptResult.iv,
                        encryptResult.authTag,
                        null
                )

        assertTrue("Decryption of empty plaintext must succeed", decryptResult.isSuccess)
        assertEquals("Decrypted empty plaintext must match", 0, decryptResult.getOrNull()?.size)
    }

    @Test
    fun testAesGcmEncryptLargePlaintext() {
        val key = cryptoEngine.generateAesKey()
        val plaintext = ByteArray(1024 * 100) { (it % 256).toByte() } // 100 KB

        val encryptResult = cryptoEngine.aesGcmEncrypt(plaintext, key, null)
        val decryptResult =
                cryptoEngine.aesGcmDecrypt(
                        encryptResult.ciphertext,
                        key,
                        encryptResult.iv,
                        encryptResult.authTag,
                        null
                )

        assertTrue("Decryption of large plaintext must succeed", decryptResult.isSuccess)
        assertArrayEquals(
                "Decrypted large plaintext must match original",
                plaintext,
                decryptResult.getOrNull()
        )
    }

    @Test
    fun testAesGcmEncryptWithAAD() {
        val key = cryptoEngine.generateAesKey()
        val plaintext = "Secret message".toByteArray(Charsets.UTF_8)
        val aad = "Additional authenticated data".toByteArray(Charsets.UTF_8)

        val encryptResult = cryptoEngine.aesGcmEncrypt(plaintext, key, aad)
        val decryptResult =
                cryptoEngine.aesGcmDecrypt(
                        encryptResult.ciphertext,
                        key,
                        encryptResult.iv,
                        encryptResult.authTag,
                        aad
                )

        assertTrue("Decryption with matching AAD must succeed", decryptResult.isSuccess)
        assertArrayEquals(
                "Decrypted plaintext must match original",
                plaintext,
                decryptResult.getOrNull()
        )
    }

    // ========== AES-GCM Authentication Tag Validation ==========

    @Test
    fun testAesGcmAuthTagValidation_CorruptedTag() {
        val key = cryptoEngine.generateAesKey()
        val plaintext = "Secret".toByteArray(Charsets.UTF_8)

        val encryptResult = cryptoEngine.aesGcmEncrypt(plaintext, key, null)

        // Corrupt the auth tag
        val corruptedTag = encryptResult.authTag.copyOf()
        corruptedTag[0] = (corruptedTag[0].toInt() xor 0xFF).toByte()

        val decryptResult =
                cryptoEngine.aesGcmDecrypt(
                        encryptResult.ciphertext,
                        key,
                        encryptResult.iv,
                        corruptedTag,
                        null
                )

        assertTrue("Decryption with corrupted tag must fail", decryptResult.isFailure)
    }

    @Test
    fun testAesGcmAuthTagValidation_WrongAAD() {
        val key = cryptoEngine.generateAesKey()
        val plaintext = "Secret".toByteArray(Charsets.UTF_8)
        val correctAAD = "Correct AAD".toByteArray(Charsets.UTF_8)
        val wrongAAD = "Wrong AAD".toByteArray(Charsets.UTF_8)

        val encryptResult = cryptoEngine.aesGcmEncrypt(plaintext, key, correctAAD)
        val decryptResult =
                cryptoEngine.aesGcmDecrypt(
                        encryptResult.ciphertext,
                        key,
                        encryptResult.iv,
                        encryptResult.authTag,
                        wrongAAD
                )

        assertTrue("Decryption with wrong AAD must fail", decryptResult.isFailure)
    }

    @Test
    fun testAesGcmAuthTagValidation_CorruptedCiphertext() {
        val key = cryptoEngine.generateAesKey()
        val plaintext = "Secret".toByteArray(Charsets.UTF_8)

        val encryptResult = cryptoEngine.aesGcmEncrypt(plaintext, key, null)

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
                        null
                )

        assertTrue("Decryption with corrupted ciphertext must fail", decryptResult.isFailure)
    }

    @Test
    fun testAesGcmAuthTagValidation_CorruptedIV() {
        val key = cryptoEngine.generateAesKey()
        val plaintext = "Secret".toByteArray(Charsets.UTF_8)

        val encryptResult = cryptoEngine.aesGcmEncrypt(plaintext, key, null)

        // Corrupt the IV
        val corruptedIV = encryptResult.iv.copyOf()
        corruptedIV[0] = (corruptedIV[0].toInt() xor 0xFF).toByte()

        val decryptResult =
                cryptoEngine.aesGcmDecrypt(
                        encryptResult.ciphertext,
                        key,
                        corruptedIV,
                        encryptResult.authTag,
                        null
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
                unwrappedKey?.encoded
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
                signature1.contentEquals(signature2)
        )

        val cert = createSelfSignedEcdsaCertificate(publicKey)

        val verify1 = cryptoEngine.ecdsaVerify(data, signature1, cert)
        val verify2 = cryptoEngine.ecdsaVerify(data, signature2, cert)

        assertTrue("First signature must verify", verify1.isSuccess)
        assertTrue("Second signature must verify", verify2.isSuccess)
    }

    // ========== Helper Functions ==========

    /**
     * Creates a self-signed X509 certificate for testing ECDSA verification. Note: This is a
     * simplified approach for testing; production code should use proper certificate management.
     */
    private fun createSelfSignedEcdsaCertificate(
            publicKey: java.security.PublicKey
    ): X509Certificate {
        try {
            // For testing, we'll use a mock certificate approach
            // In production, this would be a real X509 certificate
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)

            // Create a simple test certificate using reflection
            // This is a simplified test helper
            val certificateFactory = CertificateFactory.getInstance("X.509")

            // Generate a test certificate using internal Java APIs
            // For real testing, you'd load from a proper test certificate
            return generateTestCertificate(publicKey)
        } catch (e: Exception) {
            throw RuntimeException("Failed to create test certificate: ${e.message}", e)
        }
    }

    /**
     * Generates a simple test certificate with the provided public key. This uses sun.security
     * classes which are available in JVM.
     */
    private fun generateTestCertificate(publicKey: java.security.PublicKey): X509Certificate {
        try {
            val (_, privateKey) = cryptoEngine.generateEcdsaKeyPair()

            // Create certificate builder
            val certBuilder = sun.security.x509.X509CertInfo()

            // Set version
            certBuilder.set(
                    sun.security.x509.X509CertInfo.VERSION,
                    sun.security.x509.CertificateVersion(sun.security.x509.CertificateVersion.V3)
            )

            // Set serial number
            certBuilder.set(
                    sun.security.x509.X509CertInfo.SERIAL_NUMBER,
                    sun.security.x509.CertificateSerialNumber(
                            (System.currentTimeMillis() / 1000).toInt()
                    )
            )

            // Set algorithm ID
            val algorithm =
                    sun.security.x509.AlgorithmId(
                            sun.security.util.ObjectIdentifier("1.2.840.10045.4.3.2")
                    )
            certBuilder.set(
                    sun.security.x509.X509CertInfo.ALGORITHM_ID,
                    sun.security.x509.CertificateAlgorithmId(algorithm)
            )

            // Set issuer (self-signed)
            val issuer = sun.security.x509.X500Name("CN=test,O=test,C=US")
            certBuilder.set(sun.security.x509.X509CertInfo.ISSUER, issuer)

            // Set subject (self-signed)
            certBuilder.set(sun.security.x509.X509CertInfo.SUBJECT, issuer)

            // Set public key
            certBuilder.set(
                    sun.security.x509.X509CertInfo.KEY,
                    sun.security.x509.CertificateX509Key(publicKey)
            )

            // Set validity
            val now = Date()
            val validity =
                    sun.security.x509.CertificateValidity(
                            now,
                            Date(now.time + 365 * 24 * 60 * 60 * 1000)
                    )
            certBuilder.set(sun.security.x509.X509CertInfo.VALIDITY, validity)

            // Create certificate
            val cert = sun.security.x509.X509CertImpl(certBuilder)
            cert.sign(privateKey, "SHA256withECDSA")

            return cert
        } catch (e: Exception) {
            throw RuntimeException("Failed to generate test certificate: ${e.message}", e)
        }
    }
}
