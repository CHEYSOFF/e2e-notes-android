package my.cheysoff.core_data

import my.cheysoff.core_domain.sync.FieldClocks
import my.cheysoff.core_domain.sync.FieldValue
import my.cheysoff.core_domain.sync.Hlc
import my.cheysoff.core_domain.sync.RecordType
import my.cheysoff.core_domain.sync.SyncRecord

/**
 * Whole, valid `SyncRecord`s for the instrumented tests.
 *
 * `SyncRecord.validate` requires a value for **every** field of the type, so building one inline
 * is eight lines of boilerplate per record and eight chances to write a subtly different fixture
 * in each test. One builder is what keeps the tests about the store.
 */
internal object RecordsForTest {

    fun note(uuid: String, content: String, rowClock: Hlc): SyncRecord = SyncRecord(
        type = RecordType.NOTE,
        uuid = uuid,
        rowClock = rowClock,
        fieldClocks = emptyMap(),
        fields = mapOf(
            FieldClocks.TITLE to FieldValue.of("Groceries"),
            FieldClocks.CONTENT to FieldValue.of(content, "plain"),
            FieldClocks.CHECKLIST to FieldValue.of(""),
            FieldClocks.PINNED to FieldValue.of("0"),
            FieldClocks.FAVORITE to FieldValue.of("0"),
            FieldClocks.FOLDER to FieldValue.of(null),
            FieldClocks.UPDATED_AT to FieldValue.of("100"),
            FieldClocks.DELETED to FieldValue.of("0", null),
        ),
    )
}
