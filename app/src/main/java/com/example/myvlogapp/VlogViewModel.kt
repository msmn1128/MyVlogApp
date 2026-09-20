package com.example.myvlogapp

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.abs
import com.example.myvlogapp.data.ClipStore
import com.example.myvlogapp.data.SavedProject
import com.example.myvlogapp.data.getVideoMetadata
import com.example.myvlogapp.edit.EditHistory
import com.example.myvlogapp.edit.EditTag
import com.example.myvlogapp.export.ExportState
import com.example.myvlogapp.export.ExportStatus
import com.example.myvlogapp.export.VlogEvent
import com.example.myvlogapp.export.VlogExportService
import com.example.myvlogapp.export.VlogExporter
import com.example.myvlogapp.playback.PlaybackController
import com.example.myvlogapp.waveform.SelectedWaveform
import com.example.myvlogapp.waveform.Waveform
import com.example.myvlogapp.waveform.extractWaveform

/**
 * 編集内容の自動保存デバウンス。
 * ひとことを1文字打つたびに書き込むと重いため、入力が止まってからこのぶん待つ。
 */
private const val AUTOSAVE_DEBOUNCE_MS = 500L

/**
 * 動画を追加するとき、メタデータ（長さ・撮影時刻など）の取得を同時に待たせておく本数の上限。
 *
 * 実際の読み取りは、取り違えを防ぐため[getVideoMetadata]の中で1本ずつ直列に行われる
 * （MediaMetadataRetrieverを同時に使うと、別の動画の撮影日時が返ることがあるため）。
 * ここで絞るのは、選んだ本数ぶんのスレッドがロック待ちで塞がってしまわないようにするため。
 */
