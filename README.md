# Light iMessage Android App

Kotlin/Compose implementation of iMessage client for Light Phone III.

## Architecture

- **UI Layer:** Jetpack Compose + LightScreen components
- **Presentation:** ViewModels + Flow-based state management
- **Domain:** Message codec (Plist + AES-GCM), relay client, auth state machine
- **Data:** Room database + encrypted DataStore
- **Native:** rustpush IPC bridge via Unix socket

## Build

```bash
mise build
```

## Phases

| Phase | Component                   | Status      |
| ----- | --------------------------- | ----------- |
| 0     | SDK audit & NDK feasibility | Planned     |
| 1     | Protocol specification      | In Progress |
| 2     | Core infrastructure         | Planned     |
| 3     | rustpush & UnifiedPush      | Planned     |
| 4     | Auth & session              | Planned     |
| 5     | Messaging service           | Planned     |
| 6     | UI & keyboard               | Planned     |
| 7     | Hardware validation         | Planned     |
| 8     | Compliance & submission     | Planned     |

## Dependencies

- OkHttp (HTTP/WebSocket)
- kotlinx-serialization (JSON codec)
- Room + DataStore (persistence)
- Compose (UI framework)
- UnifiedPush connector (push distribution)
- rustpush native library (APNs + IPC)
