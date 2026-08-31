package my.cheysoff.feature_settings.model

import my.cheysoff.core_sync_net.http.ServerEndpoint

/**
 * Whether a typed sync server address is one this app will store, and if not, what is wrong with
 * it.
 *
 * ## The check is the transport's own check
 *
 * [checkSyncServerUrl] does not re-implement a URL policy. It constructs the very
 * [ServerEndpoint] the transport would be built with and reports what that constructor said. That
 * is the point: a settings screen that accepts an address the transport later rejects is a screen
 * that lies, and two copies of a security rule drift apart on the first edit to either. This is
 * why `:feature-settings` depends on `:core-sync-net` at all — see the note in its
 * `build.gradle.kts`.
 *
 * ## The scheme rule, and the trade-off behind it
 *
 * `ServerEndpoint` requires `https`, and permits plain `http` only to a loopback host
 * (`localhost`, `127.0.0.1`, `::1`). A LAN address over `http` — `http://192.168.1.10:8080` — is
 * **refused**, and this screen says so rather than quietly downgrading.
 *
 * It is worth being precise about what that costs and what it buys, because the obvious objection
 * is a good one: every note body on this wire is already sealed end to end, so an eavesdropper on
 * a plain-HTTP hop learns no note content. What they do learn is the metadata — how many records,
 * how often, how large — and, decisively, **the bearer session token**. That token is not a read
 * capability. `SyncApi` uses it for `pushRecords` and for `revokeDevice`: someone who lifts it off
 * a home network can write records into the account and revoke the user's other devices. Note
 * confidentiality survives plain HTTP; account integrity does not. So the refusal is deliberate,
 * and the way to run a LAN server is the way `server/README.md` already describes — a
 * TLS-terminating reverse proxy in front of it.
 *
 * Loopback is the single exception because on loopback there is no hop: nothing leaves the device,
 * and there is no traffic for TLS to protect.
 *
 * ## What is not checked here
 *
 * That anything is listening. That is a network round trip, it is a separate action in the UI, and
 * an address that fails to answer today is still the right address to have stored.
 */
sealed interface SyncServerUrlCheck {

    /**
     * The address is storable. [normalized] is what gets persisted — trimmed, with any trailing
     * slash removed — which is the same normalisation the transport applies, so the stored string
     * and the transport's `baseUrl` are byte-identical.
     */
    data class Ok(val normalized: String) : SyncServerUrlCheck

    /** The address is not storable. [message] is shown to the user verbatim. */
    data class Rejected(val message: String) : SyncServerUrlCheck
}

/**
 * Validate a typed sync server address.
 *
 * Blank input is [SyncServerUrlCheck.Rejected] rather than "clear the setting": clearing is a
 * separate, explicit action, so an accidentally emptied field cannot silently un-configure sync.
 */
fun checkSyncServerUrl(raw: String): SyncServerUrlCheck {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) {
        return SyncServerUrlCheck.Rejected("Enter a server address, or use Clear to remove it.")
    }
    // Whitespace inside the address is caught here rather than by URI parsing, because
    // `URI("https://a b")` throws with a message about an index into a string the user did not
    // think of as indexed. "There is a space in it" is the thing that is actually wrong.
    if (trimmed.any { it.isWhitespace() }) {
        return SyncServerUrlCheck.Rejected("A server address can't contain spaces.")
    }
    return try {
        // No pin: nothing in the app can supply one yet. See SyncEndpointPlan in :app for why.
        SyncServerUrlCheck.Ok(ServerEndpoint(trimmed).baseUrl)
    } catch (e: IllegalArgumentException) {
        // ServerEndpoint's own `require` messages are written as sentences about the URL -- "the
        // sync server URL must be http or https", "plain http is only allowed to a loopback
        // address; this server needs https" -- so they are shown rather than replaced. A message
        // invented here would be a second place to keep in step with the rule it describes.
        SyncServerUrlCheck.Rejected(sentenceCase(e.message))
    }
}

/**
 * `ServerEndpoint`'s messages start lower case, because they are written to read well inside an
 * exception. On screen they are a sentence, so the first letter is raised and a full stop added.
 * Nothing else about the text is touched.
 */
private fun sentenceCase(message: String?): String {
    val text = message?.trim().orEmpty().ifEmpty { "That isn't a valid server address" }
    val capitalised = text.replaceFirstChar { it.uppercase() }
    return if (capitalised.endsWith(".")) capitalised else "$capitalised."
}
