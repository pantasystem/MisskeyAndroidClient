package net.pantasystem.milktea.data.infrastructure.notes

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import net.pantasystem.milktea.api.mastodon.status.TootStatusDTO
import net.pantasystem.milktea.api_streaming.ChannelBody
import net.pantasystem.milktea.api_streaming.channel.ChannelAPI
import net.pantasystem.milktea.api_streaming.channel.connectUserTimeline
import net.pantasystem.milktea.api_streaming.mastodon.Event
import net.pantasystem.milktea.data.streaming.ChannelAPIWithAccountProvider
import net.pantasystem.milktea.data.streaming.StreamingAPIProvider
import net.pantasystem.milktea.model.account.Account
import net.pantasystem.milktea.model.account.page.CanOnlyMedia
import net.pantasystem.milktea.model.account.page.Pageable
import net.pantasystem.milktea.model.notes.Note
import net.pantasystem.milktea.model.notes.NoteStreaming
import javax.inject.Inject

class NoteStreamingImpl @Inject constructor(
    private val channelAPIProvider: ChannelAPIWithAccountProvider,
    val noteDataSourceAdder: NoteDataSourceAdder,
    val streamingAPIProvider: StreamingAPIProvider,
) : NoteStreaming {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun connect(getAccount: suspend () -> Account, pageable: Pageable): Flow<Note> {
        return flow {
            emit(getAccount())
        }.filter {
            pageable is Pageable.GlobalTimeline
                    || pageable is Pageable.HybridTimeline
                    || pageable is Pageable.LocalTimeline
                    || pageable is Pageable.HomeTimeline
                    || pageable is Pageable.UserListTimeline
                    || pageable is Pageable.Antenna
                    || pageable is Pageable.UserTimeline
                    || pageable is Pageable.ChannelTimeline
                    || pageable is Pageable.Mastodon.HomeTimeline
                    || pageable is Pageable.Mastodon.LocalTimeline
                    || pageable is Pageable.Mastodon.PublicTimeline
                    || pageable is Pageable.Mastodon.ListTimeline
                    || pageable is Pageable.Mastodon.HashTagTimeline
        }.flatMapLatest { ac ->
            when (pageable) {
                is Pageable.GlobalTimeline -> {
                    requireNotNull(channelAPIProvider.get(ac)).connect(ChannelAPI.Type.Global)
                        .convertToNote(getAccount, pageable)
                }
                is Pageable.HybridTimeline -> {
                    requireNotNull(channelAPIProvider.get(ac)).connect(ChannelAPI.Type.Hybrid)
                        .convertToNote(getAccount, pageable)
                }
                is Pageable.LocalTimeline -> {
                    requireNotNull(channelAPIProvider.get(ac)).connect(ChannelAPI.Type.Local)
                        .convertToNote(getAccount, pageable)
                }
                is Pageable.HomeTimeline -> {
                    requireNotNull(channelAPIProvider.get(ac)).connect(ChannelAPI.Type.Home)
                        .convertToNote(getAccount, pageable)
                }
                is Pageable.UserListTimeline -> {
                    requireNotNull(channelAPIProvider.get(ac))
                        .connect(ChannelAPI.Type.UserList(userListId = pageable.listId))
                        .convertToNote(getAccount, pageable)
                }
                is Pageable.Antenna -> {
                    requireNotNull(channelAPIProvider.get(ac))
                        .connect(ChannelAPI.Type.Antenna(antennaId = pageable.antennaId))
                        .convertToNote(getAccount, pageable)
                }
                is Pageable.UserTimeline -> {
                    requireNotNull(channelAPIProvider.get(ac))
                        .connectUserTimeline(pageable.userId).convertToNote(getAccount, pageable)
                }
                is Pageable.ChannelTimeline -> {
                    requireNotNull(channelAPIProvider.get(ac))
                        .connect(ChannelAPI.Type.Channel(channelId = pageable.channelId))
                        .convertToNote(getAccount, pageable)
                }
                is Pageable.Mastodon -> {
                    when (pageable) {
                        is Pageable.Mastodon.HashTagTimeline -> {
                            requireNotNull(streamingAPIProvider.get(ac)).connectHashTag(pageable.hashtag)
                                .convertToNoteFromStatus(getAccount, pageable)
                        }
                        Pageable.Mastodon.HomeTimeline -> {
                            requireNotNull(streamingAPIProvider.get(ac)).connectUser()
                                .convertNoteFromEvent(getAccount)
                        }
                        is Pageable.Mastodon.ListTimeline -> requireNotNull(
                            streamingAPIProvider.get(
                                ac
                            )
                        ).connectUserList(pageable.listId).convertToNoteFromStatus(getAccount, pageable)
                        is Pageable.Mastodon.LocalTimeline -> requireNotNull(
                            streamingAPIProvider.get(
                                ac
                            )
                        ).connectLocalPublic().convertToNoteFromStatus(getAccount, pageable)
                        is Pageable.Mastodon.PublicTimeline -> requireNotNull(
                            streamingAPIProvider.get(
                                ac
                            )
                        ).connectPublic().convertToNoteFromStatus(getAccount, pageable)
                        else -> throw IllegalStateException("Global, Hybrid, Local, Homeは以外のStreamは対応していません。")
                    }
                }
                else -> throw IllegalStateException("Global, Hybrid, Local, Homeは以外のStreamは対応していません。")
            }
        }
    }

    private fun Flow<ChannelBody>.convertToNote(getAccount: suspend () -> Account, pageable: Pageable): Flow<Note> {
        return mapNotNull {
            it as? ChannelBody.ReceiveNote
        }.filter {
            if (pageable is CanOnlyMedia<*>) {
                if (pageable.getOnlyMedia()) {
                    !it.body.fileIds.isNullOrEmpty()
                } else {
                    true
                }
            } else {
                true
            }
        }.map {
            noteDataSourceAdder.addNoteDtoToDataSource(getAccount(), it.body)
        }
    }

    private fun Flow<TootStatusDTO>.convertToNoteFromStatus(getAccount: suspend () -> Account, pageable: Pageable): Flow<Note> {
        return filter {
            if (pageable is CanOnlyMedia<*>) {
                if (pageable.getOnlyMedia()) {
                    it.mediaAttachments.isNotEmpty()
                } else {
                    true
                }
            } else {
                true
            }
        }.map {
            noteDataSourceAdder.addTootStatusDtoIntoDataSource(getAccount(), it)
        }
    }

    private fun Flow<Event>.convertNoteFromEvent(getAccount: suspend () -> Account): Flow<Note> {
        return mapNotNull {
            (it as? Event.Update?)?.status
        }.map {
            noteDataSourceAdder.addTootStatusDtoIntoDataSource(getAccount(), it)
        }
    }
}