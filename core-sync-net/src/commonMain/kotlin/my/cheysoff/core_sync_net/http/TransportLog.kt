package my.cheysoff.core_sync_net.http

/**
 * The only thing this module is allowed to say out loud about a request.
 *
 * ## Why it is shaped like this
 *
 * The server's own `RequestLog` logs a route *template* and has no overload that accepts a path,
 * because a log full of real paths **is** the per-record edit history in a second place
 * (`server/README.md`, "What the log file contains"). This is the client half of that rule, and it
 * takes the same approach: the interface simply has nowhere to put a URL, a header, a body or an
 * identifier, so no call site can log one by accident and no future edit can add one without
 * changing this signature -- which is a change a reviewer sees.
 *
 * What must never reach a log line, on this side of the wire:
 *
 *  - the **bearer token**, which is a live credential;
 *  - any **signature**, which is not secret but is a fingerprint of the device key;
 *  - the **account ID**, which is derived from the Account Root Key and names the user's account
 *    on every server they use;
 *  - any **blinded record ID**, because a sequence of them is the per-record edit history the
 *    blinding exists to hide;
 *  - any **envelope byte**.
 *
 * The default is [NONE]: this module logs nothing at all unless a caller deliberately asks it to.
 * A transport that is silent by default cannot leak by default.
 */
fun interface TransportLog {

    /**
     * One completed request.
     *
     * @param routeTemplate the route as it appears in the route table -- `/v1/records/{id}/history`,
     *   never a path with a real record ID in it.
     */
    fun request(method: String, routeTemplate: String, status: Int, durationMillis: Long)

    companion object {
        /** Logs nothing. The default. */
        val NONE: TransportLog = TransportLog { _, _, _, _ -> }
    }
}
