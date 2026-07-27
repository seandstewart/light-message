package com.lightphone.imessage.domain.crypto

import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.MGF1ParameterSpec
import java.security.spec.RSAKeyGenParameterSpec
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * Result class for AES-GCM encryption. Contains IV (12 bytes), ciphertext, and auth tag (16 bytes).
 */
data class AesGcmResult(val iv: ByteArray, val ciphertext: ByteArray, val authTag: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AesGcmResult

        if (!iv.contentEquals(other.iv)) return false
        if (!ciphertext.contentEquals(other.ciphertext)) return false
        if (!authTag.contentEquals(other.authTag)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = iv.contentHashCode()
        result = 31 * result + ciphertext.contentHashCode()
        result = 31 * result + authTag.contentHashCode()
        return result
    }
}

/**
 * Cryptographic engine for AES-256-GCM, RSA-2048-OAEP, and ECDSA P-256 operations. Uses only
 * javax.crypto and java.security (no BouncyCastle or SpongyCastle).
 *
 * Provider pinning: all `Cipher` and `Signature` instances are constructed via the [cipher] and
 * [signature] helpers, which honor an optional [CRYPTO_PROVIDER] name. Today the field is `null`
 * (JCA default provider), but this indirection sets up the story for pinning to Conscrypt on
 * Android and SunJCE on JVM tests without touching call sites.
 */
class CryptoEngine {
    private val secureRandom = SecureRandom()

