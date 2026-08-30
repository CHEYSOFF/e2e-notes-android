package manana.sync.server

/**
 * The server's entire logging surface.
 *
 * ### What is deliberately absent
 *
 * There is **no overload that takes a request path**. Every log line names a *route template* --
 * `GET /v1/records/{id}/history`, never `GET /v1/records/9tQk…/history` -- because the `{id}` is a
 * blinded record ID and a log file full of them is a per-record edit history in the clear, which is
 * one of the few things the operator is not otherwise handed. The template is passed in by the
 * route that already knows it, rather than derived from the live request, so there is no code path
 * through which a real path could reach a log line.
 *
 * For the same reason nothing here ever receives an envelope, an account ID, a device ID, a device
 * label or a public key. [detail] exists for the operator debugging their own deployment and is
 * silent unless debug logging is switched on explicitly; even then, the caller is responsible for
 * what it passes, and no call site in this server passes account-scoped data.
 *
 * `server/README.md` states what an operator can and cannot see. This class is where the "cannot"
 * half is enforced, and `LoggingTest` asserts it against the real route table.
 *
 * ### Why not SLF4J directly
 *
 * The [sink] indirection is what lets a test assert on every line the server emits, which is the
 * only way to check a negative like "no account ID ever appears in a log". slf4j-simple is on the
 * classpath solely to satisfy Ktor's own logger; it is configured to WARN so it never adds lines of
 * its own that this class did not produce. Ktor's `CallLogging` plugin is deliberately **not**
 * installed -- it logs full request paths, which is precisely the leak described above.
 */
class RequestLog(
    private val sink: (String) -> Unit = ::println,
    private val debugEnabled: Boolean = false,
) {
    /**
     * One line per completed request: what was called, how it ended, how long it took, and how many
     * bytes moved. All four are things the operator can observe from outside the process anyway.
     */
    fun request(
        method: String,
        routeTemplate: String,
        status: Int,
        durationMillis: Long,
        requestBytes: Int,
        responseBytes: Int,
    ) {
        sink(
            "INFO $method $routeTemplate -> $status ${durationMillis}ms " +
                "in=${requestBytes}B out=${responseBytes}B"
        )
    }

    /** Operator-facing detail, off unless debug logging is enabled. */
    fun detail(message: String) {
        if (debugEnabled) sink("DEBUG $message")
    }

    /** A condition the operator needs to know about. Never carries request data. */
    fun warn(message: String) {
        sink("WARN $message")
    }
}
