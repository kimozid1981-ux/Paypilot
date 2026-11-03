package app.paypilot.app.data

import app.paypilot.core.model.Channel
import app.paypilot.core.model.Client
import app.paypilot.core.model.ComplianceDocument
import app.paypilot.core.model.DocumentType
import app.paypilot.core.model.Invoice
import app.paypilot.core.model.InvoiceStatus
import app.paypilot.core.model.Money
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/** Example data for demo mode, dated relative to today so it always looks current. */
object SampleData {
    fun clients() = listOf(
        Client("c1", "Bati-Ouest SAS", "Mme Leroy", "compta@bati-ouest.example", "+33600000001", Channel.SMS,
            requiredDocuments = setOf(DocumentType.URSSAF_VIGILANCE, DocumentType.KBIS)),
        Client("c2", "Hôpital Nord", "Service facturation", "factures@hopital-nord.example", null, Channel.EMAIL,
            isPublicSector = true, requiresPurchaseOrder = true, requiredDocuments = setOf(DocumentType.URSSAF_VIGILANCE, DocumentType.TAX_CERTIFICATE)),
        Client("c3", "Immo 35", "M. Garnier", "garnier@immo35.example", "+33600000003", Channel.WHATSAPP,
            requiredDocuments = setOf(DocumentType.URSSAF_VIGILANCE)),
    )

    fun invoices(today: LocalDate) = listOf(
        Invoice("i1", "F-2027-118", "c1", "Rénovation bureaux, lot peinture", Money.eur(1980),
            issueDate = today.minus(DatePeriod(days = 42)), dueDate = today.minus(DatePeriod(days = 12)), status = InvoiceStatus.APPROVED, remindersSent = 1,
            lastReminderOn = today.minus(DatePeriod(days = 7))),
        Invoice("i2", "F-2027-121", "c2", "Peinture couloirs bâtiment B", Money.eur(1170),
            issueDate = today.minus(DatePeriod(days = 35)), dueDate = today.minus(DatePeriod(days = 5)), status = InvoiceStatus.REFUSED),
        Invoice("i3", "F-2027-124", "c3", "Ravalement façade, acompte", Money.eur(2400),
            issueDate = today.minus(DatePeriod(days = 28)), dueDate = today.plus(DatePeriod(days = 2)), status = InvoiceStatus.RECEIVED),
        Invoice("i4", "F-2027-126", "c1", "Pose de revêtement de sol", Money.eur(2870),
            issueDate = today.minus(DatePeriod(days = 10)), dueDate = today.plus(DatePeriod(days = 20)), status = InvoiceStatus.DEPOSITED),
        Invoice("i5", "F-2027-109", "c3", "Peinture appartement T3", Money.eur(1450),
            issueDate = today.minus(DatePeriod(days = 60)), dueDate = today.minus(DatePeriod(days = 30)), status = InvoiceStatus.PAID, amountPaid = Money.eur(1450)),
    )

    fun documents(today: LocalDate) = listOf(
        ComplianceDocument(DocumentType.URSSAF_VIGILANCE, today.minus(DatePeriod(days = 168)), today.plus(DatePeriod(days = 12))),
        ComplianceDocument(DocumentType.KBIS, today.minus(DatePeriod(days = 20)), today.plus(DatePeriod(days = 70))),
        ComplianceDocument(DocumentType.DECENNIAL_INSURANCE, today.minus(DatePeriod(days = 100)), today.plus(DatePeriod(days = 265))),
        ComplianceDocument(DocumentType.TAX_CERTIFICATE, today.minus(DatePeriod(days = 40)), today.plus(DatePeriod(days = 325))),
        ComplianceDocument(DocumentType.BANK_DETAILS, today.minus(DatePeriod(days = 400)), null),
    )
}
