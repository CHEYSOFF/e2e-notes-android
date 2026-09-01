package my.cheysoff.desktop.pairing

import my.cheysoff.core_pairing.protocol.RendezvousUrl
import java.util.prefs.Preferences

/**
 * The last pairing server address that actually worked, remembered between launches.
 *
 * Stored in [Preferences] beside the window geometry, and for the same reason: it is a host name
 * with no privacy weight beyond "this person runs a Mañana server at this address", which is
 * already visible to anyone who watches the machine make a request. Nothing here is key material
 * and nothing here is note content — those live in the vault.
 *
 * Only an address that produced a bundle is remembered. A typed address that never worked is a typo
 * worth forgetting, and prefilling it on the next attempt would reproduce the mistake.
 *
 * Read back through [RendezvousUrl.parse] rather than trusted, because the preferences store is an
 * ordinary registry key or plist that anything on the machine can write.
 */
object PairingServer {

    private const val KEY = "pairing.server"

    private val prefs: Preferences? = runCatching {
        Preferences.userRoot().node("my/cheysoff/manana/desktop")
    }.getOrNull()

    /** The remembered address, or an empty string. Never a value that would fail to parse. */
    fun remembered(): String {
        val stored = prefs?.get(KEY, "") ?: return ""
        return RendezvousUrl.parse(stored)?.base ?: ""
    }

    /** Best effort: a locked-down machine where preferences are unwritable still pairs fine. */
    fun remember(url: RendezvousUrl) {
        val p = prefs ?: return
        runCatching {
            p.put(KEY, url.base)
            p.flush()
        }
    }
}
