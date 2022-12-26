package net.pantasystem.milktea.note.editor.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import net.pantasystem.milktea.app_store.account.AccountStore
import net.pantasystem.milktea.common.*
import net.pantasystem.milktea.common_android.eventbus.EventBus
import net.pantasystem.milktea.common_viewmodel.UserViewData
import net.pantasystem.milktea.model.account.Account
import net.pantasystem.milktea.model.account.AccountRepository
import net.pantasystem.milktea.model.account.UnauthorizedException
import net.pantasystem.milktea.model.channel.Channel
import net.pantasystem.milktea.model.channel.ChannelRepository
import net.pantasystem.milktea.model.drive.DriveFileRepository
import net.pantasystem.milktea.model.drive.FileProperty
import net.pantasystem.milktea.model.drive.FilePropertyDataSource
import net.pantasystem.milktea.model.emoji.Emoji
import net.pantasystem.milktea.model.file.AppFile
import net.pantasystem.milktea.model.instance.MetaRepository
import net.pantasystem.milktea.model.instance.Version
import net.pantasystem.milktea.model.notes.*
import net.pantasystem.milktea.model.notes.draft.DraftNoteRepository
import net.pantasystem.milktea.model.notes.draft.DraftNoteService
import net.pantasystem.milktea.model.notes.reservation.NoteReservationPostExecutor
import net.pantasystem.milktea.model.setting.LocalConfigRepository
import net.pantasystem.milktea.model.setting.RememberVisibility
import net.pantasystem.milktea.model.user.User
import net.pantasystem.milktea.worker.note.CreateNoteWorkerExecutor
import java.util.*
import javax.inject.Inject

