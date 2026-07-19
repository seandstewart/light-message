# Architecture Decision Records

## Overview

All structural and technical decisions for the LightOS iMessage Client are documented as Architecture Decision Records (ADRs). Each ADR captures context, decision rationale, and consequences.

## Records

| ADR | Title | Status |
|---|---|---|
| [ADR 001](./ADR-001-kotlin-reimplementation.md) | Pure Kotlin Reimplementation (Path A) | Accepted |
| [ADR 002](./ADR-002-okhttp-client.md) | OkHttp as Sole HTTP Client | Accepted |
| [ADR 003](./ADR-003-plist-codec.md) | Custom Plist Codec over kotlinx.serialization | Accepted |
| [ADR 004](./ADR-004-jce-crypto.md) | Standard Android JCE for Cryptography | Accepted |
| [ADR 005](./ADR-005-unifiedpush-notifications.md) | UnifiedPush for Notification Delivery | Accepted |
| [ADR 006](./ADR-006-room-datastore.md) | Room and DataStore for Persistence | Accepted |
| [ADR 007](./ADR-007-lp3keyboard.md) | Embedded Lp3Keyboard over System IME | Accepted |
| [ADR 008](./ADR-008-workmanager-sync.md) | androidx.work for Background Sync | Accepted |
