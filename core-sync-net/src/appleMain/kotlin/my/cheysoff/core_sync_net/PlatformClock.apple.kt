package my.cheysoff.core_sync_net

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

/**
 * `NSDate` is the Apple equivalent of `System.currentTimeMillis`: wall clock, epoch-based, and
 * subject to the user changing it — which is exactly what this seam wants. The server checks the
 * `ts` on a signed request against its own clock within five minutes, so a device whose clock is
 * wrong should be *told* that, not quietly corrected.
 *
 * `timeIntervalSince1970` is a `Double` of seconds. The multiply-then-truncate loses sub-millisecond
 * precision, which nothing here has ever had or wanted, and stays exact for milliseconds until well
 * past the year 200,000: a `Double` has 53 bits of mantissa and epoch milliseconds needs 41.
 *
 * NOT COMPILED — see `docs/BUILDING-IOS.md`.
 */
internal actual fun currentTimeMillis(): Long =
    (NSDate().timeIntervalSince1970 * 1000.0).toLong()
