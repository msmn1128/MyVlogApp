package com.example.myvlogapp.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.example.myvlogapp.canReplaceWithProject
import com.example.myvlogapp.edit.TimelineStore
import com.example.myvlogapp.formatSavedAt
import com.example.myvlogapp.projectLoadedMessage
import com.example.myvlogapp.projectUnreadableMessage

// =====================================================================================
// 一時保存（名前を付けて残す編集内容）の保存・読み出し。
//
// VlogViewModel から切り出したもの。長らく切り出せなかったのは、読み出しがクリップ一覧・
// 再生・履歴を同時に触っていたため。その3つが TimelineStore に揃ったので、こちらは
// 「ClipStore と TimelineStore の間を取り持つ」だけになり、独立できるようになった。
// =====================================================================================

/**
 * @param scope 呼び出し元（VlogViewModel）のスコープ。画面が消えたら保存処理も止まる
 * @param isAdding 動画を読み込み中か。読み込み中のタイムラインは途中の状態なので、
 *   保存も読み出しもさせない（呼ぶたびに最新を返すこと）
 * @param sendMessage 画面へのお知らせ（Toast）
 */
internal class ProjectsController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val timeline: TimelineStore,
    private val isAdding: () -> Boolean,
    private val sendMessage: (String) -> Unit
) {

    private val _projects = MutableStateFlow<List<SavedProject>>(emptyList())
    val projects: StateFlow<List<SavedProject>> = _projects.asStateFlow()

    /** 保存一覧を開くたびに呼ぶ。ここでしか変わらないので常時監視はしない */
    fun refresh() {
        scope.launch { _projects.value = ClipStore.listProjects(context) }
    }

    /**
     * 動画を読み込み中は、一時保存の保存・上書き・読み出しをしない。
     * 読み込み中のタイムラインは途中の状態で、保存すると一部だけが残り、読み出すと
     * あとから読み込み終えた動画が読み出した内容に混ざってしまうため。
     * @return 読み込み中で断った場合はtrue（通知済み）
     */
    private fun refuseWhileAdding(): Boolean {
        if (!isAdding()) return false
        sendMessage("動画を読み込み中です。終わってからもう一度お試しください")
        return true
    }

    /** いまの編集内容に名前を付けて残す。動画はコピーしないので一瞬で終わる */
    fun save(name: String) {
        if (refuseWhileAdding()) return
        val clipsToSave = timeline.current
        if (clipsToSave.isEmpty()) {
            sendMessage("保存できる編集内容がありません")
            return
        }

        scope.launch {
            val label = name.trim().ifBlank { formatSavedAt(System.currentTimeMillis()) }
            // 同名があれば連番が付く。メッセージには実際に付いた名前を出す
            val savedName = ClipStore.saveProject(context, label, clipsToSave)
            _projects.value = ClipStore.listProjects(context)
            sendMessage(
                if (savedName != null) "「$savedName」を保存しました"
                else "保存は${ClipStore.MAX_PROJECTS}件までです。不要なものを削除してください"
            )
        }
    }

    /**
     * 既存の保存内容へ上書きする（一覧の行を長押しして確認したときの動作）。
     * 上書きされた保存の中身は戻せない（「もとに戻す」で戻るのはタイムラインの編集だけ）ため、
     * 呼び出し側（SaveLoadDialog）で確認ダイアログを挟む。
     */
    fun overwrite(id: Long, name: String) {
        if (refuseWhileAdding()) return
        val clipsToSave = timeline.current
        if (clipsToSave.isEmpty()) {
            sendMessage("保存できる編集内容がありません")
            return
        }

        scope.launch {
            val overwritten = ClipStore.overwriteProject(context, id, clipsToSave)
            _projects.value = ClipStore.listProjects(context)
            sendMessage(
                if (overwritten) "「$name」に上書きしました"
                else "この保存は上書きできませんでした"
            )
        }
    }

    /**
     * 保存した編集内容へ差し替える。
     *
     * 履歴に積んでから入れ替えるので、読み出す前の状態には「もとに戻す」で戻れる。
     * ただし、保存内の動画が1本も読めないときは、作業中のタイムラインが空になってしまうので
     * 差し替えずに断る（[canReplaceWithProject]）。
     *
     * @param onLoaded 差し替えた直後に呼ぶ。撮影時刻の取り直しなど、
     *   タイムラインの中身に依存する後処理を呼び出し元（VlogViewModel）に任せるため
     */
    fun load(id: Long, onLoaded: () -> Unit) {
        if (refuseWhileAdding()) return
        scope.launch {
            val restored = ClipStore.loadProject(context, id)
            if (restored == null) {
                sendMessage("この保存は読み出せませんでした")
                return@launch
            }
            if (!canReplaceWithProject(loaded = restored.clips.size, dropped = restored.dropped)) {
                sendMessage(projectUnreadableMessage(restored.dropped))
                return@launch
            }
            // 入れ替える直前にもう一度確かめる。読み出しを押すと一覧はすぐ閉じ、上の
            // 読み出し（保存内の動画を1本ずつ開いて確かめる）の間も「動画を追加」を押せる。
            // その追加の読み込み中にここで入れ替えると、あとから読み込み終えた動画が
            // 読み出した内容に混ざる。動画を開くのが遅いとき（クラウド上のファイルなど）に起きうる
            if (refuseWhileAdding()) return@launch

            timeline.replaceAll(restored.clips, record = true)
            onLoaded()

            sendMessage(projectLoadedMessage(restored.dropped))
        }
    }

    fun delete(id: Long) {
        scope.launch {
            ClipStore.deleteProject(context, id)
            _projects.value = ClipStore.listProjects(context)
        }
    }
}
