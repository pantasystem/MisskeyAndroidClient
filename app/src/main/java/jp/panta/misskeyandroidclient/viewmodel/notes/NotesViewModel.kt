package jp.panta.misskeyandroidclient.viewmodel.notes

import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import jp.panta.misskeyandroidclient.model.api.MisskeyAPI
import jp.panta.misskeyandroidclient.model.auth.ConnectionInstance
import jp.panta.misskeyandroidclient.model.drive.FileProperty
import jp.panta.misskeyandroidclient.model.notes.*
import jp.panta.misskeyandroidclient.model.notes.poll.Vote
import jp.panta.misskeyandroidclient.model.users.User
import jp.panta.misskeyandroidclient.util.eventbus.EventBus
import jp.panta.misskeyandroidclient.view.SafeUnbox
import jp.panta.misskeyandroidclient.viewmodel.notes.media.FileViewData
import jp.panta.misskeyandroidclient.viewmodel.notes.media.MediaViewData
import jp.panta.misskeyandroidclient.viewmodel.notes.poll.PollViewData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.File

class NotesViewModel(
    ci: ConnectionInstance,
    api: MisskeyAPI
) : ViewModel(){
    private val TAG = "NotesViewModel"
    var connectionInstance = ci
    var misskeyAPI = api

    val statusMessage = EventBus<String>()

    val errorStatusMessage = EventBus<String>()

    val reNoteTarget = EventBus<PlaneNoteViewData>()

    val quoteRenoteTarget = EventBus<PlaneNoteViewData>()

    val replyTarget = EventBus<PlaneNoteViewData>()

    val reactionTarget = EventBus<PlaneNoteViewData>()

    val submittedNotesOnReaction = EventBus<PlaneNoteViewData>()

    val shareTarget = EventBus<PlaneNoteViewData>()
    val shareNoteState = MutableLiveData<State>()

    val targetUser = EventBus<User>()

    val targetNote = EventBus<PlaneNoteViewData>()

    val targetFile = EventBus<Pair<FileViewData, MediaViewData>>()

    fun setTargetToReNote(note: PlaneNoteViewData){
        //reNoteTarget.postValue(note)
        Log.d("NotesViewModel", "登録しました: $note")
        reNoteTarget.event = note
    }

    fun setTargetToReply(note: PlaneNoteViewData){
        replyTarget.event = note
    }

    fun setTargetToShare(note: PlaneNoteViewData){
        shareTarget.event = note
        loadNoteState(note)
    }

    fun setTargetToUser(user: User){
        targetUser.event = user
    }

    fun setTargetToNote(){
        targetNote.event = shareTarget.event
    }
    fun setTargetToNote(note: PlaneNoteViewData){
        targetNote.event = note
    }

    fun postRenote(){
        val renoteId = reNoteTarget.event?.toShowNote?.id
        if(renoteId != null){
            val request = CreateNote(i = connectionInstance.getI()!!, text = null, renoteId = renoteId)
            misskeyAPI.create(request).enqueue(object : Callback<CreateNote.Response>{
                override fun onResponse(
                    call: Call<CreateNote.Response>,
                    response: Response<CreateNote.Response>
                ) {
                    statusMessage.event = "renoteしました"

                }
                override fun onFailure(call: Call<CreateNote.Response>, t: Throwable) {
                    errorStatusMessage.event = "renote失敗しました"

                }

            })
        }
    }

    fun putQuoteRenoteTarget(){
        quoteRenoteTarget.event = reNoteTarget.event
    }

    //直接送信
    fun postReaction(planeNoteViewData: PlaneNoteViewData, reaction: String){
        val myReaction = planeNoteViewData.myReaction.value

        viewModelScope.launch(Dispatchers.IO){
            //リアクション解除処理をする
            submittedNotesOnReaction.event = planeNoteViewData
            Log.d("NotesViewModel", "postReaction(n, n)")
            try{
                if(myReaction != null){
                    misskeyAPI.deleteReaction(
                        DeleteNote(
                            i = connectionInstance.getI()!!,
                            noteId = planeNoteViewData.toShowNote.id
                        )
                    ).execute()

                }
                val res = misskeyAPI.createReaction(CreateReaction(
                    i = connectionInstance.getI()!!,
                    reaction = reaction,
                    noteId = planeNoteViewData.toShowNote.id
                )).execute()
                Log.d("NotesViewModel", "結果: $res")
            }catch(e: Exception){
                Log.e("NotesViewModel", "postReaction error", e)
            }

        }
    }

    fun setTargetToReaction(planeNoteViewData: PlaneNoteViewData){
        Log.d("NotesViewModel", "connectionInstance: $connectionInstance")
        reactionTarget.event = planeNoteViewData
    }

    //setTargetToReactionが呼び出されている必要がある
    fun postReaction(reaction: String){
        val targetNote =  reactionTarget.event
        if(targetNote != null){
            postReaction(targetNote, reaction)
        }
    }

    fun addFavorite(note: PlaneNoteViewData? = shareTarget.event){
        note?: return

        misskeyAPI.createFavorite(
            NoteRequest(
                i = connectionInstance.getI(),
                noteId = note.toShowNote.id
            )
        ).enqueue(object : Callback<Unit>{
            override fun onResponse(call: Call<Unit>, response: Response<Unit>) {
                Log.d(TAG, "お気に入りに追加しました")
                statusMessage.event = "お気に入りに追加しました"
            }
            override fun onFailure(call: Call<Unit>, t: Throwable) {
                Log.e(TAG, "お気に入りに追加失敗しました", t)
                statusMessage.event = "お気に入りにへの追加に失敗しました"
            }
        })

    }

    fun deleteFavorite(note: PlaneNoteViewData? = shareTarget.event){
        note?: return

        misskeyAPI.deleteFavorite(
            NoteRequest(
                i = connectionInstance.getI(),
                noteId = note.toShowNote.id
            )
        ).enqueue(object : Callback<Unit>{
            override fun onResponse(call: Call<Unit>, response: Response<Unit>) {
                Log.d(TAG, "お気に入りから削除しました")
                statusMessage.event = "お気に入りから削除しました"
            }

            override fun onFailure(call: Call<Unit>, t: Throwable) {
                Log.e(TAG, "お気に入りの削除に追加失敗しました", t)
                statusMessage.event = "お気に入りの削除に失敗しました"
            }
        })
    }

    fun removeNoteFromShareTarget(){
        val note = shareTarget.event

        val isNowCurrentAccount = note?.connectionInstance?.userId == connectionInstance.userId

        if(note != null && isNowCurrentAccount){
            removeNote(note)
        }
    }
    fun removeNote(planeNoteViewData: PlaneNoteViewData){
        misskeyAPI.delete(
            DeleteNote(
                i = connectionInstance.getI()!!,
                noteId = planeNoteViewData.toShowNote.id
            )
        ).enqueue(object : Callback<Unit>{
            override fun onResponse(call: Call<Unit>, response: Response<Unit>) {
                Log.d(TAG, "削除に成功しました")
                if(response.code() == 204){
                    statusMessage.event = "削除に成功しました"
                }
            }

            override fun onFailure(call: Call<Unit>, t: Throwable) {
                Log.d(TAG, "削除に失敗しました")
            }
        })
    }

    private fun loadNoteState(planeNoteViewData: PlaneNoteViewData){
        misskeyAPI.noteState(NoteRequest(i = connectionInstance.getI(), noteId = planeNoteViewData.toShowNote.id))
            .enqueue(object : Callback<State>{
                override fun onResponse(call: Call<State>, response: Response<State>) {
                    val nowNoteId = shareTarget.event?.toShowNote?.id
                    if(nowNoteId == planeNoteViewData.toShowNote.id){
                        val state = response.body()?: return
                        Log.d(TAG, "state: $state")
                        shareNoteState.postValue(state)
                    }
                }
                override fun onFailure(call: Call<State>, t: Throwable) {
                    Log.e(TAG, "note stateの取得に失敗しました", t)
                }
            })
    }

    fun showFile(media: MediaViewData, file: FileViewData){
        targetFile.event = Pair(file, media)
    }

    fun vote(poll: PollViewData, choice: PollViewData.Choice){
        if(SafeUnbox.unbox(poll.canVote.value)){
            misskeyAPI.vote(
                Vote(
                    i = connectionInstance.getI()!!,
                    choice = choice.number,
                    noteId = poll.noteId
                )
            ).enqueue(object : Callback<Unit>{
                override fun onResponse(call: Call<Unit>, response: Response<Unit>) {
                    if(response.code() == 204){
                        Log.d(TAG, "投票に成功しました")
                    }else{
                        Log.d(TAG, "投票に失敗しました")
                    }
                }

                override fun onFailure(call: Call<Unit>, t: Throwable) {
                    Log.d(TAG, "投票に失敗しました")
                }
            })
        }
    }
}