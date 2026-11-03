package app.paypilot.server

import app.paypilot.core.security.Base32
import app.paypilot.core.security.Totp
import app.paypilot.server.ai.AiDrafter
import app.paypilot.server.ai.Draft
import app.paypilot.server.ai.ReminderDraftRequest
import app.paypilot.server.ai.ReplyDraftRequest
import app.paypilot.server.auth.AuthService
import app.paypilot.server.auth.InMemoryUserStore
import app.paypilot.server.connectors.ConnectorRegistry
import app.paypilot.server.connectors.StripeConnector
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ServerTest {
    private val secret = "test-secret-that-is-long-enough-for-hmac-256!!"
    private val strongPassword = "camion bleu sous la pluie"

    private val fakeAi = object : AiDrafter {
        override fun draftReminder(request: ReminderDraftRequest) = Draft("Bonjour, rappel pour ${request.invoiceNumber}.", null, "fake")
        override fun draftReply(request: ReplyDraftRequest) = Draft("Merci pour votre message.", null, "fake")
    }

    private fun ApplicationTestBuilder.setup(): io.ktor.client.HttpClient {
        application { payPilotModule(AuthService(InMemoryUserStore(), secret), fakeAi, ConnectorRegistry.default()) }
        return createClient { install(ContentNegotiation) { json() } }
    }

    @Test fun weakPasswordIsRejected() = testApplication {
        val client = setup()
        val r = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest("sami@atlas.fr", "azerty123", "Peinture Atlas"))
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertEquals("weak_password", r.body<ErrorResponse>().error)
    }

    @Test fun registerLoginAndProtectedRoutes() = testApplication {
        val client = setup()
        val reg = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest("Sami@Atlas.fr", strongPassword, "Peinture Atlas"))
        }
        assertEquals(HttpStatusCode.Created, reg.status)
        val tokens = reg.body<TokenResponse>()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/me").status)
        val me = client.get("/v1/me") { bearerAuth(tokens.accessToken) }.body<MeResponse>()
        assertEquals("sami@atlas.fr", me.email)

        val draft = client.post("/v1/ai/draft-reminder") {
            bearerAuth(tokens.accessToken)
            contentType(ContentType.Application.Json)
            setBody(ReminderDraftRequest("Peinture Atlas", "Sami", "Bati-Ouest", null, "F-118", "1 980 €", "25/08/2027", 12,
                app.paypilot.core.reminders.ReminderTone.FRIENDLY, app.paypilot.core.model.Channel.SMS))
        }.body<Draft>()
        assertTrue(draft.text.contains("F-118"))

        // Refresh tokens rotate: the same one cannot be used twice.
        val refreshed = client.post("/v1/auth/refresh") { contentType(ContentType.Application.Json); setBody(RefreshRequest(tokens.refreshToken)) }
        assertEquals(HttpStatusCode.OK, refreshed.status)
        val reused = client.post("/v1/auth/refresh") { contentType(ContentType.Application.Json); setBody(RefreshRequest(tokens.refreshToken)) }
        assertEquals(HttpStatusCode.BadRequest, reused.status)
    }

    @Test fun lockoutAfterFiveWrongPasswords() = testApplication {
        val client = setup()
        client.post("/v1/auth/register") { contentType(ContentType.Application.Json); setBody(RegisterRequest("a@b.fr", strongPassword, "Atlas")) }
        repeat(4) {
            val r = client.post("/v1/auth/login") { contentType(ContentType.Application.Json); setBody(LoginRequest("a@b.fr", "wrong password here")) }
            assertEquals(HttpStatusCode.Unauthorized, r.status)
        }
        val fifth = client.post("/v1/auth/login") { contentType(ContentType.Application.Json); setBody(LoginRequest("a@b.fr", "wrong password here")) }
        assertEquals(HttpStatusCode.TooManyRequests, fifth.status)
        val evenRight = client.post("/v1/auth/login") { contentType(ContentType.Application.Json); setBody(LoginRequest("a@b.fr", strongPassword)) }
        assertEquals(HttpStatusCode.TooManyRequests, evenRight.status)
    }

    @Test fun twoStepVerificationFlow() = testApplication {
        val client = setup()
        val tokens = client.post("/v1/auth/register") { contentType(ContentType.Application.Json); setBody(RegisterRequest("t@b.fr", strongPassword, "Atlas")) }.body<TokenResponse>()
        val uri = client.post("/v1/auth/2fa/setup") { bearerAuth(tokens.accessToken) }.body<TotpSetupResponse>().otpauthUri
        val secretParam = URI(uri).rawQuery.split('&').first { it.startsWith("secret=") }.substringAfter('=')
        val totpSecret = Base32.decode(secretParam)
        val code = Totp().generate(totpSecret, System.currentTimeMillis() / 1000)
        val recovery = client.post("/v1/auth/2fa/confirm") { bearerAuth(tokens.accessToken); contentType(ContentType.Application.Json); setBody(CodeRequest(code)) }
        assertEquals(HttpStatusCode.OK, recovery.status)
        assertEquals(8, recovery.body<RecoveryCodesResponse>().recoveryCodes.size)

        val login = client.post("/v1/auth/login") { contentType(ContentType.Application.Json); setBody(LoginRequest("t@b.fr", strongPassword)) }.body<LoginResponse>()
        assertEquals("second_factor_required", login.status)
        val challenge = assertNotNull(login.challengeToken)
        val bad = client.post("/v1/auth/2fa/verify") { contentType(ContentType.Application.Json); setBody(SecondFactorRequest(challenge, "000000")) }
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        // The code used for setup cannot be replayed; use the next time step.
        val next = Totp().generate(totpSecret, System.currentTimeMillis() / 1000 + 30)
        val ok = client.post("/v1/auth/2fa/verify") { contentType(ContentType.Application.Json); setBody(SecondFactorRequest(challenge, next)) }
        assertEquals(HttpStatusCode.OK, ok.status)
    }

    @Test fun stripeParsing() {
        val body = """{"object":"list","data":[{"id":"in_1","number":"F-001","customer_name":"Bati-Ouest","amount_due":198000,"amount_paid":0,"currency":"eur","status":"open","due_date":1787443200}]}"""
        val invoices = StripeConnector().parse(body)
        assertEquals(1, invoices.size)
        assertEquals(198000, invoices[0].amountDueCents)
        assertEquals("EUR", invoices[0].currency)
    }
}
