# ADR 003: Custom Plist Codec over kotlinx.serialization

**Status:** Accepted

**Context:**  
Apple's iMessage protocol and authentication flows rely on binary property lists (`bplist`) and XML plists. No Plist parsing library is present in the `LightSdkPlugin` whitelist.

**Decision:**  
Implement a custom Plist parser and serializer as an extension of `kotlinx.serialization`. XML plist will be implemented first; binary plist (`bplist00`) will follow.

**Consequences:**
- **Positive:** Avoids non-whitelisted dependency; integrates directly with the chosen serialization framework.
- **Negative:** Non-trivial implementation effort; risk of parsing edge cases in Apple's proprietary binary format.
