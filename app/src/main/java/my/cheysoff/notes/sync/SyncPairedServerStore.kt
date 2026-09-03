package my.cheysoff.notes.sync

import my.cheysoff.core_domain.repository.SyncSettingsRepository
import my.cheysoff.feature_pairing.di.PairedServerStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes what a pairing agreed about the account's server into the two places sync reads it from.
 *
 * ## Why the address is overwritten and the id is not
 *
 * They answer different questions. The address is "where does this account live", and a pairing is
 * a statement about that from the device that already holds the account — sealed, so it is the
 * account device's authenticated word rather than anything this phone guessed. If this phone had a
 * different address stored, that address belonged to a different account and keeping it would leave
 * the phone pointing at a server that has never heard of it.
 *
 * The device id is "what is this phone called on that server", and it is per account and assigned
 * by the server. [SyncEnrolmentStore] already keys it by account handle, so writing it cannot
 * disturb an id belonging to any other account — and an absent one is left absent rather than
 * cleared, because a pairing that could not vouch has nothing to say about it.
 *
 * ## What it deliberately does not do
 *
 * It does not verify the address, contact it, or start a sync. The address came out of a seal and
 * is therefore what the account device meant; whether it is reachable is a question for the first
 * pass, which reports it in a place the user is looking.
 */
@Singleton
class SyncPairedServerStore @Inject constructor(
    private val syncSettings: SyncSettingsRepository,
    private val enrolmentStore: SyncEnrolmentStore,
) : PairedServerStore {

    override suspend fun record(accountId: String, serverUrl: String, deviceId: String?) {
        syncSettings.setServerUrl(serverUrl)
        if (deviceId != null) enrolmentStore.setDeviceId(accountId, deviceId)
    }
}
