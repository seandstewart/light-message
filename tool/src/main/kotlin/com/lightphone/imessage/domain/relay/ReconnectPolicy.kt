package com.lightphone.imessage.domain.relay

/**
 * Backoff policy for WebSocket reconnection attempts. Implements exponential backoff: 2^attempt *
 * baseDelayMs, capped at maxDelayMs. Spec: milestone-2.md § 6.4 (Relay Reconnect with Backoff).
 *
 * Example with defaults:
 * - Attempt 0: 1s (2^0 * 1000ms)
 * - Attempt 1: 2s (2^1 * 1000ms)
 * - Attempt 2: 4s (2^2 * 1000ms)
 * - Attempt 3: 8s (2^3 * 1000ms)
 * - Attempt 4: 16s (2^4 * 1000ms)
 * - Attempt 5+: 32s (capped)
 */
data class ReconnectPolicy(
    val maxAttempts: Int = 5,
    val baseDelayMs: Long = 1000, // 1s initial backoff
    val maxDelayMs: Long = 32000, // 32s cap
) {
    /**
     * Compute delay in milliseconds for a given retry attempt. Uses formula: baseDelayMs * (2 ^
     * attempt), capped at maxDelayMs.
     *
     * @param attempt Retry attempt number (0-based)
     * @return Delay in milliseconds
     */
    fun getDelayMs(attempt: Int): Long {
        // Clamp exponent to 5 to prevent overflow: 2^5 = 32, and 32 * 1000 = 32000
        val exponent = attempt.coerceAtMost(5)
        val delay = baseDelayMs * (1L shl exponent) // 1 << exponent = 2^exponent
        return delay.coerceAtMost(maxDelayMs)
    }

    /**
     * Check if retry is allowed for a given attempt.
     * @param attempt Attempt number (0-based)
     * @return true if attempt < maxAttempts
     */
    fun shouldRetry(attempt: Int): Boolean = attempt < maxAttempts
}
