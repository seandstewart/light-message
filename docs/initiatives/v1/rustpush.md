# Bridging rustpush into UnifiedPush

## Correct Architecture

rustpush should act as a **local UnifiedPush distributor**. Your app already implements the UnifiedPush app-side connector. rustpush maintains the APNs connection and delivers iMessage payloads through the standard UnifiedPush `MESSAGE` broadcast mechanism. Your existing push receiver processes them without knowing the source changed.

```
rustpush native (APNs TLS) → JNI callback → UP Distributor Service → MESSAGE broadcast → App's UP Receiver
```

## Why This Works

- **UnifiedPush is source-agnostic.** The app-side connector only cares about receiving `org.unifiedpush.android.connector.MESSAGE` broadcasts with a `byte[]` payload.
- **APNs is the push server.** In normal UnifiedPush, the distributor connects to a remote push server (ntfy, Gotify, etc.). Here, rustpush connects directly to APNs over TLS. The "server" is just Apple's infrastructure instead of a self-hosted one.
- **Battery optimization is already solved.** rustpush runs as a foreground service with a persistent socket. This is identical to how ntfy or Gotify distributors operate.

## Implementation Details

### 1. Native Layer: rustpush as a Background Service

OpenBubbles already does this. When the main app closes, only the Rust service runs:

> "When the main app is closed, the only code running is a rust service, keeping the socket open and staying on top of registration renewals." [^1]

Your JNI wrapper exposes three functions:

```rust
// Rust side (exposed via cbindgen/ffi)
pub extern "C" fn rustpush_start(
    credentials_ptr: *const c_char,
    callback: extern "C" fn(msg_ptr: *const u8, len: usize),
) -> i32;

pub extern "C" fn rustpush_send(
    handle: i32,
    recipient: *const c_char,
    body: *const u8,
    len: usize,
) -> i32;

pub extern "C" fn rustpush_stop(handle: i32);
```

`rustpush_start` initiates the APNs TLS handshake via `aps_client.rs`, handles IDS registration via `ids/identity_manager.rs`, and spawns a background thread that blocks on the APNs socket. When a message arrives, it deserializes the iMessage payload and invokes the Java callback.

### 2. Java/Kotlin: UnifiedPush Distributor Service

Declare a `PushService` that extends the UnifiedPush distributor base class:

```kotlin
class RustPushDistributorService : PushService() {
    private external fun nativeStart(callback: MessageCallback): Int
    private external fun nativeSend(handle: Int, recipient: String, body: ByteArray): Int
    private external fun nativeStop(handle: Int)

    companion object {
        init { System.loadLibrary("rustpush") }
    }

    override fun onMessage(message: ByteArray, instance: String) {
        // This is called when the *distributor* receives from upstream.
        // For rustpush, "upstream" is the JNI callback.
        // Not used directly; we trigger broadcasts manually.
    }

    override fun onNewEndpoint(endpoint: String, instance: String) {
        // Return a dummy localhost endpoint or a unique URI
        // indicating this is a local rustpush source.
        // The app server never pushes to it; APNs is the real source.
        sendRegistrationChanged(endpoint, instance)
    }

    fun deliverToApp(payload: ByteArray) {
        // Broadcast to the app's UnifiedPush receiver
        val intent = Intent("org.unifiedpush.android.connector.MESSAGE")
        intent.`package` = applicationContext.packageName
        intent.putExtra("message", payload)
        intent.putExtra("instance", "imessage")
        sendBroadcast(intent)
    }
}
```

The `MessageCallback` interface bridges Rust → Java:

```kotlin
interface MessageCallback {
    fun onMessage(payload: ByteArray)
}

// Passed to Rust via JNI; Rust invokes this when APNs delivers
class RustMessageCallback(private val service: RustPushDistributorService) : MessageCallback {
    override fun onMessage(payload: ByteArray) {
        service.deliverToApp(payload)
    }
}
```

### 3. App-Side: Existing UnifiedPush Receiver

Your app already has this. No changes needed:

```kotlin
class MyPushService : PushService() {
    override fun onMessage(message: ByteArray, instance: String) {
        if (instance == "imessage") {
            val imessage = parseIMessage(message) // your existing parser
            showNotification(imessage)
        } else {
            // handle other UnifiedPush sources
        }
    }
}
```

### 4. Lifecycle & Registration

