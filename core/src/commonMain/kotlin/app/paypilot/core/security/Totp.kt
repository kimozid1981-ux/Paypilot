package app.paypilot.core.security

/**
 * Time-based one-time passwords (RFC 6238), compatible with Google Authenticator, Microsoft
 * Authenticator, 1Password, Aegis and others.
 */
class Totp(
    private val digits: Int = 6,
    private val periodSeconds: Long = 30,
    /** Accept codes from this many periods before/after now, to absorb clock drift. */
    private val allowedDrift: Int = 1,
) {
    fun generate(secret: ByteArray, epochSeconds: Long): String {
        val counter = epochSeconds / periodSeconds
        return hotp(secret, counter)
    }

    /**
     * Returns the matched time step, or null. The caller must store the last accepted step and
     * reject a code whose step is not greater, so a code cannot be replayed.
     */
    fun verify(secret: ByteArray, code: String, epochSeconds: Long, lastAcceptedStep: Long? = null): Long? {
        val cleaned = code.filter { it.isDigit() }
        if (cleaned.length != digits) return null
        val current = epochSeconds / periodSeconds
        for (offset in -allowedDrift..allowedDrift) {
            val step = current + offset
            if (lastAcceptedStep != null && step <= lastAcceptedStep) continue
            if (constantTimeEquals(hotp(secret, step), cleaned)) return step
        }
        return null
    }

    fun hotp(secret: ByteArray, counter: Long): String {
        val msg = ByteArray(8) { i -> (counter ushr (56 - 8 * i)).toByte() }
        val hash = Sha1.hmac(secret, msg)
        val offset = hash[hash.size - 1].toInt() and 0x0f
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        var mod = 1
        repeat(digits) { mod *= 10 }
        return (binary % mod).toString().padStart(digits, '0')
    }

    /** otpauth:// link shown as a QR code when the user turns on 2-step verification. */
    fun provisioningUri(secret: ByteArray, account: String, issuer: String = "PayPilot"): String {
        fun enc(s: String) = s.encodeToByteArray().joinToString("") { b ->
            val c = (b.toInt() and 0xff).toChar()
            if (c.isLetterOrDigit() && c.code < 128 || c in "-._~") c.toString() else "%" + (b.toInt() and 0xff).toString(16).uppercase().padStart(2, '0')
        }
        return "otpauth://totp/${enc(issuer)}:${enc(account)}?secret=${Base32.encode(secret)}&issuer=${enc(issuer)}&digits=$digits&period=$periodSeconds"
    }
}

fun constantTimeEquals(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
    return diff == 0
}
