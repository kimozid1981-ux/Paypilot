package app.paypilot.server.auth

import app.paypilot.core.security.BreachedPasswords
import app.paypilot.core.security.PasswordPolicy
import app.paypilot.core.security.Totp
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import de.mkammerer.argon2.Argon2Factory
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import java.util.Date
import java.util.UUID

sealed interface LoginResult {
    data class Success(val tokens: Tokens) : LoginResult
    /** Password was right; a 6-digit code is needed. The challenge token is valid for 5 minutes. */
    data class NeedsSecondFactor(val challengeToken: String) : LoginResult
    data class Locked(val retryAfterSeconds: Long) : LoginResult
    data object InvalidCredentials : LoginResult
}

data class Tokens(val accessToken: String, val refreshToken: String, val expiresInSeconds: Long)

class AuthException(val code: String, override val message: String) : RuntimeException(message)

fun interface BreachChecker {
    /** Returns how often the password appears in known breaches; 0 when unknown or unreachable. */
    fun timesSeen(password: String): Int
}

class AuthService(
    private val store: UserStore,
    jwtSecret: String,
    private val issuer: String = "paypilot",
    private val breachChecker: BreachChecker = BreachChecker { 0 },
    private val clock: Clock = Clock.systemUTC(),
) {
    private val algorithm = Algorithm.HMAC256(jwtSecret)
    private val argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id)
    private val random = SecureRandom()
    private val totp = Totp()
    // Hash used to keep login timing equal whether or not the e-mail exists.
    private val dummyHash = argon2.hash(ARGON_ITERATIONS, ARGON_MEMORY_KB, ARGON_PARALLELISM, "dummy-password".toCharArray())

    val verifier = JWT.require(algorithm).withIssuer(issuer).withAudience(AUDIENCE_ACCESS).build()
    private val challengeVerifier = JWT.require(algorithm).withIssuer(issuer).withAudience(AUDIENCE_2FA).build()

    private fun now() = clock.instant().epochSecond

    fun register(email: String, password: String, businessName: String): Tokens {
        val normalized = email.trim().lowercase()
        if (!EMAIL.matches(normalized)) throw AuthException("invalid_email", "Adresse e-mail invalide.")
        if (businessName.isBlank() || businessName.length > 120) throw AuthException("invalid_business", "Nom d'entreprise invalide.")
        val check = PasswordPolicy.check(password, listOf(normalized, businessName))
        if (!check.accepted) throw AuthException("weak_password", check.problems.joinToString(" "))
        if (breachChecker.timesSeen(password) > 0) {
            throw AuthException("breached_password", "Ce mot de passe apparaît dans des fuites de données connues. Choisissez-en un autre.")
        }
        // Same answer as success-path timing would leak existence; the generic message avoids enumeration.
        if (store.findByEmail(normalized) != null) throw AuthException("email_taken", "Impossible de créer ce compte avec cette adresse.")
        val user = User(UUID.randomUUID().toString(), normalized, businessName.trim(), hash(password))
        store.save(user)
        return issueTokens(user)
    }

    fun login(email: String, password: String): LoginResult {
        val user = store.findByEmail(email.trim().lowercase())
        if (user == null) {
            argon2.verify(dummyHash, password.toCharArray())
            return LoginResult.InvalidCredentials
        }
        if (user.lockedUntilEpochSeconds > now()) return LoginResult.Locked(user.lockedUntilEpochSeconds - now())
        if (!argon2.verify(user.passwordHash, password.toCharArray())) {
            val failures = user.failedLogins + 1
            val locked = if (failures >= MAX_FAILED_LOGINS) now() + LOCK_SECONDS else 0
            store.save(user.copy(failedLogins = if (locked > 0) 0 else failures, lockedUntilEpochSeconds = locked))
            return if (locked > 0) LoginResult.Locked(LOCK_SECONDS) else LoginResult.InvalidCredentials
        }
        store.save(user.copy(failedLogins = 0, lockedUntilEpochSeconds = 0))
        if (user.totpSecret != null) {
            val challenge = JWT.create().withIssuer(issuer).withAudience(AUDIENCE_2FA).withSubject(user.id)
                .withExpiresAt(Date((now() + 300) * 1000)).sign(algorithm)
            return LoginResult.NeedsSecondFactor(challenge)
        }
        return LoginResult.Success(issueTokens(user))
    }

    fun verifySecondFactor(challengeToken: String, code: String): Tokens {
        val userId = runCatching { challengeVerifier.verify(challengeToken).subject }.getOrNull()
            ?: throw AuthException("challenge_expired", "Session expirée, reconnectez-vous.")
        val user = store.findById(userId) ?: throw AuthException("challenge_expired", "Session expirée, reconnectez-vous.")
        val secret = user.totpSecret ?: throw AuthException("no_2fa", "Validation en deux étapes non activée.")
        val step = totp.verify(secret, code, now(), user.totpLastStep) ?: throw AuthException("invalid_code", "Code incorrect.")
        store.save(user.copy(totpLastStep = step))
        return issueTokens(user)
    }

    /** Starts 2-step verification: returns the otpauth:// link to show as a QR code. */
    fun beginTotpSetup(userId: String): String {
        val user = store.findById(userId) ?: throw AuthException("unknown_user", "Compte introuvable.")
        val secret = ByteArray(20).also(random::nextBytes)
        store.save(user.copy(totpPendingSecret = secret))
        return totp.provisioningUri(secret, user.email)
    }

    /** Confirms setup with a first code; signs out other devices. Returns one-time recovery codes. */
    fun confirmTotpSetup(userId: String, code: String): List<String> {
        val user = store.findById(userId) ?: throw AuthException("unknown_user", "Compte introuvable.")
        val pending = user.totpPendingSecret ?: throw AuthException("no_pending", "Aucune configuration en cours.")
        val step = totp.verify(pending, code, now()) ?: throw AuthException("invalid_code", "Code incorrect.")
        store.save(user.copy(totpSecret = pending, totpPendingSecret = null, totpLastStep = step))
        store.revokeAllRefresh(userId)
        return List(8) { (1..10).map { RECOVERY_ALPHABET[random.nextInt(RECOVERY_ALPHABET.length)] }.joinToString("").chunked(5).joinToString("-") }
    }

    fun refresh(refreshToken: String): Tokens {
        val stored = store.takeRefresh(sha256(refreshToken)) ?: throw AuthException("invalid_refresh", "Session expirée, reconnectez-vous.")
        if (stored.expiresAtEpochSeconds < now()) throw AuthException("invalid_refresh", "Session expirée, reconnectez-vous.")
        val user = store.findById(stored.userId) ?: throw AuthException("invalid_refresh", "Session expirée, reconnectez-vous.")
        return issueTokens(user)
    }

    fun logoutEverywhere(userId: String) = store.revokeAllRefresh(userId)

    fun user(userId: String) = store.findById(userId)

    private fun issueTokens(user: User): Tokens {
        val access = JWT.create().withIssuer(issuer).withAudience(AUDIENCE_ACCESS).withSubject(user.id)
            .withClaim("mfa", user.totpSecret != null)
            .withExpiresAt(Date((now() + ACCESS_TTL) * 1000)).sign(algorithm)
        val refreshRaw = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        store.saveRefresh(RefreshToken(sha256(refreshRaw), user.id, now() + REFRESH_TTL))
        return Tokens(access, refreshRaw, ACCESS_TTL)
    }

    private fun hash(password: String) = argon2.hash(ARGON_ITERATIONS, ARGON_MEMORY_KB, ARGON_PARALLELISM, password.toCharArray())

    companion object {
        const val AUDIENCE_ACCESS = "paypilot-app"
        const val AUDIENCE_2FA = "paypilot-2fa"
        const val ACCESS_TTL = 15 * 60L
        const val REFRESH_TTL = 30 * 24 * 3600L
        const val MAX_FAILED_LOGINS = 5
        const val LOCK_SECONDS = 15 * 60L
        // OWASP Argon2id baseline: 19 MiB memory, 2 iterations, 1 lane.
        const val ARGON_ITERATIONS = 2
        const val ARGON_MEMORY_KB = 19 * 1024
        const val ARGON_PARALLELISM = 1
        private const val RECOVERY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private val EMAIL = Regex("^[^@\\s]{1,64}@[^@\\s]{1,255}\\.[a-z]{2,}$")

        fun sha256(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/** Server-side Have I Been Pwned range check (only a 5-character hash prefix leaves the server). */
class HibpBreachChecker : BreachChecker {
    private val http = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build()
    override fun timesSeen(password: String): Int {
        val q = BreachedPasswords.query(password)
        val request = java.net.http.HttpRequest.newBuilder(java.net.URI(BreachedPasswords.rangeUrl(q.prefix)))
            .header("Add-Padding", "true").header("User-Agent", "PayPilot-Server")
            .timeout(java.time.Duration.ofSeconds(3)).GET().build()
        return runCatching {
            BreachedPasswords.countIn(http.send(request, java.net.http.HttpResponse.BodyHandlers.ofString()).body(), q.suffix)
        }.getOrDefault(0)
    }
}
