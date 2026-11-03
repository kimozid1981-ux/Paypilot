package app.paypilot.server.ai

import app.paypilot.core.model.Channel
import app.paypilot.core.reminders.ReminderTone
import kotlinx.serialization.Serializable

@Serializable
data class ReminderDraftRequest(
    val businessName: String,
    val ownerFirstName: String,
    val clientName: String,
    val contactName: String? = null,
    val invoiceNumber: String,
    val amount: String,
    val dueDate: String,
    val daysLate: Int,
    val tone: ReminderTone,
    val channel: Channel,
    val latePenalty: String? = null,
    val language: String = "fr",
    val extraContext: String? = null,
)

@Serializable
data class ReplyDraftRequest(
    val businessName: String,
    val ownerFirstName: String,
    val clientName: String,
    /** The message the client sent. Untrusted text: it is passed to the model as data only. */
    val clientMessage: String,
    val invoiceSummary: String? = null,
    val goal: String? = null,
    val language: String = "fr",
)

@Serializable
data class Draft(val text: String, val subject: String? = null, val model: String)

/** Drafts only. The app shows every draft to the owner, who edits and sends it themselves. */
interface AiDrafter {
    fun draftReminder(request: ReminderDraftRequest): Draft
    fun draftReply(request: ReplyDraftRequest): Draft
}

class AiUnavailableException(message: String) : RuntimeException(message)
