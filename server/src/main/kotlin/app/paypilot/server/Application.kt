package app.paypilot.server

import app.paypilot.server.ai.AiDrafter
import app.paypilot.server.ai.AiUnavailableException
import app.paypilot.server.ai.ClaudeDrafter
import app.paypilot.server.ai.ReminderDraftRequest
import app.paypilot.server.ai.ReplyDraftRequest
import app.paypilot.server.auth.AuthException
import app.paypilot.server.auth.AuthService
import app.paypilot.server.auth.BreachChecker
import app.paypilot.server.auth.HibpBreachChecker
import app.paypilot.server.auth.InMemoryUserStore
import app.paypilot.server.auth.LoginResult
import app.paypilot.server.auth.Tokens
import app.paypilot.server.connectors.ConnectorRegistry
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.minutes

@Serializable data class RegisterRequest(val email: String, val password: String, val businessName: String)
@Serializable data class LoginRequest(val email: String, val password: String)
@Serializable data class SecondFactorRequest(val challengeToken: String, val code: String)
@Serializable data class RefreshRequest(val refreshToken: String)
@Serializable data class CodeRequest(val code: String)
@Serializable data class TokenResponse(val accessToken: String, val refreshToken: String, val expiresInSeconds: Long)
@Serializable data class LoginResponse(val status: String, val tokens: TokenResponse? = null, val challengeToken: String? = null, val retryAfterSeconds: Long? = null)
@Serializable data class TotpSetupResponse(val otpauthUri: String)
@Serializable data class RecoveryCodesResponse(val recoveryCodes: List<String>)
@Serializable data class MeResponse(val id: String, val email: String, val businessName: String, val twoFactorEnabled: Boolean)
@Serializable data class ErrorResponse(val error: String, val message: String)

private fun Tokens.dto() = TokenResponse(accessToken, refreshToken, expiresInSeconds)

fun main() {
    val config = ServerConfig.fromEnv()
    val auth = AuthService(
        InMemoryUserStore(),
        config.jwtSecret,
        breachChecker = if (config.checkBreachedPasswords) HibpBreachChecker() else BreachChecker { 0 },
    )
    val ai: AiDrafter? = if (config.anthropicApiKeyPresent) ClaudeDrafter() else null
    embeddedServer(Netty, port = config.port) { payPilotModule(auth, ai, ConnectorRegistry.default()) }.start(wait = true)
}

private val AUTH_LIMIT = RateLimitName("auth")
private val AI_LIMIT = RateLimitName("ai")

fun Application.payPilotModule(auth: AuthService, ai: AiDrafter?, connectors: ConnectorRegistry) {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
    install(DefaultHeaders) {
        header("Strict-Transport-Security", "max-age=63072000; includeSubDomains")
        header("X-Content-Type-Options", "nosniff")
        header("X-Frame-Options", "DENY")
        header("Referrer-Policy", "no-referrer")
        header("Cache-Control", "no-store")
        header("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
    }
    install(RateLimit) {
        register(AUTH_LIMIT) { rateLimiter(limit = 10, refillPeriod = 1.minutes) }
        register(AI_LIMIT) { rateLimiter(limit = 30, refillPeriod = 1.minutes) }
    }
    install(Authentication) {
        jwt("access") {
            verifier(auth.verifier)
            validate { cred -> cred.payload.subject?.let { JWTPrincipal(cred.payload) } }
            challenge { _, _ -> call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized", "Session expirée, reconnectez-vous.")) }
        }
    }
    install(StatusPages) {
        exception<AuthException> { call, e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.code, e.message)) }
        exception<AiUnavailableException> { call, e -> call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("ai_unavailable", e.message ?: "")) }
        exception<io.ktor.server.plugins.BadRequestException> { call, _ -> call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", "Requête invalide.")) }
        exception<Throwable> { call, e ->
            call.application.environment.log.error("Unhandled error", e)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("internal", "Erreur interne."))
        }
    }

    routing {
        get("/health") { call.respond(mapOf("status" to "ok")) }

        route("/v1/auth") {
            rateLimit(AUTH_LIMIT) {
                post("/register") {
                    val r = call.receive<RegisterRequest>()
                    call.respond(HttpStatusCode.Created, auth.register(r.email, r.password, r.businessName).dto())
                }
                post("/login") {
                    val r = call.receive<LoginRequest>()
                    when (val result = auth.login(r.email, r.password)) {
                        is LoginResult.Success -> call.respond(LoginResponse("ok", tokens = result.tokens.dto()))
                        is LoginResult.NeedsSecondFactor -> call.respond(LoginResponse("second_factor_required", challengeToken = result.challengeToken))
                        is LoginResult.Locked -> call.respond(HttpStatusCode.TooManyRequests, LoginResponse("locked", retryAfterSeconds = result.retryAfterSeconds))
                        LoginResult.InvalidCredentials -> call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid_credentials", "E-mail ou mot de passe incorrect."))
                    }
                }
                post("/2fa/verify") {
                    val r = call.receive<SecondFactorRequest>()
                    call.respond(auth.verifySecondFactor(r.challengeToken, r.code).dto())
                }
                post("/refresh") { call.respond(auth.refresh(call.receive<RefreshRequest>().refreshToken).dto()) }
            }
        }

        authenticate("access") {
            get("/v1/me") {
                val user = auth.user(call.userId()) ?: return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized", "Compte introuvable."))
                call.respond(MeResponse(user.id, user.email, user.businessName, user.totpSecret != null))
            }
            post("/v1/auth/2fa/setup") { call.respond(TotpSetupResponse(auth.beginTotpSetup(call.userId()))) }
            post("/v1/auth/2fa/confirm") { call.respond(RecoveryCodesResponse(auth.confirmTotpSetup(call.userId(), call.receive<CodeRequest>().code))) }
            post("/v1/auth/logout-everywhere") { auth.logoutEverywhere(call.userId()); call.respond(HttpStatusCode.NoContent) }
            get("/v1/connectors") { call.respond(connectors.all()) }

            rateLimit(AI_LIMIT) {
                post("/v1/ai/draft-reminder") {
                    val drafter = ai ?: throw AiUnavailableException("L'assistant IA n'est pas configuré sur ce serveur.")
                    call.respond(drafter.draftReminder(call.receive<ReminderDraftRequest>()))
                }
                post("/v1/ai/draft-reply") {
                    val drafter = ai ?: throw AiUnavailableException("L'assistant IA n'est pas configuré sur ce serveur.")
                    call.respond(drafter.draftReply(call.receive<ReplyDraftRequest>()))
                }
            }
        }
    }
}

private fun RoutingCall.userId(): String = principal<JWTPrincipal>()!!.payload.subject
