package app.paypilot.app.ui

import app.paypilot.app.data.ApiException
import app.paypilot.app.data.AppState
import app.paypilot.app.data.ReminderDraftBody
import app.paypilot.app.data.ReplyDraftBody
import app.paypilot.core.model.Channel
import app.paypilot.core.model.Invoice
import app.paypilot.core.model.InvoiceStatus
import app.paypilot.core.model.StatusEvent
import app.paypilot.core.reminders.NextAction
import app.paypilot.core.reminders.ReminderPlanner
import app.paypilot.core.reminders.ReminderTemplates
import app.paypilot.core.reminders.ReminderTone
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.datetime.number
import kotlin.time.Clock

fun statusLabel(s: InvoiceStatus) = when (s) {
    InvoiceStatus.DRAFT -> "Brouillon"
    InvoiceStatus.DEPOSITED -> "Déposée"
    InvoiceStatus.ISSUED -> "Émise"
    InvoiceStatus.RECEIVED -> "Reçue par le client"
    InvoiceStatus.MADE_AVAILABLE -> "Mise à disposition"
    InvoiceStatus.IN_HAND -> "Prise en charge"
    InvoiceStatus.APPROVED -> "Approuvée"
    InvoiceStatus.PARTIALLY_APPROVED -> "Approuvée en partie"
    InvoiceStatus.DISPUTED -> "En litige"
    InvoiceStatus.SUSPENDED -> "Suspendue"
    InvoiceStatus.REFUSED -> "Refusée"
    InvoiceStatus.PAYMENT_SENT -> "Paiement transmis"
    InvoiceStatus.PAID -> "Encaissée"
    InvoiceStatus.REJECTED -> "Rejetée"
    InvoiceStatus.PARTIALLY_PAID -> "Payée en partie"
}

fun frDate(d: kotlinx.datetime.LocalDate) = "${d.day.toString().padStart(2, '0')}/${d.month.number.toString().padStart(2, '0')}/${d.year}"

@Composable
fun InvoiceListScreen(state: AppState, open: (String) -> Unit) {
    var filterOverdue by remember { mutableStateOf(false) }
    val list = state.invoices.filter { !filterOverdue || it.isOverdue(state.today) }.sortedBy { it.dueDate }
    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Column(Modifier.padding(top = 16.dp)) { ScreenTitle("Factures", "${state.invoices.count { it.status.isOpen }} en cours") } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !filterOverdue, onClick = { filterOverdue = false }, label = { Text("Toutes") })
                FilterChip(selected = filterOverdue, onClick = { filterOverdue = true }, label = { Text("En retard") })
            }
        }
        items(list, key = { it.id }) { inv ->
            val late = inv.daysLate(state.today)
            SectionCard(Modifier.clickable { open(inv.id) }) {
                RowSpread {
                    Column(Modifier.weight(1f)) {
                        Text(state.clientOf(inv).name, fontWeight = FontWeight.SemiBold)
                        Text("${inv.number} · ${inv.title}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Amount(inv.outstanding.format(), size = 14)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(statusLabel(inv.status), PP.LimeSoft, PP.LimeText)
                    if (late > 0 && inv.status.isOpen) Pill("$late j de retard", PP.OrangeSoft, PP.OrangeText)
                }
            }
        }
        item { Text("") }
    }
}

