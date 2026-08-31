package my.cheysoff.core_domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * The one thing the user has to tell the app before it can talk to a sync server: where that
 * server is.
 *
 * Deliberately separate from [SettingsRepository] rather than two more methods on it. That
 * interface is about how the notes list looks and how it is sorted; this one is the address a
 * sealed envelope would be posted to, and the module that implements it is the module that owns
 * the sync transport's other adapters. Keeping them apart means a change to either cannot
 * accidentally widen the other's implementation.
 *
 * ## What a stored value is, and is not
 *
 * The stored string is a **base URL that has already been validated and normalised** — scheme,
 * host, optional port and optional path prefix, with any trailing slash removed. The settings
 * screen refuses to store anything else. It is not a promise that the URL still resolves, that a
 * server is listening, or that the certificate is one this device has ever seen: none of those can
 * be known without a network round trip.
 *
 * Null means "the user has not set one", which is the state every install starts in and the state
 * an install stays in forever if its owner never wants sync. It is not an error.
 */
interface SyncSettingsRepository {

    /**
     * The stored sync server base URL, or null when none is set.
     *
     * A flow rather than a suspending read because the settings screen mirrors it: the row shows
     * what was actually persisted, never what was typed.
     */
    val serverUrl: Flow<String?>

    /**
     * Store [url], or clear the setting when it is null.
     *
     * The caller is responsible for having validated [url] first; an implementation stores the
     * string it is given. Everything that later reads this value re-validates it anyway, because a
     * preferences file is not a trusted input.
     */
    suspend fun setServerUrl(url: String?)
}
