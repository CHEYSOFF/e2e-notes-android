package my.cheysoff.core_sync_net

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every JSON field name lives in `SyncWire.kt` and nowhere else in this module's main source.
 *
 * ## Why this is worth a test
 *
 * The wire format is not settled. `hlc`, `recType` and the plaintext device `label` are all
 * candidates for removal, because the server stores them in the clear and, if it never reads them,
 * they do not belong outside the sealed envelope -- `hlc` in particular tells the operator which of
 * the user's devices made every individual edit. Nothing has ever synced, so the change is a rebase
 * today and a migration across every device after Phase 3 ships.
 *
 * Whoever makes that change should be able to delete a constant and follow the compiler. That is
 * only true while no other file spells a field name out, and "no other file spells it out" is the
 * kind of property that is true when it is written and false three commits later. So it is
 * asserted rather than hoped for.
 *
 * ## How it reads the source
 *
 * By scanning the files, which is unusual and deserves its justification: there is no other way to
 * observe "this string does not appear in that file". The scan is narrow -- one module's main
 * source, string literals only, comment lines skipped -- and its failure message names the file and
 * the field, so a genuine new occurrence is a one-line fix rather than a puzzle.
 */
class WireFieldNamesAreInOnePlaceTest {

    /**
     * The protocol's whole vocabulary of JSON field names, from `server/.../Wire.kt`.
     *
     * Written out here rather than read from `SyncWire`'s private constants on purpose: this test
     * is a second, independent statement of what the field names are, so a rename that updated the
     * constant and forgot a call site still has something to disagree with.
     */
    private val fieldNames = listOf(
        "error", "message",
        "status", "version",
        "accountId", "deviceId", "devicePublicKey", "newPublicKey", "voucherDeviceId",
        "deviceLabel", "ts", "signature", "createdAt",
        "challenge", "expiresAt", "token",
        "devices", "label", "publicKey", "revokedAt", "self",
        "blindedId", "recType", "hlc", "seq", "envelope", "receivedAt",
        "records", "nextCursor", "hasMore",
        "items", "baseSeq", "results", "accountSeq", "current",
        "versions",
    )

    @Test
    fun `no main-source file except SyncWire names a JSON field`() {
        val sourceRoot = File(repositoryRoot(), "core-sync-net/src/main/java")
        assertTrue("the module's source root moved: $sourceRoot", sourceRoot.isDirectory)

        val offenders = mutableListOf<String>()
        sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "SyncWire.kt" }
            .forEach { file ->
                val literals = codeStringLiterals(file.readText())
                for (name in fieldNames) {
                    if (name in literals) offenders += "${file.name} spells the wire field '$name'"
                }
            }

        assertTrue(
            "wire field names must appear only in SyncWire.kt, so removing one is a small diff:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `SyncWire really does hold every field name`() {
        val syncWire = File(repositoryRoot(), "core-sync-net/src/main/java/my/cheysoff/core_sync_net/wire/SyncWire.kt")
        assertTrue("SyncWire.kt moved: $syncWire", syncWire.isFile)
        val literals = codeStringLiterals(syncWire.readText())

        val missing = fieldNames.filterNot { it in literals }
        assertTrue(
            "these field names are not declared in SyncWire.kt -- either the protocol changed and " +
                "this test's list is stale, or a name is being built somewhere else: $missing",
            missing.isEmpty(),
        )
    }

    /**
     * Every double-quoted literal on a line that is not a comment.
     *
     * Deliberately crude. It does not understand raw strings, escapes or a `//` inside a literal,
     * and it does not need to: an over-broad match here produces a false failure that a human reads
     * in ten seconds, while the alternative -- a real Kotlin parser -- is a dependency and a
     * maintenance burden for one assertion.
     */
    private fun codeStringLiterals(source: String): Set<String> = source.lineSequence()
        .map { it.trim() }
        .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
        .flatMap { line -> Regex("\"([^\"\\\\\n]*)\"").findAll(line).map { it.groupValues[1] } }
        .toSet()

    /**
     * The repository root, passed in by the build.
     *
     * Not derived from `user.dir`: Gradle happens to set the test JVM's working directory to the
     * module directory today, but that is a property of the test task rather than a contract, and a
     * test that silently scans the wrong tree passes for the wrong reason.
     */
    private fun repositoryRoot(): File {
        val configured = System.getProperty("manana.repo.root")
        assertTrue(
            "manana.repo.root is not set; see core-sync-net/build.gradle.kts",
            !configured.isNullOrEmpty(),
        )
        return File(configured!!)
    }
}
