package app.paypilot.core.security

enum class PasswordStrength { TOO_WEAK, WEAK, GOOD, STRONG }

data class PasswordCheck(
    val strength: PasswordStrength,
    val score: Int,
    /** Problems in French, shown under the field. Empty when the password is accepted. */
    val problems: List<String>,
) {
    val accepted: Boolean get() = problems.isEmpty() && strength >= PasswordStrength.GOOD
}

/**
 * Password rules follow NIST SP 800-63B: length first, no forced symbol rules, block common,
 * personal and breached passwords. A passphrase like "camion bleu sous la pluie" is accepted.
 */
object PasswordPolicy {
    const val MIN_LENGTH = 12
    const val MAX_LENGTH = 128

    private val common = setOf(
        "password", "motdepasse", "azerty", "azertyuiop", "qwerty", "123456", "12345678", "123456789",
        "1234567890", "000000", "111111", "soleil", "bonjour", "marseille", "doudou", "chouchou",
        "loulou", "iloveyou", "admin", "welcome", "letmein", "football", "paypilot", "facture",
    )
    private val sequences = listOf("0123456789", "abcdefghijklmnopqrstuvwxyz", "azertyuiop", "qwertyuiop", "qsdfghjklm", "asdfghjkl")

    fun check(password: String, personalInfo: List<String> = emptyList()): PasswordCheck {
        val problems = mutableListOf<String>()
        if (password.length < MIN_LENGTH) problems += "Au moins $MIN_LENGTH caractères (une phrase marche très bien)."
        if (password.length > MAX_LENGTH) problems += "$MAX_LENGTH caractères maximum."
        val lower = password.lowercase()
        val stripped = lower.filter { it.isLetterOrDigit() }
        val letters = stripped.filter { it.isLetter() }
        val isCommonWithDecoration = common.any { word ->
            stripped == word || letters == word || (stripped.contains(word) && stripped.replace(word, "").all { it.isDigit() })
        }
        if (isCommonWithDecoration) {
            problems += "Ce mot de passe est trop courant."
        }
        personalInfo.map { it.lowercase().trim() }.filter { it.length >= 3 }.forEach { info ->
            val parts = info.split('@', '.', ' ', '-', '_').filter { it.length >= 3 }
            if (parts.any { lower.contains(it) }) problems += "N'utilisez pas votre nom, e-mail ou entreprise."
        }
        if (password.isNotEmpty() && password.toSet().size <= 2) problems += "Trop de caractères répétés."

        var score = 0
        score += minOf(password.length, 24) * 3
        score += classes(password) * 6
        score += minOf(password.toSet().size, 16) * 2
        score -= sequencePenalty(lower)
        score -= repeatPenalty(password)
        if (password.count { it == ' ' } >= 2 && password.length >= 16) score += 15 // passphrase bonus
        score = score.coerceIn(0, 100)

        val strength = when {
            problems.isNotEmpty() || score < 45 -> if (score < 30) PasswordStrength.TOO_WEAK else PasswordStrength.WEAK
            score < 70 -> PasswordStrength.GOOD
            else -> PasswordStrength.STRONG
        }
        if (problems.isEmpty() && strength < PasswordStrength.GOOD) problems += "Ajoutez quelques mots ou caractères variés."
        return PasswordCheck(strength, score, problems.distinct())
    }

    private fun classes(p: String): Int =
        listOf(p.any { it.isLowerCase() }, p.any { it.isUpperCase() }, p.any { it.isDigit() }, p.any { !it.isLetterOrDigit() }).count { it }

    private fun sequencePenalty(lower: String): Int {
        var penalty = 0
        for (seq in sequences) for (len in 4..seq.length) for (start in 0..seq.length - len) {
            val part = seq.substring(start, start + len)
            if (lower.contains(part) || lower.contains(part.reversed())) penalty = maxOf(penalty, len * 4)
        }
        return penalty
    }

    private fun repeatPenalty(p: String): Int {
        var longest = 1
        var run = 1
        for (i in 1 until p.length) {
            run = if (p[i] == p[i - 1]) run + 1 else 1
            longest = maxOf(longest, run)
        }
        return if (longest >= 3) longest * 4 else 0
    }
}

/**
 * Have I Been Pwned "range" check with k-anonymity: only the first 5 hex characters of the
 * SHA-1 hash leave the device. https://haveibeenpwned.com/API/v3#PwnedPasswords
 */
object BreachedPasswords {
    data class Query(val prefix: String, val suffix: String)

    fun query(password: String): Query {
        val hex = Sha1.digest(password.encodeToByteArray()).toHex().uppercase()
        return Query(hex.take(5), hex.drop(5))
    }

    fun rangeUrl(prefix: String) = "https://api.pwnedpasswords.com/range/$prefix"

    /** Number of times the password appeared in known breaches (0 = not found). */
    fun countIn(rangeResponse: String, suffix: String): Int =
        rangeResponse.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.substringBefore(':').equals(suffix, ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
}
