package app.paypilot.core.model

import kotlinx.serialization.Serializable
import kotlin.math.abs

/** Amount in minor units (cents) to avoid floating point errors. */
@Serializable
data class Money(val cents: Long, val currency: String = "EUR") : Comparable<Money> {
    operator fun plus(other: Money): Money = sameCurrency(other).let { Money(cents + other.cents, currency) }
    operator fun minus(other: Money): Money = sameCurrency(other).let { Money(cents - other.cents, currency) }
    override fun compareTo(other: Money): Int = sameCurrency(other).let { cents.compareTo(other.cents) }
    val isZero: Boolean get() = cents == 0L

    private fun sameCurrency(other: Money) {
        require(currency == other.currency) { "Currency mismatch: $currency vs ${other.currency}" }
    }

    /** French formatting: "1 980,50 €" (narrow no-break space as thousands separator). */
    fun format(showCents: Boolean = cents % 100 != 0L): String {
        val sign = if (cents < 0) "−" else ""
        val units = abs(cents) / 100
        val rest = abs(cents) % 100
        val grouped = units.toString().reversed().chunked(3).joinToString(" ").reversed()
        val symbol = if (currency == "EUR") "€" else currency
        return if (showCents) "$sign$grouped,${rest.toString().padStart(2, '0')} $symbol" else "$sign$grouped $symbol"
    }

    companion object {
        fun eur(euros: Long, cents: Long = 0) = Money(euros * 100 + cents, "EUR")
        fun zero(currency: String = "EUR") = Money(0, currency)
    }
}

fun Iterable<Money>.sum(currency: String = "EUR"): Money = fold(Money.zero(currency)) { acc, m -> acc + m }
