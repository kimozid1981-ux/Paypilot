package app.paypilot.server.auth

import java.util.concurrent.ConcurrentHashMap

data class User(
    val id: String,
    val email: String,
    val businessName: String,
    val passwordHash: String,
    val totpSecret: ByteArray? = null,
    val totpPendingSecret: ByteArray? = null,
    val totpLastStep: Long? = null,
    val failedLogins: Int = 0,
    val lockedUntilEpochSeconds: Long = 0,
)

data class RefreshToken(val tokenHash: String, val userId: String, val expiresAtEpochSeconds: Long)

/** Storage boundary. v0.1 ships the in-memory version; a PostgreSQL one replaces it before production. */
interface UserStore {
    fun findByEmail(email: String): User?
    fun findById(id: String): User?
    fun save(user: User)
    fun saveRefresh(token: RefreshToken)
    /** Removes and returns the token, so each refresh token works once (rotation). */
    fun takeRefresh(tokenHash: String): RefreshToken?
    fun revokeAllRefresh(userId: String)
}

class InMemoryUserStore : UserStore {
    private val users = ConcurrentHashMap<String, User>()
    private val refresh = ConcurrentHashMap<String, RefreshToken>()
    override fun findByEmail(email: String) = users.values.firstOrNull { it.email == email }
    override fun findById(id: String) = users[id]
    override fun save(user: User) { users[user.id] = user }
    override fun saveRefresh(token: RefreshToken) { refresh[token.tokenHash] = token }
    override fun takeRefresh(tokenHash: String) = refresh.remove(tokenHash)
    override fun revokeAllRefresh(userId: String) { refresh.values.removeIf { it.userId == userId } }
}
