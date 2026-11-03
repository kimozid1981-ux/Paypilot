package app.paypilot.app.ui

import app.paypilot.app.data.AppState
import app.paypilot.core.cash.CashCalculator
import app.paypilot.core.model.DocumentType
import app.paypilot.core.model.Invoice
import app.paypilot.core.reminders.NextAction
import app.paypilot.core.reminders.ReminderPlanner
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

data class SuggestedAction(val invoice: Invoice?, val title: String, val detail: String, val amount: String, val cta: String)

fun suggestedActions(state: AppState): List<SuggestedAction> {
    val planner = ReminderPlanner()
    val today = state.today
    val fromInvoices = state.invoices.mapNotNull { inv ->
        val client = state.clientOf(inv)
        when (val next = planner.next(inv, client, today)) {
            is NextAction.SendReminder -> SuggestedAction(inv, "Relancer ${client.name}",
                "${inv.number} · ${next.daysLate} j de retard · ${toneLabel(next.tone)}", inv.outstanding.format(), "Préparer la relance")
            is NextAction.FixBlocker -> SuggestedAction(inv, "${client.name} bloque le paiement", "${inv.number} · ${next.reason}", inv.outstanding.format(), "Voir la facture")
            NextAction.Nothing -> null
        }
    }
    val docs = state.documents.mapNotNull { d ->
        val days = d.daysUntilExpiry(today) ?: return@mapNotNull null
        if (days > 30) return@mapNotNull null
        val asking = state.clients.count { d.type in it.requiredDocuments }
        SuggestedAction(null, "${d.type.labelFr} à renouveler",
            (if (days < 0) "Expirée" else "Expire dans $days j") + if (asking > 0) " · demandée par $asking client(s)" else "", "", "Ouvrir le coffre")
    }
    return fromInvoices + docs
}

fun toneLabel(t: app.paypilot.core.reminders.ReminderTone) = when (t) {
    app.paypilot.core.reminders.ReminderTone.FRIENDLY -> "rappel cordial"
    app.paypilot.core.reminders.ReminderTone.FIRM -> "relance ferme"
    app.paypilot.core.reminders.ReminderTone.FORMAL_NOTICE -> "mise en demeure"
}

@Composable
fun TodayScreen(state: AppState, openInvoice: (String) -> Unit, openDocuments: () -> Unit) {
    val summary = CashCalculator.summarize(state.invoices, state.today)
    val actions = suggestedActions(state)
    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.padding(top = 16.dp, start = 4.dp)) {
                Text(if (state.demoMode) "Mode démo · données d'exemple" else state.businessName, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Bonjour ${state.ownerFirstName}", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        item {
            SectionCard(dark = true) {
                RowSpread {
                    Text("Attendu cette semaine", fontSize = 13.sp, color = PP.MutedOnDark)
                    if (summary.overdueCount > 0) Pill("${summary.overdueCount} en retard", PP.Orange, PP.Ink)
                }
                Amount(summary.expectedThisWeek.format(), size = 40, color = PP.Ground)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    LabelValue("En retard", summary.overdue.format(), onDark = true)
                    LabelValue("Bloquées", "${summary.blockedCount}", onDark = true)
                    LabelValue("Retard moyen", "${summary.averageDaysLate.roundToInt()} j", onDark = true)
                }
            }
        }
        item {
            RowSpread {
                Text("À valider (${actions.size})", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
                Text("Rien ne part sans vous", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (actions.isEmpty()) item { SectionCard { Text("Tout est à jour. Rien à faire aujourd'hui.") } }
        items(actions) { a ->
            SectionCard {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(a.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text(a.detail, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (a.amount.isNotEmpty()) Amount(a.amount, size = 14)
                }
                Button(onClick = { a.invoice?.let { openInvoice(it.id) } ?: openDocuments() }, modifier = Modifier.fillMaxWidth()) { Text(a.cta) }
            }
        }
        item { Text("") }
    }
}

val DocumentType.shortLabel: String get() = labelFr
