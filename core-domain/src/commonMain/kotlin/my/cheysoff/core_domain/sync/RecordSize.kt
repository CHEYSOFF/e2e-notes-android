package my.cheysoff.core_domain.sync

/**
 * A cheap upper-ish estimate of how many bytes one record's payload will occupy.
 *
 * ## Why an estimate rather than the real number
 *
 * The engine batches records; the transport seals them. By the time an envelope's true size is
 * known the batch has already been sent, so the engine has to size a batch from something it can
 * see. It can see the field values, and for every record this estimate is used on, the field values
 * *are* the payload -- an attachment's base64 image is three orders of magnitude larger than the
 * JSON scaffolding around it.
 *
 * Every string is counted in UTF-8 bytes, not `String.length` (UTF-16 code units) -- see
 * [utf8Length] for why that distinction matters here. With that fixed, the only remaining error is
 * the JSON scaffolding around each value, which genuinely is a small constant, and the budgets
 * that consume this ([SyncEngine.PUSH_BYTE_BUDGET] against the server's `maxRequestBytes`) are set
 * with a factor of two of headroom so that error cannot decide anything. Do not tighten those
 * budgets to the point where this number's accuracy starts to matter; make this exact instead.
 *
 * Base64 expansion is deliberately NOT applied here. The budget it feeds is expressed in the same
 * units this returns, and the 4/3 is accounted for once, where the budget is chosen.
 */
object RecordSize {

    /** Rough cost of a JSON key, its quotes, its colon and its comma. */
    private const val KEY_OVERHEAD = 8

    /** Rough cost of the envelope header, the clock map and the record's own scaffolding. */
    private const val RECORD_OVERHEAD = 256

    fun estimateBytes(record: SyncRecord): Int {
        var total = RECORD_OVERHEAD + utf8Length(record.uuid)
        record.fields.forEach { (key, value) ->
            total += utf8Length(key) + KEY_OVERHEAD
            value.parts.forEach { part -> total += part?.let { utf8Length(it) } ?: 0 }
        }
        record.fieldClocks.keys.forEach { key -> total += utf8Length(key) + KEY_OVERHEAD }
        return total
    }

    /**
     * The UTF-8 byte length of [text], counted without allocating.
     *
     * `String.length` is UTF-16 code units, which is the same number only for ASCII. Using it here
     * made every budget in this file wrong by a factor of two for Cyrillic and three for CJK --
     * and the budgets exist to keep a batch under the server's request cap, so being wrong in that
     * direction meant a `413`, which is not attributable to a record and therefore halts the pass
     * permanently.
     *
     * A surrogate pair encodes to four UTF-8 bytes and is counted as two per half, which comes to
     * the same four. A lone surrogate is malformed input and is counted as two; it cannot be
     * round-tripped anyway.
     */
    private fun utf8Length(text: String): Int {
        var total = 0
        for (ch in text) {
            val code = ch.code
            total += when {
                code < 0x80 -> 1
                code < 0x800 -> 2
                ch.isSurrogate() -> 2
                else -> 3
            }
        }
        return total
    }
}
