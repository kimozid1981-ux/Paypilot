package app.paypilot.app.ui

import app.paypilot.app.data.ApiException
import app.paypilot.app.data.AppState
import app.paypilot.app.data.Screen
import app.paypilot.core.security.PasswordPolicy
import app.paypilot.core.security.PasswordStrength
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
private fun AuthFrame(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 420.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Spacer(Modifier.height(24.dp))
            Text("PayPilot", fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text("Faites-vous payer, sans courir après vos clients.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
fun SignInScreen(state: AppState) {
    var createAccount by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var business by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showServer by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        if (state.hasSavedSession()) state.screen = Screen.LOCKED
    }

    AuthFrame {
        OutlinedTextField(email, { email = it.trim() }, Modifier.fillMaxWidth(), label = { Text("E-mail") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Mot de passe") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        if (createAccount) {
            val check = PasswordPolicy.check(password, listOf(email, business))
            val (label, progress) = when (check.strength) {
                PasswordStrength.TOO_WEAK -> "Trop faible" to 0.15f
                PasswordStrength.WEAK -> "Faible" to 0.4f
                PasswordStrength.GOOD -> "Bon" to 0.7f
                PasswordStrength.STRONG -> "Excellent" to 1f
            }
            LinearProgressIndicator(progress = { if (password.isEmpty()) 0f else progress }, modifier = Modifier.fillMaxWidth())
            Text(if (password.isEmpty()) "Astuce : une phrase de 4 ou 5 mots est facile à retenir et très solide." else "$label. ${check.problems.joinToString(" ")}",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(business, { business = it }, Modifier.fillMaxWidth(), label = { Text("Nom de l'entreprise") }, singleLine = true)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp) }
        Button(
            onClick = {
                error = null
                if (createAccount && !PasswordPolicy.check(password, listOf(email, business)).accepted) {
                    error = "Choisissez un mot de passe plus solide."; return@Button
                }
                busy = true
                scope.launch {
                    try {
                        val api = state.api()
                        if (createAccount) {
                            val tokens = api.register(email, password, business)
                            api.accessToken = tokens.accessToken
                            state.onSignedIn(tokens, runCatching { api.me() }.getOrNull())
                        } else {
                            val result = api.login(email, password)
                            when (result.status) {
                                "ok" -> { val t = result.tokens!!; api.accessToken = t.accessToken; state.onSignedIn(t, runCatching { api.me() }.getOrNull()) }
                                "second_factor_required" -> { state.challengeToken = result.challengeToken; state.screen = Screen.SECOND_FACTOR }
                                "locked" -> error = "Trop de tentatives. Réessayez dans ${(result.retryAfterSeconds ?: 900) / 60} min."
                                else -> error = "Connexion impossible."
                            }
                        }
                    } catch (e: ApiException) {
                        error = e.message
                    } catch (e: Exception) {
                        error = "Serveur injoignable. Vérifiez votre connexion ou l'adresse du serveur."
                    } finally {
                        busy = false
                        password = ""
                    }
                }
            },
            enabled = !busy && email.isNotBlank() && password.isNotBlank() && (!createAccount || business.isNotBlank()),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text(if (createAccount) "Créer mon compte" else "Se connecter") }
        TextButton(onClick = { createAccount = !createAccount; error = null }, modifier = Modifier.fillMaxWidth()) {
            Text(if (createAccount) "J'ai déjà un compte" else "Créer un compte")
        }
        OutlinedButton(onClick = { state.startDemo() }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text("Essayer en mode démo (sans compte)")
        }
        TextButton(onClick = { showServer = !showServer }) { Text("Paramètres avancés") }
        if (showServer) {
            OutlinedTextField(state.serverUrl, { state.serverUrl = it.trim() }, Modifier.fillMaxWidth(),
                label = { Text("Adresse du serveur (https://)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        }
    }
}

@Composable
fun SecondFactorScreen(state: AppState) {
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AuthFrame {
        Text("Validation en deux étapes", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text("Entrez le code à 6 chiffres affiché dans votre application d'authentification.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, Modifier.fillMaxWidth(), label = { Text("Code") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = {
            scope.launch {
                try {
                    val api = state.api()
                    val t = api.verifySecondFactor(state.challengeToken.orEmpty(), code)
                    api.accessToken = t.accessToken
                    state.onSignedIn(t, runCatching { api.me() }.getOrNull())
                } catch (e: ApiException) { error = e.message } catch (e: Exception) { error = "Serveur injoignable." }
            }
        }, enabled = code.length == 6, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Valider") }
        TextButton(onClick = { state.screen = Screen.SIGN_IN }) { Text("Retour") }
    }
}

@Composable
fun SetPinScreen(state: AppState) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AuthFrame {
        Text("Choisissez un code de déverrouillage", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text("6 chiffres. Il protège l'app sur cet appareil quand vous ne l'utilisez pas" +
            (if (state.platform.biometric != null) ", en plus de l'empreinte ou du visage." else "."), color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(6) }, Modifier.fillMaxWidth(), label = { Text("Code") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
        OutlinedTextField(confirm, { confirm = it.filter(Char::isDigit).take(6) }, Modifier.fillMaxWidth(), label = { Text("Confirmez le code") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = {
            error = when {
                pin != confirm -> "Les deux codes sont différents."
                pin.toSet().size == 1 || "0123456789".contains(pin) || "9876543210".contains(pin) -> "Code trop simple."
                else -> { state.setPin(pin); null }
            }
        }, enabled = pin.length == 6 && confirm.length == 6, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Enregistrer") }
    }
}

@Composable
fun LockedScreen(state: AppState) {
    var pin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var resuming by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun afterUnlock() {
        if (state.demoMode || state.invoices.isNotEmpty()) { state.unlock(); return }
        resuming = true
        scope.launch {
            val ok = state.resumeSession()
            resuming = false
            if (ok) state.unlock() else { message = "Session expirée, reconnectez-vous."; state.signOut() }
        }
    }

    LaunchedEffect(Unit) {
        state.platform.biometric?.authenticate("Déverrouiller PayPilot") { ok -> if (ok) afterUnlock() }
    }
    AuthFrame {
        Text("PayPilot est verrouillé", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(6) }, Modifier.fillMaxWidth(), label = { Text("Code à 6 chiffres") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = {
            val result = state.tryPin(pin)
            pin = ""
            if (result == null) afterUnlock() else message = result
        }, enabled = pin.length == 6 && !resuming, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(if (resuming) "Connexion…" else "Déverrouiller") }
        state.platform.biometric?.let { bio ->
            OutlinedButton(onClick = { bio.authenticate("Déverrouiller PayPilot") { ok -> if (ok) afterUnlock() } }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text("Utiliser l'empreinte ou le visage")
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = { state.signOut() }) { Text("Se déconnecter") }
        }
    }
}
