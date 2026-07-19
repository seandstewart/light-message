# ADR 001: Pure Kotlin Reimplementation (Path A)

**Status:** Accepted

**Context:**
OpenBubbles is a monolithic Flutter application with a Rust core. It is not a distributable library. The Light SDK explicitly forbids arbitrary native libraries and whitelists only specific JVM dependencies. Extracting and linking the Rust core via JNI or FFI would require both decoupling it from Flutter-Rust-Bridge and obtaining an explicit native-code exemption.

**Decision:**
Reimplement the Apple ID authentication state machine, iMessage envelope codec, and relay client protocol entirely in Kotlin within the Light SDK's permitted dependency surface.

**Consequences:**

- **Positive:** Full compliance with Light SDK policy; no native build complexity; debuggable with standard Android tooling.
- **Negative:** High implementation cost; ~~protocol must be reverse-engineered from the OpenBubbles Rust source rather than imported~~ **[SUPERSEDED by ADR-005: rustpush handles APNs + native crypto; Kotlin implements messaging codec + auth state machine only. Protocol specs now documented in design.md §2–8 + JSON schemas.]**
