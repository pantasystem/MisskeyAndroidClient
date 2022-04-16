package jp.panta.misskeyandroidclient.ui.notes.viewmodel.reaction

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import net.pantasystem.milktea.common.Logger
import jp.panta.misskeyandroidclient.model.notes.Note
import jp.panta.misskeyandroidclient.model.notes.reaction.ReactionHistory
import jp.panta.misskeyandroidclient.model.notes.reaction.ReactionHistoryDataSource
import jp.panta.misskeyandroidclient.model.notes.reaction.ReactionHistoryPaginator
import jp.panta.misskeyandroidclient.model.notes.reaction.ReactionHistoryRequest
import jp.panta.misskeyandroidclient.viewmodel.MiCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

class ReactionHistoryViewModel(
    private val reactionHistoryDataSource: ReactionHistoryDataSource,
    private val paginator: ReactionHistoryPaginator,
    val logger: net.pantasystem.milktea.common.Logger?
) : ViewModel(){


    @Suppress("UNCHECKED_CAST")
    class Factory(
        val noteId: Note.Id,
        val type: String?,
        val miCore: MiCore
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ReactionHistoryViewModel(
                miCore.getReactionHistoryDataSource(),
                miCore.getReactionHistoryPaginatorFactory().create(
                    ReactionHistoryRequest(noteId, type)
                ),
                miCore.loggerFactory.create("ReactionHistoryVM")
            ) as T
        }
    }

    val isLoading = MutableLiveData(false)
    val histories = MutableLiveData<List<ReactionHistory>>(emptyList())

    init {
        reactionHistoryDataSource.filter(paginator.reactionHistoryRequest.noteId, paginator.reactionHistoryRequest.type).onEach {
            histories.postValue(it)
        }.catch {

        }.launchIn(viewModelScope + Dispatchers.IO)
    }

    fun next() {
        if(isLoading.value == true) {
            return
        }
        isLoading.value = true
        viewModelScope.launch(Dispatchers.IO) {

            runCatching {
                paginator.next()
            }.onFailure {
                logger?.error("リアクションの履歴の取得に失敗しました", e = it)
            }
            isLoading.postValue(false)
        }
    }

}