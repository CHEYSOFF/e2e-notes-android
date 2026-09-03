package manana.sync.server

/**
 * What an enrolment attempt found, so the route can tell a first enrolment from a retry and both
 * from a key that has been revoked.
 *
 * These were one nullable String, which made a retry and a revoked key indistinguishable and sent
 * `409 device_exists` for each. The first of those is a device that is correctly on the account and
 * merely lost the reply; refusing it is how such a device becomes permanently unable to sync with
 * nothing actually wrong.
 */
sealed interface EnrolOutcome {

    /** A new row. The key had never been seen on this account. */
    data class Enrolled(val deviceId: String) : EnrolOutcome

    /**
     * This exact key is already enrolled and live, so the caller is retrying and [deviceId] is the
     * answer it missed. Nothing was written.
     */
    data class AlreadyEnrolled(val deviceId: String) : EnrolOutcome

    /**
     * The key is enrolled but revoked. Not a retry, and the refusal is the point of revocation: a
     * revoked key must not come back through the front door.
     */
    data object Revoked : EnrolOutcome
}
