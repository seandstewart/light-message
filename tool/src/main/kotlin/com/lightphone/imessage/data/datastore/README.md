# Encrypted DataStore Repository

## Overview

This package provides encrypted storage for sensitive authentication credentials and key material. Persistence today is via [`androidx.security.crypto.EncryptedSharedPreferences`][esp] with an AES-256-GCM master key resident in the AndroidKeyStore.

[esp]: https://developer.android.com/reference/androidx/security/crypto/EncryptedSharedPreferences

## Files

- **`EncryptedTokenRepository.kt`** — Main implementation of `ITokenRepository`.

## Architecture

### ITokenRepository Interface

Defines the contract for secure token/key storage with suspend functions and `Result` error handling:

```kotlin
interface ITokenRepository {
    suspend fun saveSessionToken(token: String, expiresAt: Long): Result<Unit>
    suspend fun getSessionToken(): Result<String?>
    suspend fun clearSessionToken(): Result<Unit>

    suspend fun savePrivateKey(key: PrivateKey, keyId: String): Result<Unit>
    suspend fun getPrivateKey(keyId: String): Result<PrivateKey?>
    suspend fun listPrivateKeys(): Result<List<String>>
    suspend fun deletePrivateKey(keyId: String): Result<Unit>

    suspend fun saveAppleId(appleId: String): Result<Unit>
    suspend fun getAppleId(): Result<String?>

    suspend fun saveHardwareInfo(hwInfo: ByteArray): Result<Unit>
    suspend fun getHardwareInfo(): Result<ByteArray?>
}
```

### EncryptedTokenRepository Implementation

- **Master key**: A hardware-backed AES-256-GCM master key is created lazily by `MasterKey.Builder(context).setKeyScheme(AES256_GCM).build()`. The key lives in the AndroidKeyStore under the default alias `_androidx_security_master_key_` and survives app/process restarts.

- **At-rest encryption**: All values are encrypted transparently by `EncryptedSharedPreferences` — pref keys with AES-256-SIV, pref values with AES-256-GCM. The library manages IVs, auth tags, and key rotation internally.

- **Storage layout**: A single `SharedPreferences` file, `encrypted_tokens.xml`, in the app's default preferences directory. Fields:

  | Field                     | Type        | Notes                                      |
  | ------------------------- | ----------- | ------------------------------------------ |
  | `session_token`           | `String`    | Encrypted session token                    |
  | `session_expires`         | `Long`      | Expiration timestamp (ms since epoch)      |
  | `apple_id`                | `String`    | Encrypted Apple ID                         |
  | `hardware_info`           | `String`    | Base64-encoded raw bytes, then encrypted   |
  | `private_key_ids`         | `StringSet` | Set of stored keyIds                       |
  | `private_key.<keyId>`     | `String`    | PKCS#8 DER, base64-encoded, then encrypted |
  | `private_key_alg.<keyId>` | `String`    | JCA algorithm name (`"RSA"`, `"EC"`, …)    |

- **keyId validation**: `keyId` must match `[A-Za-z0-9_-]{1,64}`. Enforced on save, delete, and get. `listPrivateKeys` additionally filters out entries that fail the pattern.

- **Coroutine safety**: All operations dispatch to `Dispatchers.IO` via `withContext`.

## Security Notes

### Current State

- Master key is generated inside the AndroidKeyStore and never leaves it. On devices with a TEE / StrongBox the underlying `androidx.security` implementation binds the key to hardware.
- Data at rest is authenticated (AES-GCM) — corruption or tampering causes read failures rather than silent misdecryption.
- Session tokens have an explicit expiration timestamp and are treated as absent once expired.
- Private keys are stored as PKCS#8 DER bytes alongside their JCA algorithm name so the correct `KeyFactory` can rebuild them.
- Read paths that raise on `EncryptedSharedPreferences` (e.g. after master-key invalidation from biometric enrollment change) best-effort clear the affected record. This prevents an unrecoverable read loop and lets callers re-run the sign-in flow.
- keyIds are charset-restricted so a malicious identifier cannot escape into or collide with reserved pref keys.

### Known trade-offs

- **Heap exposure.** `getString` returns a Kotlin `String`, which lives on the JVM heap for the lifetime of the reference. Callers that want tighter control over lifetime would need a different storage layer (e.g. Keystore-only, `CharArray`-based API).
- **No key rotation.** The AndroidKeyStore master key is not rotated by this class. If we later need rotation, either introduce a versioned alias (`master_key_v2`) with a migration step, or delete `encrypted_tokens.xml` and force sign-in.
- **Alpha dependency.** `androidx.security:security-crypto` is currently at `1.1.0-alpha06`. Track its stability before shipping to production.

## Error Handling

All operations return `Result<T>`:

```kotlin
repository.getSessionToken()
    .onSuccess { token -> /* token may be null if expired or missing */ }
    .onFailure { error -> /* keystore or storage failure */ }
```

Failures preserve the original exception as the cause; error messages from callers should not concatenate `error.message` because sensitive material could leak through it.

## Usage Example

```kotlin
val repo = EncryptedTokenRepository(applicationContext)

// Save session token with 1-hour expiration
val expiresAt = System.currentTimeMillis() + 3_600_000L
repo.saveSessionToken("my_auth_token", expiresAt)
    .onFailure { Log.e("TokenRepo", "save failed", it) }

// Retrieve token (returns null if expired or absent)
val token = repo.getSessionToken().getOrNull()

// Store an RSA private key
val (_, priv) = cryptoEngine.generateRsaKeyPair()
repo.savePrivateKey(priv, "device_key_v1")

// Load it back
val key = repo.getPrivateKey("device_key_v1").getOrNull()
```

## Testing Considerations

1. Interact via `ITokenRepository` in production code and mock the interface in unit tests.
2. Instrumented tests can exercise `EncryptedTokenRepository` directly with an `androidx.test` `Context`.
3. Round-trip each field type after a simulated process restart (recreate the repository instance) to confirm persistence.
4. Verify expiration semantics for session tokens (both past and future `expiresAt`).
5. Verify that decryption failures clear the affected record and surface `Result.failure` with the original exception cause.
