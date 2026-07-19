# ADR 007: Embedded Lp3Keyboard over System IME

**Status:** Accepted

**Context:**  
LightOS tools do not use the Android system Input Method Editor (IME). The `com.thelightphone.lp3keyboard` library is whitelisted and provides two modes: `Lp3Keyboard` (embedded, no chrome) and `Lp3KeyboardWrapper` (self-contained with dismiss UI).

**Decision:**  
Use the `Lp3Keyboard` embedded composable inside the `ThreadScreen`. All input logic (key entry, backspace, send) will be handled via the `Lp3KeyboardCallback` interface.

**Consequences:**
- **Positive:** No system navigation or IME chrome; full control over the keyboard's appearance and behavior within the Light theme.
- **Negative:** Must manually implement every input action; no free autocorrect or gesture typing from the OS.
