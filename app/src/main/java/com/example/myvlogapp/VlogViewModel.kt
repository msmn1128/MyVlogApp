package com.example.myvlogapp // ← ご自身のパッケージ名に合わせて変更してください

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 履歴に積む上限。1件あたりクリップ一覧の参照コピーなので軽い */
private const val HISTORY_LIMIT = 50

/**
 * 同種の連続編集をひとつの履歴にまとめる時間。
 * スライダーを1回ドラッグしただけで数十件積まれると、「もとに戻す」を
 * 何度押しても元に戻らなくなるため。
 */
private const val HISTORY_COALESCE_MS = 900L

/**
 * 画面状態と書き出し処理の保持先。
 *
 * ViewModelに置く理由：Composable内の remember だけだと画面回転や
 * ダークモード切替で読み込んだクリップが消え、書き出しも中断されてしまう。
 * ExoPlayerもここで保持して再生位置を維持する。
 */
@OptIn(UnstableApi::class)
class VlogViewModel(application: Application) : AndroidViewModel(application) {

    private val _clips = MutableStateFlow<List<VlogClip>>(emptyList())
    val clips: StateFlow<List<VlogClip>> = _clips.asStateFlow()

    private val _selectedIndex = MutableStateFlow(0)
    val selectedIndex: StateFlow<Int> = _selectedIndex.asStateFlow()

    // 書き出しの実体は VlogExportService（Activity/ViewModelより長生きする）が持つ。
    // ここは ExportStatus を覗くだけ。
    val exportState: StateFlow<ExportState> = ExportStatus.state

    private val _events = Channel<VlogEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    /**
     * クリップの終わりまで来たら次へ進むか、そこで止まるか。
     * オフだと1本ずつ見直しながら編集できる。設定は次回起動時にも引き継ぐ。
     */
    private val _autoAdvance = MutableStateFlow(true)
    val autoAdvance: StateFlow<Boolean> = _autoAdvance.asStateFlow()

    /** 選択中クリップの再生位置。波形の再生ヘッド表示に使う */
    private val _playbackPositionMs = MutableStateFlow(0L)
    val playbackPositionMs: StateFlow<Long> = _playbackPositionMs.asStateFlow()

    /**
     * 波形のキャッシュ。キーはURI文字列。
     *
     * clip.id ではなくURIで持つのは、同じ動画を2回追加したときに
     * デコードをやり直さずに済ませるため。値がnullは「取得できなかった」。
     */
    private val _waveforms = MutableStateFlow<Map<String, Waveform?>>(emptyMap())
    val waveforms: StateFlow<Map<String, Waveform?>> = _waveforms.asStateFlow()

    private val waveformJobs = mutableMapOf<String, Job>()

    // --- 履歴（もとに戻す / やり直す） ---------------------------------------------------
    private data class Snapshot(val clips: List<VlogClip>, val selectedIndex: Int)

    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()
    private var lastEditTag: String? = null
    private var lastEditAt = 0L

    /** タイムラインの動画は自動再生しない */
    val player: ExoPlayer = ExoPlayer.Builder(application).build().apply {
        playWhenReady = false
        repeatMode = Player.REPEAT_MODE_OFF
    }

    val selectedClip: VlogClip? get() = _clips.value.getOrNull(_selectedIndex.value)

    // --- ExoPlayer操作の共通化 ------------------------------------------------------------
    //
    // 「シークして表示上の再生位置も合わせる」処理が各操作に散らばっていたのをまとめたもの。
    // 一時停止を伴うか（ユーザー操作で位置を動かすとき）伴わないか（自動再生を続けたまま
    // 頭出しするとき）で2種類に分けてある。

    /** シークして表示位置も合わせる。再生中でも止めない（自動遷移など再生を継続したい場面用） */
    private fun seekWithoutPause(index: Int, positionMs: Long) {
        player.seekTo(index, positionMs)
        _playbackPositionMs.value = positionMs
    }

    /** 再生を止めてからシークする。ユーザーがトリミング等で位置を直接動かす操作用 */
    private fun seekAndSync(index: Int, positionMs: Long) {
        player.playWhenReady = false
        seekWithoutPause(index, positionMs)
    }

