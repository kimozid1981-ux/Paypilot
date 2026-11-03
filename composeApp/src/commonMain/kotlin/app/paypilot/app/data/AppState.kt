package app.paypilot.app.data

import app.paypilot.app.platform.PlatformServices
import app.paypilot.core.model.Client
import app.paypilot.core.model.ComplianceDocument
import app.paypilot.core.model.Invoice
import app.paypilot.core.security.AppLockPolicy
import app.paypilot.core.security.Pbkdf2
import app.paypilot.core.security.constantTimeEquals
import app.paypilot.core.security.toHex
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock

enum class Screen { SIGN_IN, SECOND_FACTOR, SET_PIN, LOCKED, MAIN }

/**
 * Holds the session and the business data. Access tokens live only in memory; the refresh
 * token and the PIN hash go to the platform's protected storage.
 */
class AppState(val platform: PlatformServices) {
    var screen by mutableStateOf(Screen.SIGN_IN)
    var demoMode by mutableStateOf(false)
    var businessName by mutableStateOf("")
    var ownerFirstName by mutableStateOf("")
    var email by mutableStateOf("")
    var twoFactorEnabled by mutableStateOf(false)
    var challengeToken: String? = null
    var serverUrl by mutableStateOf(platform.secureStore.get(KEY_SERVER) ?: DEFAULT_SERVER)

    val clients = mutableStateListOf<Client>()
    val invoices = mutableStateListOf<Invoice>()
    val documents = mutableStateListOf<ComplianceDocument>()

    val lockPolicy = AppLockPolicy()
    private var failedPins = platform.secureStore.get(KEY_FAILED)?.toIntOrNull() ?: 0
    var lastActive = nowSeconds()
    var pinWaitUntil by mutableStateOf(0L)

    private var apiClient: ApiClient? = null
    fun api(): ApiClient = apiClient?.takeIf { it.baseUrl == serverUrl.trimEnd('/') }
        ?: ApiClient(serverUrl, platform.httpClient()).also { apiClient = it }

    val today: LocalDate get() = Clock.System.todayIn(TimeZone.currentSystemDefault())

    fun hasSavedSession() = platform.secureStore.get(KEY_REFRESH) != null && platform.secureStore.get(KEY_PIN) != null

    fun startDemo() {
        demoMode = true
        businessName = "Peinture Atlas"
        ownerFirstName = "Sami"
        email = "demo@paypilot.app"
        loadSample()
        screen = if (platform.secureStore.get(KEY_PIN) == null) Screen.SET_PIN else Screen.MAIN
    }

    fun loadSample() {
        clients.clear(); clients.addAll(SampleData.clients())
        invoices.clear(); invoices.addAll(SampleData.invoices(today))
        documents.clear(); documents.addAll(SampleData.documents(today))
    }

    fun onSignedIn(tokens: TokenDto, me: MeDto?) {
        demoMode = false
        api().accessToken = tokens.accessToken
        platform.secureStore.put(KEY_REFRESH, tokens.refreshToken)
        platform.secureStore.put(KEY_SERVER, serverUrl)
        me?.let { businessName = it.businessName; email = it.email; twoFactorEnabled = it.twoFactorEnabled }
        if (ownerFirstName.isBlank()) ownerFirstName = email.substringBefore('@').substringBefore('.').replaceFirstChar { it.uppercase() }
        // v0.1 keeps business data on the device; server sync arrives with the database.
        if (invoices.isEmpty()) loadSample()
        screen = if (platform.secureStore.get(KEY_PIN) == null) Screen.SET_PIN else Screen.MAIN
    }

    suspend fun resumeSession(): Boolean {
        val refresh = platform.secureStore.get(KEY_REFRESH) ?: return false
        return runCatching {
            val tokens = api().refresh(refresh)
            api().accessToken = tokens.accessToken
            platform.secureStore.put(KEY_REFRESH, tokens.refreshToken)
            val me = api().me()
            businessName = me.businessName; email = me.email; twoFactorEnabled = me.twoFactorEnabled
            if (ownerFirstName.isBlank()) ownerFirstName = email.substringBefore('@').substringBefore('.').replaceFirstChar { it.uppercase() }
            if (invoices.isEmpty()) loadSample()
            true
        }.getOrDefault(false)
    }

    fun setPin(pin: String) {
        val salt = platform.randomBytes(16)
        val hash = Pbkdf2.derive(pin.encodeToByteArray(), salt, PIN_ITERATIONS)
        platform.secureStore.put(KEY_PIN, salt.toHex() + ":" + hash.toHex())
        resetFailures()
        markActive()
        screen = Screen.MAIN
    }

    /** Returns null when unlocked, otherwise a message to show. */
    fun tryPin(pin: String): String? {
        val now = nowSeconds()
        if (pinWaitUntil > now) return "Patientez ${pinWaitUntil - now} s avant de réessayer."
        val stored = platform.secureStore.get(KEY_PIN) ?: return "Aucun code défini."
        val (saltHex, hashHex) = stored.split(':')
        val candidate = Pbkdf2.derive(pin.encodeToByteArray(), saltHex.hexToBytes(), PIN_ITERATIONS).toHex()
        if (constantTimeEquals(candidate, hashHex)) {
            resetFailures(); unlock(); return null
        }
        failedPins += 1
        platform.secureStore.put(KEY_FAILED, failedPins.toString())
        return when (val r = lockPolicy.onFailedAttempt(failedPins)) {
            is AppLockPolicy.AttemptResult.Wrong -> {
                pinWaitUntil = now + r.waitSeconds
                "Code incorrect. ${r.remainingBeforeWipe} essais avant la déconnexion complète."
            }
            AppLockPolicy.AttemptResult.Wipe -> { signOut(); "Trop d'essais : vous avez été déconnecté et les données locales ont été effacées." }
            AppLockPolicy.AttemptResult.Unlocked -> null
        }
    }

    fun unlock() { markActive(); screen = Screen.MAIN }
    fun markActive() { lastActive = nowSeconds() }

    fun checkAutoLock() {
        if (screen == Screen.MAIN && platform.secureStore.get(KEY_PIN) != null && lockPolicy.shouldLock(lastActive, nowSeconds())) {
            screen = Screen.LOCKED
        }
    }

    fun signOut() {
        platform.secureStore.clear()
        platform.secureStore.put(KEY_SERVER, serverUrl)
        apiClient?.accessToken = null
        failedPins = 0
        demoMode = false
        clients.clear(); invoices.clear(); documents.clear()
        screen = Screen.SIGN_IN
    }

    private fun resetFailures() {
        failedPins = 0
        pinWaitUntil = 0
        platform.secureStore.remove(KEY_FAILED)
    }

    fun clientOf(invoice: Invoice): Client = clients.firstOrNull { it.id == invoice.clientId } ?: Client(invoice.clientId, "Client")

    fun updateInvoice(updated: Invoice) {
        val i = invoices.indexOfFirst { it.id == updated.id }
        if (i >= 0) invoices[i] = updated else invoices.add(0, updated)
    }

    companion object {
        const val DEFAULT_SERVER = "https://api.paypilot.example"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_PIN = "pin_hash"
        const val KEY_FAILED = "pin_failures"
        const val KEY_SERVER = "server_url"
        const val PIN_ITERATIONS = 60_000
        fun nowSeconds(): Long = Clock.System.now().epochSeconds
    }
}

private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
