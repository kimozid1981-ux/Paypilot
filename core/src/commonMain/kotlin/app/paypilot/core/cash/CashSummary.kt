package app.paypilot.core.cash

import app.paypilot.core.model.Invoice
import app.paypilot.core.model.Money
import app.paypilot.core.model.sum
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

data class CashSummary(
    val expectedThisWeek: Money,
    val overdue: Money,
    val overdueCount: Int,
    val blockedCount: Int,
    val averageDaysLate: Double,
)

object CashCalculator {
    /** Expected = open invoices due between today and today + 6 days, plus anything already overdue. */
    fun summarize(invoices: List<Invoice>, today: LocalDate, currency: String = "EUR"): CashSummary {
        val open = invoices.filter { it.status.isOpen && !it.outstanding.isZero && it.amountInclTax.currency == currency }
        val weekEnd = today.plus(DatePeriod(days = 6))
        val overdue = open.filter { it.isOverdue(today) }
        val dueThisWeek = open.filter { it.dueDate in today..weekEnd }
        val late = overdue.map { it.daysLate(today) }
        return CashSummary(
            expectedThisWeek = (dueThisWeek + overdue).distinctBy { it.id }.map { it.outstanding }.sum(currency),
            overdue = overdue.map { it.outstanding }.sum(currency),
            overdueCount = overdue.size,
            blockedCount = open.count { it.status.isBlocked },
            averageDaysLate = if (late.isEmpty()) 0.0 else late.average(),
        )
    }
}
