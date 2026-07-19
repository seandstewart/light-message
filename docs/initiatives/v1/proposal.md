# Initiative v1: LightOS iMessage Tool

## Architecture

```
┌───────────────────────────────────────────────┐
│  UI Layer (androidx.compose)                  │
│  ├── ConversationListScreen (LightScreen)     │
│  ├── ThreadScreen (LightScreen, Lp3Keyboard)  │
│  └── SettingsScreen (LightScreen)             │
├───────────────────────────────────────────────┤
│  Presentation Layer (androidx.lifecycle)      │
│  ├── ConversationListViewModel                │
│  ├── ThreadViewModel                          │
│  └── AuthViewModel                            │
├───────────────────────────────────────────────┤
│  Domain Layer (Kotlin-native)                 │
│  ├── RelayService (OkHttp)                    │
│  ├── AppleIdAuth (state machine, HTTP)        │
│  ├── MessageCodec (custom Plist, AES-GCM)     │
│  └── AttachmentManager (OkHttp, kotlinx-io)   │
├───────────────────────────────────────────────┤
│  Native Push Layer (rustpush .so service)     │
│  ├── aps_client (Rust) - APNs TLS connection  │
│  ├── UnifiedPush bridge (Rust)                │
│  └── IPC boundary (Unix domain socket / AIDL) │
├───────────────────────────────────────────────┤
│  Data Layer (Room, DataStore)                 │
│  ├── MessageDao, ThreadDao, ContactDao        │
│  ├── EncryptedTokenStore (DataStore)          │
│  └── Room Database (messages, sessions)       │
└───────────────────────────────────────────────┘
```

### Push Notification Flow

1. **rustpush** (native service) establishes and maintains APNs TLS session after one-time hardware validation.
2. On incoming Apple push, **rustpush UnifiedPush bridge** POSTs the notification payload to the local/self-hosted UnifiedPush distributor endpoint.
3. **UnifiedPush distributor** (lightweight, on-device or local network) delivers the push to the app.
4. **LightOS Kotlin app** receives the push via `org.unifiedpush.android:connector` and routes it to `PushReceiver` -> `MessageRepository` -> UI.

## Milestones

| Phase                                     | Duration  | Deliverable                                                                                                                                                                                                                                                                                                                                                          | Exit Criteria                                                                                                                                                    |
| ----------------------------------------- | --------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **0. SDK Audit & NDK Feasibility**        | 1 week    | Confirm Light SDK native library policy (`.so` loading via `System.loadLibrary`, JNI/NDK restrictions). Verify `OkHttp`, `kotlinx.serialization`, `Room`, `UnifiedPush` connector are available.                                                                                                                                                                     | Whitelist confirmation for NDK/rust native binaries; emulator builds.                                                                                            |
| **1. Protocol Specification**             | 3–4 weeks | Extract and document: Apple ID auth state machine, iMessage envelope format (Plist, AES-GCM), attachment URLs, **Mac relay one-time activation OpenAPI** (retry 3×, backoff), **rustpush APNs/IPC contract** (heartbeat 30s, PONG timeout 5s, queue ≤100, correlation IDs, length-prefixed framing).                                                                 | Spec covers auth, send, receive, reaction, attachment, Mac relay activation, and **rustpush push bridge** paths with IPC unit tests passing.                     |
| **2. Core Infrastructure**                | 3 weeks   | `OkHttp` client, `kotlinx.serialization` codecs (JSON + custom Plist), `Room` schema, `DataStore` encrypted prefs. **rustpush cross-compilation** (`cargo-ndk`) for Android ARM64. **IPC implementation**: length-prefixed frame parsing, correlation ID matching, queue management (≤100 depth, overflow handling).                                                 | Unit tests pass: Plist round-trip, HTTP mock, Room CRUD, **IPC framing parsing, queue overflow rejection**, **rustpush .so loads and initializes**.              |
| **3. rustpush APNs & UnifiedPush Bridge** | 3 weeks   | rustpush native service running on Android; APNs connection established (one-time hardware validation via Mac/NAC). UnifiedPush bridge forwarding notifications with IPC heartbeat (PING/PONG every 30s, 5s timeout). Kotlin `PushReceiver` consuming via `org.unifiedpush.android:connector`. Reconnect on heartbeat failure with exponential backoff (1s–60s cap). | End-to-end: Apple push -> rustpush IPC (with heartbeat validation) -> UnifiedPush -> Kotlin app -> Room entry. **IPC heartbeat recovery tested**.                |
| **4. Auth & Session (Kotlin)**            | 3 weeks   | `AppleIdAuth` Kotlin class: login, 2FA, hardware info provisioning to relay, session token retrieval, **relay retry policy (3 attempts, exponential backoff 1s/2s/4s)**. `AuthViewModel` with `LightScreen`. Persist `sinceToken` for crash recovery.                                                                                                                | Can authenticate with relay and persist session in `DataStore`; **relay timeout triggers retry with backoff; max 3 credential + 3 2FA attempts before lockout**. |
| **5. Messaging Service (Kotlin)**         | 3 weeks   | `MessageCodec` (Plist + AES-GCM with auth tag verification and checksum validation), `RelayService` (HTTP/WebSocket to relay for send/receive). `MessageRepository` with `Flow`; **duplicate send detection (within 30s window)**.                                                                                                                                   | Text message send/receive via relay in integration test; **attachment AES-GCM decryption verified**; **no duplicate sends on client retry**.                     |
| **6. UI & Keyboard**                      | 2 weeks   | `ConversationListScreen`, `ThreadScreen` with `Lp3Keyboard`, `AttachmentViewer`, `SettingsScreen`.                                                                                                                                                                                                                                                                   | Screens functional in emulator.                                                                                                                                  |
| **7. Hardware & E2E Validation**          | 2 weeks   | Light Phone III deployment. Verify rustpush APNs wake, push delivery, message send/receive, battery impact.                                                                                                                                                                                                                                                          | Confirmed end-to-end message with iMessage user on hardware.                                                                                                     |
| **8. Compliance & Submission**            | 1 week    | Open source repo, dependency audit (whitelist + native binary inventory), signed APK submission.                                                                                                                                                                                                                                                                     | Tool queued in Light build pipeline.                                                                                                                             |

