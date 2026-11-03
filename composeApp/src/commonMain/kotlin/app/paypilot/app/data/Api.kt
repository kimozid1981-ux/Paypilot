package app.paypilot.app.data

import app.paypilot.core.model.Channel
import app.paypilot.core.reminders.ReminderTone
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class RegisterBody(val email: String, val password: String, val businessName: String)
@Serializable data class LoginBody(val email: String, val password: String)
@Serializable data class SecondFactorBody(val challengeToken: String, val code: String)
@Serializable data class RefreshBody(val refreshToken: String)
@Serializable data class CodeBody(val code: String)
@Serializable data class TokenDto(val accessToken: String, val refreshToken: String, val expiresInSeconds: Long)
@Serializable data class LoginDto(val status: String, val tokens: TokenDto? = null, val challengeToken: String? = null, val retryAfterSeconds: Long? = null)
@Serializable data class TotpSetupDto(val otpauthUri: String)
@Serializable data class RecoveryDto(val recoveryCodes: List<String>)
@Serializable data class MeDto(val id: String, val email: String, val businessName: String, val twoFactorEnabled: Boolean)
@Serializable data class ErrorDto(val error: String, val message: String)
@Serializable data class ConnectorDto(val id: String, val name: String, val kind: String, val available: Boolean, val note: String)
@Serializable data class DraftDto(val text: String, val subject: String? = null, val model: String)
@Serializable data class ReminderDraftBody(
    val businessName: String, val ownerFirstName: String, val clientName: String, val contactName: String? = null,
    val invoiceNumber: String, val amount: String, val dueDate: String, val daysLate: Int,
    val tone: ReminderTone, val channel: Channel, val latePenalty: String? = null,
)
@Serializable data class ReplyDraftBody(
    val businessName: String, val ownerFirstName: String, val clientName: String,
    val clientMessage: String, val invoiceSummary: String? = null, val goal: String? = null,
)

class ApiException(val code: String, message: String) : Exception(message)

/** Talks to the PayPilot server. Only HTTPS URLs are accepted, except localhost for development. */
class ApiClient(baseUrl: String, engine: HttpClient) {
    val baseUrl: String = baseUrl.trimEnd('/')

    init {
        require(this.baseUrl.startsWith("https://") || this.baseUrl.startsWith("http://localhost") || this.baseUrl.startsWith("http://10.0.2.2")) {
            "L'adresse du serveur doit commencer par https://"
        }
    }

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val http = engine.config { install(ContentNegotiation) { json(json) } }
    var accessToken: String? = null

    private suspend inline fun <reified T> HttpResponse.read(): T {
        if (status.isSuccess()) return body()
        val err = runCatching { body<ErrorDto>() }.getOrNull()
        throw ApiException(err?.error ?: "http_${status.value}", err?.message ?: "Erreur serveur (${status.value}).")
    }

    suspend fun register(email: String, password: String, business: String): TokenDto =
        http.post("$baseUrl/v1/auth/register") { contentType(ContentType.Application.Json); setBody(RegisterBody(email, password, business)) }.read()

    suspend fun login(email: String, password: String): LoginDto {
        val r = http.post("$baseUrl/v1/auth/login") { contentType(ContentType.Application.Json); setBody(LoginBody(email, password)) }
        if (r.status.value == 429) return r.body()
        return r.read()
    }

    suspend fun verifySecondFactor(challenge: String, code: String): TokenDto =
        http.post("$baseUrl/v1/auth/2fa/verify") { contentType(ContentType.Application.Json); setBody(SecondFactorBody(challenge, code)) }.read()

    suspend fun refresh(refreshToken: String): TokenDto =
        http.post("$baseUrl/v1/auth/refresh") { contentType(ContentType.Application.Json); setBody(RefreshBody(refreshToken)) }.read()

    suspend fun me(): MeDto = http.get("$baseUrl/v1/me") { bearerAuth(accessToken.orEmpty()) }.read()
    suspend fun beginTotp(): TotpSetupDto = http.post("$baseUrl/v1/auth/2fa/setup") { bearerAuth(accessToken.orEmpty()) }.read()
    suspend fun confirmTotp(code: String): RecoveryDto =
        http.post("$baseUrl/v1/auth/2fa/confirm") { bearerAuth(accessToken.orEmpty()); contentType(ContentType.Application.Json); setBody(CodeBody(code)) }.read()
    suspend fun logoutEverywhere() { http.post("$baseUrl/v1/auth/logout-everywhere") { bearerAuth(accessToken.orEmpty()) } }
    suspend fun connectors(): List<ConnectorDto> = http.get("$baseUrl/v1/connectors") { bearerAuth(accessToken.orEmpty()) }.read()
    suspend fun draftReminder(body: ReminderDraftBody): DraftDto =
        http.post("$baseUrl/v1/ai/draft-reminder") { bearerAuth(accessToken.orEmpty()); contentType(ContentType.Application.Json); setBody(body) }.read()
    suspend fun draftReply(body: ReplyDraftBody): DraftDto =
        http.post("$baseUrl/v1/ai/draft-reply") { bearerAuth(accessToken.orEmpty()); contentType(ContentType.Application.Json); setBody(body) }.read()
}
