package com.example.myvlogapp.edit

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs
import com.example.myvlogapp.MAX_CLIPS
import com.example.myvlogapp.MIN_TEXT_SEGMENT_MS
import com.example.myvlogapp.TextSegment
import com.example.myvlogapp.VideoMeta
import com.example.myvlogapp.VlogClip
import com.example.myvlogapp.clampTimelineShift
import com.example.myvlogapp.mergeByShotAt

// =====================================================================================
// タイムライン（クリップ一覧）の持ち主。
//
// VlogViewModel から切り出したもの。「クリップ一覧」「もとに戻す/やり直すの履歴」
// 「ExoPlayerのプレイリストとの同期」「一覧を書き換える操作どうしの直列化」の4つは
// 互いに切り離せない。編集のたびに履歴へ積み、プレイリストも同じ形に保つ必要があるため。
// この4つをひとまとまりにしたのがこのクラスで、上に載る機能（動画の追加・一時保存の
// 読み書き・書き出し）は、ここを通してタイムラインを触る。
//
// 一時保存をViewModelから切り出せなかったのは、読み出しがこの4つを同時に触るからだった。
// 4つがここに揃ったことで、ProjectsController（data/）は独立できるようになった。
// =====================================================================================

/** [TimelineStore.insertByShotAt] の結果。呼び出し元が追加結果の通知に使う */
internal class InsertResult(
    val inserted: List<VlogClip>,
    /** ロックの中で「すでにタイムラインにあった」と分かって除いた件数 */
    val alreadyPresent: Int,
    /** 上限（[MAX_CLIPS]）を超えるため入れなかった件数 */
    val overLimit: Int
)

/**
 * @param playback 再生側。実機では PlaybackController、テストでは偽物を渡す（理由は[TimelinePlayback]）
 * @param elapsedMs 履歴のまとめ判定に使う時計。実機では`SystemClock::elapsedRealtime`を渡す。
 *   壁時計だと時刻合わせで巻き戻り、まとめ判定が意図せず効いたり効かなかったりするため
 * @param sendMessage 画面へのお知らせ（Toast）。編集が断られたときにだけ使う
 * @param onUrisReleased タイムラインから外れた動画のURI。波形のキャッシュとデコード中の
 *   ジョブを捨てるために、呼び出し元（VlogViewModel）へ知らせる。
 *   一覧を更新した「あと」に呼ぶこと（まだ他のクリップが同じURIを使っているかを見るため）
 */
