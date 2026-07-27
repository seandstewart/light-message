package com.lightphone.imessage.data.datastore

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import kotlinx.coroutines.Dispatchers
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
    suspend fun saveSessionToken(
            token: String,
            expiresAt: Long,
    ): Result<Unit>

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
    suspend fun savePrivateKey(
            key: PrivateKey,
            keyId: String,
    ): Result<Unit>

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
 * [ITokenRepository] implementation backed by [androidx.security.crypto.EncryptedSharedPreferences]
 * .
 *
 * Values are transparently encrypted with an AES-256-GCM data key that is itself wrapped by an
 * AndroidKeyStore-resident master key. The master key is created (and persisted) lazily on first
 * access, so state survives process and app restarts. If the master key is invalidated by the
 * platform (e.g. after biometric enrollment change) reads will surface exceptions; callers should
 * treat that as "sign-in required" and clear dependent state.
 *
 * ### Heap exposure Values are returned as [String]/[ByteArray] and therefore live in the JVM heap
 * for the lifetime of the returned reference. Callers that need short-lived credential material
 * should overwrite their references promptly. A `CharArray`/`ByteArray`-only API would require
 * dropping `EncryptedSharedPreferences` in favour of a custom storage layer, which we consider not
 * worth the additional attack surface today.
 *
 * @param context Android application context.
 */