    /**
     * ExoPlayerのプレイリストを丸ごと差し替える。
     * setMediaItemsはバッファを含め状態を作り直すため、続けてprepareし、
     * 意図せず再生が始まらないようplayWhenReadyも明示的に止めておく。
     */
    private fun rebuildPlaylist(clips: List<VlogClip>) {
        player.setMediaItems(clips.map { MediaItem.fromUri(it.uri) })
        player.prepare()
        player.playWhenReady = false
    }

    /** 先頭へ戻して止める。最後まで再生し終えたときの共通処理 */
    private fun returnToStart() {
        val first = _clips.value.firstOrNull() ?: return
        _selectedIndex.value = 0
        seekAndSync(0, first.startMs)
    }

    init {
        // 復元してから保存を始める。順番が逆だと、復元前の空リストを
        // 保存してしまい前回の内容が消える。
        viewModelScope.launch {
            restoreClips()

            // 編集内容を自動保存する。collectLatestとdelayの組み合わせで、
            // ひとことを1文字打つたびに書き込むのを避けている。
            _clips.collectLatest { clips ->
                delay(500)
                ClipStore.save(getApplication(), clips)
            }
        }

        // VlogExportService からの完了・失敗通知をUIのイベントとして中継する
        viewModelScope.launch {
            ExportStatus.events.collect { _events.send(it) }
        }

        // 前回、書き出し中に強制終了していた場合の後始末。
        // 今まさに書き出し中（サービスが同一プロセスで生存中）なら触らない。
        if (!ExportStatus.isRunning) {
            viewModelScope.launch(Dispatchers.IO) {
                VlogExporter.cleanupOrphanedPendingFiles(getApplication())
            }
        }

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val index = player.currentMediaItemIndex
                val clip = _clips.value.getOrNull(index) ?: return
                _selectedIndex.value = index
                // 自動遷移すると次のクリップは0秒から始まってしまうため、
                // トリミング開始位置へ合わせ直す
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && clip.startMs > 0) {
                    player.seekTo(index, clip.startMs)
                }
            }

            /**
             * 最後まで再生し終えたら先頭へ戻す。
             *
             * enforceTrimBounds では拾えないケースがある。トリミング終端が動画の
             * 実際の末尾と一致していると、80msごとの監視が終端に気付くより先に
             * ExoPlayer側が STATE_ENDED まで進み、isPlaying が false になって
             * 監視が素通りしてしまうため。
             */
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState != Player.STATE_ENDED) return
                player.playWhenReady = false
                if (!_autoAdvance.value) return
                returnToStart()
            }
        })
    }

    /** 前回の続きを読み込む。いま実際に読めるものだけが対象 */
    private suspend fun restoreClips() {
        applyAutoAdvance(ClipStore.restoreAutoAdvance(getApplication()))

        val restored = ClipStore.restore(getApplication())

        if (restored.clips.isNotEmpty()) {
            _clips.value = restored.clips
            rebuildPlaylist(restored.clips)
            select(0)
        }

        // 復元直後を「起点」にする。ここで履歴を消しておかないと、
        // アプリを開いた直後に「もとに戻す」を押せてしまい、空の状態へ戻ってしまう。
        clearHistory()

        if (restored.dropped > 0) {
            sendMessage(
                "${restored.dropped} 件の動画は復元できませんでした" +
                        "（移動・削除されたか、アクセス権限が取り消されています）"
            )
        }
    }

    /** 選択された動画をタイムラインの末尾に追加する */
    fun addClips(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val context = getApplication<Application>()
            val added = withContext(Dispatchers.IO) {
                uris.mapIndexed { offset, uri ->
                    val meta = getVideoMetadata(context, uri)
                    VlogClip(
                        id = System.nanoTime() + offset,
                        uri = uri,
                        timeText = meta.timeText,
                        dateText = meta.dateText,
                        durationMs = meta.durationMs,
                        width = meta.width,
                        height = meta.height,
                        startMs = 0L,
                        endMs = meta.durationMs
                    )
                }
            }

            val wasEmpty = _clips.value.isEmpty()
            recordHistory()
            _clips.value = _clips.value + added

            // 既存の再生位置を保ったまま新しいクリップだけ追加する
            player.addMediaItems(added.map { MediaItem.fromUri(it.uri) })
            player.prepare()
            player.playWhenReady = false

            if (wasEmpty) select(0)

            val skipped = added.count { !it.isValid }
            if (skipped > 0) {
                _events.send(VlogEvent.Message("$skipped 件の動画は長さを取得できませんでした"))
            }
        }
    }

    fun select(index: Int) {
        if (index !in _clips.value.indices) return
        _selectedIndex.value = index
        seekAndSync(index, _clips.value[index].startMs)
    }

    /**
     * トリミング範囲の変更。
     *
     * @param previewAtMs プレビューに出す位置。波形の右端を掴んでいるのに左端の映像が
     *   出ると「どこで切れるのか」が確認できないため、掴んでいる側を渡してもらう。
     */
    fun updateTrim(startMs: Long, endMs: Long, previewAtMs: Long = startMs) {
        recordHistory("trim:${_selectedIndex.value}")
        updateSelected { it.copy(startMs = startMs, endMs = endMs) }

        // 再生したまま端を動かすと、映像が流れていって切れ目を確認できない。
        // 触った時点で止めて、指の位置のコマを出す。
        val previewMs = previewAtMs.coerceIn(startMs, endMs)
        seekAndSync(_selectedIndex.value, previewMs)
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
        val newStart = targetStartMs.coerceIn(0L, maxStart)
        if (newStart == clip.startMs) return
        val delta = newStart - clip.startMs
        val newEnd = newStart + span

        recordHistory("trimmove:${_selectedIndex.value}")
        updateSelected { current ->
            current.copy(
                startMs = newStart,
                endMs = newEnd,
                // 先頭の区間は常に絶対位置0（動画そのものの頭）なので動かさない。
                // 区切りは下限を1msにクランプし、0へ丸めて先頭区間と衝突しないようにする
                // （区切りだけが0になると「先頭は必ず0」の前提が崩れ、以後の判定が壊れる）
                texts = current.texts.map { segment ->
                    if (segment.startMs == 0L) segment
                    else segment.copy(
                        startMs = (segment.startMs + delta).coerceIn(1L, current.durationMs)
                    )
                }
            )
        }

        val previewMs = previewAtMs.coerceIn(newStart, newEnd)
        seekAndSync(_selectedIndex.value, previewMs)
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

        val lowerBound = clip.texts[index - 1].startMs + MIN_TEXT_SEGMENT_MS
        val upperBound =
            (clip.texts.getOrNull(index + 1)?.startMs ?: clip.durationMs) - MIN_TEXT_SEGMENT_MS
        if (lowerBound > upperBound) return

        val clamped = newAtMs.coerceIn(lowerBound, upperBound)
        if (clamped == clip.texts[index].startMs) return

        recordHistory("splitmove:${_selectedIndex.value}:$index")
        updateSelected { current ->
            current.copy(
                texts = current.texts.mapIndexed { i, segment ->
                    if (i == index) segment.copy(startMs = clamped) else segment
                }
            )
        }

        seekAndSync(_selectedIndex.value, clamped)
    }

    /** 波形をタップしたときの頭出し。トリミング範囲の外へは飛ばさない */
    fun seekWithinTrim(positionMs: Long) {
        val clip = selectedClip ?: return
        val clampedMs = positionMs.coerceIn(clip.startMs, clip.endMs)
        seekWithoutPause(_selectedIndex.value, clampedMs)
    }

    /**
     * ひとことの書き換え。書き換わるのは再生ヘッドが指している区間だけ。
     *
     * 入力欄の表示も同じ「再生ヘッドの位置の区間」を出しているので、
     * 見えている文字と書き換わる文字は必ず一致する。
     */
    fun updateText(text: String) {
        val clip = selectedClip ?: return
        val target = clip.textIndexAt(_playbackPositionMs.value)
        recordHistory("text:${_selectedIndex.value}:$target")
        updateSelected { current ->
            current.copy(
                texts = current.texts.mapIndexed { index, segment ->
                    if (index == target) segment.copy(text = text) else segment
                }
            )
        }
    }

    /**
     * 再生ヘッドの位置でひとことを2つに割る。動画は切らない。
     *
     * 後半には初期値の「ひとこと」を入れる。前半の文字をそのまま複製すると、
     * 分割できたのかどうかがプレビューからは分からないため。
     */
    fun splitTextAtPlayhead() {
        val clip = selectedClip ?: return
        val at = _playbackPositionMs.value

        val tooCloseToEdge =
            at - clip.startMs < MIN_TEXT_SEGMENT_MS || clip.endMs - at < MIN_TEXT_SEGMENT_MS
        if (tooCloseToEdge) {
            sendMessage("区切る位置が端に寄りすぎています")
            return
        }
        if (clip.texts.any { kotlin.math.abs(it.startMs - at) < MIN_TEXT_SEGMENT_MS }) {
            sendMessage("すぐ近くに区切りがあります")
            return
        }

        recordHistory()
        val inserted = (clip.texts + TextSegment(at, DEFAULT_HITOKOTO)).sortedBy { it.startMs }
        updateSelected { it.copy(texts = inserted) }

        // 分割した後半の頭を出しておく。編集対象がそのまま新しい区間になるので、
        // 続けて入力欄へ打ち込める。
        seekAndSync(_selectedIndex.value, at)
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
     * 選択中のクリップを前後に動かす（書き出し順もこの並びになる）。
     * ExoPlayerのプレイリストも同時に動かして、プレビューと順番をずらさない。
     */
    fun moveSelected(offset: Int) {
        val from = _selectedIndex.value
        val to = from + offset
        if (from !in _clips.value.indices || to !in _clips.value.indices) return

        recordHistory()
        _clips.value = _clips.value.toMutableList().apply { add(to, removeAt(from)) }
        player.moveMediaItem(from, to)
        _selectedIndex.value = to
    }

    fun removeSelected() {
        val index = _selectedIndex.value
        if (index !in _clips.value.indices) return

        recordHistory()
        _clips.value = _clips.value.toMutableList().apply { removeAt(index) }
        player.removeMediaItem(index)

        val newIndex = index.coerceAtMost(_clips.value.lastIndex.coerceAtLeast(0))
        _selectedIndex.value = newIndex
        val nextPositionMs = selectedClip?.startMs ?: 0L

        // removeMediaItemによる自動遷移はreason=REMOVEで、AUTO専用の頭出し
        // （onMediaItemTransition内）が効かない。ここで明示的に合わせないと、
        // 表示中のひとこと・時刻は新しいクリップのものなのに、映像だけ0秒目のままずれる。
        if (_clips.value.isNotEmpty()) {
            seekAndSync(newIndex, nextPositionMs)
        } else {
            _playbackPositionMs.value = nextPositionMs
        }
    }

    /** タイムラインを空にする。押し間違えても「もとに戻す」で復帰できる */
    fun removeAll() {
        if (_clips.value.isEmpty()) return

        recordHistory()
        _clips.value = emptyList()
        player.clearMediaItems()
        _selectedIndex.value = 0
        _playbackPositionMs.value = 0L
    }

    // --- 一時保存 -----------------------------------------------------------------------

    private val _projects = MutableStateFlow<List<SavedProject>>(emptyList())
    val projects: StateFlow<List<SavedProject>> = _projects.asStateFlow()

    /** 保存一覧を開くたびに呼ぶ。ここでしか変わらないので常時監視はしない */
    fun refreshProjects() {
        viewModelScope.launch {
            _projects.value = ClipStore.listProjects(getApplication())
        }
    }

    /** いまの編集内容に名前を付けて残す。動画はコピーしないので一瞬で終わる */
    fun saveProject(name: String) {
        val clipsToSave = _clips.value
        if (clipsToSave.isEmpty()) {
            sendMessage("保存できる編集内容がありません")
            return
        }

        viewModelScope.launch {
            val label = name.trim().ifBlank { formatSavedAt(System.currentTimeMillis()) }
            val saved = ClipStore.saveProject(getApplication(), label, clipsToSave)
            _projects.value = ClipStore.listProjects(getApplication())
            sendMessage(
                if (saved) "「$label」を保存しました"
                else "保存は${ClipStore.MAX_PROJECTS}件までです。不要なものを削除してください"
            )
        }
    }

    /**
     * 保存した編集内容へ差し替える。
     *
     * 履歴に積んでから入れ替えるので、読み出す前の状態には「もとに戻す」で帰れる。
     */
    fun loadProject(id: Long) {
        viewModelScope.launch {
            val restored = ClipStore.loadProject(getApplication(), id)
            if (restored == null) {
                sendMessage("この保存は読み出せませんでした")
                return@launch
            }

            recordHistory()
            _clips.value = restored.clips
            rebuildPlaylist(restored.clips)
            // 空の保存を読み出したときだけここで0に戻す。中身があるときは
            // select(0) が同じ代入をやり直すことになるので、そちらだけに任せる。
            if (restored.clips.isNotEmpty()) select(0)
            else {
                _selectedIndex.value = 0
                _playbackPositionMs.value = 0L
            }

            sendMessage(
                if (restored.dropped > 0) {
                    "読み出しました（${restored.dropped} 件の動画は見つかりませんでした）"
                } else {
                    "読み出しました（もとに戻すで読み出す前へ帰れます）"
                }
            )
        }
    }

    fun deleteProject(id: Long) {
        viewModelScope.launch {
            ClipStore.deleteProject(getApplication(), id)
            _projects.value = ClipStore.listProjects(getApplication())
        }
    }

    // --- もとに戻す / やり直す -----------------------------------------------------------

    fun undo() = undoRedo(from = undoStack, to = redoStack)

    fun redo() = undoRedo(from = redoStack, to = undoStack)

    /** undo/redoは互いに鏡像の処理なので1つにまとめてある。行き先のスタックだけが違う */
    private fun undoRedo(from: ArrayDeque<Snapshot>, to: ArrayDeque<Snapshot>) {
        val target = from.removeLastOrNull() ?: return
        to.addLast(currentSnapshot())
        resetCoalescing()
        applySnapshot(target)
        refreshHistoryFlags()
    }

    /**
     * 変更を加える「直前」に呼ぶ。
     *
     * @param tag 同じタグの編集が [HISTORY_COALESCE_MS] 以内に続いた場合はまとめる。
     *   スライダーのドラッグや文字入力のように連続で飛んでくる編集に付ける。
     *   nullを渡すと必ず1件として積まれる（追加・削除・並べ替えなど一発で完結する操作）。
     */
    private fun recordHistory(tag: String? = null) {
        val now = SystemClock.elapsedRealtime()
        if (tag != null && tag == lastEditTag && now - lastEditAt < HISTORY_COALESCE_MS) {
            lastEditAt = now
            return
        }

        undoStack.addLast(currentSnapshot())
        if (undoStack.size > HISTORY_LIMIT) undoStack.removeFirst()
        redoStack.clear()

        lastEditTag = tag
        lastEditAt = now
        refreshHistoryFlags()
    }

    private fun currentSnapshot() = Snapshot(_clips.value, _selectedIndex.value)

    /**
     * 履歴の状態を画面へ戻す。
     *
     * 並び順や本数が変わっていないときはプレイリストを作り直さない。
     * setMediaItems はバッファを捨ててしまうので、ひとことやトリミングを
     * 戻しただけで再生が止まって見えるのを避けている。
     */
    private fun applySnapshot(snapshot: Snapshot) {
        val playlistChanged =
            snapshot.clips.map { it.uri } != _clips.value.map { it.uri }

        _clips.value = snapshot.clips

        if (playlistChanged) rebuildPlaylist(snapshot.clips) else player.playWhenReady = false

        val index = snapshot.selectedIndex
            .coerceIn(0, snapshot.clips.lastIndex.coerceAtLeast(0))
        _selectedIndex.value = index
        snapshot.clips.getOrNull(index)?.let { seekWithoutPause(index, it.startMs) }
    }

    private fun clearHistory() {
        undoStack.clear()
        redoStack.clear()
        resetCoalescing()
        refreshHistoryFlags()
    }

    private fun resetCoalescing() {
        lastEditTag = null
        lastEditAt = 0L
    }

    private fun refreshHistoryFlags() {
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }

    // --- 波形 ---------------------------------------------------------------------------

    /**
     * 波形をバックグラウンドで用意する。取得済み・取得中のURIは何もしない。
     * 画面側から選択中クリップぶんだけ呼べばよい（全件を先読みするとデコードが渋滞する）。
     */
    fun requestWaveform(clip: VlogClip) {
        val key = clip.uri.toString()
        if (_waveforms.value.containsKey(key)) return
        if (waveformJobs[key]?.isActive == true) return

        waveformJobs[key] = viewModelScope.launch {
            val waveform = extractWaveform(getApplication(), clip.uri, clip.durationMs)
            _waveforms.value = _waveforms.value + (key to waveform)
            waveformJobs.remove(key)
        }
    }

    // --- 再生 ---------------------------------------------------------------------------

    /**
     * 画面から一定間隔（80ms）で呼ばれる。再生位置の更新とトリミング終端の監視をまとめて行う。
     */
    fun refreshPlaybackProgress() {
        if (player.currentMediaItemIndex == _selectedIndex.value) {
            _playbackPositionMs.value = player.currentPosition
        }
        enforceTrimBounds()
    }

    /**
     * トリミング終端に達したら次のクリップの開始位置へ進める。
     * 最後まで再生し終えたら停止して先頭に戻す（書き出し結果と同じ流れをプレビューできる）。
     *
     * selectedIndexではなく再生中のインデックスを見るのは、
     * ExoPlayerが自動遷移した直後の一瞬だけ両者がずれるため。
     */
    private fun enforceTrimBounds() {
        if (!player.isPlaying) return
        val index = player.currentMediaItemIndex
        val clip = _clips.value.getOrNull(index) ?: return
        if (player.currentPosition < clip.endMs) return

        val next = index + 1
        when {
            // 連続再生オフ：いまのクリップの終わりで止め、最後のコマを出したままにする
            !_autoAdvance.value -> {
                _selectedIndex.value = index
                seekAndSync(index, clip.endMs)
            }

            next in _clips.value.indices -> {
                _selectedIndex.value = next
                seekWithoutPause(next, _clips.value[next].startMs)
            }

            // 最後まで再生し終えたら先頭へ戻す（書き出し結果と同じ流れを繰り返し確認できる）
            else -> returnToStart()
        }
    }

    fun setAutoAdvance(enabled: Boolean) {
        applyAutoAdvance(enabled)
        ClipStore.saveAutoAdvance(getApplication(), enabled)
    }

    /**
     * pauseAtEndOfMediaItems も合わせて切り替える。
     * 80msごとの監視だけだと、トリミング終端が動画の実際の末尾と一致している場合に
     * ExoPlayerの自動遷移が先に走ってしまい、オフにしても次が流れることがある。
     */
    private fun applyAutoAdvance(enabled: Boolean) {
        _autoAdvance.value = enabled
        player.pauseAtEndOfMediaItems = !enabled
    }

    // --- シーク（波形をなぞって再生位置を動かす） -----------------------------------------

    /** なぞる前の再生状態。離したときに再生中だったら続きから流し直す */
    private var resumeAfterScrub = false

    fun beginScrub() {
        resumeAfterScrub = player.isPlaying
        // なぞっている間に映像が進むと指の位置とコマがずれるので、いったん止める
        player.playWhenReady = false
    }

    fun endScrub() {
        if (resumeAfterScrub) player.playWhenReady = true
        resumeAfterScrub = false
    }

    fun pause() {
        player.playWhenReady = false
    }

    /**
     * 書き出しは VlogExportService（フォアグラウンドサービス）に委ねる。
     * viewModelScopeで直接実行しないのは、バックグラウンドに回すとOSに
     * プロセスごと回収されうるため。サービス化してActivity/ViewModelより
     * 長生きさせる。
     */
    fun export() {
        if (ExportStatus.isRunning) return

        val target = _clips.value
        when {
            target.isEmpty() -> {
                sendMessage("動画を追加してください")
                return
            }
            target.any { !it.isValid } -> {
                sendMessage("トリミング範囲が不正なクリップがあります")
                return
            }
        }

        player.playWhenReady = false
        VlogExportService.start(getApplication(), target)
    }

    fun cancelExport() {
        VlogExportService.cancel(getApplication())
    }

    private fun updateSelected(transform: (VlogClip) -> VlogClip) {
        val index = _selectedIndex.value
        if (index !in _clips.value.indices) return
        _clips.value = _clips.value.toMutableList().also { it[index] = transform(it[index]) }
    }

    private fun sendMessage(text: String) {
        viewModelScope.launch { _events.send(VlogEvent.Message(text)) }
    }

    override fun onCleared() {
        // 書き出し自体は VlogExportService で継続させる（ここではキャンセルしない）。
        // ユーザーが画面を閉じてもバックグラウンドで書き出しを終わらせるための挙動。
        player.release()
        super.onCleared()
    }
}