internal class TimelineStore(
    private val playback: TimelinePlayback,
    elapsedMs: () -> Long,
    private val sendMessage: (String) -> Unit,
    private val onUrisReleased: (List<Uri>) -> Unit
) {

    private val _clips = MutableStateFlow<List<VlogClip>>(emptyList())
    val clips: StateFlow<List<VlogClip>> = _clips.asStateFlow()

    /** いまのクリップ一覧。StateFlowを経由せず直接読みたい場面用 */
    val current: List<VlogClip> get() = _clips.value

    val selectedClip: VlogClip? get() = _clips.value.getOrNull(playback.selectedIndexValue)

    // --- 履歴（もとに戻す / やり直す） ---------------------------------------------------
    //
    // 積む・まとめる・取り出すの仕組みは EditHistory が持つ。ここに残すのは
    // 「スナップショットに何を含めるか」と「取り出した状態を画面へ戻す方法」の2つだけ。
    private data class Snapshot(val clips: List<VlogClip>, val selectedIndex: Int)

    private val history = EditHistory<Snapshot>(elapsedMs = elapsedMs)

    val canUndo: StateFlow<Boolean> get() = history.canUndo
    val canRedo: StateFlow<Boolean> get() = history.canRedo

    /**
     * タイムラインを丸ごと別の内容へ入れ替えた回数
     * （一時保存の読み出しと、その「もとに戻す / やり直す」）。
     *
     * 画面はこれが変わったらタイル一覧（LazyRow）を作り直す。1件ずつの追加・削除と違い、
     * 丸ごとの入れ替えでは全クリップのidが一斉に変わる。するとタイルが消えるアニメーション
     * （`Modifier.animateItem`）が取り残され、**消えたはずのタイルが画面に残り続ける**
     * （どこかに触れて再コンポーズが起きるまで消えない）。実機で踏んだのは
     * 「一時保存を読み出す → もとに戻す」で、選択位置も一緒に動く場合。
     * 一覧ごと作り直してしまえば、持ち越すアニメーション自体が無くなる。
     *
     * 中身が変わらない入れ替え（ひとことやトリミングだけのundo）では増やさない。
     * そこまで作り直すと、1件ずつの編集でタイルがアニメーションしなくなる。
     */
    private val _replacementCount = MutableStateFlow(0)
    val replacementCount: StateFlow<Int> = _replacementCount.asStateFlow()

    /**
     * 一覧を非同期の下ごしらえを伴って書き換える操作（クリップ追加・一時保存の読み込みなど）を
     * 直列化するロック。
     *
     * これらは「バックグラウンドでの下ごしらえ → 完了後に一覧へ反映」という形を取るため、
     * 2つの操作が重なると片方の反映が失われることがある（例：動画追加のメタデータ取得中に
     * 一時保存を読み込むと、その後の追加が古い一覧を基準に追記してしまい、読み出した内容を
     * 巻き戻すか、逆に読み出しが追加の結果を消してしまう）。反映（コミット）部分だけを
     * このロックで囲み、常に最新の一覧を基準にする。
     */
    private val mutex = Mutex()

    // --- 一覧をまとめて入れ替える -------------------------------------------------------

    /**
     * タイムラインを丸ごと差し替える（前回の続きの復元・一時保存の読み出し）。
     *
     * @param record 履歴に積むか。復元は「ここが起点」なので積まない（積むと、アプリを
     *   開いた直後に「もとに戻す」を押せてしまい、空の状態へ戻ってしまう）。
     *   一時保存の読み出しは積む（読み出す前へ戻れるように）。
     */
    suspend fun replaceAll(clips: List<VlogClip>, record: Boolean) = mutex.withLock {
        // 空のまま空で置き換えるなら何もしない。起動直後（前回の続きが無い）に
        // プレイヤーを作り直さないため。
        if (clips.isEmpty() && _clips.value.isEmpty()) return@withLock

        if (record) recordHistory()
        _clips.value = clips
        _replacementCount.value++
        playback.rebuildPlaylist(clips)
        // 空を読み出したときだけここで0に戻す。中身があるときは select(0) が
        // 同じ代入をやり直すことになるので、そちらだけに任せる。
        if (clips.isNotEmpty()) {
            playback.select(0)
        } else {
            playback.setSelectedIndex(0)
            playback.setPositionMs(0L)
        }
    }

    /**
     * 撮影/作成日時順になる位置へ差し込み、追加した中で最も古いものを選択する。
     *
     * ロックの外で行った重複チェックは、メタデータの取得中に別の追加が同じ動画を先に
     * 入れてしまう競合には対応できない。ここでもう一度確かめて除外する。
     * 上限も、追加の直前の本数を基準にここで守る（並行した追加で超えないように）。
     */
    suspend fun insertByShotAt(candidates: List<VlogClip>): InsertResult = mutex.withLock {
        val currentUris = _clips.value.map { it.uri }.toSet()
        val fresh = candidates.filter { it.uri !in currentUris }
        val room = (MAX_CLIPS - _clips.value.size).coerceAtLeast(0)
        val toMerge = fresh.take(room)
        val result = InsertResult(
            inserted = toMerge,
            alreadyPresent = candidates.size - fresh.size,
            overLimit = fresh.size - toMerge.size
        )
        if (toMerge.isEmpty()) return@withLock result

        val oldestAddedId = toMerge.minByOrNull { it.sortKeyMs }?.id

        recordHistory()
        val (merged, insertions) = mergeByShotAt(_clips.value, toMerge)
        _clips.value = merged
        playback.insertIntoPlaylist(insertions)

        oldestAddedId?.let { id ->
            val index = merged.indexOfFirst { it.id == id }
            if (index >= 0) playback.select(index)
        }
        result
    }

    /**
     * 撮影時刻を取り直した結果を反映する。
     * 並び順は変えない（ユーザーが並べ替えた順序を壊さないため）。履歴にも積まない（編集ではない）。
     *
     * 履歴に積んである過去の状態にも同じ結果を当てる。撮影時刻は動画ファイルから決まる値で、
     * 編集の一部ではないため。当てないと、取り直し（起動直後に裏で1本ずつ読む）の最中に
     * 編集してから「もとに戻す」を押したとき、時刻が取り直し前の値へ戻ってしまう
     * （印も外れるので、次の起動でまた読み直すことにもなる）。
     *
     * @param refreshed クリップidごとの取り直し結果。取り直しの対象でなかったクリップ
     *   （この間に追加されたものなど）は触らない
     */
    suspend fun applyRefreshedShotTimes(refreshed: Map<Long, VideoMeta>) = mutex.withLock {
        _clips.value = _clips.value.withRefreshedShotTimes(refreshed)
        history.updateAll { it.copy(clips = it.clips.withRefreshedShotTimes(refreshed)) }
    }

    private fun List<VlogClip>.withRefreshedShotTimes(refreshed: Map<Long, VideoMeta>) = map { clip ->
        val meta = refreshed[clip.id] ?: return@map clip
        // 確かな値が取れなければ、値はそのままに「試した」印だけ付ける
        if (!meta.shotAtReliable) return@map clip.copy(shotAtRefreshed = true)
        clip.copy(
            timeText = meta.timeText,
            dateText = meta.dateText,
            shotAtMillis = meta.shotAtMillis,
            shotAtReliable = true,
            shotAtRefreshed = true
        )
    }

    /** 履歴を空にする。復元直後など「ここを起点にしたい」場面で呼ぶ */
    fun clearHistory() = history.clear()

    // --- 編集コマンド -------------------------------------------------------------------

    fun select(index: Int) = playback.select(index)

    /**
     * トリミング範囲の変更。
     *
     * @param previewAtMs プレビューに出す位置。波形の右端を掴んでいるのに左端の映像が
     *   出ると「どこで切れるのか」が確認できないため、掴んでいる側を渡してもらう。
     */
    fun updateTrim(startMs: Long, endMs: Long, previewAtMs: Long = startMs) {
        recordHistory(EditTag.Trim(playback.selectedIndexValue))
        updateSelected { it.copy(startMs = startMs, endMs = endMs) }

        // 再生したまま端を動かすと、映像が流れていって切れ目を確認できない。
        // 触った時点で止めて、指の位置のコマを出す。
        val previewMs = previewAtMs.coerceIn(startMs, endMs)
        playback.seekAndPause(previewMs)
    }

    /**
     * いまのトリム選択の左端から指定の長さだけを選び直す（操作バーの 2s / 4s プリセット）。
     * 常に先頭からだと押すたびにシークし直しになって面倒なため、
     * 選択済みの開始位置をそのまま起点にする。
     */
    fun applyTrimPreset(lengthMs: Long) {
        val clip = selectedClip ?: return
        if (clip.durationMs <= 0L) return
        val startMs = clip.startMs.coerceIn(0L, clip.durationMs)
        updateTrim(startMs = startMs, endMs = (startMs + lengthMs).coerceAtMost(clip.durationMs))
    }

    /**
     * トリミング区間を長さそのままで前後に移動する。
     *
     * 端をつまんで伸縮するのとは別に、範囲の内側を長押し→スライドしたときに使う。
     * ひとことの区切り（先頭は除く）も同じ分だけ一緒にずらし、区間との相対位置を保つ。
     *
     * @param targetStartMs 動かした先の開始位置（クランプ前）。ドラッグ開始時の値からの
     *   絶対位置で渡してもらう（毎フレーム相対差分を積み上げると誤差が溜まるため）。
     */
    fun moveTrim(targetStartMs: Long, previewAtMs: Long) {
        val clip = selectedClip ?: return
        val span = clip.trimmedDurationMs
        if (span <= 0L) return

        val maxStart = (clip.durationMs - span).coerceAtLeast(0L)
        // トリム範囲と区切りは同じ量だけ動かす（相対位置を保つのがこの操作の目的）。
        // 区切りが動画の範囲からはみ出すぶんは、区切りを丸めるのではなく移動そのものを
        // 手前で止める（理由は[clampTimelineShift]）。
        val delta = clampTimelineShift(
            texts = clip.texts,
            requested = targetStartMs.coerceIn(0L, maxStart) - clip.startMs,
            durationMs = clip.durationMs
        )
        if (delta == 0L) return
        val newStart = clip.startMs + delta
        val newEnd = newStart + span

        recordHistory(EditTag.TrimMove(playback.selectedIndexValue))
        updateSelected { current ->
            current.copy(
                startMs = newStart,
                endMs = newEnd,
                // 先頭の区間は常に絶対位置0（動画そのものの頭）なので動かさない。
                // それ以外はすべて同じdeltaで動く。はみ出さない量まで詰めてあるので、
                // ここで個別に丸める必要はない。
                texts = current.texts.map { segment ->
                    if (segment.startMs == 0L) segment
                    else segment.copy(startMs = segment.startMs + delta)
                }
            )
        }

        val previewMs = previewAtMs.coerceIn(newStart, newEnd)
        playback.seekAndPause(previewMs)
    }

    /**
     * ひとことの区切りをひとつ、時間軸上で動かす。
     *
     * 前後の区切り（無ければクリップの端）を越えないようクランプする。
     * [index] は [VlogClip.texts] の添字（0＝先頭は区切りではないので対象外）。
     */
    fun moveSplit(index: Int, newAtMs: Long) {
        val clip = selectedClip ?: return
        if (index !in clip.texts.indices || index == 0) return

        // 下限・上限とも、動画全体ではなくいまのトリム範囲で止める。波形上のドラッグは
        // 動画全体（0〜尺）を範囲にしているので、ここで止めないとトリムで切り落とした
        // 部分まで区切りを動かせてしまい、そこへシークしたプレビューに書き出されない
        // コマが出る。基準は区切りを入れるとき（splitTextAtPlayhead）の「端に寄りすぎ」と同じ。
        // 手前側は、1つ前の区切りとトリム開始のうち後ろにある方から間隔を取る
        // （1つ前が先頭区間＝絶対位置0だと、トリムで頭を落としていても0が基準になってしまう）。
        val lowerBound = maxOf(clip.texts[index - 1].startMs, clip.startMs) + MIN_TEXT_SEGMENT_MS
        val upperBound =
            (clip.texts.getOrNull(index + 1)?.startMs ?: clip.endMs) - MIN_TEXT_SEGMENT_MS
        if (lowerBound > upperBound) return

        val clamped = newAtMs.coerceIn(lowerBound, upperBound)
        if (clamped == clip.texts[index].startMs) return

        recordHistory(EditTag.SplitMove(playback.selectedIndexValue, index))
        updateSegment(index) { it.copy(startMs = clamped) }

        playback.seekAndPause(clamped)
    }

    /**
     * ひとことの書き換え。書き換わるのは再生ヘッドが指している区間だけ。
     *
     * 入力欄の表示も同じ「再生ヘッドの位置の区間」を出しているので、
     * 見えている文字と書き換わる文字は必ず一致する。
     */
    fun updateText(text: String) {
        val clip = selectedClip ?: return
        val target = clip.textIndexAt(playback.positionMsValue)
        recordHistory(EditTag.Text(playback.selectedIndexValue, target))
        updateSegment(target) { it.copy(text = text) }
    }

    /**
     * 再生ヘッドの位置でひとことを2つに割る。動画は切らない。
     *
     * 後半は空文字にする（動画追加時の初期区間と同じ扱い）。前半の文字をそのまま
     * 複製すると、分割できたのかどうかが入力欄からは分からないため。空にしておけば
     * 入力欄には「ひとこと」がplaceholderとしてグレー表示され、未入力なのが一目でわかる。
     * 「N／M区間目」のバッジと波形の区切り線でも分割自体は分かるので、
     * プレビュー映像に何も焼き込まない代わりにそちらで確認できる。
     */
    fun splitTextAtPlayhead() {
        val clip = selectedClip ?: return
        val at = playback.positionMsValue

        val tooCloseToEdge =
            at - clip.startMs < MIN_TEXT_SEGMENT_MS || clip.endMs - at < MIN_TEXT_SEGMENT_MS
        if (tooCloseToEdge) {
            sendMessage("区切る位置が端に寄りすぎています")
            return
        }
        if (clip.texts.any { abs(it.startMs - at) < MIN_TEXT_SEGMENT_MS }) {
            sendMessage("すぐ近くに区切りがあります")
            return
        }

        recordHistory()
        val inserted = (clip.texts + TextSegment(at)).sortedBy { it.startMs }
        updateSelected { it.copy(texts = inserted) }

        // 分割した後半の頭を出しておく。編集対象がそのまま新しい区間になるので、
        // 続けて入力欄へ打ち込める。
        playback.seekAndPause(at)
    }

    /**
     * 区切りをひとつ解除する。手前の区間の文字が、後ろの区間ぶんまで伸びる。
     *
     * 位置が同じ区切りが万一2つあっても、消すのは1つだけにする
     * （まとめて消すと、押した覚えのない区切りまで一緒に消えてしまう）。
     */
    fun removeSplit(atMs: Long) {
        val clip = selectedClip ?: return
        val target = clip.texts.indexOfFirst { it.startMs == atMs && it.startMs != 0L }
        if (target < 0) return

        recordHistory()
        updateSelected { current ->
            current.copy(
                texts = current.texts.filterIndexed { index, _ -> index != target }
            )
        }
    }

    /**
     * 指定したクリップのミュートを切り替える。タイムラインのクリップタイルの
     * 長押しで呼ぶ想定のため、選択中インデックスではなくidで対象を探す
     * （長押しされたクリップが選択中とは限らないため）。
     */
    fun toggleClipMute(clipId: Long) {
        val index = _clips.value.indexOfFirst { it.id == clipId }
        if (index < 0) return

        recordHistory()
        updateClips { this[index] = this[index].copy(isMuted = !this[index].isMuted) }
        playback.applyVolume()
    }

    /**
     * 選択中のクリップを前後に動かす（書き出し順もこの並びになる）。
     * ExoPlayerのプレイリストも同時に動かして、プレビューと順番をずらさない。
     */
    fun moveSelected(offset: Int) {
        val from = playback.selectedIndexValue
        val to = from + offset
        if (from !in _clips.value.indices || to !in _clips.value.indices) return

        recordHistory()
        updateClips { add(to, removeAt(from)) }
        playback.moveItem(from, to)
    }

    fun removeSelected() {
        val index = playback.selectedIndexValue
        if (index !in _clips.value.indices) return
        val removedUri = _clips.value[index].uri

        recordHistory()
        updateClips { removeAt(index) }
        playback.removeItem(index)
        onUrisReleased(listOf(removedUri))

        val newIndex = index.coerceAtMost(_clips.value.lastIndex.coerceAtLeast(0))
        playback.setSelectedIndex(newIndex)
        val nextPositionMs = selectedClip?.startMs ?: 0L

        // removeMediaItemによる自動遷移はreason=REMOVEで、AUTO専用の頭出し
        // （onMediaItemTransition内）が効かない。ここで明示的に合わせないと、
        // 表示中のひとこと・時刻は新しいクリップのものなのに、映像だけ0秒目のままずれる。
        if (_clips.value.isNotEmpty()) {
            playback.seekAndPause(nextPositionMs)
        } else {
            playback.setPositionMs(nextPositionMs)
        }
    }

    /** タイムラインを空にする。押し間違えても「もとに戻す」で復帰できる */
    fun removeAll() {
        if (_clips.value.isEmpty()) return
        val removedUris = _clips.value.map { it.uri }.distinct()

        recordHistory()
        _clips.value = emptyList()
        playback.clearItems()
        onUrisReleased(removedUris)
    }

    // --- もとに戻す / やり直す -----------------------------------------------------------

    fun undo() {
        history.undo(currentSnapshot())?.let(::applySnapshot)
    }

    fun redo() {
        history.redo(currentSnapshot())?.let(::applySnapshot)
    }

    /**
     * 変更を加える「直前」に呼ぶ。[tag]の意味は [EditHistory.record] を参照。
     */
    private fun recordHistory(tag: EditTag? = null) = history.record(currentSnapshot(), tag)

    private fun currentSnapshot() = Snapshot(_clips.value, playback.selectedIndexValue)

    /**
     * 履歴の状態を画面へ戻す。
     *
     * 並び順や本数が変わっていないときはプレイリストを作り直さない。
     * setMediaItems はバッファを捨ててしまうので、ひとことやトリミングを
     * 戻しただけで再生が止まって見えるのを避けている。
     *
     * 音量は最後に必ず合わせ直す。プレイリストを作り直さず選択クリップも変わらない
     * とき（選択中クリップのミュートをundoした場合など）は、音量を合わせ直す経路を
     * どこも通らず、表示はミュート解除に戻ったのに再生すると無音のまま、になる。
     */
    private fun applySnapshot(snapshot: Snapshot) {
        val playlistChanged =
            snapshot.clips.map { it.uri } != _clips.value.map { it.uri }

        _clips.value = snapshot.clips

        if (playlistChanged) {
            _replacementCount.value++
            playback.rebuildPlaylist(snapshot.clips)
        } else {
            playback.pause()
        }

        val index = snapshot.selectedIndex
            .coerceIn(0, snapshot.clips.lastIndex.coerceAtLeast(0))
        playback.setSelectedIndex(index)
        snapshot.clips.getOrNull(index)?.let { playback.seekWithoutPause(it.startMs) }
        playback.applyVolume()
    }

    // --- 一覧の書き換え -----------------------------------------------------------------

    /** 選択中クリップの区間を1つだけ書き換える。範囲外の[index]なら何もしない */
    private fun updateSegment(index: Int, transform: (TextSegment) -> TextSegment) {
        updateSelected { clip ->
            if (index !in clip.texts.indices) return@updateSelected clip
            clip.copy(texts = clip.texts.toMutableList().apply { this[index] = transform(this[index]) })
        }
    }

    private fun updateSelected(transform: (VlogClip) -> VlogClip) {
        val index = playback.selectedIndexValue
        if (index !in _clips.value.indices) return
        updateClips { this[index] = transform(this[index]) }
    }

    /** 一覧を書き換える。可変リストのコピーに対して変更し、新しいリストとして反映する */
    private inline fun updateClips(edit: MutableList<VlogClip>.() -> Unit) {
        _clips.value = _clips.value.toMutableList().apply(edit)
    }
}