class EncryptedTokenRepository(
        private val context: Context,
) : ITokenRepository {
    companion object {
        private const val PREFS_NAME = "encrypted_tokens"

        // Field keys
        private const val KEY_SESSION_TOKEN = "session_token"
        private const val KEY_SESSION_EXPIRES = "session_expires"
        private const val KEY_APPLE_ID = "apple_id"
        private const val KEY_HARDWARE_INFO = "hardware_info"
        private const val KEY_PRIVATE_KEY_IDS = "private_key_ids"
        private const val KEY_PRIVATE_KEY_PREFIX = "private_key."
        private const val KEY_PRIVATE_KEY_ALG_PREFIX = "private_key_alg."

        /** Restricted keyId charset: URL-safe, bounded length — prevents pref-key collisions. */
        private val KEY_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")

        private fun requireValidKeyId(keyId: String) {
            require(KEY_ID_PATTERN.matches(keyId)) {
                "invalid keyId (must match ${KEY_ID_PATTERN.pattern})"
            }
        }
    }

    private val prefs: SharedPreferences by lazy { createPrefs() }

    private fun createPrefs(): SharedPreferences {
        val masterKey =
                MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override suspend fun saveSessionToken(
            token: String,
            expiresAt: Long,
    ): Result<Unit> =
            withContext(Dispatchers.IO) {
                try {
                    prefs.edit()
                            .putString(KEY_SESSION_TOKEN, token)
                            .putLong(KEY_SESSION_EXPIRES, expiresAt)
                            .apply()
                    Result.success(Unit)
                } catch (e: Throwable) {
                    Result.failure(e)
                }
            }

    override suspend fun getSessionToken(): Result<String?> =
            withContext(Dispatchers.IO) {
                try {
                    val token = prefs.getString(KEY_SESSION_TOKEN, null)
                    if (token == null) {
                        Result.success(null)
                    } else {
                        val expiresAt = prefs.getLong(KEY_SESSION_EXPIRES, 0L)
                        if (expiresAt == 0L || System.currentTimeMillis() > expiresAt) {
                            Result.success(null)
                        } else {
                            Result.success(token)
                        }
                    }
                } catch (e: Throwable) {
                    // Corrupt / unauthenticated record — best-effort clear so future reads
                    // don't loop on the same broken ciphertext (e.g. after master key
                    // invalidation). Preserves original failure cause.
                    runCatching {
                        prefs.edit().remove(KEY_SESSION_TOKEN).remove(KEY_SESSION_EXPIRES).apply()
                    }
                    Result.failure(e)
                }
            }

    override suspend fun clearSessionToken(): Result<Unit> =
            withContext(Dispatchers.IO) {
                try {
                    prefs.edit().remove(KEY_SESSION_TOKEN).remove(KEY_SESSION_EXPIRES).apply()
                    Result.success(Unit)
                } catch (e: Throwable) {
                    Result.failure(e)
                }
            }

    override suspend fun savePrivateKey(
            key: PrivateKey,
            keyId: String,
    ): Result<Unit> =
            withContext(Dispatchers.IO) {
                try {
                    requireValidKeyId(keyId)
                    val encoded = key.encoded ?: error("PrivateKey has no PKCS#8 encoding")
                    val encodedB64 = Base64.encodeToString(encoded, Base64.NO_WRAP)
                    val ids =
                            (prefs.getStringSet(KEY_PRIVATE_KEY_IDS, emptySet())
                                    ?: emptySet()) + keyId
                    prefs.edit()
                            .putString(KEY_PRIVATE_KEY_PREFIX + keyId, encodedB64)
                            .putString(KEY_PRIVATE_KEY_ALG_PREFIX + keyId, key.algorithm)
                            .putStringSet(KEY_PRIVATE_KEY_IDS, ids)
                            .apply()
                    Result.success(Unit)
                } catch (e: Throwable) {
                    Result.failure(e)
                }
            }

    override suspend fun getPrivateKey(keyId: String): Result<PrivateKey?> =
            withContext(Dispatchers.IO) {
                try {
                    requireValidKeyId(keyId)
                    val encoded =
                            prefs.getString(KEY_PRIVATE_KEY_PREFIX + keyId, null)
                                    ?: return@withContext Result.success(null)
                    val algorithm =
                            prefs.getString(KEY_PRIVATE_KEY_ALG_PREFIX + keyId, null) ?: "RSA"
                    val bytes = Base64.decode(encoded, Base64.NO_WRAP)
                    val privateKey =
                            KeyFactory.getInstance(algorithm)
                                    .generatePrivate(PKCS8EncodedKeySpec(bytes))
                    Result.success(privateKey)
                } catch (e: Throwable) {
                    // Clear the individual record on decryption / parse failure to avoid a
                    // permanent read loop when the master key is invalidated.
                    runCatching {
                        prefs.edit()
                                .remove(KEY_PRIVATE_KEY_PREFIX + keyId)
                                .remove(KEY_PRIVATE_KEY_ALG_PREFIX + keyId)
                                .apply()
                    }
                    Result.failure(e)
                }
            }

    override suspend fun listPrivateKeys(): Result<List<String>> =
            withContext(Dispatchers.IO) {
                try {
                    val ids = prefs.getStringSet(KEY_PRIVATE_KEY_IDS, emptySet()) ?: emptySet()
                    // Defensive: drop anything that no longer matches the accepted charset.
                    Result.success(ids.filter { KEY_ID_PATTERN.matches(it) })
                } catch (e: Throwable) {
                    Result.failure(e)
                }
            }

    override suspend fun deletePrivateKey(keyId: String): Result<Unit> =
            withContext(Dispatchers.IO) {
                try {
                    requireValidKeyId(keyId)
                    val ids =
                            (prefs.getStringSet(KEY_PRIVATE_KEY_IDS, emptySet())
                                    ?: emptySet()) - keyId
                    prefs.edit()
                            .remove(KEY_PRIVATE_KEY_PREFIX + keyId)
                            .remove(KEY_PRIVATE_KEY_ALG_PREFIX + keyId)
                            .putStringSet(KEY_PRIVATE_KEY_IDS, ids)
                            .apply()
                    Result.success(Unit)
                } catch (e: Throwable) {
                    Result.failure(e)
                }
            }

    override suspend fun saveAppleId(appleId: String): Result<Unit> =
            withContext(Dispatchers.IO) {
                try {
                    prefs.edit().putString(KEY_APPLE_ID, appleId).apply()
                    Result.success(Unit)
                } catch (e: Throwable) {
                    Result.failure(e)
                }
            }

    override suspend fun getAppleId(): Result<String?> =
            withContext(Dispatchers.IO) {
                try {
                    Result.success(prefs.getString(KEY_APPLE_ID, null))
                } catch (e: Throwable) {
                    runCatching { prefs.edit().remove(KEY_APPLE_ID).apply() }
                    Result.failure(e)
                }
            }

    override suspend fun saveHardwareInfo(hwInfo: ByteArray): Result<Unit> =
            withContext(Dispatchers.IO) {
                try {
                    val encoded = Base64.encodeToString(hwInfo, Base64.NO_WRAP)
                    prefs.edit().putString(KEY_HARDWARE_INFO, encoded).apply()
                    Result.success(Unit)
                } catch (e: Throwable) {
                    Result.failure(e)
                }
            }

    override suspend fun getHardwareInfo(): Result<ByteArray?> =
            withContext(Dispatchers.IO) {
                try {
                    val encoded =
                            prefs.getString(KEY_HARDWARE_INFO, null)
                                    ?: return@withContext Result.success(null)
                    Result.success(Base64.decode(encoded, Base64.NO_WRAP))
                } catch (e: Throwable) {
                    runCatching { prefs.edit().remove(KEY_HARDWARE_INFO).apply() }
                    Result.failure(e)
                }
            }
}
