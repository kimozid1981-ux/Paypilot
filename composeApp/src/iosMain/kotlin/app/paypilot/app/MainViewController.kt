package app.paypilot.app

import androidx.compose.ui.window.ComposeUIViewController
import app.paypilot.app.platform.MemoryStore
import app.paypilot.app.platform.PlatformServices
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

@OptIn(ExperimentalForeignApi::class)
private fun secureRandom(n: Int): ByteArray {
    val bytes = ByteArray(n)
    if (n == 0) return bytes
    val status = bytes.usePinned { SecRandomCopyBytes(kSecRandomDefault, n.toULong(), it.addressOf(0)) }
    check(status == 0) { "SecRandomCopyBytes failed" }
    return bytes
}

fun MainViewController() = ComposeUIViewController {
    PayPilotApp(
        PlatformServices(
            platformName = "iOS",
            // v0.1: session kept in memory; Keychain storage and Face ID come next.
            secureStore = MemoryStore(),
            biometric = null,
            httpClient = { HttpClient(Darwin) },
            sessionOnly = true,
            randomBytes = ::secureRandom,
        ),
    )
}