## Risk Register

| Risk                                                          | Impact | Mitigation                                                                                                                                                                                         |
| ------------------------------------------------------------- | ------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Light SDK blocks native library loading (JNI/NDK)**         | High   | Phase 0 must confirm `.so` loading is permitted. If blocked, petition Light with rustpush as an open-source Rust dependency, citing the `anki-android-backend` precedent already on the whitelist. |
| **IPC connection loss (heartbeat timeout / socket close)**    | Medium | Implement exponential backoff (1s, 2s, 4s, 8s, cap 60s). Persist `sinceToken` for crash recovery. **Phase 3 gate includes heartbeat validation test**.                                             |
| **Mac relay timeout during activation**                       | Medium | Retry policy: 3 attempts with exponential backoff (1s, 2s, 4s). If all fail, emit `AuthenticationFailed` with reason `RELAY_UNAVAILABLE`. Fallback to HTTP long-polling if APNs unavailable.       |
| **rustpush APNs connection fails on Light Phone III network** | Medium | rustpush uses standard TLS to Apple servers. Test on hardware early (Phase 3). Fallback to HTTP long-polling via Mac relay if APNs is blocked by carrier/firewall.                                 |
| **UnifiedPush distributor not available on-device**           | Medium | Bundle a lightweight distributor (e.g., UP-FCM-Distributor fork, or a minimal Rust-based distributor) with the app, or run it as a local service.                                                  |
| **Plist binary format too complex for custom Kotlin parser**  | High   | Start with XML plist. Defer binary plist to Phase 5. If binary plist is mandatory for message payloads, increase Phase 1 by 1 week.                                                                |
| **`javax.crypto` lacks Apple-specific cipher modes**          | Medium | Use standard AES-GCM. If deprecated algorithms are required for legacy auth, petition with specific artifact.                                                                                      |
| **Mac relay dependency for hardware validation**              | Medium | rustpush reduces the relay to one-time activation (NAC or `open-absinthe`). Document this requirement clearly.                                                                                     |

## Dependencies

| Artifact                                      | Function                  | Notes                                                                         |
| --------------------------------------------- | ------------------------- | ----------------------------------------------------------------------------- |
| `com.squareup.okhttp3:okhttp`                 | HTTP/WebSocket client     | Selected over `io.ktor` to avoid modular whitelist mismatches.                |
| `org.jetbrains.kotlinx:kotlinx-serialization` | JSON/Proto serialization  | Custom Plist codec built on top.                                              |
| `androidx.room`                               | Message/thread cache      | With `kotlinx-coroutines` Flow.                                               |
| `androidx.datastore`                          | Encrypted secrets         | Auth tokens, session keys.                                                    |
| `org.unifiedpush.android:connector`           | Push notification receipt | Consumes rustpush-forwarded Apple pushes.                                     |
| `com.thelightphone.lp3keyboard`               | Embedded text input       | `Lp3Keyboard` composable in thread screen.                                    |
| **rustpush (native)**                         | APNs client, push bridge  | Compiled via `cargo-ndk` to `arm64-v8a` `.so`. JNI/Unix socket IPC to Kotlin. |
| **UnifiedPush distributor**                   | Push routing              | Either bundled lightweight distributor or local bridge.                       |

## Decision Gateways

1. **Gate 0 (End of Phase 0):** If Light SDK blocks NDK/native `.so` loading, halt. rustpush cannot be integrated.
2. **Gate 1 (End of Phase 1):** If rustpush's APNs/IPC contract or the OpenBubbles message codec cannot be fully documented, halt. **IPC framing unit tests must pass** (heartbeat, correlation IDs, queue depth, frame parsing).
3. **Gate 2 (End of Phase 3):** If rustpush native service cannot receive an Apple push and forward it via UnifiedPush to Kotlin `PushReceiver` -> Room, halt. **Full path validation required:** Apple push → rustpush IPC → UnifiedPush POST → UnifiedPush connector → `PushReceiver` → Room entry.
4. **Gate 3 (End of Phase 5):** If a text message cannot be sent/received via the Kotlin relay client, halt. **Verify:** message codec complete, relay retry policy functional (3×, backoff), **attachment AES-GCM decryption verified**.

## References

- **ADR 001–008:** See Architecture Decision Records (ADR 005 updated above).
- **ADR 005:** rustpush -> UnifiedPush bridge specification.
- Light SDK dependency whitelist: `LightSdkPlugin.kt` lines 17–37
- rustpush/OpenBubbles integration research: `assistant.kagi.com/share/082d48c0-ffda-4749-8582-fe22fbc81a07`
- OpenBubbles source structure: `github.com/OpenBubbles/openbubbles-app`
- Light SDK: `github.com/lightphone/light-sdk`
- Light keyboard: `github.com/lightphone/light-keyboard`
