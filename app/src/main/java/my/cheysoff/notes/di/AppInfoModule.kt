package my.cheysoff.notes.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import my.cheysoff.core_domain.model.AppInfo
import my.cheysoff.notes.BuildConfig
import javax.inject.Singleton

/**
 * Publishes the application module's own [BuildConfig] values as an injectable [AppInfo].
 *
 * This module lives in `:app` because that is the only place `my.cheysoff.notes.BuildConfig`
 * exists. A library module has a BuildConfig of its own, and it describes the library — its
 * `VERSION_NAME` would not be the app's version — so feature modules take the values through DI
 * instead of reading a BuildConfig directly.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppInfoModule {

    @Provides
    @Singleton
    fun provideAppInfo(): AppInfo = AppInfo(
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
    )
}
