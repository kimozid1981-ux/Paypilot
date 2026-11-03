package app.paypilot.core.security

/** RFC 4648 Base32, the encoding authenticator apps use for TOTP secrets. */
object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun encode(data: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                sb.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return sb.toString()
    }

    fun decode(text: String): ByteArray {
        val clean = text.uppercase().filter { it != '=' && !it.isWhitespace() && it != '-' }
        val out = ArrayList<Byte>(clean.length * 5 / 8)
        var buffer = 0
        var bits = 0
        for (c in clean) {
            val v = ALPHABET.indexOf(c)
            require(v >= 0) { "Invalid Base32 character" }
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                out.add((buffer shr (bits - 8)).toByte())
                bits -= 8
            }
        }
        return out.toByteArray()
    }
}
