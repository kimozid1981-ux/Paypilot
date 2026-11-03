package app.paypilot.server

/** All secrets come from the environment, never from the code or the app. */
data class ServerConfig(
    val port: Int,
    val jwtSecret: String,
    val jwtIssuer: String = "paypilot",
    val anthropicApiKeyPresent: Boolean,
    val checkBreachedPasswords: Boolean,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): ServerConfig {
            val secret = env["PAYPILOT_JWT_SECRET"]
                ?: error("PAYPILOT_JWT_SECRET is not set (use at least 32 random bytes, e.g. `openssl rand -base64 48`).")
            require(secret.length >= 32) { "PAYPILOT_JWT_SECRET must be at least 32 characters." }
            return ServerConfig(
                port = env["PORT"]?.toIntOrNull() ?: 8080,
                jwtSecret = secret,
                anthropicApiKeyPresent = !env["ANTHROPIC_API_KEY"].isNullOrBlank(),
                checkBreachedPasswords = env["PAYPILOT_CHECK_BREACHED"] != "false",
            )
        }
    }
}
