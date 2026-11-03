package app.paypilot.core

import app.paypilot.core.cash.CashCalculator
import app.paypilot.core.model.Client
import app.paypilot.core.model.Invoice
import app.paypilot.core.model.InvoiceStatus
import app.paypilot.core.model.Money
import app.paypilot.core.reminders.NextAction
import app.paypilot.core.reminders.ReminderPlanner
import app.paypilot.core.reminders.ReminderTone
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BusinessTest {
    private val today = LocalDate(2027, 9, 6)
    private val client = Client("c1", "Bati-Ouest SAS")

    private fun invoice(id: String, due: LocalDate, amount: Long, status: InvoiceStatus = InvoiceStatus.APPROVED, reminders: Int = 0, po: String? = null) =
        Invoice(id, "F-$id", "c1", "Travaux", Money.eur(amount), issueDate = LocalDate(2027, 7, 1), dueDate = due, status = status, remindersSent = reminders, purchaseOrder = po)

    @Test fun moneyFormatting() {
        assertEquals("1 980 €", Money.eur(1980).format())
        assertEquals("1 980,50 €", Money.eur(1980, 50).format())
        assertEquals("12 345 678 €", Money.eur(12345678).format())
    }

    @Test fun cashSummary() {
        val invoices = listOf(
            invoice("1", LocalDate(2027, 8, 25), 1980),
            invoice("2", LocalDate(2027, 9, 8), 2400),
            invoice("3", LocalDate(2027, 9, 30), 900),
            invoice("4", LocalDate(2027, 8, 1), 1170, status = InvoiceStatus.REFUSED),
            invoice("5", LocalDate(2027, 8, 1), 500, status = InvoiceStatus.PAID),
        )
        val s = CashCalculator.summarize(invoices, today)
        assertEquals(Money.eur(1980 + 2400 + 1170), s.expectedThisWeek)
        assertEquals(Money.eur(1980 + 1170), s.overdue)
        assertEquals(2, s.overdueCount)
        assertEquals(1, s.blockedCount)
    }

    @Test fun reminderEscalation() {
        val planner = ReminderPlanner()
        assertEquals(NextAction.Nothing, planner.next(invoice("a", LocalDate(2027, 9, 5), 100), client, today))
        val friendly = assertIs<NextAction.SendReminder>(planner.next(invoice("b", LocalDate(2027, 8, 25), 1980), client, today))
        assertEquals(ReminderTone.FRIENDLY, friendly.tone)
        assertEquals(12, friendly.daysLate)
        val firm = assertIs<NextAction.SendReminder>(planner.next(invoice("c", LocalDate(2027, 8, 25), 1980, reminders = 1), client, today))
        assertEquals(ReminderTone.FIRM, firm.tone)
        val formal = assertIs<NextAction.SendReminder>(planner.next(invoice("d", LocalDate(2027, 7, 25), 1980, reminders = 2), client, today))
        assertEquals(ReminderTone.FORMAL_NOTICE, formal.tone)
        assertIs<NextAction.FixBlocker>(planner.next(invoice("e", LocalDate(2027, 8, 25), 1980, status = InvoiceStatus.REFUSED), client, today))
        val poClient = client.copy(requiresPurchaseOrder = true)
        assertIs<NextAction.FixBlocker>(planner.next(invoice("f", LocalDate(2027, 8, 25), 1980), poClient, today))
    }

    @Test fun latePenaltyIncludesFixedIndemnity() {
        // 1 000 EUR, 12%/year, 73 days -> 24 EUR interest + 40 EUR indemnity
        assertEquals(Money.eur(64), ReminderPlanner().latePenalty(Money.eur(1000), 73))
    }

    @Test fun einvoicingCodes() {
        assertEquals(InvoiceStatus.PAID, InvoiceStatus.fromEinvoicingCode(212))
        assertEquals(listOf(200, 210, 212, 213), InvoiceStatus.entries.filter { it.mandatory }.mapNotNull { it.einvoicingCode })
    }
}
