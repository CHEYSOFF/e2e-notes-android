package my.cheysoff.feature_notes.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import my.cheysoff.feature_notes.ui.attachment.AndroidImageImporter
import my.cheysoff.feature_notes.ui.attachment.ImageImporter

/**
 * Binds [ImageImporter] to its one real implementation. The interface exists so
 * `SingleNoteViewModelTest` can substitute a fake -- [android.graphics.ImageDecoder] needs a real
 * device and cannot run under a plain JVM unit test. See `ImageImporter.kt`'s own KDoc.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AttachmentModule {

    @Binds
    abstract fun bindImageImporter(impl: AndroidImageImporter): ImageImporter
}
