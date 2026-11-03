package app.paypilot.core.security

/**
 * Local app lock: after inactivity the app asks for biometrics or the PIN again, and repeated
 * wrong PINs trigger a growing wait, then a full sign-out (data wiped from the device).
 */
class AppLockPolicy(
    val inactivityTimeoutSeconds: Long = 120,
    private val freeAttempts: Int = 3,
    private val wipeAfterAttempts: Int = 10,
) {
    sealed interface AttemptResult {
        data object Unlocked : AttemptResult
        data class Wrong(val remainingBeforeWipe: Int, val waitSeconds: Long) : AttemptResult
        data object Wipe : AttemptResult
    }

    fun shouldLock(lastActiveEpochSeconds: Long, nowEpochSeconds: Long): Boolean =
        nowEpochSeconds - lastActiveEpochSeconds >= inactivityTimeoutSeconds

    fun onFailedAttempt(failuresIncludingThis: Int): AttemptResult {
        if (failuresIncludingThis >= wipeAfterAttempts) return AttemptResult.Wipe
        val extra = failuresIncludingThis - freeAttempts
        val wait = if (extra <= 0) 0L else minOf(30L * (1L shl (extra - 1)), 15 * 60L)
        return AttemptResult.Wrong(wipeAfterAttempts - failuresIncludingThis, wait)
    }
}