@HiltViewModel
class NoteEditorViewModel @Inject constructor(
    loggerFactory: Logger.Factory,
    private val getAllMentionUsersUseCase: GetAllMentionUsersUseCase,
    private val filePropertyDataSource: FilePropertyDataSource,
    private val metaRepository: MetaRepository,
    private val driveFileRepository: DriveFileRepository,
    accountStore: AccountStore,
    private val draftNoteService: DraftNoteService,
    private val draftNoteRepository: DraftNoteRepository,
    private val noteReservationPostExecutor: NoteReservationPostExecutor,
    private val userViewDataFactory: UserViewData.Factory,
    private val noteRepository: NoteRepository,
    private val channelRepository: ChannelRepository,
    private val noteEditorSwitchAccountExecutor: NoteEditorSwitchAccountExecutor,
    private val createNoteWorkerExecutor: CreateNoteWorkerExecutor,
    private val accountRepository: AccountRepository,
    private val localConfigRepository: LocalConfigRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val dispatcher: CoroutineDispatcher = Dispatchers.IO

    private val logger = loggerFactory.create("NoteEditorViewModel")

    private val currentAccount = MutableStateFlow<Account?>(null)

    val text = savedStateHandle.getStateFlow<String?>(NoteEditorSavedStateKey.Text.name, null)

    val textCursorPos = MutableSharedFlow<TextWithCursorPos>(extraBufferCapacity = 10)

    val cw = savedStateHandle.getStateFlow<String?>(NoteEditorSavedStateKey.Cw.name, null)
    val hasCw = savedStateHandle.getStateFlow(NoteEditorSavedStateKey.HasCW.name, false)

    val files = savedStateHandle.getStateFlow<List<AppFile>>(
        NoteEditorSavedStateKey.PickedFiles.name,
        emptyList()
    )

    val totalImageCount = files.map {
        it.size
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val channelId =
        savedStateHandle.getStateFlow<Channel.Id?>(NoteEditorSavedStateKey.ChannelId.name, null)
    private val replyId =
        savedStateHandle.getStateFlow<Note.Id?>(NoteEditorSavedStateKey.ReplyId.name, null)
    private val renoteId =
        savedStateHandle.getStateFlow<Note.Id?>(NoteEditorSavedStateKey.RenoteId.name, null)


    @OptIn(ExperimentalCoroutinesApi::class)
    val maxTextLength =
        currentAccount.filterNotNull().flatMapLatest { account ->
            metaRepository.observe(account.normalizedInstanceDomain).filterNotNull().map { meta ->
                meta.maxNoteTextLength ?: 1500
            }
        }.stateIn(
            viewModelScope + Dispatchers.IO,
            started = SharingStarted.Lazily,
            initialValue = 1500
        )


    val maxFileCount = currentAccount.filterNotNull().mapNotNull {
        metaRepository.get(it.normalizedInstanceDomain)?.getVersion()
    }.map {
        if (it >= Version("12.100.2")) {
            16
        } else {
            4
        }
    }.stateIn(viewModelScope + Dispatchers.IO, started = SharingStarted.Eagerly, initialValue = 4)

    private val _visibility = savedStateHandle.getStateFlow<Visibility?>(
        NoteEditorSavedStateKey.Visibility.name,
        null
    )
    val visibility = combine(_visibility, currentAccount.filterNotNull().map {
        localConfigRepository.getRememberVisibility(it.accountId).getOrElse {
            RememberVisibility.None
        }
    }, channelId) { formVisibilityState, settingVisibilityState, channelId ->
        when {
            formVisibilityState != null -> formVisibilityState
            settingVisibilityState is RememberVisibility.None -> Visibility.Public(false)
            settingVisibilityState is RememberVisibility.Remember -> settingVisibilityState.visibility
            channelId != null -> Visibility.Public(true)
            else -> Visibility.Public(false)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Visibility.Public(false))

    val isLocalOnly = visibility.map {
        it.isLocalOnly()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val reservationPostingAt =
        savedStateHandle.getStateFlow<Date?>(NoteEditorSavedStateKey.ScheduleAt.name, null)

    val poll = savedStateHandle.getStateFlow<PollEditingState?>(
        NoteEditorSavedStateKey.Poll.name,
        null
    )

    private val noteEditorFormState = combine(text, cw, hasCw) { text, cw, hasCw ->
        NoteEditorFormState(
            text = text,
            cw = cw,
            hasCw = hasCw
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NoteEditorFormState())

    val address = visibility.map {
        it as? Visibility.Specified
    }.map {
        it?.visibleUserIds?.map { uId ->
            setUpUserViewData(uId)
        } ?: emptyList()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val isSpecified = visibility.map {
        it is Visibility.Specified
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val textRemaining = combine(maxTextLength, noteEditorFormState.map { it.text }) { max, t ->
        max - (t?.codePointCount(0, t.length) ?: 0)
    }.catch {
        logger.error("observe meta error", it)
    }.stateIn(viewModelScope + Dispatchers.IO, started = SharingStarted.Lazily, initialValue = 1500)

    @OptIn(ExperimentalCoroutinesApi::class)
    val channels = currentAccount.filterNotNull().flatMapLatest {
        suspend {
            channelRepository.findFollowedChannels(it.accountId).onFailure {
                logger.error("load channel error", it)
            }.getOrThrow()
        }.asLoadingStateFlow().onEach {
            logger.debug("Channel state:${it}")
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        initialValue = ResultState.Loading(StateContent.NotExist())
    )

    @FlowPreview
    @ExperimentalCoroutinesApi
    val currentUser: StateFlow<UserViewData?> =
        currentAccount.filterNotNull().map {
            val userId = User.Id(it.accountId, it.remoteId)
            userViewDataFactory.create(
                userId,
                viewModelScope,
                dispatcher
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)


    private val draftNoteId =
        savedStateHandle.getStateFlow<Long?>(NoteEditorSavedStateKey.DraftNoteId.name, null)

    private val visibilityAndChannelId = combine(visibility, channelId) { v, c ->
        VisibilityAndChannelId(v, c)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VisibilityAndChannelId())

    private val noteEditorSendToState = combine(
        visibilityAndChannelId,
        replyId,
        renoteId,
        reservationPostingAt,
        draftNoteId,
    ) { vc, replyId, renoteId, scheduleDate, dfId ->
        NoteEditorSendToState(
            visibility = vc.visibility,
            channelId = vc.channelId,
            replyId = replyId,
            renoteId = renoteId,
            schedulePostAt = scheduleDate?.let {
                Instant.fromEpochMilliseconds(it.time)
            },
            draftNoteId = dfId
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NoteEditorSendToState())

    val uiState = combine(
        noteEditorFormState,
        noteEditorSendToState,
        files,
        poll,
        currentAccount,
    ) { formState, sendToState, files, poll, account ->
        NoteEditorUiState(
            formState = formState,
            sendToState = sendToState,
            poll = poll,
            files = files,
            currentAccount = account,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NoteEditorUiState())

    val isPostAvailable = uiState.map {
        it.checkValidate(textMaxLength = maxTextLength.value, maxFileCount = maxFileCount.value)
    }.asLiveData()

    val isPost = EventBus<Boolean>()

    val showPollDatePicker = EventBus<Unit>()
    val showPollTimePicker = EventBus<Unit>()


    val isSaveNoteAsDraft = EventBus<Long?>()

    init {
        accountStore.observeCurrentAccount.filterNotNull().map {
            it to noteEditorSwitchAccountExecutor(
                currentAccount.value,
                noteEditorSendToState.value,
                it
            )
        }.onEach { (account, result) ->
            if (account.accountId != currentAccount.value?.accountId && currentAccount.value != null) {
                savedStateHandle.setReplyId(result.replyId)
                savedStateHandle.setRenoteId(result.renoteId)
                savedStateHandle.setChannelId(result.channelId)
            }
            if (currentAccount.value != null) {
                savedStateHandle.setVisibility(null)
            }
            currentAccount.value = account
        }.launchIn(viewModelScope + Dispatchers.IO)
    }

    fun setRenoteTo(noteId: Note.Id?) {
        savedStateHandle.setRenoteId(noteId)
    }

    fun setReplyTo(noteId: Note.Id?) {
        savedStateHandle.setReplyId(noteId)
        if (noteId == null) {
            return
        }
        viewModelScope.launch {

            // NOTE: リプライ先のcwの状態をフォームに反映するようにする
            noteRepository.find(noteId).onSuccess { note ->
                savedStateHandle.setHasCw(note.cw != null)
                savedStateHandle.setCw(note.cw)
                savedStateHandle.setVisibility(note.visibility)
                savedStateHandle.setChannelId(note.channelId)
            }

            getAllMentionUsersUseCase(noteId).onSuccess { users ->
                val (text, pos) = savedStateHandle.getText()
                    .addMentionUserNames(
                        users.map { it.displayUserName }, 0
                    )
                savedStateHandle.setText(text)
                textCursorPos.tryEmit(TextWithCursorPos(text, pos))
            }
        }
    }

    fun setDraftNoteId(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            draftNoteRepository.findOne(id).mapCatching {
                val account = accountRepository.get(it.accountId).getOrThrow()
                it.toNoteEditingState().copy(
                    currentAccount = account
                )
            }.onSuccess { note ->
                currentAccount.value = note.currentAccount
                savedStateHandle.applyBy(note)
            }
        }

    }


    fun changeText(text: String) {
        savedStateHandle[NoteEditorSavedStateKey.Text.name] = text
    }

    fun addPollChoice() {
        savedStateHandle.setPoll(savedStateHandle.getPoll().addPollChoice())
    }

    fun changePollChoice(id: UUID, text: String) {
        savedStateHandle.setPoll(
            savedStateHandle.getPoll().updatePollChoice(id, text)
        )
    }

    fun removePollChoice(id: UUID) {
        savedStateHandle.setPoll(
            savedStateHandle.getPoll().removePollChoice(id)
        )
    }


    fun togglePollMultiple() {
        savedStateHandle.setPoll(savedStateHandle.getPoll()?.toggleMultiple())
    }

    fun setPollExpiresAt(expiresAt: PollExpiresAt) {
        val state = savedStateHandle.getPoll()
        savedStateHandle.setPoll(
            state?.copy(
                expiresAt = expiresAt
            )
        )
    }


    fun post() {
        currentAccount.value?.let { account ->
            viewModelScope.launch(Dispatchers.IO) {
                val reservationPostingAt =
                    savedStateHandle.getNoteEditingUiState(account, visibility.value).sendToState.schedulePostAt
                draftNoteService.save(
                    savedStateHandle.getNoteEditingUiState(account, visibility.value).toCreateNote(account)
                ).mapCatching { dfNote ->
                    if (reservationPostingAt == null || reservationPostingAt <= Clock.System.now()) {
                        createNoteWorkerExecutor.enqueue(dfNote.draftNoteId)
                    } else {
                        noteReservationPostExecutor.register(dfNote)
                    }
                }.onSuccess {
                    withContext(Dispatchers.Main) {
                        isPost.event = true
                    }
                }.onFailure {
                    logger.error("登録失敗", it)
                }
            }
        }

    }

    fun toggleNsfw(appFile: AppFile) {
        when (appFile) {
            is AppFile.Local -> {
                savedStateHandle.setFiles(files.value.toggleFileSensitiveStatus(appFile))
                savedStateHandle[NoteEditorSavedStateKey.PickedFiles.name] =
                    files.value.toggleFileSensitiveStatus(appFile)
            }
            is AppFile.Remote -> {
                viewModelScope.launch(Dispatchers.IO) {
                    runCancellableCatching {
                        driveFileRepository.toggleNsfw(appFile.id)
                    }
                }
            }
        }

    }

    fun add(file: AppFile) {
        val files = files.value.toMutableList()
        files.add(
            file
        )
        savedStateHandle.setFiles(files)
    }


    private fun addAllFileProperty(fpList: List<FileProperty>) {
        val files = savedStateHandle.getFiles().toMutableList()
        files.addAll(fpList.map {
            AppFile.Remote(it.id)
        })
        savedStateHandle.setFiles(files)

    }

    fun addFilePropertyFromIds(ids: List<FileProperty.Id>) {
        viewModelScope.launch(Dispatchers.IO) {
            filePropertyDataSource.findIn(ids).onSuccess {
                addAllFileProperty(it)
            }
        }
    }

    fun removeFileNoteEditorData(file: AppFile) {
        savedStateHandle.setFiles(
            savedStateHandle.getFiles().removeFile(file)
        )
    }


    fun fileTotal(): Int {
        return files.value.size
    }


    fun enablePoll() {
        val poll =
            if (savedStateHandle.getPoll() == null) PollEditingState(emptyList(), false) else null
        savedStateHandle.setPoll(poll)
    }

    fun disablePoll() {
        savedStateHandle.setPoll(null)
    }

    fun setText(text: String) {
        savedStateHandle.setText(text)
    }

    fun changeCwEnabled() {
        savedStateHandle.setHasCw(!savedStateHandle.getHasCw())
    }

    fun setCw(text: String?) {
        savedStateHandle.setCw(text)
    }

    fun setVisibility(visibility: Visibility) {
        logger.debug("公開範囲がセットされた:$visibility")
        savedStateHandle.setChannelId(null)
        savedStateHandle.setVisibility(visibility)
    }

    fun setChannelId(channelId: Channel.Id?) {
        savedStateHandle.setChannelId(channelId)
        if (channelId == null) {
            return
        } else {
            savedStateHandle.setVisibility(Visibility.Public(true))
        }
    }

    fun toggleReservationAt() {
        savedStateHandle.setScheduleAt(
            if (reservationPostingAt.value == null) Date(
                Clock.System.now().toEpochMilliseconds()
            ) else null
        )
    }


    fun setAddress(added: List<User.Id>, removed: List<User.Id>) {
        val list = ((visibility.value as? Visibility.Specified)?.visibleUserIds
            ?: emptyList()).toMutableList()
        list.addAll(
            added
        )

        list.removeAll {
            removed.any()
        }

        savedStateHandle.setVisibility(Visibility.Specified(list))
    }


    fun addMentionUserNames(userNames: List<String>, pos: Int): Int {
        val (text, nextPos) = savedStateHandle.getText()
            .addMentionUserNames(userNames, pos)
        savedStateHandle.setText(text)
        return nextPos
    }

    fun addEmoji(emoji: Emoji, pos: Int): Int {
        return addEmoji(":${emoji.name}:", pos)
    }

    fun addEmoji(emoji: String, pos: Int): Int {
        val builder = StringBuilder(savedStateHandle.getText() ?: "")
        builder.insert(pos, emoji)
        savedStateHandle.setText(builder.toString())
        logger.debug("position:${pos + emoji.length - 1}")
        return pos + emoji.length
    }

    fun setSchedulePostAt(instant: Instant?) {
        savedStateHandle.setScheduleAt(instant?.let {
            Date(it.toEpochMilliseconds())
        })
    }


    fun saveDraft() {
        if (!canSaveDraft()) {
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            when (val account = currentAccount.value) {
                null -> Result.failure(UnauthorizedException())
                else -> Result.success(account)
            }.mapCatching { account ->
                draftNoteService.save(uiState.value.toCreateNote(account)).getOrThrow()
            }.onSuccess { result ->
                isSaveNoteAsDraft.event = result.draftNoteId
            }.onFailure { e ->
                logger.error("下書き保存に失敗した", e)
            }
        }
    }

    fun canSaveDraft(): Boolean {
        return uiState.value.shouldDiscardingConfirmation()
    }


    fun clear() {
        savedStateHandle.applyBy(NoteEditorUiState())
    }

    private fun setUpUserViewData(userId: User.Id): UserViewData {
        return userViewDataFactory.create(userId, viewModelScope, dispatcher)
    }


}

data class TextWithCursorPos(val text: String?, val cursorPos: Int)