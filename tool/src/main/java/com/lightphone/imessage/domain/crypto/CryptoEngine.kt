package com.lightphone.imessage.domain.crypto

import java.security.InvalidKeyException
import java.security.KeyGenerator
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.RSAKeyGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

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
     * @return AesGcmResult with IV, ciphertext, and authTag, or null on failure
     * @throws Exception if encryption fails
     */
    fun aesGcmEncrypt(
        plaintext: ByteArray,
        key: SecretKey,
        aad: ByteArray?,
    ): AesGcmResult {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")

        // Generate 12-byte IV
        val iv = ByteArray(12)
        secureRandom.nextBytes(iv)

        // Initialize with IV and 128-bit (16-byte) auth tag length
        val gcmSpec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec)

        // Add AAD if provided
        aad?.let { cipher.updateAAD(it) }

        // Encrypt plaintext
        val ciphertext = cipher.doFinal(plaintext)

        // Extract ciphertext and auth tag
        // GCM includes auth tag at the end of ciphertext
        val authTagLength = 16 // 128 bits / 8
        val actualCiphertext = ciphertext.copyOfRange(0, ciphertext.size - authTagLength)
        val authTag = ciphertext.copyOfRange(ciphertext.size - authTagLength, ciphertext.size)

        return AesGcmResult(iv, actualCiphertext, authTag)
    }

    /**
     * Decrypts AES-256-GCM encrypted data.
     *
     * @param ciphertext Encrypted data (without IV and tag)
     * @param key AES-256 SecretKey (must match encryption key)
     * @param iv 12-byte initialization vector
     * @param tag 16-byte authentication tag
     * @param aad Optional additional authenticated data (must match encryption AAD)
     * @return Result containing decrypted plaintext or failure
     */
    fun aesGcmDecrypt(
        ciphertext: ByteArray,
        key: SecretKey,
        iv: ByteArray,
        tag: ByteArray,
        aad: ByteArray?,
    ): Result<ByteArray> =
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")

            // Initialize with IV and 128-bit auth tag length
            val gcmSpec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec)

            // Add AAD if provided
            aad?.let { cipher.updateAAD(it) }

            // Concatenate ciphertext and tag for decryption
            val encryptedData = ciphertext + tag
            val plaintext = cipher.doFinal(encryptedData)

            Result.success(plaintext)
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
     * @param aesKey The AES SecretKey to wrap
     * @param publicKey RSA-2048 PublicKey
     * @return Result containing wrapped key bytes or failure
     */
    fun rsaOaepWrap(
        aesKey: SecretKey,
        publicKey: PublicKey,
    ): Result<ByteArray> =
        try {
            val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA256AndMGF1Padding")
            cipher.init(Cipher.WRAP_MODE, publicKey, secureRandom)
            val wrappedKey = cipher.wrap(aesKey)
            Result.success(wrappedKey)
        } catch (e: InvalidKeyException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }

    /**
     * Unwraps an AES key that was wrapped with RSA-2048-OAEP-SHA256.
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
            val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA256AndMGF1Padding")
            cipher.init(Cipher.UNWRAP_MODE, privateKey)
            val unwrappedKey = cipher.unwrap(wrappedKey, "AES", Cipher.SECRET_KEY)
            Result.success(unwrappedKey as SecretKey)
        } catch (e: InvalidKeyException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }

    /**
     * Signs data using ECDSA P-256 with SHA-256.
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
            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initSign(privateKey, secureRandom)
            signature.update(data)
            val signatureBytes = signature.sign()
            Result.success(signatureBytes)
        } catch (e: InvalidKeyException) {
            Result.failure(e)
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
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initVerify(publicKey)
            sig.update(data)
            val isValid = sig.verify(signature)
            if (isValid) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Signature verification failed"))
            }
        } catch (e: InvalidKeyException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
}
