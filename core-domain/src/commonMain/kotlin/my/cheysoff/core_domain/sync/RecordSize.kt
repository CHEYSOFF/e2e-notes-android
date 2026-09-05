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
 * The error is therefore a small constant, and the budgets that consume this
 * ([SyncEngine.PUSH_BYTE_BUDGET] against the server's `maxRequestBytes`) are set with a factor of
 * two of headroom so that the error cannot decide anything. Do not tighten those budgets to the
 * point where this number's accuracy starts to matter; make this exact instead.
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
        var total = RECORD_OVERHEAD + record.uuid.length
        record.fields.forEach { (key, value) ->
            total += key.length + KEY_OVERHEAD
            value.parts.forEach { part -> total += part?.length ?: 0 }
        }
        record.fieldClocks.keys.forEach { key -> total += key.length + KEY_OVERHEAD }
        return total
    }
}
