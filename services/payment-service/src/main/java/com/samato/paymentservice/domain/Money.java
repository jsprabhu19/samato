package com.samato.paymentservice.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Money value object.
 *
 *   amount:   BigDecimal in RUPEES (₹).  NOT paise — that's the wire
 *             format. We use BigDecimal everywhere internally because:
 *               (1) it's the industry-standard answer for money in Java
 *               (2) we can keep the scale consistent (2 decimal places)
 *               (3) arithmetic is scale-aware, so 1.00 + 2.00 = 3.00,
 *                   not 3.0000000000000004 like with double
 *               (4) it survives JPA round-tripping without loss
 *
 *   currency: 3-letter ISO 4217 (always "INR" in the bible).
 *
 * The {@link #canonical(BigDecimal)} method normalises scale so 1, 1.0
 * and 1.00 all become the same record. Without it, two equal amounts
 * would be unequal in a Set, which is a classic money bug.
 *
 * Why not integer paise internally?  Because:
 *   - Currencies like JPY have no minor unit
 *   - BHD has 3 decimal places
 *   - BigDecimal is the "obviously correct" choice for a payment bible
 * We DO convert to paise when calling Razorpay (its API requires it) —
 * see {@link com.samato.paymentservice.api.RazorpayClientImpl}.
 */
public record Money(BigDecimal amount, String currency) {

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        if (currency.length() != 3) {
            throw new IllegalArgumentException("Currency must be a 3-letter ISO code: " + currency);
        }
        amount = canonical(amount);
    }

    public static Money of(BigDecimal amount, String currency) {
        return new Money(amount, currency);
    }

    public static Money ofRupees(String amount) {
        return new Money(new BigDecimal(amount), "INR");
    }

    /** BigDecimal with a fixed 2-decimal scale. HALF_EVEN is banker's rounding. */
    public static BigDecimal canonical(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_EVEN);
    }

    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money subtract(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    public boolean isPositive() { return amount.signum() > 0; }
    public boolean isZero()     { return amount.signum() == 0; }
    public boolean isNegative() { return amount.signum() < 0; }

    /** Convert rupees to paise (smallest unit). 1 INR = 100 paise. */
    public long toPaise() {
        return amount.multiply(BigDecimal.valueOf(100))
                     .setScale(0, RoundingMode.HALF_EVEN)
                     .longValueExact();
    }

    /** Construct a Money from a paise amount. */
    public static Money fromPaise(long paise, String currency) {
        return new Money(
                BigDecimal.valueOf(paise).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_EVEN),
                currency);
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "Currency mismatch: " + currency + " vs " + other.currency);
        }
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency;
    }
}