@Composable
fun InvoiceCaseScreen(state: AppState, invoiceId: String, back: () -> Unit) {
    val invoice = state.invoices.firstOrNull { it.id == invoiceId }
    if (invoice == null) { androidx.compose.runtime.LaunchedEffect(invoiceId) { back() }; return }
    val client = state.clientOf(invoice)
    val planner = remember { ReminderPlanner() }
    val next = planner.next(invoice, client, state.today)
    var channel by remember(invoiceId) { mutableStateOf(client.preferredChannel) }
    var draft by remember(invoiceId) { mutableStateOf("") }
    var subject by remember(invoiceId) { mutableStateOf<String?>(null) }
    var note by remember(invoiceId) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var poInput by remember(invoiceId) { mutableStateOf(invoice.purchaseOrder.orEmpty()) }
    var clientMessage by remember(invoiceId) { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current

    val tone = (next as? NextAction.SendReminder)?.tone ?: ReminderTone.FRIENDLY
    val daysLate = invoice.daysLate(state.today)
    val penalty = planner.latePenalty(invoice.outstanding, daysLate).format()

    fun prepareDraft() {
        busy = true; note = null
        scope.launch {
            val fallback = ReminderTemplates.draft(tone, channel, ReminderTemplates.Input(state.ownerFirstName, state.businessName, client.contactName,
                invoice.number, invoice.outstanding.format(), frDate(invoice.dueDate), daysLate, if (tone == ReminderTone.FORMAL_NOTICE) penalty else null))
            if (state.demoMode) {
                draft = fallback.body; subject = fallback.subject; note = "Mode démo : modèle de message hors ligne."
            } else try {
                val d = state.api().draftReminder(ReminderDraftBody(state.businessName, state.ownerFirstName, client.name, client.contactName,
                    invoice.number, invoice.outstanding.format(), frDate(invoice.dueDate), daysLate, tone, channel,
                    if (tone == ReminderTone.FORMAL_NOTICE) penalty else null))
                draft = d.text; subject = d.subject; note = "Rédigé par l'assistant IA. Relisez avant d'envoyer."
            } catch (e: Exception) {
                draft = fallback.body; subject = fallback.subject
                note = "Assistant IA indisponible (${(e as? ApiException)?.message ?: "hors ligne"}). Modèle hors ligne utilisé."
            }
            busy = false
        }
    }

    fun send() {
        val target = when (channel) {
            Channel.SMS -> client.phone?.let { "sms:$it?body=${urlEncode(draft)}" }
            Channel.WHATSAPP -> client.phone?.let { "https://wa.me/${it.filter(Char::isDigit)}?text=${urlEncode(draft)}" }
            Channel.EMAIL -> client.email?.let { "mailto:$it?subject=${urlEncode(subject ?: "Facture ${invoice.number}")}&body=${urlEncode(draft)}" }
        }
        if (target == null) { clipboard.setText(AnnotatedString(draft)); note = "Coordonnées manquantes : message copié."; return }
        runCatching { uri.openUri(target) }.onFailure { clipboard.setText(AnnotatedString(draft)); note = "Impossible d'ouvrir l'application : message copié." }
        state.updateInvoice(invoice.copy(remindersSent = invoice.remindersSent + 1, lastReminderOn = state.today))
        note = "Relance ouverte dans votre application. Elle part de votre compte, à votre nom."
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = back) { Text("‹ Retour") }
        ScreenTitle(invoice.title, "${invoice.number} · ${client.name}")
        SectionCard {
            RowSpread {
                Column { Amount(invoice.outstanding.format(), size = 28); Text("Échéance ${frDate(invoice.dueDate)}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (daysLate > 0 && invoice.status.isOpen) Pill("$daysLate j de retard", PP.OrangeSoft, PP.OrangeText)
            }
        }
        SectionCard {
            Text("Parcours", fontWeight = FontWeight.SemiBold)
            val steps = listOf(InvoiceStatus.DEPOSITED, InvoiceStatus.RECEIVED, InvoiceStatus.APPROVED, InvoiceStatus.PAID)
            val reached = steps.indexOfLast { it.ordinal <= invoice.status.ordinal && !invoice.status.isBlocked }.coerceAtLeast(0)
            steps.forEachIndexed { i, s ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (i <= reached) "●" else "○", color = if (i <= reached) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(statusLabel(s), fontWeight = if (i == reached) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
            if (invoice.status.isBlocked) Pill(statusLabel(invoice.status), PP.OrangeSoft, PP.OrangeText)
            Text("Statuts issus de votre plateforme de facturation électronique dès qu'elle est connectée.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (next is NextAction.FixBlocker) {
            SectionCard {
                Text("À régler d'abord", fontWeight = FontWeight.SemiBold)
                Text(next.reason)
                if (client.requiresPurchaseOrder) {
                    OutlinedTextField(poInput, { poInput = it.trim() }, Modifier.fillMaxWidth(), label = { Text("N° de bon de commande") }, singleLine = true)
                    Button(onClick = {
                        state.updateInvoice(invoice.copy(purchaseOrder = poInput, status = InvoiceStatus.DEPOSITED,
                            history = invoice.history + StatusEvent(InvoiceStatus.DEPOSITED, Clock.System.now(), "Redéposée avec bon de commande")))
                    }, enabled = poInput.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Ajouter et redéposer") }
                }
            }
        }

        if (invoice.status.isOpen) {
            SectionCard(dark = true) {
                Text(if (next is NextAction.SendReminder) "Relance proposée : ${toneLabel(tone)}" else "Préparer un message", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(Channel.SMS to "SMS", Channel.WHATSAPP to "WhatsApp", Channel.EMAIL to "E-mail").forEach { (c, label) ->
                        FilterChip(selected = channel == c, onClick = { channel = c; draft = "" }, label = { Text(label) })
                    }
                }
                if (draft.isEmpty()) {
                    Button(onClick = { prepareDraft() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Rédaction…" else "Rédiger avec l'assistant") }
                } else {
                    subject?.let { OutlinedTextField(it, { v -> subject = v }, Modifier.fillMaxWidth(), label = { Text("Objet") }) }
                    OutlinedTextField(draft, { draft = it }, Modifier.fillMaxWidth().heightIn(min = 140.dp), label = { Text("Message (modifiable)") })
                    Button(onClick = { send() }, modifier = Modifier.fillMaxWidth()) { Text("Envoyer depuis mon ${if (channel == Channel.EMAIL) "e-mail" else "téléphone"}") }
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(draft)); note = "Message copié." }, modifier = Modifier.fillMaxWidth()) { Text("Copier") }
                }
                note?.let { Text(it, fontSize = 12.sp, color = PP.MutedOnDark) }
            }
            SectionCard {
                Text("Répondre à un message du client", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(clientMessage, { clientMessage = it }, Modifier.fillMaxWidth().heightIn(min = 90.dp), label = { Text("Collez le message reçu") })
                Button(onClick = {
                    busy = true
                    scope.launch {
                        draft = try {
                            if (state.demoMode) error("demo")
                            state.api().draftReply(ReplyDraftBody(state.businessName, state.ownerFirstName, client.name, clientMessage,
                                "${invoice.number} ${invoice.outstanding.format()} échue le ${frDate(invoice.dueDate)}")).text
                        } catch (e: Exception) {
                            "Bonjour, merci pour votre message concernant la facture ${invoice.number}. Je reviens vers vous rapidement. ${state.ownerFirstName} – ${state.businessName}"
                        }
                        subject = null
                        note = if (state.demoMode) "Mode démo : réponse type hors ligne." else "Brouillon de réponse prêt ci-dessus. Relisez avant d'envoyer."
                        busy = false
                    }
                }, enabled = clientMessage.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth()) { Text("Proposer une réponse") }
            }
            OutlinedButton(onClick = {
                state.updateInvoice(invoice.copy(status = InvoiceStatus.PAID, amountPaid = invoice.amountInclTax,
                    history = invoice.history + StatusEvent(InvoiceStatus.PAID, Clock.System.now(), "Marquée payée")))
                back()
            }, modifier = Modifier.fillMaxWidth()) { Text("Marquer comme encaissée") }
        }
    }
}