private const val METADATA_PARALLELISM = 4

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

    val selectedIndex: StateFlow<Int> get() = playback.selectedIndex

    // 書き出しの実体は VlogExportService（Activity/ViewModelより長生きする）が持つ。
    // ここは ExportStatus を覗くだけ。
    val exportState: StateFlow<ExportState> = ExportStatus.state

    private val _events = Channel<VlogEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val canUndo: StateFlow<Boolean> get() = history.canUndo
    val canRedo: StateFlow<Boolean> get() = history.canRedo

    /**
     * 動画を追加中（メタデータを読んでいる間）か。画面に進捗を出し、追加ボタンの連打を止めるのに使う。
     * 追加は並行して走りうるので、実行中の件数で数える（更新はメインスレッドだけ）。
     */
    private var addingCount = 0
    private val _isAdding = MutableStateFlow(false)
    val isAdding: StateFlow<Boolean> = _isAdding.asStateFlow()

    /**
     * クリップの終わりまで来たら次へ進むか、そこで止まるか。
     * オフだと1本ずつ見直しながら編集できる。設定は次回起動時にも引き継ぐ（[setAutoAdvance]）。
     */
    val autoAdvance: StateFlow<Boolean> get() = playback.autoAdvance

    /**
     * タイムライン全体のミュート。プレビューの音量と書き出しの両方に効く。
     * クリップ個別のミュート（[VlogClip.isMuted]）とは独立していて、こちらがonの間は
     * 個別の設定に関わらず全クリップとタイトル効果音が無音になる。
     */
    val timelineMuted: StateFlow<Boolean> get() = playback.timelineMuted

    /** 選択中クリップの再生位置。波形の再生ヘッド表示に使う */
    val playbackPositionMs: StateFlow<Long> get() = playback.playbackPositionMs

    /** 再生中か。画面側が再生位置のポーリングを回すかどうかの判断に使う */
    val isPlaying: StateFlow<Boolean> get() = playback.isPlaying

    /**
     * 波形のキャッシュ。キーはURI文字列。
     *
     * clip.id ではなくURIで持つのは、同じ動画を2回追加したときに
     * デコードをやり直さずに済ませるため。値がnullは「取得できなかった」。
     *
     * 画面へはこのMap自体は出さない（[selectedWaveform]だけを見せる）。Mapのまま渡すと、
     * 波形が1本届くたびにMapが差し替わって、タイムライン全体が再コンポーズされてしまう。
     */
    private val _waveforms = MutableStateFlow<Map<String, Waveform?>>(emptyMap())

    private val waveformJobs = mutableMapOf<String, Job>()

    // --- 履歴（もとに戻す / やり直す） ---------------------------------------------------
    //
    // 積む・まとめる・取り出すの仕組みは EditHistory（edit/EditHistory.kt）が持つ。
    // ここに残すのは「スナップショットに何を含めるか」と「取り出した状態を画面へ戻す方法」
    // の2つだけ。
    private data class Snapshot(val clips: List<VlogClip>, val selectedIndex: Int)

    // 壁時計ではなく端末の起動からの経過時間を渡す。時刻合わせで巻き戻ると、
    // まとめ判定が意図せず効いたり効かなかったりするため。
    private val history = EditHistory<Snapshot>(elapsedMs = SystemClock::elapsedRealtime)

    /**
     * プレビュー再生の受け持ち。ExoPlayerの保持・プレイリストの同期・再生位置の監視・
     * トリミング終端での停止はすべてこちら（playback/PlaybackController.kt）にあり、
     * ViewModelはクリップ一覧を渡して操作を頼むだけにしてある。
     */
    private val playback = PlaybackController(application) { _clips.value }

    /** プレビュー(PlayerView)へ渡すためだけに公開している */
    val player: ExoPlayer get() = playback.player

    val selectedClip: VlogClip? get() = _clips.value.getOrNull(playback.selectedIndexValue)

    /**
     * `_clips`を非同期の後始末を伴って書き換える操作（クリップ追加・一時保存の読み込みなど）を
     * 直列化するロック。
     *
     * これらは「バックグラウンドでの下ごしらえ → 完了後に_clips.valueへ反映」という
     * 形を取るため、2つの操作が重なると片方の反映が失われることがある
     * （例：動画追加のメタデータ取得中に一時保存を読み込むと、その後addClipsが
     * 古い_clips.valueを基準に追記してしまい、loadProjectの結果を巻き戻すか、
     * 逆にloadProjectがaddClipsの結果を消してしまう）。
     * 反映（コミット）部分だけをこのロックで囲み、常に最新の_clips.valueを
     * 基準にする。
     */
    private val clipsMutationMutex = Mutex()

    init {
        // 復元してから保存を始める。順番が逆だと、復元前の空リストを
        // 保存してしまい前回の内容が消える。
        viewModelScope.launch {
            restoreClips()

            // 編集内容を自動保存する。collectLatestとdelayの組み合わせで、
            // ひとことを1文字打つたびに書き込むのを避けている。
            _clips.collectLatest { clips ->
                delay(AUTOSAVE_DEBOUNCE_MS)
                // JSONの組み立てとSharedPreferencesの初回読み込み待ちでメインスレッドを塞がない。
                // DefaultではなくIOなのは、SharedPreferencesの初回アクセスがディスクの
                // 読み込み待ちでブロックしうるため（CPU向けの有界プールを塞いでしまう）。
                // ClipStoreの他の経路もすべてIOで揃えてある。
                withContext(Dispatchers.IO) { ClipStore.save(getApplication(), clips) }
            }
        }

        // 選択が変わったら、そのクリップの波形を用意する。以前は画面側の
        // LaunchedEffect が担っていたが、画面に波形のMapを持たせないために
        // こちらへ移した。同じURIを選び直しただけでは取り直さない。
        viewModelScope.launch {
            combine(_clips, playback.selectedIndex) { clips, index -> clips.getOrNull(index) }
                .distinctUntilChangedBy { it?.uri }
                .collect { clip -> clip?.let(::requestWaveform) }
        }

        // VlogExportService からの完了・失敗通知をUIのイベントとして中継する
        viewModelScope.launch {
            ExportStatus.events.collect { _events.send(it) }
        }

        // 前回、書き出し中に強制終了していた場合の後始末。ギャラリー側（IS_PENDINGのまま
        // 残った項目）と、cacheDir側（結合途中の動画。数GBになりうる）の両方を掃除する。
        // 今まさに書き出し中（サービスが同一プロセスで生存中）なら、書き込み中のものを
        // 消してしまうので触らない。
        if (!ExportStatus.isRunning) {
            viewModelScope.launch(Dispatchers.IO) {
                VlogExporter.cleanupOrphanedPendingFiles(getApplication())
                VlogExporter.cleanupOrphanedWorkFiles(getApplication())
            }
        }
    }

    /** 前回の続きを読み込む。いま実際に読めるものだけが対象 */
    private suspend fun restoreClips() {
        playback.setAutoAdvance(ClipStore.restoreAutoAdvance(getApplication()))

        val restored = ClipStore.restore(getApplication())

        clipsMutationMutex.withLock {
            if (restored.clips.isNotEmpty()) {
                _clips.value = restored.clips
                playback.rebuildPlaylist(restored.clips)
                playback.select(0)
            }
        }

        // 復元直後を「起点」にする。ここで履歴を消しておかないと、
        // アプリを開いた直後に「もとに戻す」を押せてしまい、空の状態へ戻ってしまう。
        history.clear()

        refreshUnreliableShotTimes()
        releaseUnusedPermissions()

        if (restored.dropped > 0) {
            sendMessage(
                "${restored.dropped} 件の動画は復元できませんでした" +
                        "（移動・削除されたか、アクセス権限が取り消されています）"
            )
        }
    }

    /**
     * ファイル選択（SAF）で取った永続権限のうち、タイムラインにも一時保存にも使われていないものを解放する。
     *
     * 権限の保持数にはアプリごとの上限（Android 10以前は128、11以降は512）があり、
     * 解放しないまま動画を追加し続けると、上限を超えて新しい動画の権限を取れなくなる
     * （取れなかった動画は、次に開いたとき復元されない）。
     */
    private fun releaseUnusedPermissions() {
        viewModelScope.launch {
            // 判断の時点の状態を見る（読み込み中に呼ばれた場合や、追加中の動画がある場合は見送る）
            ClipStore.releaseUnreferencedPermissions(
                context = getApplication(),
                isBusy = { _isAdding.value },
                timelineUris = { _clips.value.map { it.uri } }
            )
        }
    }

    /**
     * 撮影時刻を確かな手がかりから取れていないクリップ（[VlogClip.shotAtReliable]がfalse）の時刻を、
     * 動画から取り直す。復元時と一時保存の読み出し時に呼ぶ。
     *
     * 撮影時刻は動画ファイルから決まる値で、ユーザーが編集するものではない。それなのに追加した時点の
     * 値をそのまま保存し続けると、その時に手がかりが足りず追加時刻などで代用した値が、
     * 同じ動画を追加し直しても「追加済み」でスキップされるため、消して追加し直すまで残ってしまう。
     * 取り直しても確かな値が取れなければ、いまの値のままにして[VlogClip.shotAtRefreshed]を立て、
     * 以後は試さない（手がかりが何も無い動画を毎起動読み直すのを避けるため）。
     * 並び順は変えない（ユーザーが並べ替えた順序を壊さないため）。履歴にも積まない（編集ではないため）。
     */
    private fun refreshUnreliableShotTimes() {
        val targets = _clips.value.filter { !it.shotAtReliable && !it.shotAtRefreshed }
        if (targets.isEmpty()) return

        val context = getApplication<Application>()
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            // getVideoMetadata は直列に動くので、並列にしても速くならない。順番に読む
            val refreshed = withContext(Dispatchers.IO) {
                targets.associate { clip ->
                    clip.id to getVideoMetadata(
                        context, clip.uri, clip.shotAtMillis.takeIf { it > 0L } ?: now
                    )
                }
            }

            clipsMutationMutex.withLock {
                _clips.value = _clips.value.map { clip ->
                    // 取り直しの対象でなかったクリップ（この間に追加されたものなど）は触らない
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
            }
        }
    }

    /**
     * 選択された動画を撮影/作成日時順になる位置へ追加し、追加した中で最も古いものを選択する。
     *
     * タイムラインへ入れないもの（通知する）:
     *  - すでにタイムラインにある動画
     *  - 長さなどを読み取れなかった動画（壊れたファイル、コピー途中のファイルなど）。
     *    尺0のクリップは書き出しを止めてしまうので、追加せず、選び直してもらう
     */
    fun addClips(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _isAdding.value = ++addingCount > 0
            try {
                addClipsNow(uris)
            } finally {
                _isAdding.value = --addingCount > 0
            }
        }
    }

    private suspend fun addClipsNow(uris: List<Uri>) {
        val context = getApplication<Application>()

        // タイムラインに既にある動画は追加せずスキップする。
        // distinct() は uris 自体に同じURIが重複して含まれるケース
        // （呼び出し元が誤って同じ動画を2回渡した場合など）に対応するため。
        //
        // ファイル名+サイズなどの内容ベースでの同一性判定も検討したが、
        // 偶然ファイル名とサイズが一致する別動画を誤って同一と判定して
        // 無言でスキップしてしまうリスク（データ消失）があり、URI一致の
        // 方が安全なためこちらを採用している。「アプリ内ギャラリーと
        // ファイルピッカーの両方から同じ動画を選ぶと重複が検知できない」
        // ケースは既知の制約として残す。
        // 通知の件数は、引き算で辻褄を合わせるのではなく理由ごとに数える。
        // uris.size から引いていた頃は、呼び出し元が同じURIを2回渡しただけで
        // 「1件は追加済みのためスキップしました」と出ていた（タイムラインには無いのに）。
        // また、スキップの理由が増えるたびに引き算の式を直す必要があった。
        val requested = uris.distinct()
        val existingUris = _clips.value.map { it.uri }.toSet()
        val newUris = requested.filter { it !in existingUris }
        val alreadyInTimeline = requested.size - newUris.size
        if (newUris.isEmpty()) {
            addSkipMessage(alreadyAdded = alreadyInTimeline, unreadable = 0)?.let(::sendMessage)
            return
        }
        // すでに上限いっぱいなら、読み込むまでもなく断る
        if (_clips.value.size >= MAX_CLIPS) {
            addSkipMessage(
                alreadyAdded = alreadyInTimeline, unreadable = 0, overLimit = newUris.size
            )?.let(::sendMessage)
            return
        }

        // 読み取り自体は getVideoMetadata の中で1本ずつ直列に行われる（同時に読むと、別の動画の
        // 撮影日時が返ることがあるため）。ここでは待たせる本数をMETADATA_PARALLELISMに絞るだけ。
        //
        // メタデータが一切取れない動画のための最終フォールバック時刻は、並列取得の完了
        // タイミング（実行順とは無関係）に左右されないよう、ここで選択順に沿って1件ずつ
        // 確実にずらした時刻を用意しておく。
        val fallbackBaseMillis = System.currentTimeMillis()
        val loaded = withContext(Dispatchers.IO) {
            newUris.mapParallel(METADATA_PARALLELISM) { offset, uri ->
                val meta = getVideoMetadata(context, uri, fallbackBaseMillis + offset)
                VlogClip(
                    id = nextClipId(),
                    uri = uri,
                    timeText = meta.timeText,
                    dateText = meta.dateText,
                    durationMs = meta.durationMs,
                    startMs = 0L,
                    endMs = meta.durationMs,
                    shotAtMillis = meta.shotAtMillis,
                    shotAtReliable = meta.shotAtReliable
                )
            }
        }
        // 長さを読めなかった動画は入れない（尺0のクリップは書き出しを止めてしまう）
        val (readable, unreadable) = loaded.partition { it.isValid }

        // メタデータの取得中に、別のaddClips呼び出しが同じ動画を先に追加していた件数。
        // ロックの中でしか分からないので、ここで受け取って通知の件数に足す。
        var addedWhileLoading = 0
        val (toMerge, overLimit) = clipsMutationMutex.withLock {
            // ロック取得前のチェックは、メタデータ取得中に別の
            // addClips 呼び出しが同じ動画を先に追加してしまう競合には対応できない。
            // マージ直前にロック内でもう一度チェックし、その分を除外する。
            // 上限もここで、追加の直前の本数を基準に守る（並行した追加で超えないように）。
            val currentUris = _clips.value.map { it.uri }.toSet()
            val candidates = readable.filter { it.uri !in currentUris }
            addedWhileLoading = readable.size - candidates.size
            val room = (MAX_CLIPS - _clips.value.size).coerceAtLeast(0)
            val toMerge = candidates.take(room)
            val overLimit = candidates.size - toMerge.size
            if (toMerge.isEmpty()) return@withLock toMerge to overLimit

            val oldestAddedId = toMerge.minByOrNull { it.sortKeyMs }?.id

            recordHistory()
            val (merged, insertions) = mergeByShotAt(_clips.value, toMerge)
            _clips.value = merged
            playback.insertIntoPlaylist(insertions)

            oldestAddedId?.let { id ->
                val index = merged.indexOfFirst { it.id == id }
                if (index >= 0) playback.select(index)
            }
            toMerge to overLimit
        }

        addSkipMessage(
            alreadyAdded = alreadyInTimeline + addedWhileLoading,
            unreadable = unreadable.size,
            overLimit = overLimit
        )?.let(::sendMessage)
    }

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

        val lowerBound = clip.texts[index - 1].startMs + MIN_TEXT_SEGMENT_MS
        // 次の区切りが無い（＝最後の区間を動かす）場合の上限はクリップ全体の長さ(durationMs)
        // ではなく、いまのトリム終端(endMs)にする。durationMsのままだと、トリムで
        // 後半を切り落とした後も区切りをトリム範囲の外まで動かせてしまう。
        val upperBound =
            (clip.texts.getOrNull(index + 1)?.startMs ?: clip.endMs) - MIN_TEXT_SEGMENT_MS
        if (lowerBound > upperBound) return

        val clamped = newAtMs.coerceIn(lowerBound, upperBound)
        if (clamped == clip.texts[index].startMs) return

        recordHistory(EditTag.SplitMove(playback.selectedIndexValue, index))
        updateSelected { current ->
            current.copy(
                texts = current.texts.mapIndexed { i, segment ->
                    if (i == index) segment.copy(startMs = clamped) else segment
                }
            )
        }

        playback.seekAndPause(clamped)
    }

    /** 波形をタップしたときの頭出し。トリミング範囲の外へは飛ばさない */
    fun seekWithinTrim(positionMs: Long) = playback.seekWithinTrim(positionMs)

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
        val inserted = (clip.texts + TextSegment(at, DEFAULT_HITOKOTO)).sortedBy { it.startMs }
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
        cancelWaveformJobIfUnused(removedUri)

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
        removedUris.forEach { cancelWaveformJobIfUnused(it) }
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

    /**
     * 動画を読み込み中は、一時保存の保存・上書き・読み出しをしない。
     * 読み込み中のタイムラインは途中の状態で、保存すると一部だけが残り、読み出すと
     * あとから読み込み終えた動画が読み出した内容に混ざってしまうため。
     * @return 読み込み中で断った場合はtrue（通知済み）
     */
    private fun refuseWhileAdding(): Boolean {
        if (!_isAdding.value) return false
        sendMessage("動画を読み込み中です。終わってからもう一度お試しください")
        return true
    }

    /** いまの編集内容に名前を付けて残す。動画はコピーしないので一瞬で終わる */
    fun saveProject(name: String) {
        if (refuseWhileAdding()) return
        val clipsToSave = _clips.value
        if (clipsToSave.isEmpty()) {
            sendMessage("保存できる編集内容がありません")
            return
        }

        viewModelScope.launch {
            val label = name.trim().ifBlank { formatSavedAt(System.currentTimeMillis()) }
            // 同名があれば連番が付く。メッセージには実際に付いた名前を出す
            val savedName = ClipStore.saveProject(getApplication(), label, clipsToSave)
            _projects.value = ClipStore.listProjects(getApplication())
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
    fun overwriteProject(id: Long, name: String) {
        if (refuseWhileAdding()) return
        val clipsToSave = _clips.value
        if (clipsToSave.isEmpty()) {
            sendMessage("保存できる編集内容がありません")
            return
        }

        viewModelScope.launch {
            val overwritten = ClipStore.overwriteProject(getApplication(), id, clipsToSave)
            _projects.value = ClipStore.listProjects(getApplication())
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
     */
    fun loadProject(id: Long) {
        if (refuseWhileAdding()) return
        viewModelScope.launch {
            val restored = ClipStore.loadProject(getApplication(), id)
            if (restored == null) {
                sendMessage("この保存は読み出せませんでした")
                return@launch
            }
            if (!canReplaceWithProject(loaded = restored.clips.size, dropped = restored.dropped)) {
                sendMessage(projectUnreadableMessage(restored.dropped))
                return@launch
            }

            clipsMutationMutex.withLock {
                recordHistory()
                _clips.value = restored.clips
                playback.rebuildPlaylist(restored.clips)
                // 空の保存を読み出したときだけここで0に戻す。中身があるときは
                // select(0) が同じ代入をやり直すことになるので、そちらだけに任せる。
                if (restored.clips.isNotEmpty()) playback.select(0)
                else {
                    playback.setSelectedIndex(0)
                    playback.setPositionMs(0L)
                }
            }
            refreshUnreliableShotTimes()

            sendMessage(projectLoadedMessage(restored.dropped))
        }
    }

    fun deleteProject(id: Long) {
        viewModelScope.launch {
            ClipStore.deleteProject(getApplication(), id)
            _projects.value = ClipStore.listProjects(getApplication())
        }
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
     */
    private fun applySnapshot(snapshot: Snapshot) {
        val playlistChanged =
            snapshot.clips.map { it.uri } != _clips.value.map { it.uri }

        _clips.value = snapshot.clips

        if (playlistChanged) playback.rebuildPlaylist(snapshot.clips) else playback.pause()

        val index = snapshot.selectedIndex
            .coerceIn(0, snapshot.clips.lastIndex.coerceAtLeast(0))
        playback.setSelectedIndex(index)
        snapshot.clips.getOrNull(index)?.let { playback.seekWithoutPause(it.startMs) }
    }

    // --- 波形 ---------------------------------------------------------------------------

    /**
     * 選択中クリップの波形。画面はこれだけを見る。
     *
     * 以前は画面側が波形のMap全体を collect し、`LaunchedEffect(selectedClip?.uri)` で
     * 取得を頼んでいた。Mapは波形が1本届くたびに差し替わるため、タイムライン全体が
     * そのたびに再コンポーズされていた。選択中の1本だけを流せば、実際に表示が変わる
     * ときにしか流れない。
     */
    val selectedWaveform: StateFlow<SelectedWaveform> =
        combine(_clips, playback.selectedIndex, _waveforms) { clips, index, waveforms ->
            val key = clips.getOrNull(index)?.uri?.toString()
                ?: return@combine SelectedWaveform(waveform = null, isLoading = false)
            if (key in waveforms) SelectedWaveform(waveforms[key], isLoading = false)
            else SelectedWaveform(waveform = null, isLoading = true)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, SelectedWaveform())

    /**
     * 波形をバックグラウンドで用意する。取得済み・取得中のURIは何もしない。
     * 選択中クリップぶんだけ呼ぶ（全件を先読みするとデコードが渋滞して、
     * 肝心の「いま触っているクリップ」の表示が後回しになる）。
     */
    private fun requestWaveform(clip: VlogClip) {
        val key = clip.uri.toString()
        if (_waveforms.value.containsKey(key)) return
        if (waveformJobs[key]?.isActive == true) return

        waveformJobs[key] = viewModelScope.launch {
            val waveform = extractWaveform(getApplication(), clip.uri, clip.durationMs)
            _waveforms.value = _waveforms.value + (key to waveform)
            waveformJobs.remove(key)
        }
    }

    /**
     * クリップが削除されたときに、対応する波形の取得ジョブとキャッシュを捨てる。
     * ジョブをキャンセルしないと無駄なデコードが完了時まで走り続け、キャッシュを
     * 残したままだと、もう画面に出ないクリップの波形（長い動画だと1本あたり
     * 数万バイト）をアプリが終わるまで抱えたままになる。
     *
     * 同じ動画を2回追加している場合はURIが重複するため、削除後もまだ他のクリップが
     * 同じURIを参照していれば消さない（そちらの表示に使われている波形を巻き添えにしない）。
     * 呼び出し側は、_clips.valueを削除後の状態に更新してから呼ぶこと。
     */
    private fun cancelWaveformJobIfUnused(uri: Uri) {
        val key = uri.toString()
        if (_clips.value.none { it.uri.toString() == key }) {
            waveformJobs.remove(key)?.cancel()
            _waveforms.value = _waveforms.value - key
        }
    }

    // --- 再生（実処理は PlaybackController） ----------------------------------------------
    //
    // 画面からはViewModelだけを見ていればよいよう、再生まわりも窓口はここに残す。
    // 中身は playback/PlaybackController.kt にあり、ここは受け渡しだけを行う。

    /**
     * 画面から[PLAYBACK_POLL_INTERVAL_MS]間隔で呼ばれる。
     * 再生位置の更新とトリミング終端の監視をまとめて行う。
     */
    fun refreshPlaybackProgress() = playback.refreshProgress()

    /** 連続再生のオン/オフ。次回起動時にも引き継ぐので、切り替えと同時に保存する */
    fun setAutoAdvance(enabled: Boolean) {
        playback.setAutoAdvance(enabled)
        ClipStore.saveAutoAdvance(getApplication(), enabled)
    }

    /**
     * タイムライン全体のミュートを切り替える。プレビュー中の音量に加え、
     * 現在の状態は[export]が読んで書き出し音声にも反映する（起動時の引き継ぎはしない）。
     */
    fun setTimelineMuted(muted: Boolean) = playback.setTimelineMuted(muted)

    fun beginScrub() = playback.beginScrub()

    fun endScrub() = playback.endScrub()

    fun beginInteractiveSeek() = playback.beginInteractiveSeek()

    fun endInteractiveSeek() = playback.endInteractiveSeek()

    fun pause() = playback.pause()

    fun togglePlayback() = playback.togglePlayback()

    /**
     * 書き出しは VlogExportService（フォアグラウンドサービス）に委ねる。
     * viewModelScopeで直接実行しないのは、バックグラウンドに回すとOSに
     * プロセスごと回収されうるため。サービス化してActivity/ViewModelより
     * 長生きさせる。
     *
     * @param includeTitle 先頭のタイトルカード（黒背景＋日付＋効果音）を付けるかどうか。
     *   書き出しボタンのタップ（true）／長押し（false）で呼び分ける。
     * @param customTitleText タイトルカードに焼き込む文言。null/空文字なら先頭クリップの
     *   撮影日（[VlogClip.dateText]）を使う。タイトル作成ダイアログで自由入力を選んだときのみ渡る。
     */
    fun export(includeTitle: Boolean = true, customTitleText: String? = null) {
        // 書き出し中に押し直したとき、何も起きないと「押せていない」のか
        // 「始まっているのか」が画面から分からないので、理由を返す
        if (ExportStatus.isRunning) {
            sendMessage("すでに書き出し中です")
            return
        }

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

        playback.pause()
        VlogExportService.start(
            getApplication(), target, includeTitle, playback.isTimelineMuted, customTitleText
        )
    }

    fun cancelExport() {
        VlogExportService.cancel(getApplication())
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

    private fun sendMessage(text: String) {
        viewModelScope.launch { _events.send(VlogEvent.Message(text)) }
    }

    override fun onCleared() {
        // 書き出し自体は VlogExportService で継続させる（ここではキャンセルしない）。
        // ユーザーが画面を閉じてもバックグラウンドで書き出しを終わらせるための挙動。
        playback.release()
        // super.onCleared() は呼ばない。ViewModel側で@EmptySuperが付いており
        // （中身が空であることが保証されている）、呼ぶとlintに警告される。
    }
}
