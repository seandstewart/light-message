package com.lightphone.imessage.data.provisioning

/**
 * HTTPS client interface for the hardware provisioning / activation endpoint.
 *
 * Handles registering a device with the relay after successful 2FA and polling Apple's servers for
 * activation status. Distinct from `domain.native.INativeServiceClient`, which speaks to a local
 * IPC (Unix domain) socket exposed by the Rust native service.
 *
 * TODO(provisioning): no production implementation exists yet — only test mocks reference this
 * interface today. Wire up a real HTTP client (OkHttp / Ktor) once the provisioning endpoint spec
 * is finalized. Tracked as a known gap outside of the auth-refactor scope.
 */
interface IProvisioningClient {
    /**
     * Registers the hardware device with the relay after successful 2FA.
     *
     * This call triggers:
     * - Generate or retrieve device certificate
     * - Register certificate with server
     * - Set up push notification endpoint
     * - Initialize IDS identity if needed
     *
     * @param sessionToken Valid session token from relay
     * @param email Apple ID email (for identification)
     * @return Result containing device certificate/ID or error details
     */
    suspend fun registerHardware(
            sessionToken: String,
            email: String,
    ): Result<HardwareInfo>

    /**
     * Polls for activation status from Apple's servers. Checks if the device certificate has been
     * signed and is ready for use.
     *
     * @param deviceId Device identifier assigned during registration
     * @param maxAttempts Maximum number of poll attempts (typically 30–60)
     * @param pollIntervalMs Delay between polls in milliseconds (typically 1000–2000)
     * @return Result containing activation confirmation or timeout/error
     */
    suspend fun pollActivationStatus(
            deviceId: String,
            maxAttempts: Int = 30,
            pollIntervalMs: Long = 1000,
    ): Result<ActivationStatus>
}

/** Represents successfully provisioned hardware. */
data class HardwareInfo(val deviceId: String, val certificateData: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HardwareInfo) return false
        if (deviceId != other.deviceId) return false
        if (!certificateData.contentEquals(other.certificateData)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = deviceId.hashCode()
        result = 31 * result + certificateData.contentHashCode()
        return result
    }
}

/** Represents activation status from Apple's servers. */
sealed class ActivationStatus {
    /** Device is activated and ready to use. */
    object Activated : ActivationStatus()

    /** Still waiting for activation (polling should continue). */
    object Pending : ActivationStatus()

    /**
     * Activation failed or was rejected.
     * @param reason Error description
     */
    data class Failed(val reason: String) : ActivationStatus()
}
