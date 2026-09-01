package my.cheysoff.desktop.vault

import my.cheysoff.core_crypto.PassphraseCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PassphrasePolicyTest {

    @Test
    fun `a passphrase of the minimum length is accepted`() {
        assertEquals(
            PassphrasePolicy.Verdict.Accepted,
            PassphrasePolicy.check("correct-horse".toCharArray()),
        )
    }

    @Test
    fun `one character short is refused`() {
        val justShort = CharArray(PassphrasePolicy.MIN_LENGTH - 1) { 'a' }
        assertEquals(PassphrasePolicy.Verdict.TooShort, PassphrasePolicy.check(justShort))
    }

    @Test
    fun `exactly the minimum length is accepted`() {
        val exact = CharArray(PassphrasePolicy.MIN_LENGTH) { 'a' }
        assertEquals(PassphrasePolicy.Verdict.Accepted, PassphrasePolicy.check(exact))
    }

    @Test
    fun `an empty passphrase is refused for length rather than for its digits`() {
        assertEquals(PassphrasePolicy.Verdict.TooShort, PassphrasePolicy.check(CharArray(0)))
    }

    /**
     * The rule this module exists for: a long PIN is still a PIN. Sixteen digits is well over
     * [PassphrasePolicy.MIN_LENGTH] and is still 10^16 candidates against an offline attacker with
     * the file in hand.
     */
    @Test
    fun `digits alone are refused however long`() {
        assertEquals(
            PassphrasePolicy.Verdict.AllDigits,
            PassphrasePolicy.check("1234567890123456".toCharArray()),
        )
    }

    @Test
    fun `one non-digit is enough to leave the all-digits shape`() {
        assertEquals(
            PassphrasePolicy.Verdict.Accepted,
            PassphrasePolicy.check("123456789012a".toCharArray()),
        )
    }

    /**
     * The desktop must cost strictly more per guess than the phone, because it has neither a
     * hardware-bound key nor a lockout to slow an attacker down. Asserted as an inequality against
     * the phone's constant rather than as a literal, so that raising the phone's number later
     * cannot silently leave the desktop below it.
     */
    @Test
    fun `the desktop iteration count is above the phone's`() {
        assertTrue(
            "desktop ${PassphrasePolicy.ITERATIONS} must exceed phone ${PassphraseCipher.ITERATIONS}",
            PassphrasePolicy.ITERATIONS > PassphraseCipher.ITERATIONS,
        )
    }
}
