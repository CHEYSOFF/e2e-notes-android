package my.cheysoff.core_blocks

/**
 * Fractional-index keys: strings that sort (by plain code-point comparison) in the order of the
 * blocks they position, with room to insert between any two neighbours without touching either.
 *
 * Why strings and not numbers: two devices insert into the same gap offline and both must succeed
 * with no renumbering, and a renumbering would rewrite every block's position clock and turn one
 * insertion into a note-wide merge conflict.
 *
 * Keys are base-62 fractions over the digits `0-9A-Za-z`, which is also their ASCII order, so
 * `String.compareTo` is the fraction order on every platform. A key never ends in `'0'`: trailing
 * zeros do not change the value, so `"a"` and `"a0"` would be the same position under two names and
 * nothing could be inserted strictly between them.
 */
object FractionalIndex {

    private const val DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private const val BASE = 62

    /**
     * A key strictly between [before] and [after]; null means that side is open. An empty string
     * counts as open on the [before] side ("no position yet").
     *
     * @throws IllegalArgumentException if a bound is not a canonical key or [before] >= [after].
     */
    fun between(before: String?, after: String?): String {
        val a = before.orEmpty()
        require(a.isEmpty() || isValid(a)) { "Not a valid fractional index: '$a'" }
        if (after != null) {
            require(isValid(after)) { "Not a valid fractional index: '$after'" }
            require(a < after) { "before ('$a') must sort before after ('$after')" }
        }
        return midpoint(a, after)
    }

    /**
     * Whether [key] is a usable position: non-empty, only digits, no trailing '0'. Stamping uses
     * it to spot positions that arrived in a synced body (the codec does not validate them) so one
     * malformed peer value cannot make every later save of the note throw.
     */
    internal fun isValid(key: String) = key.isNotEmpty() && key.all { it in DIGITS } && !key.endsWith('0')

    private fun digit(c: Char) = DIGITS.indexOf(c)

    /** [a] may be empty (the zero fraction); [b] null is the open upper end. Requires a < b. */
    private fun midpoint(a: String, b: String?): String {
        if (b != null) {
            // Keys agreeing on a prefix (a read as padded with zeros) share it, so solve the tails.
            var n = 0
            while ((if (n < a.length) a[n] else '0') == b[n]) n++
            if (n > 0) return b.substring(0, n) + midpoint(a.drop(n), b.substring(n))
        }
        val digitA = if (a.isNotEmpty()) digit(a[0]) else 0
        val digitB = if (b != null) digit(b[0]) else BASE
        if (digitB - digitA > 1) {
            // Room for a whole digit between the two; it is >= 1 so the key cannot end in '0'.
            return DIGITS[(digitA + digitB + 1) / 2].toString()
        }
        // Adjacent leading digits. If b is longer, its first digit alone is already below b and
        // above a. Otherwise keep a's digit and find a midpoint in the rest of a.
        if (b != null && b.length > 1) return b.substring(0, 1)
        return DIGITS[digitA] + midpoint(a.drop(1), null)
    }
}
