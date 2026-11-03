package app.paypilot.core.model

import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

@Serializable
data class Client(
    val id: String,
    val name: String,
    val contactName: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val preferredChannel: Channel = Channel.EMAIL,
    /** Public-sector client paid through Chorus Pro. */
    val isPublicSector: Boolean = false,
    /** Documents this client asks for before paying. */
    val requiredDocuments: Set<DocumentType> = emptySet(),
    /** Client requires a purchase order number on every invoice. */
    val requiresPurchaseOrder: Boolean = false,
)

@Serializable
enum class Channel { EMAIL, SMS, WHATSAPP }

/**
 * Lifecycle of an invoice. The first group mirrors the statuses of the French e-invoicing
 * reform (approved platforms); DRAFT and PARTIALLY_PAID are PayPilot's own.
 */
@Serializable
enum class InvoiceStatus(val einvoicingCode: Int?, val mandatory: Boolean) {
    DRAFT(null, false),
    DEPOSITED(200, true),        // Déposée
    ISSUED(201, false),          // Émise par la plateforme
    RECEIVED(202, false),        // Reçue par la plateforme
    MADE_AVAILABLE(203, false),  // Mise à disposition
    IN_HAND(204, false),         // Prise en charge
    APPROVED(205, false),        // Approuvée
    PARTIALLY_APPROVED(206, false),
    DISPUTED(207, false),        // En litige
    SUSPENDED(208, false),       // Suspendue
    REFUSED(210, true),          // Refusée (business reason)
    PAYMENT_SENT(211, false),    // Paiement transmis
    PAID(212, true),             // Encaissée
    REJECTED(213, true),         // Rejetée (technical reason)
    PARTIALLY_PAID(null, false);

    val isOpen: Boolean get() = this != PAID && this != DRAFT
    val isBlocked: Boolean get() = this == REFUSED || this == REJECTED || this == DISPUTED || this == SUSPENDED

    companion object {
        fun fromEinvoicingCode(code: Int): InvoiceStatus? = entries.firstOrNull { it.einvoicingCode == code }
    }
}

@Serializable
data class StatusEvent(val status: InvoiceStatus, val at: Instant, val reason: String? = null)

@Serializable
data class Invoice(
    val id: String,
    val number: String,
    val clientId: String,
    val title: String,
    val amountInclTax: Money,
    val amountPaid: Money = Money.zero(amountInclTax.currency),
    val issueDate: LocalDate,
    val dueDate: LocalDate,
    val status: InvoiceStatus,
    val history: List<StatusEvent> = emptyList(),
    val purchaseOrder: String? = null,
    val remindersSent: Int = 0,
    val lastReminderOn: LocalDate? = null,
) {
    val outstanding: Money get() = amountInclTax - amountPaid
    fun daysLate(today: LocalDate): Int = if (status.isOpen && today > dueDate) (today.toEpochDays() - dueDate.toEpochDays()).toInt() else 0
    fun isOverdue(today: LocalDate): Boolean = daysLate(today) > 0 && !outstanding.isZero
}

@Serializable
enum class DocumentType(val labelFr: String, val validityDays: Int?) {
    URSSAF_VIGILANCE("Attestation de vigilance URSSAF", 180),
    KBIS("Extrait Kbis", 90),
    DECENNIAL_INSURANCE("Assurance décennale", 365),
    TAX_CERTIFICATE("Attestation fiscale", 365),
    BANK_DETAILS("RIB professionnel", null),
    FOREIGN_WORKERS_LIST("Liste des salariés étrangers", 180),
}

@Serializable
data class ComplianceDocument(val type: DocumentType, val issuedOn: LocalDate, val expiresOn: LocalDate?) {
    fun daysUntilExpiry(today: LocalDate): Int? = expiresOn?.let { (it.toEpochDays() - today.toEpochDays()).toInt() }
}
