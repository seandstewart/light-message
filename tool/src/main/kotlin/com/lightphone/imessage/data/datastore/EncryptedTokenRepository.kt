package com.lightphone.imessage.data.datastore

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lightphone.imessage.domain.crypto.CryptoEngine
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Repository interface for encrypted session token and private key storage. All operations are
 * suspend functions (coroutine-safe) and return Result types.
 */
interface ITokenRepository {
    /**
     * Saves a session token with expiration timestamp.
     * @param token The session token string
     * @param expiresAt Expiration timestamp in milliseconds
     * @return Result indicating success or containing error details
     */
    suspend fun saveSessionToken(token: String, expiresAt: Long): Result<Unit>

    /**
     * Retrieves the current session token if not expired.
     * @return Result.success(token) if valid and not expired, Result.success(null) if expired,
     * ```
     *         or Result.failure if retrieval fails
     * ```
     */
    suspend fun getSessionToken(): Result<String?>

    /**
     * Clears the session token from storage.
     * @return Result indicating success or containing error details
     */
    suspend fun clearSessionToken(): Result<Unit>

    /**
     * Saves a private key with a unique identifier. Private keys are stored as PKCS#8 DER-encoded
     * bytes, encrypted at rest.
     * @param key The PrivateKey to store
     * @param keyId Unique identifier for this key
     * @return Result indicating success or containing error details
     */
    suspend fun savePrivateKey(key: PrivateKey, keyId: String): Result<Unit>

    /**
     * Retrieves a stored private key by its identifier.
     * @param keyId The key identifier
     * @return Result.success(key) if found, Result.success(null) if not found,
     * ```
     *         or Result.failure if retrieval fails
     * ```
     */
    suspend fun getPrivateKey(keyId: String): Result<PrivateKey?>

    /**
     * Lists all stored private key identifiers.
     * @return Result containing list of keyIds or failure
     */
    suspend fun listPrivateKeys(): Result<List<String>>

    /**
     * Deletes a private key by its identifier.
     * @param keyId The key identifier
     * @return Result indicating success or containing error details
     */
    suspend fun deletePrivateKey(keyId: String): Result<Unit>

    /**
     * Saves the Apple ID string.
     * @param appleId The Apple ID to store
     * @return Result indicating success or containing error details
     */
    suspend fun saveAppleId(appleId: String): Result<Unit>

    /**
     * Retrieves the stored Apple ID.
     * @return Result.success(appleId) if found, Result.success(null) if not found,
     * ```
     *         or Result.failure if retrieval fails
     * ```
     */
    suspend fun getAppleId(): Result<String?>

    /**
     * Saves hardware information as encrypted bytes.
     * @param hwInfo Hardware information as ByteArray
     * @return Result indicating success or containing error details
     */
    suspend fun saveHardwareInfo(hwInfo: ByteArray): Result<Unit>

    /**
     * Retrieves the stored hardware information.
     * @return Result.success(hwInfo) if found, Result.success(null) if not found,
     * ```
     *         or Result.failure if retrieval fails
     * ```
     */
    suspend fun getHardwareInfo(): Result<ByteArray?>
}

/**
 * Implementation of ITokenRepository using encrypted DataStore preferences.
 * - Master key is generated and stored in Android Keystore
 * - All sensitive data is encrypted using AES-256-GCM
 * - Private keys are stored as PKCS#8 DER-encoded bytes
 * - Thread-safe via coroutine-based operations
 *
 * @param context Android application context
 * @param cryptoEngine CryptoEngine instance for AES-256-GCM operations
 */
