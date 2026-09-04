package my.cheysoff.core_sync_codec

/**
 * A `recType` wire key that stands in for "a type this build has never heard of", for tests that
 * exercise the `UNKNOWN_TYPE` / `UnknownType` path rather than any real record type.
 *
 * ## Why this needs to be a named constant rather than a literal picked per test
 *
 * It was `"sketch"` here until sketches became a real `RecordType` (Task 3 of the sketch-blocks
 * plan), which silently flipped these tests from testing the unknown-type path to testing the
 * real one. The fix reached for `"attachment"` next, which turned out to be *planned* --
 * `docs/design/image-attachments.md` specifies it as the next record type -- so it would have
 * broken the same way the moment attachments shipped, and the person fixing it then would have
 * had no signal that the same mistake was happening again.
 *
 * So: **do not replace this string with another plausible feature name**, and do not add a second
 * ad hoc "obviously fake" literal elsewhere for the same purpose. If a real `RecordType` is ever
 * added whose `wireKey` collides with this constant's value (it shouldn't -- see the value itself)
 * fix it here, once, and every test using it is fixed with it.
 */
internal const val UNIMPLEMENTED_TEST_RECORD_TYPE = "unimplemented-test-type"
