package app.paypilot.core.security

/**
 * Pure Kotlin SHA-1 and HMAC-SHA1, used for TOTP (RFC 6238 requires HMAC-SHA1 for compatibility
 * with authenticator apps) and for the k-anonymity breached-password check. Not used to store
 * passwords: the server hashes those with Argon2id.
 */
object Sha1 {
    fun digest(input: ByteArray): ByteArray {
        var h0 = 0x67452301
        var h1 = 0xEFCDAB89.toInt()
        var h2 = 0x98BADCFE.toInt()
        var h3 = 0x10325476
        var h4 = 0xC3D2E1F0.toInt()

        val ml = input.size.toLong() * 8
        val padLen = ((56 - (input.size + 1) % 64) + 64) % 64
        val msg = ByteArray(input.size + 1 + padLen + 8)
        input.copyInto(msg)
        msg[input.size] = 0x80.toByte()
        for (i in 0 until 8) msg[msg.size - 1 - i] = (ml ushr (8 * i)).toByte()

        val w = IntArray(80)
        for (chunk in msg.indices step 64) {
            for (i in 0 until 16) {
                val j = chunk + i * 4
                w[i] = ((msg[j].toInt() and 0xff) shl 24) or ((msg[j + 1].toInt() and 0xff) shl 16) or
                    ((msg[j + 2].toInt() and 0xff) shl 8) or (msg[j + 3].toInt() and 0xff)
            }
            for (i in 16 until 80) w[i] = (w[i - 3] xor w[i - 8] xor w[i - 14] xor w[i - 16]).rotateLeft(1)
            var a = h0; var b = h1; var c = h2; var d = h3; var e = h4
            for (i in 0 until 80) {
                val (f, k) = when {
                    i < 20 -> ((b and c) or (b.inv() and d)) to 0x5A827999
                    i < 40 -> (b xor c xor d) to 0x6ED9EBA1
                    i < 60 -> ((b and c) or (b and d) or (c and d)) to 0x8F1BBCDC.toInt()
                    else -> (b xor c xor d) to 0xCA62C1D6.toInt()
                }
                val temp = a.rotateLeft(5) + f + e + k + w[i]
                e = d; d = c; c = b.rotateLeft(30); b = a; a = temp
            }
            h0 += a; h1 += b; h2 += c; h3 += d; h4 += e
        }
        val out = ByteArray(20)
        intArrayOf(h0, h1, h2, h3, h4).forEachIndexed { idx, h ->
            for (i in 0 until 4) out[idx * 4 + i] = (h ushr (24 - 8 * i)).toByte()
        }
        return out
    }

    fun hmac(key: ByteArray, message: ByteArray): ByteArray {
        val blockSize = 64
        var k = if (key.size > blockSize) digest(key) else key
        k = k.copyOf(blockSize)
        val ipad = ByteArray(blockSize) { (k[it].toInt() xor 0x36).toByte() }
        val opad = ByteArray(blockSize) { (k[it].toInt() xor 0x5c).toByte() }
        return digest(opad + digest(ipad + message))
    }
}

fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