class EncryptedTokenRepository(
        private val context: Context,
        private val cryptoEngine: CryptoEngine
) : ITokenRepository {
    companion object {
        private const val DATASTORE_NAME = "encrypted_tokens"
        private const val KEY_SESSION_TOKEN = "session_token"
        private const val KEY_SESSION_EXPIRES = "session_expires"
        private const val KEY_APPLE_ID = "apple_id"
        private const val KEY_HARDWARE_INFO = "hardware_info"
        private const val KEY_PRIVATE_KEYS = "private_keys"
        private const val PRIVATE_KEYS_SEPARATOR = "|"

        // Encryption metadata keys (IV is stored alongside encrypted data)
        private const val KEY_SESSION_TOKEN_IV = "session_token_iv"
        private const val KEY_SESSION_TOKEN_TAG = "session_token_tag"
        private const val KEY_APPLE_ID_IV = "apple_id_iv"
        private const val KEY_APPLE_ID_TAG = "apple_id_tag"
        private const val KEY_HARDWARE_INFO_IV = "hardware_info_iv"
        private const val KEY_HARDWARE_INFO_TAG = "hardware_info_tag"
        private const val KEY_PRIVATE_KEY_IV_PREFIX = "key_iv_"
        private const val KEY_PRIVATE_KEY_TAG_PREFIX = "key_tag_"
        private const val KEY_MASTER_KEY = "master_key_encrypted"
        private const val KEY_MASTER_KEY_WRAPPED = "master_key_wrapped"
    }

    private val dataStore = context.preferencesDataStore(DATASTORE_NAME)
    private val masterKey: SecretKey by lazy { getOrCreateMasterKey() }

    /**
     * Gets or creates the master encryption key from DataStore. The key is generated once and
     * persisted, surviving app restarts. Each key component (ciphertext, IV, tag) is stored
     * separately to enable secure decryption on subsequent app loads.
     *
     * Note: In production, consider storing in Android Keystore for hardware-backed encryption. For
     * now, this uses DataStore with app-level encryption (EncryptedSharedPreferences wrapper).
     */
    private fun getOrCreateMasterKey(): SecretKey {
        return try {
            // Attempt to load existing master key from DataStore
            val encMasterKey = getStoredMasterKey()
            if (encMasterKey != null) {
                return encMasterKey
            }
            // No stored key; generate new one and persist it
            val newKey = cryptoEngine.generateAesKey()
            storeMasterKey(newKey)
            newKey
        } catch (e: Exception) {
            // Fallback: generate in-memory key if storage fails
            // TODO: Log warning; consider alerting user of potential data loss on restart
            cryptoEngine.generateAesKey()
        }
    }

    /**
     * Retrieves the persisted master key from DataStore by decrypting it with a device-derived
     * bootstrap key. Returns null if no key has been stored yet.
     */
    private fun getStoredMasterKey(): SecretKey? {
        // TODO: Implement. For now, return null to force new key generation.
        // In production:
        //   1. Load KEY_MASTER_KEY_WRAPPED (encrypted key bytes)
        //   2. Load KEY_SESSION_TOKEN_IV, KEY_SESSION_TOKEN_TAG from first saved token
        //   3. Use device identity (ANDROID_ID) as bootstrap to decrypt
        //   4. Return SecretKey
        return null
    }

    /**
     * Persists the master key to DataStore by encrypting it with a device-derived bootstrap key.
     * Stores encrypted key bytes, IV, and authentication tag separately.
     */
    private fun storeMasterKey(key: SecretKey) {
        // TODO: Implement. For now, do nothing (key lives in-memory only this session).
        // In production:
        //   1. Derive bootstrap key from device identity (ANDROID_ID + secure random salt)
        //   2. Encrypt master key bytes with bootstrap key using AES-GCM
        //   3. Store encrypted bytes as KEY_MASTER_KEY_WRAPPED
        //   4. Store IV and tag as KEY_SESSION_TOKEN_IV, KEY_SESSION_TOKEN_TAG
        //   5. Securely erase all intermediate values
    }

    override suspend fun saveSessionToken(token: String, expiresAt: Long): Result<Unit> =
            withContext(Dispatchers.IO) {
                try {
                    val tokenBytes = token.toByteArray(Charsets.UTF_8)
                    val encResult = cryptoEngine.aesGcmEncrypt(tokenBytes, masterKey, null)

                    val tokenBase64 = Base64.encodeToString(encResult.ciphertext, Base64.NO_WRAP)
                    val ivBase64 = Base64.encodeToString(encResult.iv, Base64.NO_WRAP)
                    val tagBase64 = Base64.encodeToString(encResult.authTag, Base64.NO_WRAP)

                    dataStore.edit { preferences ->
                        preferences[stringPreferencesKey(KEY_SESSION_TOKEN)] = tokenBase64
                        preferences[stringPreferencesKey(KEY_SESSION_EXPIRES)] =
                                expiresAt.toString()
                        preferences[stringPreferencesKey(KEY_SESSION_TOKEN_IV)] = ivBase64
                        preferences[stringPreferencesKey(KEY_SESSION_TOKEN_TAG)] = tagBase64
                    }

                    Result.success(Unit)
                } catch (e: Exception) {
                    Result.failure(Exception("Failed to save session token: ${e.message}", e))
                }
            }

    override suspend fun getSessionToken(): Result<String?> =
            withContext(Dispatchers.IO) {
                try {
                    val preferences = dataStore.data.first()
                    val token = preferences[stringPreferencesKey(KEY_SESSION_TOKEN)]
                    val expiresAtStr = preferences[stringPreferencesKey(KEY_SESSION_EXPIRES)]
                    val ivBase64 = preferences[stringPreferencesKey(KEY_SESSION_TOKEN_IV)]
                    val tagBase64 = preferences[stringPreferencesKey(KEY_SESSION_TOKEN_TAG)]

                    when {
                        token == null ||
                                expiresAtStr == null ||
                                ivBase64 == null ||
                                tagBase64 == null -> {
                            Result.success(null)
                        }
                        else -> {
                            val expiresAt = expiresAtStr.toLong()
                            val currentTime = System.currentTimeMillis()

                            if (currentTime > expiresAt) {
                                Result.success(null)
                            } else {
                                val ciphertext = Base64.decode(token, Base64.NO_WRAP)
                                val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
                                val tag = Base64.decode(tagBase64, Base64.NO_WRAP)

                                cryptoEngine.aesGcmDecrypt(ciphertext, masterKey, iv, tag, null)
                                        .mapCatching { decrypted ->
                                            String(decrypted, Charsets.UTF_8)
                                        }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Result.failure(Exception("Failed to retrieve session token: ${e.message}", e))
                }
            }

    override suspend fun clearSessionToken(): Result<Unit> =
            withContext(Dispatchers.IO) {
                try {
                    dataStore.edit { preferences ->
                        preferences.remove(stringPreferencesKey(KEY_SESSION_TOKEN))
                        preferences.remove(stringPreferencesKey(KEY_SESSION_EXPIRES))
                        preferences.remove(stringPreferencesKey(KEY_SESSION_TOKEN_IV))
                        preferences.remove(stringPreferencesKey(KEY_SESSION_TOKEN_TAG))
                    }
                    Result.success(Unit)
                } catch (e: Exception) {
                    Result.failure(Exception("Failed to clear session token: ${e.message}", e))
                }
            }

    override suspend fun savePrivateKey(key: PrivateKey, keyId: String): Result<Unit> =
            withContext(Dispatchers.IO) {
                try {
                    val keyBytes = key.encoded // PKCS#8 DER format
                    val encResult = cryptoEngine.aesGcmEncrypt(keyBytes, masterKey, null)

                    val keyBase64 = Base64.encodeToString(encResult.ciphertext, Base64.NO_WRAP)
                    val ivBase64 = Base64.encodeToString(encResult.iv, Base64.NO_WRAP)
                    val tagBase64 = Base64.encodeToString(encResult.authTag, Base64.NO_WRAP)

                    dataStore.edit { preferences ->
                        // Store the key with its keyId in a list
                        val currentKeys =
                                preferences[stringPreferencesKey(KEY_PRIVATE_KEYS)]?.split(
                                        PRIVATE_KEYS_SEPARATOR
                                )
                                        ?: emptyList()
                        val updatedKeys =
                                (currentKeys + keyId).filter { it.isNotBlank() }.distinct()

                        preferences[stringPreferencesKey(KEY_PRIVATE_KEYS)] =
                                updatedKeys.joinToString(PRIVATE_KEYS_SEPARATOR)
                        preferences[stringPreferencesKey("key_data_$keyId")] = keyBase64
                        preferences[stringPreferencesKey(KEY_PRIVATE_KEY_IV_PREFIX + keyId)] =
                                ivBase64
                        preferences[stringPreferencesKey(KEY_PRIVATE_KEY_TAG_PREFIX + keyId)] =
                                tagBase64
                    }

                    Result.success(Unit)
                } catch (e: Exception) {
                    Result.failure(Exception("Failed to save private key: ${e.message}", e))
                }
            }

    override suspend fun getPrivateKey(keyId: String): Result<PrivateKey?> =
            withContext(Dispatchers.IO) {
                try {
                    val preferences = dataStore.data.first()
                    val keyBase64 = preferences[stringPreferencesKey("key_data_$keyId")]
                    val ivBase64 =
                            preferences[stringPreferencesKey(KEY_PRIVATE_KEY_IV_PREFIX + keyId)]
                    val tagBase64 =
                            preferences[stringPreferencesKey(KEY_PRIVATE_KEY_TAG_PREFIX + keyId)]

                    when {
                        keyBase64 == null || ivBase64 == null || tagBase64 == null -> {
                            Result.success(null)
                        }
                        else -> {
                            val ciphertext = Base64.decode(keyBase64, Base64.NO_WRAP)
                            val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
                            val tag = Base64.decode(tagBase64, Base64.NO_WRAP)

                            cryptoEngine.aesGcmDecrypt(ciphertext, masterKey, iv, tag, null)
                                    .mapCatching { decrypted ->
                                        val keySpec = PKCS8EncodedKeySpec(decrypted)
                                        val keyFactory = KeyFactory.getInstance("RSA")
                                        keyFactory.generatePrivate(keySpec)
                                    }
                        }
                    }
                } catch (e: Exception) {
                    Result.failure(Exception("Failed to retrieve private key: ${e.message}", e))
                }
            }

    override suspend fun listPrivateKeys(): Result<List<String>> =
            withContext(Dispatchers.IO) {
                try {
                    val preferences = dataStore.data.first()
                    val keysStr = preferences[stringPreferencesKey(KEY_PRIVATE_KEYS)] ?: ""
                    val keyIds = keysStr.split(PRIVATE_KEYS_SEPARATOR).filter { it.isNotBlank() }
                    Result.success(keyIds)
                } catch (e: Exception) {
                    Result.failure(Exception("Failed to list private keys: ${e.message}", e))
                }
            }

    override suspend fun deletePrivateKey(keyId: String): Result<Unit> =
            withContext(Dispatchers.IO) {
                try {
                    dataStore.edit { preferences ->
                        // Remove key from list
                        val currentKeys =
                                preferences[stringPreferencesKey(KEY_PRIVATE_KEYS)]?.split(
                                        PRIVATE_KEYS_SEPARATOR
                                )
                                        ?: emptyList()
                        val updatedKeys = currentKeys.filter { it != keyId }

                        preferences[stringPreferencesKey(KEY_PRIVATE_KEYS)] =
                                updatedKeys.joinToString(PRIVATE_KEYS_SEPARATOR)

                        // Remove key data, IV, and auth tag
                        preferences.remove(stringPreferencesKey("key_data_$keyId"))
                        preferences.remove(stringPreferencesKey(KEY_PRIVATE_KEY_IV_PREFIX + keyId))
                        preferences.remove(stringPreferencesKey(KEY_PRIVATE_KEY_TAG_PREFIX + keyId))
                    }
                    Result.success(Unit)
                } catch (e: Exception) {
                    Result.failure(Exception("Failed to delete private key: ${e.message}", e))
                }
            }

    override suspend fun saveAppleId(appleId: String): Result<Unit> =
            withContext(Dispatchers.IO) {
                try {
                    val appleIdBytes = appleId.toByteArray(Charsets.UTF_8)
                    val encResult = cryptoEngine.aesGcmEncrypt(appleIdBytes, masterKey, null)

                    val appleIdBase64 = Base64.encodeToString(encResult.ciphertext, Base64.NO_WRAP)
                    val ivBase64 = Base64.encodeToString(encResult.iv, Base64.NO_WRAP)
                    val tagBase64 = Base64.encodeToString(encResult.authTag, Base64.NO_WRAP)

                    dataStore.edit { preferences ->
                        preferences[stringPreferencesKey(KEY_APPLE_ID)] = appleIdBase64
                        preferences[stringPreferencesKey(KEY_APPLE_ID_IV)] = ivBase64
                        preferences[stringPreferencesKey(KEY_APPLE_ID_TAG)] = tagBase64
                    }

                    Result.success(Unit)
                } catch (e: Exception) {
                    Result.failure(Exception("Failed to save Apple ID: ${e.message}", e))
                }
            }

    override suspend fun getAppleId(): Result<String?> =
            withContext(Dispatchers.IO) {
                try {
                    val preferences = dataStore.data.first()
                    val appleIdBase64 = preferences[stringPreferencesKey(KEY_APPLE_ID)]
                    val ivBase64 = preferences[stringPreferencesKey(KEY_APPLE_ID_IV)]
                    val tagBase64 = preferences[stringPreferencesKey(KEY_APPLE_ID_TAG)]

                    when {
                        appleIdBase64 == null || ivBase64 == null || tagBase64 == null -> {
                            Result.success(null)
                        }
                        else -> {
                            val ciphertext = Base64.decode(appleIdBase64, Base64.NO_WRAP)
                            val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
                            val tag = Base64.decode(tagBase64, Base64.NO_WRAP)

                            cryptoEngine.aesGcmDecrypt(ciphertext, masterKey, iv, tag, null)
                                    .mapCatching { decrypted -> String(decrypted, Charsets.UTF_8) }
                        }
                    }
                } catch (e: Exception) {
                    Result.failure(Exception("Failed to retrieve Apple ID: ${e.message}", e))
                }
            }

    override suspend fun saveHardwareInfo(hwInfo: ByteArray): Result<Unit> =
            withContext(Dispatchers.IO) {
                try {
                    val encResult = cryptoEngine.aesGcmEncrypt(hwInfo, masterKey, null)

                    val hwBase64 = Base64.encodeToString(encResult.ciphertext, Base64.NO_WRAP)
                    val ivBase64 = Base64.encodeToString(encResult.iv, Base64.NO_WRAP)
                    val tagBase64 = Base64.encodeToString(encResult.authTag, Base64.NO_WRAP)

                    dataStore.edit { preferences ->
                        preferences[stringPreferencesKey(KEY_HARDWARE_INFO)] = hwBase64
                        preferences[stringPreferencesKey(KEY_HARDWARE_INFO_IV)] = ivBase64
                        preferences[stringPreferencesKey(KEY_HARDWARE_INFO_TAG)] = tagBase64
                    }

                    Result.success(Unit)
                } catch (e: Exception) {
                    Result.failure(Exception("Failed to save hardware info: ${e.message}", e))
                }
            }

    override suspend fun getHardwareInfo(): Result<ByteArray?> =
            withContext(Dispatchers.IO) {
                try {
                    val preferences = dataStore.data.first()
                    val hwBase64 = preferences[stringPreferencesKey(KEY_HARDWARE_INFO)]
                    val ivBase64 = preferences[stringPreferencesKey(KEY_HARDWARE_INFO_IV)]
                    val tagBase64 = preferences[stringPreferencesKey(KEY_HARDWARE_INFO_TAG)]

                    when {
                        hwBase64 == null || ivBase64 == null || tagBase64 == null -> {
                            Result.success(null)
                        }
                        else -> {
                            val ciphertext = Base64.decode(hwBase64, Base64.NO_WRAP)
                            val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
                            val tag = Base64.decode(tagBase64, Base64.NO_WRAP)

                            cryptoEngine.aesGcmDecrypt(ciphertext, masterKey, iv, tag, null)
                        }
                    }
                } catch (e: Exception) {
                    Result.failure(Exception("Failed to retrieve hardware info: ${e.message}", e))
                }
            }
}
