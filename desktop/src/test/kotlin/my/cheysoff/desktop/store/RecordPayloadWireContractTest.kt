package my.cheysoff.desktop.store

import my.cheysoff.core_domain.sync.Hlc
import my.cheysoff.core_domain.sync.RecordType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the desktop codec's output to the exact bytes `:core-sync-codec`'s
 * `RecordPayloadWireFormatTest` pins the Android codec's output to.
 *
 * There are, right now, two implementations of this payload format: this one and the one in
 * `:core-sync-codec` that the Android app uses. They agree — every wire key is identical and both
 * build the JSON object key by key — but "they agree" was a thing believed rather than checked. The
 * shared module's KDoc even asserted that this fixture was already pinned on the desktop side. It
 * was not, and a comment claiming a test that does not exist is worse than no comment: it is the
 * shape of defect this codebase has shipped three times.
 *
 * So the golden below is duplicated on purpose, byte for byte, from
 * `core-sync-codec/src/.../RecordPayloadWireFormatTest.kt`. If either implementation drifts, one of
 * the two tests goes red and names the divergence — instead of a note written on the phone arriving
 * at the laptop as `RecordFault.UNREADABLE` on a record that decrypted perfectly well.
 *
 * **This test is scaffolding for a duplication that should not survive.** The right end state is
 * one codec: the desktop maps `Note` to payload directly while `:core-sync-codec` goes through
 * `SyncRecord`, so collapsing them is a real refactor rather than a delete, and it is not something
 * to attempt while merging two branches. Delete this file with the desktop's copy of the codec.
 *
 * Changing any byte of [GOLDEN] is a protocol break, not a test update. Every record already on a
 * server was written in the old form.
 */
class RecordPayloadWireContractTest {

    private companion object {
        const val GOLDEN: String =
            """{"v":1,"recType":"note","uuid":"note-1","hlc":"1756612345678-3-a1b2c3d4",""" +
                """"fields":{"title":"Groceries","content":"<p>milk</p>","contentFormat":"html",""" +
                """"checklist":"[x] one","isPinned":"1","isFavorite":"1","folderId":"folder-9",""" +
                """"createdAt":"100","updatedAt":"200","isDeleted":"0","deletedAt":null},""" +
                """"clocks":{"title":"1756612000000-0-beef"},"del":false,"serializer":1}"""
    }

    @Test
    fun `the desktop codec writes the same bytes the shared codec is pinned to`() {
        val payload = RecordPayload(
            recType = RecordType.NOTE,
            uuid = "note-1",
            rowClock = Hlc(1_756_612_345_678L, 3, "a1b2c3d4"),
            fields = mapOf(
                PayloadFields.TITLE to "Groceries",
                PayloadFields.CONTENT to "<p>milk</p>",
                PayloadFields.CONTENT_FORMAT to "html",
                PayloadFields.CHECKLIST to "[x] one",
                PayloadFields.IS_PINNED to "1",
                PayloadFields.IS_FAVORITE to "1",
                PayloadFields.FOLDER_ID to "folder-9",
                PayloadFields.CREATED_AT to "100",
                PayloadFields.UPDATED_AT to "200",
                PayloadFields.IS_DELETED to "0",
                PayloadFields.DELETED_AT to null,
            ),
            clocks = mapOf("title" to Hlc(1_756_612_000_000L, 0, "beef")),
        )

        assertEquals(GOLDEN, RecordPayloadCodec.encode(payload).decodeToString())
    }
}
