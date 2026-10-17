package com.jedrama.fork.di

import android.content.Context
import com.jedrama.fork.generation.SeriesDraftStore
import com.jedrama.fork.library.Library
import com.jedrama.fork.media.EpisodeRenderer
import com.jedrama.fork.media.Ffmpeg
import com.jedrama.fork.media.Images
import com.jedrama.fork.media.ImageCache
import com.jedrama.fork.media.RenderPlan
import com.jedrama.fork.media.RenderSettings
import com.jedrama.fork.media.Speech
import com.jedrama.fork.media.Subtitles
import com.jedrama.fork.settings.SettingsStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideSettingsStore(@ApplicationContext context: Context): SettingsStore =
        SettingsStore(context)

    @Provides
    @Singleton
    fun provideLibrary(@ApplicationContext context: Context): Library =
        Library(context)

    @Provides
    @Singleton
    fun provideSeriesDraftStore(@ApplicationContext context: Context): SeriesDraftStore =
        SeriesDraftStore(context)

    @Provides
    @Singleton
    fun provideFfmpeg(@ApplicationContext context: Context): Ffmpeg =
        Ffmpeg(context)

    @Provides
    @Singleton
    fun provideImageCache(@ApplicationContext context: Context): ImageCache =
        ImageCache(context)

    @Provides
    @Singleton
    fun provideSubtitles(): Subtitles = Subtitles

    @Provides
    fun provideRenderSettings(settings: SettingsStore): RenderSettings =
        settings.renderSettings()
}