On app startup:

1. **App registers with distributor.** Your app calls `UnifiedPush.register()` targeting `RustPushDistributorService`.
2. **Distributor starts rustpush.** `RustPushDistributorService` spawns the native service, calls `rustpush_start()`, and returns a dummy endpoint.
3. **APNs connection persists.** The Rust thread holds the TLS socket. If the OS kills the service, Android's `JobScheduler` or `WorkManager` restarts it.
4. **Message arrives.** Rust parses the APNs payload, extracts the iMessage, serializes it to JSON/protobuf, and invokes the JNI callback.
5. **Distributor broadcasts.** Java receives the callback and sends the `MESSAGE` broadcast.
6. **App processes.** Your `MyPushService` receives the broadcast and handles the message.

### 5. Handling the "Endpoint" Problem

In standard UnifiedPush, the app server POSTs to the endpoint URL to trigger a push. For iMessage, there is no app server — Apple pushes directly to your device via APNs.

Options:

| Approach           | Description                                                                                                                                         |
| ------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Dummy endpoint** | Return `http://localhost/rustpush/imessage` to satisfy the protocol. The app never uses it.                                                         |
| **No endpoint**    | Skip `onNewEndpoint` entirely and directly bind the app to the distributor via shared preferences. This violates the spec but works internally.     |
| **Hybrid**         | Use the dummy endpoint for spec compliance, but also store the callback handle locally so the distributor knows which app instance to broadcast to. |

Recommended: Dummy endpoint. The spec is satisfied; the implementation is simple.

## Data Flow for Sending

Sending reverses the flow:

```
App UI → ViewModel → rustpush_send() (JNI) → Rust IDS lookup → APNs TLS write
```

Your app does not need UnifiedPush for sending. The JNI bridge is direct.

## Trade-offs

| Aspect            | Impact                                                                                                                                  |
| ----------------- | --------------------------------------------------------------------------------------------------------------------------------------- |
| **Binary size**   | +15-30MB for Rust libraries, unicorn-engine (if using `open-absinthe`), and OpenSSL.                                                    |
| **Battery**       | Rust service holds a TLS socket. Comparable to ntfy distributor. APNs uses low-power keepalive.                                         |
| **Memory**        | Rust stack + Kotlin stack. The APNs connection alone is lightweight; `open-absinthe` emulation is CPU-heavy but only during activation. |
| **Complexity**    | You now maintain JNI bindings and a Rust toolchain. Build times increase.                                                               |
| **Apple Silicon** | If the device has no Intel hardware info, `open-absinthe` NAC relay still requires a one-time Mac interaction.                          |

## Critical Implementation Notes

1. **The Rust callback must be thread-safe.** APNs delivers messages on the socket thread. Use `AttachCurrentThread` in JNI before calling into Java.
2. **Use a foreground service with a notification.** Android 8+ will kill background services. The rustpush service must be `startForeground()` with a persistent notification.
3. **Serialize the iMessage payload carefully.** Rust's `rustpush` crate has its own message types. Define a flat protobuf or JSON schema for the JNI boundary to avoid marshaling complex Rust structs.
4. **Handle APNs reconnection.** rustpush's `aps_client.rs` should manage reconnect and token refresh. Your Java layer only needs to know about connect/disconnect state for UI.
5. **Registration renewal.** IDS identities expire. rustpush must periodically re-register. The background service handles this without waking the app UI.

## Build Integration

Add to your `build.gradle`:

```kotlin
android {
    sourceSets {
        main {
            jniLibs.srcDirs = ['src/main/jniLibs']
        }
    }
}

// Build rustpush as a static or shared library
// via cargo-ndk or a custom build script
```

Use `cargo-ndk` to cross-compile rustpush for Android targets (`aarch64-linux-android`, `armv7-linux-androideabi`).

## Summary

Bridge rustpush into your UnifiedPush integration by making rustpush a **local distributor**. The native Rust layer maintains the APNs connection. When a message arrives, the JNI callback triggers a UnifiedPush `MESSAGE` broadcast to your app. Your existing push receiver processes it unchanged. Sending bypasses UnifiedPush entirely and goes directly through the JNI bridge to rustpush's IDS/APNs stack.

**References**

[^1]: [How OpenBubbles Hosted Works](https://openbubbles.app/blog/openbubbles_hosted.html)
