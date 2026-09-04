package my.cheysoff.desktop.store

/**
 * A `recType` wire key that stands in for "a type this build has never heard of", for tests that
 * exercise the not-yet-implemented-type path rather than any real record type.
 *
 * Mirrors `core_sync_codec.UNIMPLEMENTED_TEST_RECORD_TYPE` (same value, same purpose) rather than
 * sharing it: `:desktop`'s test source set only sees `:core-sync-codec`'s main output, not its
 * test sources, and this repo has no `java-test-fixtures` wiring to change that. If one is ever
 * added, collapse these two constants into one and delete whichever module loses.
 *
 * ## Why this needs to be a named constant rather than a literal picked per test
 *
 * It was `"sketch"` here until sketches became a real `RecordType` (Task 3 of the sketch-blocks
 * plan), which silently flipped this test from testing the unknown-type path to testing the real
 * one. The fix reached for `"attachment"` next, which turned out to be *planned* --
 * `docs/design/image-attachments.md` specifies it as the next record type -- so it would have
 * broken the same way the moment attachments shipped. Do not replace this string with another
 * plausible feature name.
 */
internal const val UNIMPLEMENTED_TEST_RECORD_TYPE = "unimplemented-test-type"
