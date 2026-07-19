# Codespec Index — LightOS iMessage Client

This directory contains detailed technical specifications for each milestone of the LightOS iMessage client project.

## Overview

Each milestone specification defines:

- **Formal Requirements** — goals, scope in/out, invariants
- **Data Model** — entities, relationships, schema
- **Code Architecture** — components, interfaces, patterns
- **Component Interactions** — workflows (send, receive, auth, sync, etc.)
- **Stateful Behavior** — state machines and lifecycle management
- **Algorithmic Logic** — core algorithms and procedures
- **Test Matrix** — unit, integration, edge case, invariant coverage
- **Task Dependencies** — breakdown of work with effort estimates
- **Implementation Timeline** — Gantt chart with internal stories (S1–S5)
- **Revision History** — version tracking and change log

## Milestone Specs

### [Milestone 2: Core Service & Data Model](./milestone-2.md)

**Scope:** Foundation layers — Room database, encryption, codec, auth state machine, relay connection, message encryption/decryption.

**Stories (S1–S5):**

- S1: Data Layer — Room schema, DAO, encrypted token store
- S2: Crypto & Codec — Plist codec, JCE crypto engine
- S3: Core Services — Auth, relay, message codec, native IPC
- S4: Push & Sync — Push receiver, background sync
- S5: Milestone 2 Review — API docs, ADR updates, specification review

**Deliverables:** 14 tasks, 56 hours, 14 artifacts (schema, repositories, crypto, codecs, services).

---

### [Milestone 3: rustpush APNs & UnifiedPush Bridge](./milestone-3.md)

**Scope:** Native integration — `rustpush` service deployment, APNs TLS connection, UnifiedPush bridge, Kotlin IPC client, push handler.

**Stories (S1–S5):**

- S1: Native Service Deployed — rustpush build, APKs, activation
- S2: IPC & Heartbeat Stable — JSON-RPC framing, 30s heartbeat, reconnect
- S3: Auth Delegation Complete — relay activation, cert storage
- S4: UnifiedPush Bridge Live — push routing, message insertion, room persistence
- S5: Milestone 3 Review — native service integration docs, ADR updates

**Deliverables:** 13 tasks, 52 hours, 13 artifacts (native client, IPC, push handler, service contract).

---

### [Milestone 4: UI & Compose Layer](./milestone-4.md)

**Scope:** User interface — conversation list, thread detail, message input, attachment preview, compose flows.

**Stories (S1–S5):**

- S1: Conversation List UI — fetch threads, infinite scroll, unread badge
- S2: Thread Detail UI — message history, inline attachments, typing indicator
- S3: Compose & Send UI — message input, attachment picker, rich formatting
- S4: Navigation & State — NavController setup, deep links, backstack
- S5: Milestone 4 Review — accessibility audit, UI test coverage, design review

**Deliverables:** TBD (UI components, composables, navigation graphs, tests).

---

### [Milestone 5: Attachments & Media Pipeline](./milestone-5.md)

**Scope:** File handling — attachment download/upload, image preview/thumbnail, video streaming, gallery integration, storage cleanup.

**Stories (S1–S5):**

- S1: Attachment Storage & Metadata — local file cache, expiry tracking
- S2: Download Pipeline — async download, resume, progress streaming
- S3: Upload Pipeline — async upload, encryption, progress tracking
- S4: Media Preview — image scaling, video thumbnail, gallery binding
- S5: Milestone 5 Review — media library audit, storage test coverage, performance review

**Deliverables:** TBD (download/upload workers, media codecs, preview cache, storage managers).

---

### [Milestone 6: Hardening & Production](./milestone-6.md)

**Scope:** Quality & resilience — performance optimization, crash handling, analytics, security audit, release preparation.

**Stories (S1–S5):**

- S1: Crash & ANR Handling — Crashlytics integration, thread monitoring, stale connection detection
- S2: Performance Baseline — profiling, GC tuning, memory leak detection
- S3: Security Audit — dependency scanning, certificate pinning, keystore validation
- S4: Analytics & Instrumentation — user session tracking, feature usage, performance metrics
- S5: Milestone 6 Review — release notes, CI/CD verification, app store submission

**Deliverables:** TBD (monitoring infrastructure, baseline metrics, security scan results, release bundle).

---

## Navigation

- **Back to Project:** [v1 Project Overview](../index.md)
- **Architecture Decisions:** [Architecture Decision Records](../../adrs/)
- **Project Status:** [Current Milestone & Progress](../index.md#current-milestone)

## Legend: Internal Stories (S1–S5)

Each milestone's **Implementation Timeline** Gantt chart includes **5 internal stories**:

- **S1–S4:** Logical phase gates for progress tracking within the milestone (not external milestones)
- **S5:** Milestone completion checkpoint (review, documentation, handoff)

These stories help visualize task grouping and phase dependencies. They are **not** the same as the larger project milestones (M2–M6); they are internal checkpoints specific to each milestone spec.
