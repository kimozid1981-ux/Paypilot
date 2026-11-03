package app.paypilot.app

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.paypilot.app.platform.MemoryStore
import app.paypilot.app.platform.PlatformServices
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.security.SecureRandom

fun main() = application {
    val random = SecureRandom()
    val services = PlatformServices(
        platformName = "Desktop",
        // v0.1: the session is kept in memory only on Windows/macOS/Linux. Windows Credential
        // Manager / macOS Keychain storage comes next.
        secureStore = MemoryStore(),
        biometric = null,
        httpClient = { HttpClient(OkHttp) },
        sessionOnly = true,
        randomBytes = { n -> ByteArray(n).also(random::nextBytes) },
    )
    Window(onCloseRequest = ::exitApplication, title = "PayPilot", state = rememberWindowState(width = 460.dp, height = 860.dp)) {
        PayPilotApp(services)
    }
}
