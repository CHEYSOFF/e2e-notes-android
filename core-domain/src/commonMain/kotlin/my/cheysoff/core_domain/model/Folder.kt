package my.cheysoff.core_domain.model

/**
 * A user folder/category. [colorArgb] is an optional ARGB color (as a Long); when null the UI
 * derives a stable color from the id. Kept as a plain Long so the domain has no UI dependency.
 *
 * [createdAt]/[updatedAt] are wall-clock milliseconds, 0 for rows that predate the v5 -> v6
 * migration and have not been saved since (the migration backfills 0 rather than inventing a
 * plausible time). Nothing currently sorts folders by them — [getFolders][
 * my.cheysoff.core_domain.repository.NotesRepository.getFolders] orders by name — so a 0 has no
 * user-visible effect today.
 *
 * [isDeleted]/[deletedAt] are the Trash tombstone: a deleted folder keeps its row so it can be
 * restored, and is purged for good after [TrashPolicy.RETENTION_MILLIS].
 */
data class Folder(
    val id: String,
    val name: String,
    val colorArgb: Long? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
)
