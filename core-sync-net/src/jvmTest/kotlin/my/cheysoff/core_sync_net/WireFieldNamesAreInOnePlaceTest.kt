package my.cheysoff.core_sync_net

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every JSON field name lives in `SyncWire.kt` and nowhere else in this module's main source.
 *
 * ## Why this is worth a test
 *
 * It has already been collected on once. `recType`, `hlc`, `receivedAt` and the plaintext device
 * `label` all left the wire when the server was shown never to read them, and on this side that was
 * four deleted constants and two renames in one file: whoever made the change deleted a constant
 * and followed the compiler, because there was no call site anywhere else spelling a name out.
 *
 * That is only true while no other file spells a field name out, and "no other file spells it out"
 * is the kind of property that is true when it is written and false three commits later. The wire
 * is not finished changing, so it is asserted rather than hoped for.
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
        "sealedLabel", "ts", "signature", "createdAt",
        "challenge", "expiresAt", "token",
        "devices", "publicKey", "revokedAt", "self",
        "blindedId", "seq", "envelope",
        "records", "nextCursor", "hasMore",
        "items", "baseSeq", "results", "accountSeq", "current",
        "versions",
    )

    @Test
    fun `no main-source file except SyncWire names a JSON field`() {
        val offenders = mutableListOf<String>()
        mainSources()
            .filter { it.name != "SyncWire.kt" }
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

    /**
     * The names that were taken off the wire, which no main-source file may spell any more --
     * `SyncWire.kt` included.
     *
     * The server decodes strictly, so a reinstated `recType` on an upsert item is
     * `400 malformed_request` for the whole batch, and a client reading `receivedAt` off a record
     * gets a protocol error on every page. `SyncServerContractTest` catches both against the real
     * server; this catches them without one, and names the file.
     */
    @Test
    fun `no main-source file spells a field that was removed from the wire`() {
        val removed = listOf("recType", "hlc", "receivedAt", "deviceLabel", "label")

        val offenders = mutableListOf<String>()
        mainSources().forEach { file ->
            val literals = codeStringLiterals(file.readText())
            for (name in removed) {
                if (name in literals) offenders += "${file.name} still spells '$name'"
            }
        }

        assertTrue(
            "these names left the wire and must not be sent or read any more:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `SyncWire really does hold every field name`() {
        val syncWire = mainSources().singleOrNull { it.name == "SyncWire.kt" }
        assertTrue("there must be exactly one SyncWire.kt in the module's main sources", syncWire != null)
        val literals = codeStringLiterals(syncWire!!.readText())

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
     * Every Kotlin file in every **main** source set of this module.
     *
     * Discovered rather than listed. The module is multiplatform, so its production code is spread
     * over `commonMain`, `jvmCommonMain`, `androidMain` and `jvmMain` today and will gain
     * `appleMain` the day there is a Mac -- and a hard-coded list would silently stop covering a
     * source set that was added after it was written, which is the same class of omission this whole
     * test exists to catch. The `*Main` suffix is what separates production sources from `jvmTest`;
     * this file is in the latter and must not scan itself, since it spells every field name out.
     */
    private fun mainSources(): List<File> {
        val src = File(repositoryRoot(), "core-sync-net/src")
        assertTrue("the module's source root moved: $src", src.isDirectory)
        val sourceSets = src.listFiles().orEmpty().filter { it.isDirectory && it.name.endsWith("Main") }
        assertTrue("no main source set found under $src", sourceSets.isNotEmpty())
        val files = sourceSets
            .flatMap { it.walkTopDown().filter { file -> file.isFile && file.extension == "kt" } }
        assertTrue("no Kotlin production sources found under $src", files.isNotEmpty())
        return files
    }

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
