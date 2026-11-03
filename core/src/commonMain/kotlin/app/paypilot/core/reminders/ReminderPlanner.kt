package app.paypilot.core.reminders

import app.paypilot.core.model.Client
import app.paypilot.core.model.Invoice
import app.paypilot.core.model.InvoiceStatus
import app.paypilot.core.model.Money
import kotlinx.datetime.LocalDate

enum class ReminderTone { FRIENDLY, FIRM, FORMAL_NOTICE }

sealed interface NextAction {
    data object Nothing : NextAction
    /** The invoice cannot be paid until something is fixed (refusal, rejection, missing PO...). */
    data class FixBlocker(val reason: String) : NextAction
    data class SendReminder(val tone: ReminderTone, val daysLate: Int, val latePenalty: Money) : NextAction
}

/**
 * Decides what to do next for an invoice. Nothing is ever sent automatically: the result
 * becomes a suggestion the owner approves.
 */
class ReminderPlanner(
    private val friendlyAfterDays: Int = 3,
    private val firmAfterDays: Int = 10,
    private val formalAfterDays: Int = 30,
    private val minDaysBetweenReminders: Int = 5,
    /** Annual late-payment interest rate in percent (contractual; France: at least 3x the legal rate). */
    private val annualPenaltyRatePercent: Double = 12.0,
) {
    fun next(invoice: Invoice, client: Client, today: LocalDate): NextAction {
        if (!invoice.status.isOpen || invoice.outstanding.isZero) return NextAction.Nothing
        when (invoice.status) {
            InvoiceStatus.REJECTED -> return NextAction.FixBlocker("Rejetée par la plateforme : format ou mentions à corriger")
            InvoiceStatus.REFUSED -> return NextAction.FixBlocker("Refusée par le client : motif à traiter")
            InvoiceStatus.DISPUTED -> return NextAction.FixBlocker("En litige : répondre au client")
            InvoiceStatus.SUSPENDED -> return NextAction.FixBlocker("Suspendue : pièce manquante demandée par le client")
            else -> Unit
        }
        if (client.requiresPurchaseOrder && invoice.purchaseOrder.isNullOrBlank()) {
            return NextAction.FixBlocker("Numéro de bon de commande manquant")
        }
        val late = invoice.daysLate(today)
        if (late < friendlyAfterDays) return NextAction.Nothing
        val last = invoice.lastReminderOn
        if (last != null && today.toEpochDays() - last.toEpochDays() < minDaysBetweenReminders) return NextAction.Nothing
        val tone = when {
            late >= formalAfterDays && invoice.remindersSent >= 2 -> ReminderTone.FORMAL_NOTICE
            late >= firmAfterDays && invoice.remindersSent >= 1 -> ReminderTone.FIRM
            else -> ReminderTone.FRIENDLY
        }
        return NextAction.SendReminder(tone, late, latePenalty(invoice.outstanding, late))
    }

    /**
     * French B2B late fees: interest on the outstanding amount pro rata temporis, plus the fixed
     * 40 EUR recovery indemnity (art. L441-10 and D441-5 Code de commerce).
     */
    fun latePenalty(outstanding: Money, daysLate: Int): Money {
        if (daysLate <= 0) return Money.zero(outstanding.currency)
        val interest = (outstanding.cents * annualPenaltyRatePercent / 100.0 * daysLate / 365.0).toLong()
        return Money(interest + FIXED_RECOVERY_INDEMNITY_CENTS, outstanding.currency)
    }

    companion object {
        const val FIXED_RECOVERY_INDEMNITY_CENTS = 4_000L
    }
}
