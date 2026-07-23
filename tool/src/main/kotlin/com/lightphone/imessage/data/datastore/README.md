# Encrypted DataStore Repository

## Overview

This package provides encrypted storage for sensitive authentication credentials and key material via Android's DataStore preferences system, with AES-256-GCM encryption enforced by the `CryptoEngine`.

## Files

- **`EncryptedTokenRepository.kt`** — Main implementation of `ITokenRepository` interface
- **`../../../proto/com/lightphone/imessage/token.proto`** — Protocol Buffer schema (reference; currently using preference-key strategy)

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

- **Master Key Generation**: Uses `CryptoEngine.generateAesKey()` to create a 256-bit AES key
  - Current implementation stores key in-memory only
  - **TODO**: Integrate with Android Keystore for persistent, hardware-backed key derivation

- **Data Encryption**: All sensitive data encrypted via AES-256-GCM with:
  - Random 12-byte IV per encryption
  - Base64 encoding for preference storage
  - Separate storage of IV alongside ciphertext

- **Storage Strategy**:
  - Uses `androidx.datastore.preferences.preferencesDataStore`
  - Preference keys follow pattern: `KEY_*` for data, `*_IV` for initialization vectors
  - Private keys stored as PKCS#8 DER-encoded bytes
  - Session tokens stored as UTF-8 strings with expiration check

- **Coroutine Safety**:
  - All I/O operations dispatch to `Dispatchers.IO`
  - Uses `withContext()` for proper cancellation and exception handling
  - Compatible with structured concurrency

## Security Notes

### Current State
- ✅ AES-256-GCM encryption for data at rest
- ✅ Unique IV per encryption operation
- ✅ PKCS#8 DER encoding for RSA private keys
- ✅ Session token expiration validation
- ⚠️ Master key stored in-memory only (ephemeral)

### Future Hardening
1. **Android Keystore Integration**
   - Use `KeyStore` (type: "AndroidKeyStore") for master key
   - Enable Hardware-Backed encryption if available
   - Implement key attestation

2. **Auth Tag Storage**
   - Currently using dummy 16-byte tags
   - Should store actual auth tags from GCM encryption
   - Enables full authentication verification on decryption

3. **Secure Key Derivation**
   - Implement PBKDF2 or similar for password-based master key derivation
   - Support for user authentication challenges

## Serialization Details

| Data Type | Format | Encryption | Notes |
|-----------|--------|------------|-------|
| Session Token | UTF-8 string | AES-256-GCM | Includes expiration timestamp |
| Private Key | PKCS#8 DER bytes | AES-256-GCM | RSA keys only (PrivateKey.encoded) |
| Apple ID | UTF-8 string | AES-256-GCM | Plain string |
| Hardware Info | Raw ByteArray | AES-256-GCM | Binary blob |

## Error Handling

All operations return `Result<T>` for graceful error propagation:

```kotlin
when (val result = repository.getSessionToken()) {
    is Result.Success -> {
        val token = result.getOrNull()  // May be null if expired/not found
        // Use token
    }
    is Result.Failure -> {
        // Handle encryption/storage errors
        val error = result.exceptionOrNull()
    }
}
```

## Usage Example

```kotlin
val context = applicationContext
val cryptoEngine = CryptoEngine()
val repo = EncryptedTokenRepository(context, cryptoEngine)

// Save session token with 1-hour expiration
val expiresAt = System.currentTimeMillis() + 3600000L
repo.saveSessionToken("my_auth_token", expiresAt).onSuccess {
    Log.d("TokenRepo", "Token saved")
}.onFailure { error ->
    Log.e("TokenRepo", "Failed to save token", error)
}

// Retrieve token (returns null if expired)
val token = repo.getSessionToken().getOrNull()

// Store private key
val (pub, priv) = cryptoEngine.generateRsaKeyPair()
repo.savePrivateKey(priv, "device_key_v1").onSuccess {
    Log.d("TokenRepo", "Key stored")
}

// Retrieve and use key
val key = repo.getPrivateKey("device_key_v1").getOrNull()
if (key != null) {
    // Use key for signing/decryption
}
```

## Testing Considerations

When writing tests:
1. Mock `CryptoEngine` or provide test instance
2. Mock DataStore or use `TestDataStore` utilities
3. Verify encryption/decryption round-trip
4. Test expiration logic for session tokens
5. Verify error handling paths

## Protocol Buffer (Reference)

The `token.proto` file defines the schema for future migration to protobuf-based storage. Currently, the implementation uses preference-key strategy for simplicity. To migrate:

1. Enable protobuf code generation in `build.gradle.kts`
2. Update repository to serialize/deserialize `TokenStore` messages
3. Store single `TokenStore` proto in DataStore instead of individual keys

## TODO

- [ ] Android Keystore integration for master key
- [ ] Auth tag validation on decryption
- [ ] Keystore-backed private key storage (if available)
- [ ] Migration path from preference-key to protobuf storage
- [ ] Rate-limiting for failed decryption attempts
- [ ] Optional encryption key rotation
