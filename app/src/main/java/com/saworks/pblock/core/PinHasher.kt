package com.saworks.pblock.core

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Parent PIN storage: PBKDF2-HMAC-SHA256 with a random 16-byte salt.
 * Stored as "pbkdf2-sha256$<iterations>$<salt b64>$<hash b64>"; the PIN itself is never stored.
 */
object PinHasher {
    const val ITERATIONS = 120_000
    const val MIN_LEN = 4
    const val MAX_LEN = 12
    private const val PREFIX = "pbkdf2-sha256"
    private const val KEY_BITS = 256
    private val rnd = SecureRandom()

    fun isValidPin(pin: String): Boolean = pin.length in MIN_LEN..MAX_LEN && pin.all { it in '0'..'9' }

    fun hash(pin: String, iterations: Int = ITERATIONS, salt: ByteArray = ByteArray(16).also { rnd.nextBytes(it) }): String {
        val h = derive(pin, salt, iterations)
        val enc = Base64.getEncoder()
        return "$PREFIX\$$iterations\$${enc.encodeToString(salt)}\$${enc.encodeToString(h)}"
    }

    fun verify(pin: String, stored: String?): Boolean {
        if (stored.isNullOrEmpty()) return false
        val p = stored.split('$')
        if (p.size != 4 || p[0] != PREFIX) return false
        val iter = p[1].toIntOrNull()?.takeIf { it in 1..10_000_000 } ?: return false
        return try {
            val dec = Base64.getDecoder()
            val salt = dec.decode(p[2])
            val expected = dec.decode(p[3])
            MessageDigest.isEqual(derive(pin, salt, iter), expected) // constant time
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}

/** Rate limit for wrong PINs: 3 free tries, then 30 s, doubling per failure, at most 1 hour. */
object PinThrottle {
    const val FREE_TRIES = 3
    const val BASE_MS = 30_000L
    const val MAX_MS = 60 * 60_000L

    fun delayAfter(failures: Int): Long {
        if (failures < FREE_TRIES) return 0L
        val shift = (failures - FREE_TRIES).coerceAtMost(20)
        return (BASE_MS shl shift).coerceAtMost(MAX_MS)
    }

    /** Milliseconds until the next try is allowed. A clock moved backwards never extends the wait beyond the delay. */
    fun waitMs(failures: Int, lastFailAt: Long, now: Long): Long {
        val d = delayAfter(failures)
        if (d == 0L || lastFailAt <= 0L) return 0L
        val passed = (now - lastFailAt).coerceAtLeast(0L)
        return (d - passed).coerceAtLeast(0L)
    }
}
