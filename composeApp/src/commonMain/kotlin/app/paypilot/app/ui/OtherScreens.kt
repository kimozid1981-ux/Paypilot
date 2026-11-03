package app.paypilot.app.ui

import app.paypilot.app.data.AppState
import app.paypilot.app.data.ApiException
import app.paypilot.app.data.ConnectorDto
import app.paypilot.core.model.Channel
import app.paypilot.core.model.Client
import app.paypilot.core.model.Invoice
import app.paypilot.core.model.InvoiceStatus
import app.paypilot.core.model.Money
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.plus

@Composable
fun CaptureScreen(state: AppState, created: (String) -> Unit) {
    var clientName by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var days by remember { mutableStateOf(30) }
    var po by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenTitle("Nouvelle facture à suivre", "Ajoutez une facture déjà envoyée, PayPilot s'occupe du suivi.")
        OutlinedTextField(clientName, { clientName = it }, Modifier.fillMaxWidth(), label = { Text("Client") }, singleLine = true)
        OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Chantier ou prestation") }, singleLine = true)
        OutlinedTextField(amount, { amount = it.filter { c -> c.isDigit() || c == ',' || c == '.' } }, Modifier.fillMaxWidth(),
            label = { Text("Montant TTC (€)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        Text("Délai de paiement", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0, 30, 45, 60).forEach { d -> FilterChip(selected = days == d, onClick = { days = d }, label = { Text(if (d == 0) "À réception" else "$d j") }) }
        }
        OutlinedTextField(po, { po = it }, Modifier.fillMaxWidth(), label = { Text("N° de bon de commande (si exigé)") }, singleLine = true)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = {
            val cents = amount.replace(',', '.').toDoubleOrNull()?.let { (it * 100).toLong() }
            if (clientName.isBlank() || title.isBlank() || cents == null || cents <= 0) { error = "Renseignez le client, la prestation et un montant valide."; return@Button }
            val client = state.clients.firstOrNull { it.name.equals(clientName.trim(), ignoreCase = true) }
                ?: Client("c" + state.platform.randomBytes(6).joinToString("") { (it.toInt() and 0xff).toString(16) }, clientName.trim(), preferredChannel = Channel.EMAIL).also { state.clients.add(it) }
            val id = "i" + state.platform.randomBytes(8).joinToString("") { (it.toInt() and 0xff).toString(16) }
            val number = "F-" + state.today.year + "-" + (state.invoices.size + 101)
            state.updateInvoice(Invoice(id, number, client.id, title.trim(), Money(cents), issueDate = state.today,
                dueDate = state.today.plus(DatePeriod(days = days)), status = InvoiceStatus.DEPOSITED, purchaseOrder = po.ifBlank { null }))
            created(id)
        }, modifier = Modifier.fillMaxWidth()) { Text("Ajouter et suivre") }
        Text("Bientôt : dictée vocale et photo de la facture, avec lecture automatique des montants.", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun DocumentsScreen(state: AppState) {
    val today = state.today
    val upToDate = state.documents.count { (it.daysUntilExpiry(today) ?: 999) > 30 }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ScreenTitle("Coffre documents", "Ce que vos clients exigent pour vous payer")
        SectionCard(dark = true) {
            Text("$upToDate/${state.documents.size} à jour", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("Les documents qui expirent dans moins de 30 jours apparaissent dans « Aujourd'hui ».", fontSize = 13.sp, color = PP.MutedOnDark)
        }
        state.documents.forEach { d ->
            val days = d.daysUntilExpiry(today)
            val asking = state.clients.filter { d.type in it.requiredDocuments }.joinToString { it.name }
            SectionCard {
                RowSpread {
                    Column(Modifier.weight(1f)) {
                        Text(d.type.labelFr, fontWeight = FontWeight.SemiBold)
                        Text(if (asking.isEmpty()) "Aucun client ne l'exige pour l'instant" else "Demandé par $asking", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    when {
                        days == null -> Pill("Sans expiration", PP.LimeSoft, PP.LimeText)
                        days < 0 -> Pill("Expiré", PP.OrangeSoft, PP.OrangeText)
                        days <= 30 -> Pill("Expire dans $days j", PP.OrangeSoft, PP.OrangeText)
                        else -> Pill("À jour", PP.LimeSoft, PP.LimeText)
                    }
                }
            }
        }
    }
}

@Composable
fun SecurityScreen(state: AppState) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var otpUri by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf("") }
    var recovery by remember { mutableStateOf<List<String>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var connectors by remember { mutableStateOf<List<ConnectorDto>>(emptyList()) }

    LaunchedEffect(state.demoMode) {
        if (!state.demoMode) connectors = runCatching { state.api().connectors() }.getOrDefault(emptyList())
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenTitle("Compte et sécurité", state.email)
        SectionCard {
            Text("Protection de l'appareil", fontWeight = FontWeight.SemiBold)
            Text("Verrouillage automatique après ${state.lockPolicy.inactivityTimeoutSeconds / 60} min d'inactivité. " +
                "10 codes faux effacent les données de l'appareil." +
                (if (state.platform.biometric != null) " Empreinte ou visage activés." else "") +
                (if (state.platform.sessionOnly) " Sur cet appareil, la session n'est pas mémorisée après fermeture." else ""), fontSize = 14.sp)
            OutlinedButton(onClick = { state.screen = app.paypilot.app.data.Screen.LOCKED }, modifier = Modifier.fillMaxWidth()) { Text("Verrouiller maintenant") }
        }
        SectionCard {
            Text("Validation en deux étapes", fontWeight = FontWeight.SemiBold)
            when {
                state.demoMode -> Text("Disponible avec un compte (pas en mode démo).", fontSize = 14.sp)
                state.twoFactorEnabled -> Text("Activée. Un code de votre application d'authentification est demandé à chaque connexion.", fontSize = 14.sp)
                otpUri == null -> Button(onClick = {
                    scope.launch { try { otpUri = state.api().beginTotp().otpauthUri } catch (e: ApiException) { message = e.message } catch (e: Exception) { message = "Serveur injoignable." } }
                }, modifier = Modifier.fillMaxWidth()) { Text("Activer") }
                else -> {
                    val secret = otpUri!!.substringAfter("secret=").substringBefore('&')
                    Text("Dans Google Authenticator, Microsoft Authenticator ou 1Password, ajoutez un compte avec cette clé :", fontSize = 14.sp)
                    Text(secret.chunked(4).joinToString(" "), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(otpUri!!)) }, modifier = Modifier.fillMaxWidth()) { Text("Copier le lien de configuration") }
                    OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, Modifier.fillMaxWidth(), label = { Text("Code affiché") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                    Button(onClick = {
                        scope.launch {
                            try { recovery = state.api().confirmTotp(code).recoveryCodes; state.twoFactorEnabled = true; otpUri = null }
                            catch (e: ApiException) { message = e.message } catch (e: Exception) { message = "Serveur injoignable." }
                        }
                    }, enabled = code.length == 6, modifier = Modifier.fillMaxWidth()) { Text("Confirmer") }
                }
            }
            if (recovery.isNotEmpty()) {
                Text("Codes de secours (à garder hors de ce téléphone) :", fontWeight = FontWeight.SemiBold)
                recovery.forEach { Text(it, fontSize = 15.sp) }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        SectionCard {
            Text("Plateformes connectées", fontWeight = FontWeight.SemiBold)
            if (state.demoMode || connectors.isEmpty()) {
                Text("Stripe, plateformes de facturation électronique, Chorus Pro, banques et logiciels comptables se connectent depuis le serveur PayPilot.", fontSize = 14.sp)
            }
            connectors.forEach { c ->
                RowSpread {
                    Column(Modifier.weight(1f)) {
                        Text(c.name, fontWeight = FontWeight.SemiBold)
                        Text(c.note, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Pill(if (c.available) "Disponible" else "Bientôt", if (c.available) PP.LimeSoft else PP.OrangeSoft, if (c.available) PP.LimeText else PP.OrangeText)
                }
            }
        }
        if (!state.demoMode) OutlinedButton(onClick = { scope.launch { runCatching { state.api().logoutEverywhere() }; state.signOut() } }, modifier = Modifier.fillMaxWidth()) {
            Text("Déconnecter tous mes appareils")
        }
        Button(onClick = { state.signOut() }, modifier = Modifier.fillMaxWidth()) { Text(if (state.demoMode) "Quitter le mode démo" else "Se déconnecter") }
    }
}
