package my.cheysoff.core_domain

import java.util.UUID

data class FolderPreview(
    val id: UUID = UUID.randomUUID(), // todo replace with an actual id
    val name: String,
    val notesAmount: Int,
)
