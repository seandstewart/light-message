# ADR 004: Standard Android JCE for Cryptography

**Status:** Accepted

**Context:**  
iMessage requires AES-GCM encryption, RSA/ECDSA certificate handling, and SHA-256 hashing. `BouncyCastle` and `SpongyCastle` are not whitelisted.

**Decision:**  
Use `javax.crypto` and `java.security` packages exclusively. All operations must be achievable on Android API 34 via the standard JCE provider.

**Consequences:**
- **Positive:** Zero additional dependencies; guaranteed SDK compliance.
- **Negative:** If Apple's legacy authentication requires deprecated algorithms (e.g., 3DES, non-standard EC curves), a whitelist petition will be required. These cases will be treated as bugs, not plan assumptions.
