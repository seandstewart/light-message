# ADR 005: rustpush -> UnifiedPush Bridge for Push Notifications

**Status:** Accepted

> **Amendment (v1.1):** Refined IPC contract (heartbeat, queue depth, retry logic); see design.md §2.1–2.5 and IPCMessage.json schema.

**Context:**
Google Play Services is absent on LightOS. Direct Apple APNs push requires a proprietary TLS-mutual-auth TCP protocol and a hardware-validated session (via `open-absinthe` or NAC relay). Implementing this in Kotlin is infeasible: the `open-absinthe` emulation layer (IOKit hooks, FairPlay, unicorn-engine) and the APNs TLS handshake are under active change and prohibitively complex to port. Research confirms that direct Apple API calls without the proven Rust core fail to deliver messages reliably.

The `org.unifiedpush.android:connector` artifact is explicitly whitelisted in the Light SDK, providing a FCM-free push mechanism.

**Decision:**
Use rustpush compiled as an Android native library (`.so`) to maintain the APNs connection. rustpush runs as a headless native service, receives Apple push notifications via its `aps_client` module, and forwards them through a UnifiedPush-compatible bridge/endpoint. The LightOS Kotlin app consumes these notifications exclusively via the whitelisted `org.unifiedpush.android:connector`.

**Consequences:**

- **Positive:** Leverages a proven, actively maintained Rust implementation for APNs; avoids reimplementing Apple's proprietary binary push protocol and hardware validation in Kotlin; eliminates the need for an always-on Mac relay (reduced to one-time activation); works without FCM or Google Play Services.
- **Negative:** Introduces a native Rust library and NDK build pipeline; requires JNI or local IPC (Unix domain socket / AIDL) between the native service and Kotlin UI; adds binary size and ABI complexity (ARM64 target).
- **Neutral:** The rest of the iMessage protocol (message envelope encoding, auth state machine, UI) remains in Kotlin (Path A), respecting the Light SDK's library whitelist.

**Alternatives Considered:**

- Direct APNs implementation in Kotlin (rejected: infeasible due to `open-absinthe` emulation and APNs TLS handshake complexity).
- UnifiedPush with an external server-side relay (rejected: requires an always-on infrastructure component; rustpush handles APNs directly on-device).
- Full rustpush FFI for all messaging (rejected for now: would expand native surface beyond notifications; kept as a future option if Kotlin protocol reimplementation stalls).

**Related Artifacts:**

- design.md §2.1–2.5 (IPC protocol, heartbeat, queue management, retry logic)
- docs/schemas/IPCMessage.json (IPC message contract with correlation IDs)
- docs/specs/apns-unifiedpush.async
