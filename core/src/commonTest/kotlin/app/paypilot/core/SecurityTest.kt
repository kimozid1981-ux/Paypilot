package app.paypilot.core

import app.paypilot.core.security.AppLockPolicy
import app.paypilot.core.security.Base32
import app.paypilot.core.security.BreachedPasswords
import app.paypilot.core.security.PasswordPolicy
import app.paypilot.core.security.PasswordStrength
import app.paypilot.core.security.Sha1
import app.paypilot.core.security.Totp
import app.paypilot.core.security.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecurityTest {
    @Test fun sha1KnownVectors() {
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", Sha1.digest("abc".encodeToByteArray()).toHex())
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", Sha1.digest(ByteArray(0)).toHex())
        assertEquals(
            "84983e441c3bd26ebaae4aa1f95129e5e54670f1",
            Sha1.digest("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()).toHex(),
        )
    }

    @Test fun hmacSha1Rfc2202() {
        val key = ByteArray(20) { 0x0b }
        assertEquals("b617318655057264e28bc0b6fb378c8ef146be00", Sha1.hmac(key, "Hi There".encodeToByteArray()).toHex())
    }

    @Test fun totpRfc6238Vectors() {
        val secret = "12345678901234567890".encodeToByteArray()
        val totp = Totp(digits = 8)
        assertEquals("94287082", totp.generate(secret, 59))
        assertEquals("07081804", totp.generate(secret, 1111111109))
        assertEquals("14050471", totp.generate(secret, 1111111111))
        assertEquals("89005924", totp.generate(secret, 1234567890))
        assertEquals("69279037", totp.generate(secret, 2000000000))
    }

    @Test fun totpRejectsReplayAndWrongCodes() {
        val secret = "12345678901234567890".encodeToByteArray()
        val totp = Totp()
        val now = 1_800_000_000L
        val code = totp.generate(secret, now)
        val step = assertNotNull(totp.verify(secret, code, now))
        assertNull(totp.verify(secret, code, now, lastAcceptedStep = step), "replay must fail")
        assertNull(totp.verify(secret, "000000", now))
        assertNotNull(totp.verify(secret, totp.generate(secret, now - 30), now), "one step of drift is allowed")
        assertNull(totp.verify(secret, totp.generate(secret, now - 120), now))
    }

    @Test fun base32RoundTrip() {
        val data = "12345678901234567890".encodeToByteArray()
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", Base32.encode(data))
        assertTrue(data.contentEquals(Base32.decode("gezd gnbv gy3t qojq gezd gnbv gy3t qojq")))
    }

    @Test fun passwordPolicy() {
        assertFalse(PasswordPolicy.check("azerty123").accepted)
        assertFalse(PasswordPolicy.check("Password1234!").accepted)
        assertFalse(PasswordPolicy.check("SamiAtlas2027!x", listOf("sami@atlas-peinture.fr")).accepted)
        assertFalse(PasswordPolicy.check("aaaaaaaaaaaaaaaa").accepted)
        val phrase = PasswordPolicy.check("camion bleu sous la pluie")
        assertTrue(phrase.accepted, phrase.toString())
        val strong = PasswordPolicy.check("v7#Kq!m2Rz9@pLw4")
        assertEquals(PasswordStrength.STRONG, strong.strength, strong.toString())
    }

    @Test fun breachedPasswordKAnonymity() {
        val q = BreachedPasswords.query("password")
        assertEquals("5BAA6", q.prefix)
        assertEquals("1E4C9B93F3F0682250B6CF8331B7EE68FD8", q.suffix)
        val response = "0018A45C4D1DEF81644B54AB7F969B88D65:1\r\n1E4C9B93F3F0682250B6CF8331B7EE68FD8:9545824\r\n"
        assertEquals(9545824, BreachedPasswords.countIn(response, q.suffix))
        assertEquals(0, BreachedPasswords.countIn(response, "FFFFF"))
    }

    @Test fun appLockBackoffAndWipe() {
        val policy = AppLockPolicy()
        assertTrue(policy.shouldLock(0, 120))
        assertFalse(policy.shouldLock(0, 119))
        assertEquals(AppLockPolicy.AttemptResult.Wrong(8, 0), policy.onFailedAttempt(2))
        assertEquals(AppLockPolicy.AttemptResult.Wrong(6, 30), policy.onFailedAttempt(4))
        assertEquals(AppLockPolicy.AttemptResult.Wrong(5, 60), policy.onFailedAttempt(5))
        assertEquals(AppLockPolicy.AttemptResult.Wipe, policy.onFailedAttempt(10))
    }
}

class Pbkdf2Test {
    @Test fun rfc6070Vectors() {
        val p = "password".encodeToByteArray()
        val s = "salt".encodeToByteArray()
        assertEquals("0c60c80f961f0e71f3a9b524af6012062fe037a6", app.paypilot.core.security.Pbkdf2.derive(p, s, 1).toHex())
        assertEquals("4b007901b765489abead49d926f721d065a429c1", app.paypilot.core.security.Pbkdf2.derive(p, s, 4096).toHex())
    }
}
