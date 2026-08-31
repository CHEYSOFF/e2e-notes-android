package my.cheysoff.core_sync_net

/**
 * The device's wall clock in epoch milliseconds — `System.currentTimeMillis` on the JVM, and its
 * equivalent elsewhere.
 *
 * It exists as its own `expect` rather than through a date-time library because this is the whole of
 * what this module needs a clock for: a signed request carries a `ts` the server checks against its
 * own clock within five minutes. No formatting, no time zone, no calendar. Adding a dependency for
 * one number would be a bigger decision than this one is.
 *
 * Only the **default** of [SyncHttpClient]'s `clock` parameter reaches this. Every test injects its
 * own, because "what time does this device think it is" is a protocol input.
 */
internal expect fun currentTimeMillis(): Long
