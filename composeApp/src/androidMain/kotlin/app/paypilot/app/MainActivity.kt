package app.paypilot.app

import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import app.paypilot.app.platform.BiometricAuth
import app.paypilot.app.platform.PlatformServices
import app.paypilot.app.platform.SecureStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import java.security.SecureRandom

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Hide content from screenshots, screen recording and the recent-apps preview.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        val random = SecureRandom()
        val services = PlatformServices(
            platformName = "Android",
            secureStore = EncryptedStore(applicationContext),
            biometric = if (BiometricManager.from(this).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS) AndroidBiometric(this) else null,
            httpClient = { HttpClient(Android) },
            randomBytes = { n -> ByteArray(n).also(random::nextBytes) },
        )
        setContent { PayPilotApp(services) }
    }
}

/** SharedPreferences encrypted with a key held in the Android Keystore (AES-256-GCM). */
private class EncryptedStore(context: Context) : SecureStore {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "paypilot_secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun remove(key: String) { prefs.edit().remove(key).apply() }
    override fun clear() { prefs.edit().clear().apply() }
}

private class AndroidBiometric(private val activity: AppCompatActivity) : BiometricAuth {
    override fun authenticate(reason: String, onResult: (Boolean) -> Unit) {
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(true)
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onResult(false)
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("PayPilot")
                .setSubtitle(reason)
                .setAllowedAuthenticators(BIOMETRIC_STRONG)
                .setNegativeButtonText("Utiliser le code")
                .build(),
        )
    }
}
