package app.paypilot.app.platform

import io.ktor.client.HttpClient

/** Key-value storage backed by the platform's protected storage (Android Keystore, etc.). */
interface SecureStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
    fun clear()
}

/** Fingerprint / face unlock. Null when the device has none or the platform is not wired yet. */
interface BiometricAuth {
    fun authenticate(reason: String, onResult: (Boolean) -> Unit)
}

class PlatformServices(
    val platformName: String,
    val secureStore: SecureStore,
    val biometric: BiometricAuth?,
    val httpClient: () -> HttpClient,
    /** True when the session is kept only while the app runs (no protected storage yet). */
    val sessionOnly: Boolean = false,
    val randomBytes: (Int) -> ByteArray,
)

/** Fallback store that keeps everything in memory only. */
class MemoryStore : SecureStore {
    private val map = mutableMapOf<String, String>()
    override fun get(key: String) = map[key]
    override fun put(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
    override fun clear() = map.clear()
}
