package app.paypilot.core.reminders

import app.paypilot.core.model.Channel

/**
 * Offline drafts used when the AI assistant is unreachable or switched off. Plain, polite French
 * that the owner edits before sending.
 */
object ReminderTemplates {
    data class Input(
        val ownerFirstName: String,
        val businessName: String,
        val contactName: String?,
        val invoiceNumber: String,
        val amount: String,
        val dueDate: String,
        val daysLate: Int,
        val latePenalty: String? = null,
    )

    data class Output(val subject: String?, val body: String)

    fun draft(tone: ReminderTone, channel: Channel, i: Input): Output {
        val hello = i.contactName?.let { "Bonjour $it," } ?: "Bonjour,"
        val sign = "${i.ownerFirstName} – ${i.businessName}"
        val body = when (tone) {
            ReminderTone.FRIENDLY ->
                "$hello petit rappel pour la facture ${i.invoiceNumber} (${i.amount}), échue le ${i.dueDate}. Si le paiement est déjà parti, merci de ne pas tenir compte de ce message. Bonne journée, $sign"
            ReminderTone.FIRM ->
                "$hello sauf erreur de ma part, la facture ${i.invoiceNumber} (${i.amount}) reste impayée ${i.daysLate} jours après son échéance du ${i.dueDate}. Pouvez-vous m'indiquer la date à laquelle le règlement sera effectué ? Merci, $sign"
            ReminderTone.FORMAL_NOTICE ->
                "$hello malgré mes précédentes relances, la facture ${i.invoiceNumber} d'un montant de ${i.amount}, échue le ${i.dueDate}, n'est toujours pas réglée. Je vous mets en demeure de procéder à son paiement sous 8 jours. À défaut, des pénalités de retard et l'indemnité forfaitaire de 40 € pour frais de recouvrement (art. L441-10 du Code de commerce) seront appliquées${i.latePenalty?.let { ", soit $it à ce jour" } ?: ""}. Cordialement, $sign"
        }
        val subject = if (channel == Channel.EMAIL) when (tone) {
            ReminderTone.FRIENDLY -> "Rappel : facture ${i.invoiceNumber}"
            ReminderTone.FIRM -> "Facture ${i.invoiceNumber} en retard de paiement"
            ReminderTone.FORMAL_NOTICE -> "Mise en demeure – facture ${i.invoiceNumber}"
        } else null
        val text = if (channel == Channel.SMS && body.length > 320 && tone != ReminderTone.FORMAL_NOTICE) body.take(317) + "…" else body
        return Output(subject, text)
    }
}
