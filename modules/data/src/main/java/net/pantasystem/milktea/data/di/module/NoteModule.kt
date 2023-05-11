package net.pantasystem.milktea.data.di.module

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import net.pantasystem.milktea.app_store.notes.NoteTranslationStore
import net.pantasystem.milktea.app_store.notes.TimelineStore
import net.pantasystem.milktea.data.infrastructure.notes.NoteStreamingImpl
import net.pantasystem.milktea.data.infrastructure.notes.NoteTranslationStoreImpl
import net.pantasystem.milktea.data.infrastructure.notes.ReplyStreamingImpl
import net.pantasystem.milktea.data.infrastructure.notes.TimelineStoreImpl
import net.pantasystem.milktea.data.infrastructure.notes.draft.DraftNoteRepositoryImpl
import net.pantasystem.milktea.data.infrastructure.notes.impl.DraftNoteServiceImpl
import net.pantasystem.milktea.data.infrastructure.notes.impl.NoteRepositoryImpl
import net.pantasystem.milktea.data.infrastructure.notes.impl.ObjectBoxNoteDataSource
import net.pantasystem.milktea.data.infrastructure.notes.renote.RenotesPagingServiceImpl
import net.pantasystem.milktea.model.notes.NoteDataSource
import net.pantasystem.milktea.model.notes.NoteRepository
import net.pantasystem.milktea.model.notes.NoteStreaming
import net.pantasystem.milktea.model.notes.ReplyStreaming
import net.pantasystem.milktea.model.notes.draft.DraftNoteRepository
import net.pantasystem.milktea.model.notes.draft.DraftNoteService
import net.pantasystem.milktea.model.notes.repost.RenotesPagingService
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class NoteBindModule{

    @Binds
    @Singleton
    abstract fun noteDataSource(inMem: ObjectBoxNoteDataSource): NoteDataSource

    @Binds
    @Singleton
    abstract fun noteRepository(impl: NoteRepositoryImpl): NoteRepository

    @Binds
    @Singleton
    abstract fun provideTimelineStoreFactory(impl: TimelineStoreImpl.Factory): TimelineStore.Factory

    @Binds
    @Singleton
    abstract fun provideNoteStreaming(impl: NoteStreamingImpl): NoteStreaming

    @Binds
    @Singleton
    abstract fun provideDraftNoteService(impl: DraftNoteServiceImpl): DraftNoteService

    @Binds
    @Singleton
    abstract fun bindRenotePagingService(impl: RenotesPagingServiceImpl.Factory): RenotesPagingService.Factory

    @Binds
    @Singleton
    abstract fun provideDraftNoteRepository(impl: DraftNoteRepositoryImpl): DraftNoteRepository

    @Binds
    @Singleton
    abstract fun bindReplyStreaming(impl: ReplyStreamingImpl): ReplyStreaming

}



@Module
@InstallIn(SingletonComponent::class)
abstract class AbsNoteModule {
    @Binds
    @Singleton
    abstract fun provideNoteTranslationStore(
        impl: NoteTranslationStoreImpl
    ) : NoteTranslationStore
}