    /**
     * Generates a 256-bit AES key.
     *
     * @return SecretKey suitable for AES-256 operations
     */
    fun generateAesKey(): SecretKey {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256, secureRandom)
        return keyGen.generateKey()
    }

    /**
     * Encrypts plaintext using AES-256-GCM. Generates a random 12-byte IV and returns result with
     * IV, ciphertext, and 16-byte auth tag.
     *
     * @param plaintext Data to encrypt
     * @param key AES-256 SecretKey
     * @param aad Optional additional authenticated data
     * @return `Result<AesGcmResult>`; failure captures `GeneralSecurityException` or subclasses
     */
    fun aesGcmEncrypt(
            plaintext: ByteArray,
            key: SecretKey,
            aad: ByteArray?,
    ): Result<AesGcmResult> = runCatching {
        val c = cipher("AES/GCM/NoPadding")

        // Generate 12-byte IV
        val iv = ByteArray(12)
        secureRandom.nextBytes(iv)

        // Initialize with IV and 128-bit (16-byte) auth tag length
        val gcmSpec = GCMParameterSpec(GCM_TAG_BITS, iv)
        c.init(Cipher.ENCRYPT_MODE, key, gcmSpec)

        // Add AAD if provided
        aad?.let { c.updateAAD(it) }

        // Encrypt plaintext
        val ciphertext = c.doFinal(plaintext)

        // Extract ciphertext and auth tag
        // GCM includes auth tag at the end of ciphertext
        val authTagLength = GCM_TAG_BITS / 8
        val actualCiphertext = ciphertext.copyOfRange(0, ciphertext.size - authTagLength)
        val authTag = ciphertext.copyOfRange(ciphertext.size - authTagLength, ciphertext.size)

        AesGcmResult(iv, actualCiphertext, authTag)
    }

    /**
     * Decrypts AES-256-GCM encrypted data.
     *
     * @param ciphertext Encrypted data (without IV and tag)
     * @param key AES-256 SecretKey (must match encryption key)
     * @param iv 12-byte initialization vector
     * @param tag 16-byte authentication tag
     * @param aad Optional additional authenticated data (must match encryption AAD)
     * @return Result containing decrypted plaintext or failure. Callers can distinguish
     * authentication failure by matching on `AEADBadTagException`.
     */
    fun aesGcmDecrypt(
            ciphertext: ByteArray,
            key: SecretKey,
            iv: ByteArray,
            tag: ByteArray,
            aad: ByteArray?,
    ): Result<ByteArray> =
            try {
                val c = cipher("AES/GCM/NoPadding")

                // Initialize with IV and 128-bit auth tag length
                val gcmSpec = GCMParameterSpec(GCM_TAG_BITS, iv)
                c.init(Cipher.DECRYPT_MODE, key, gcmSpec)

                // Add AAD if provided
                aad?.let { c.updateAAD(it) }

                // Concatenate ciphertext and tag for decryption (single allocation via ByteBuffer)
                val encryptedData =
                        ByteBuffer.allocate(ciphertext.size + tag.size)
                                .put(ciphertext)
                                .put(tag)
                                .array()
                val plaintext = c.doFinal(encryptedData)

                Result.success(plaintext)
            } catch (e: AEADBadTagException) {
                // Preserved as-is so callers can distinguish auth failure from other errors.
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(e)
            }

    /**
     * Generates an RSA-2048 key pair.
     *
     * @return Pair of (PublicKey, PrivateKey)
     */
    fun generateRsaKeyPair(): Pair<PublicKey, PrivateKey> {
        val keyPairGen = KeyPairGenerator.getInstance("RSA")
        val spec = RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4)
        keyPairGen.initialize(spec, secureRandom)
        val keyPair = keyPairGen.generateKeyPair()
        return Pair(keyPair.public, keyPair.private)
    }

    /**
     * Generates an ECDSA P-256 key pair.
     *
     * @return Pair of (PublicKey, PrivateKey)
     */
    fun generateEcdsaKeyPair(): Pair<PublicKey, PrivateKey> {
        val keyPairGen = KeyPairGenerator.getInstance("EC")
        val ecSpec = ECGenParameterSpec("secp256r1")
        keyPairGen.initialize(ecSpec, secureRandom)
        val keyPair = keyPairGen.generateKeyPair()
        return Pair(keyPair.public, keyPair.private)
    }

    /**
     * Wraps an AES key using RSA-2048-OAEP-SHA256.
     *
     * Note: SunJCE's `RSA/ECB/OAEPWithSHA-256AndMGF1Padding` transformation defaults its MGF1 hash
     * to SHA-1 despite the name, which breaks interop with providers that follow the spec. We
     * explicitly supply an [OAEPParameterSpec] with MGF1(SHA-256) to force spec-compliant behavior.
     *
     * @param aesKey The AES SecretKey to wrap
     * @param publicKey RSA-2048 PublicKey
     * @return Result containing wrapped key bytes or failure
     */
    fun rsaOaepWrap(
            aesKey: SecretKey,
            publicKey: PublicKey,
    ): Result<ByteArray> =
            try {
                val c = cipher("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
                val oaepSpec =
                        OAEPParameterSpec(
                                "SHA-256",
                                "MGF1",
                                MGF1ParameterSpec.SHA256,
                                PSource.PSpecified.DEFAULT,
                        )
                c.init(Cipher.WRAP_MODE, publicKey, oaepSpec, secureRandom)
                val wrappedKey = c.wrap(aesKey)
                Result.success(wrappedKey)
            } catch (e: Exception) {
                Result.failure(e)
            }

    /**
     * Unwraps an AES key that was wrapped with RSA-2048-OAEP-SHA256.
     *
     * See [rsaOaepWrap] for why an explicit [OAEPParameterSpec] is required.
     *
     * @param wrappedKey The wrapped key bytes from rsaOaepWrap
     * @param privateKey RSA-2048 PrivateKey (must match public key used in wrapping)
     * @return Result containing unwrapped SecretKey or failure
     */
    fun rsaOaepUnwrap(
            wrappedKey: ByteArray,
            privateKey: PrivateKey,
    ): Result<SecretKey> =
            try {
                val c = cipher("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
                val oaepSpec =
                        OAEPParameterSpec(
                                "SHA-256",
                                "MGF1",
                                MGF1ParameterSpec.SHA256,
                                PSource.PSpecified.DEFAULT,
                        )
                c.init(Cipher.UNWRAP_MODE, privateKey, oaepSpec)
                val unwrappedKey = c.unwrap(wrappedKey, "AES", Cipher.SECRET_KEY)
                Result.success(unwrappedKey as SecretKey)
            } catch (e: Exception) {
                Result.failure(e)
            }

    /**
     * Signs data using ECDSA P-256 with SHA-256.
     *
     * Uses provider default non-deterministic k via [SecureRandom]. Callers wanting RFC 6979
     * deterministic k must supply an appropriate [SecureRandom] or use a provider that implements
     * 6979.
     *
     * @param data The data to sign
     * @param privateKey ECDSA P-256 PrivateKey
     * @return Result containing DER-encoded signature or failure
     */
    fun ecdsaSign(
            data: ByteArray,
            privateKey: PrivateKey,
    ): Result<ByteArray> =
            try {
                val sig = signature("SHA256withECDSA")
                sig.initSign(privateKey, secureRandom)
                sig.update(data)
                val signatureBytes = sig.sign()
                Result.success(signatureBytes)
            } catch (e: Exception) {
                Result.failure(e)
            }

    /**
     * Verifies an ECDSA P-256 signature with SHA-256.
     *
     * @param data The original data that was signed
     * @param signature DER-encoded signature bytes
     * @param certificate X509Certificate containing the public key
     * @return Result.success(Unit) if valid, Result.failure if invalid or error
     */
    fun ecdsaVerify(
            data: ByteArray,
            signature: ByteArray,
            certificate: X509Certificate,
    ): Result<Unit> =
            try {
                val publicKey = certificate.publicKey
                val sig = this.signature("SHA256withECDSA")
                sig.initVerify(publicKey)
                sig.update(data)
                val isValid = sig.verify(signature)
                if (isValid) {
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("Signature verification failed"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }

    private fun cipher(alg: String): Cipher =
            CRYPTO_PROVIDER?.let { Cipher.getInstance(alg, it) } ?: Cipher.getInstance(alg)

    private fun signature(alg: String): Signature =
            CRYPTO_PROVIDER?.let { Signature.getInstance(alg, it) } ?: Signature.getInstance(alg)

    companion object {
        private const val GCM_TAG_BITS = 128

        // TODO: pin to Conscrypt on Android, SunJCE on JVM tests
        private val CRYPTO_PROVIDER: String? = null
    }
}
