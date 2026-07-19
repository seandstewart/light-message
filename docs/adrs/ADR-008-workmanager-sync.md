# ADR 008: androidx.work for Background Sync

**Status:** Accepted

**Context:**  
When the WebSocket is disconnected or the device is dozing, the tool must still retrieve messages. The `androidx.work` library is whitelisted.

**Decision:**  
Use `WorkManager` for periodic background sync tasks (e.g., relay health check, message polling) when the push channel is unavailable. WebSocket connectivity will be managed by `OkHttp` in the foreground.

**Consequences:**
- **Positive:** Reliable, battery-aware deferred execution; standard Android architecture.
- **Negative:** Aggressive polling intervals will drain battery; must default to the push channel and treat WorkManager as a fallback only.
