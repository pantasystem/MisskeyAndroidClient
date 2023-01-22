package net.pantasystem.milktea.data.infrastructure.notes

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import net.pantasystem.milktea.app_store.notes.InitialLoadQuery
import net.pantasystem.milktea.app_store.notes.TimelineStore
import net.pantasystem.milktea.common.PageableState
import net.pantasystem.milktea.common.StateContent
import net.pantasystem.milktea.common.paginator.FuturePagingController
import net.pantasystem.milktea.common.paginator.PaginationState
import net.pantasystem.milktea.common.paginator.PreviousPagingController
import net.pantasystem.milktea.common.paginator.StateLocker
import net.pantasystem.milktea.common.runCancellableCatching
import net.pantasystem.milktea.data.api.mastodon.MastodonAPIProvider
import net.pantasystem.milktea.data.api.misskey.MisskeyAPIProvider
import net.pantasystem.milktea.model.account.Account
import net.pantasystem.milktea.model.account.page.Pageable
import net.pantasystem.milktea.model.instance.MetaRepository
import net.pantasystem.milktea.model.nodeinfo.NodeInfoRepository
import net.pantasystem.milktea.model.notes.Note
import net.pantasystem.milktea.model.notes.NoteDataSource
import net.pantasystem.milktea.model.notes.NoteRelation
import net.pantasystem.milktea.model.notes.NoteRelationGetter
import javax.inject.Inject


const val LIMIT = 10

class TimelineStoreImpl(
    private val pageableTimeline: Pageable,
    private val noteAdder: NoteDataSourceAdder,
    noteDataSource: NoteDataSource,
    private val getAccount: suspend () -> Account,
    private val misskeyAPIProvider: MisskeyAPIProvider,
    coroutineScope: CoroutineScope,
    private val noteRelationGetter: NoteRelationGetter,
    private val metaRepository: MetaRepository,
    private val mastodonAPIProvider: MastodonAPIProvider,
    private val nodeInfoRepository: NodeInfoRepository,
) : TimelineStore {

    class Factory @Inject constructor(
        private val noteAdder: NoteDataSourceAdder,
        private val noteDataSource: NoteDataSource,
        private val misskeyAPIProvider: MisskeyAPIProvider,
        private val noteRelationGetter: NoteRelationGetter,
        private val metaRepository: MetaRepository,
        private val mastodonAPIProvider: MastodonAPIProvider,
        private val nodeInfoRepository: NodeInfoRepository
    ) : TimelineStore.Factory {
        override fun create(
            pageable: Pageable,
            coroutineScope: CoroutineScope,
            getAccount: suspend () -> Account
        ): TimelineStore {
            return TimelineStoreImpl(
                pageable,
                noteAdder,
                noteDataSource,
                getAccount,
                misskeyAPIProvider,
                coroutineScope,
                noteRelationGetter,
                metaRepository,
                mastodonAPIProvider,
                nodeInfoRepository = nodeInfoRepository
            )
        }
    }

    private val willAddNoteQueue = MutableSharedFlow<Note.Id>(
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        extraBufferCapacity = 1000
    )

    override val receiveNoteQueue: SharedFlow<Note.Id>
        get() = willAddNoteQueue


    val pageableStore: TimelinePagingBase by lazy {
        when (pageableTimeline) {
            is Pageable.Favorite -> {
                FavoriteNoteTimelinePagingStoreImpl(
                    pageableTimeline, noteAdder, getAccount, misskeyAPIProvider, mastodonAPIProvider
                )
            }
            is Pageable.Mastodon -> {
                MastodonTimelineStorePagingStoreImpl(
                    pageableTimeline = pageableTimeline,
                    mastodonAPIProvider = mastodonAPIProvider,
                    getAccount,
                    noteAdder,
                    nodeInfoRepository
                )
            }
            else -> TimelinePagingStoreImpl(
                pageableTimeline, noteAdder, getAccount,
                {
                    initialLoadQuery
                },
                misskeyAPIProvider,
                metaRepository,
            )
        }
    }
    override val timelineState: Flow<PageableState<List<Note.Id>>>
        get() = pageableStore.state.distinctUntilChanged()

    var latestReceiveId: Note.Id? = null

    var initialLoadQuery: InitialLoadQuery? = null

    init {
        coroutineScope.launch(Dispatchers.IO) {
            willAddNoteQueue.collect { noteId ->
                appendStreamEventNote(noteId)
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override val relatedNotes: Flow<PageableState<List<NoteRelation>>> =
        noteDataSource.state.flatMapLatest {
            timelineState.map { pageableState ->
                pageableState.suspendConvert { list ->
                    noteRelationGetter.getIn(list.distinct())
                }
            }
        }.distinctUntilChanged()


    override suspend fun loadFuture(): Result<Unit> {
        return runCancellableCatching {
            val addedCount = when (val store = pageableStore) {
                is TimelinePagingStoreImpl -> {
                    FuturePagingController(
                        store,
                        store,
                        store,
                        store,
                    ).loadFuture()
                }
                is FavoriteNoteTimelinePagingStoreImpl -> {
                    FuturePagingController(
                        store,
                        store,
                        store,
                        store,
                    ).loadFuture()
                }
                is MastodonTimelineStorePagingStoreImpl -> {
                    FuturePagingController(
                        store,
                        store,
                        store,
                        store,
                    ).loadFuture()
                }
            }
            if (addedCount.getOrElse { Int.MAX_VALUE } < LIMIT) {
                initialLoadQuery = null
            }
            latestReceiveId = null
        }
    }

    override suspend fun loadPrevious(): Result<Unit> {
        return runCancellableCatching {
            when (val store = pageableStore) {
                is TimelinePagingStoreImpl -> {
                    PreviousPagingController(
                        store,
                        store,
                        store,
                        store,
                    ).loadPrevious()
                }
                is FavoriteNoteTimelinePagingStoreImpl -> {
                    PreviousPagingController(
                        store,
                        store,
                        store,
                        store,
                    ).loadPrevious()
                }
                is MastodonTimelineStorePagingStoreImpl -> {
                    PreviousPagingController(
                        store,
                        store,
                        store,
                        store,
                    ).loadPrevious()
                }
            }
            latestReceiveId = null
        }

    }

    override suspend fun clear(initialLoadQuery: InitialLoadQuery?) {
        pageableStore.mutex.withLock {
            this.initialLoadQuery = initialLoadQuery
            pageableStore.setState(PageableState.Loading.Init())
        }
    }


    override fun onReceiveNote(noteId: Note.Id) {
        willAddNoteQueue.tryEmit(noteId)
    }

    override fun latestReceiveNoteId(): Note.Id? {
        return latestReceiveId
    }

    private suspend fun appendStreamEventNote(noteId: Note.Id) {
        val store = pageableStore
        if (store is StreamingReceivableStore) {
            store.mutex.withLock {
                if (initialLoadQuery != null) {
                    return@withLock
                }
                val content = store.getState().content

                var added = false
                store.setState(
                    PageableState.Fixed(
                        when (content) {
                            is StateContent.NotExist -> {
                                added = true
                                StateContent.Exist(
                                    listOf(noteId)
                                )
                            }
                            is StateContent.Exist -> {
                                if (content.rawContent.contains(noteId)) {
                                    content
                                } else {
                                    added = true
                                    content.copy(
                                        listOf(noteId) + content.rawContent
                                    )
                                }
                            }
                        }
                    )
                )
                if (added) {
                    latestReceiveId = noteId
                }

            }
        }
    }

}

sealed interface TimelinePagingBase : PaginationState<Note.Id>, StateLocker

interface StreamingReceivableStore : StateLocker
