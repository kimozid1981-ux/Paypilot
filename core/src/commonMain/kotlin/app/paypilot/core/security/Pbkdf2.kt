package app.paypilot.core.security

/** PBKDF2-HMAC-SHA1 (RFC 8018), used to store the local unlock PIN as a salted, slow hash. */
object Pbkdf2 {
    fun derive(password: ByteArray, salt: ByteArray, iterations: Int, keyLength: Int = 20): ByteArray {
        require(iterations > 0 && keyLength > 0)
        val blocks = (keyLength + 19) / 20
        val out = ByteArray(blocks * 20)
        for (i in 1..blocks) {
            val intBlock = byteArrayOf((i ushr 24).toByte(), (i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte())
            var u = Sha1.hmac(password, salt + intBlock)
            val t = u.copyOf()
            repeat(iterations - 1) {
                u = Sha1.hmac(password, u)
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            t.copyInto(out, (i - 1) * 20)
        }
        return out.copyOf(keyLength)
    }
}
