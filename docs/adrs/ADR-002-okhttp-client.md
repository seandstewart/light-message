# ADR 002: OkHttp as Sole HTTP Client

**Status:** Accepted

**Context:**  
Both `com.squareup.okhttp3:okhttp` and `io.ktor` are listed in the `LightSdkPlugin` whitelist. However, `Ktor` is a highly modular ecosystem (`ktor-client-core`, `ktor-client-okhttp`, `ktor-serialization`, `ktor-websockets`, etc.), and only the top-level `io.ktor` group is whitelisted. Individual sub-artifacts may fail the plugin's exact-match dependency check.

**Decision:**  
Use `OkHttp` 4.x as the exclusive HTTP, HTTPS, and WebSocket client. Do not introduce any `Ktor` artifacts.

**Consequences:**
- **Positive:** Minimal dependency surface; one artifact covers HTTP/2, WebSocket, and connection pooling.
- **Negative:** Less idiomatic Kotlin coroutine integration than Ktor; requires manual `suspend` wrappers around `OkHttp` callbacks.
