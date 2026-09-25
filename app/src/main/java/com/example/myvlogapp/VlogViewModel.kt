package com.example.myvlogapp

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.myvlogapp.data.ClipStore
import com.example.myvlogapp.data.ClipStoreProjects
import com.example.myvlogapp.data.ProjectsController
import com.example.myvlogapp.data.SavedProject
import com.example.myvlogapp.data.getVideoMetadata
import com.example.myvlogapp.data.isReadable
import com.example.myvlogapp.data.sameVideoKey
import com.example.myvlogapp.edit.TimelineStore
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

/** 動画が開けるかを確かめ直すとき（[VlogViewModel.refreshMissingClips]）に同時に開く本数 */
private const val MISSING_CHECK_PARALLELISM = 8

/**
 * 画面状態と書き出し処理の保持先。
 *
 * ViewModelに置く理由：Composable内の remember だけだと画面回転や
 * ダークモード切替で読み込んだクリップが消え、書き出しも中断されてしまう。
 * ExoPlayerもここで保持して再生位置を維持する。
 *
 * 中身は3つに分かれていて、ここはその配線と、どれにも属さない仕事（動画の追加・波形・
 * 書き出しの窓口・Toastの中継）だけを持つ：
 * - [playback]   : ExoPlayerと再生位置（playback/PlaybackController.kt）
 * - [timeline]   : クリップ一覧・履歴・プレイリスト同期（edit/TimelineStore.kt）
 * - [projectsController] : 一時保存（data/ProjectsController.kt）
 *
 * 画面（MainActivity）はこのクラスだけを見て、状態と操作を組み立てて下へ渡す。
 */
@OptIn(UnstableApi::class)
class VlogViewModel(application: Application) : AndroidViewModel(application) {

    /**
     * プレビュー再生の受け持ち。ExoPlayerの保持・プレイリストの同期・再生位置の監視・
     * トリミング終端での停止はすべてこちら（playback/PlaybackController.kt）にある。
     */
    private val playback = PlaybackController(
        context = application,
        clips = { timeline.current },
        onPlaybackError = {
            sendMessage("この動画を再生できませんでした（移動・削除されたか、アクセス権限が取り消されています）")
            refreshMissingClips()
        }
    )

    /**
     * クリップ一覧・履歴・プレイリスト同期の持ち主（edit/TimelineStore.kt）。
     * 編集操作はすべてここを通る。
     */
    private val timeline: TimelineStore = TimelineStore(
        playback = playback,
        // 壁時計ではなく端末の起動からの経過時間。時刻合わせで巻き戻ると、
        // 履歴のまとめ判定が意図せず効いたり効かなかったりするため
        elapsedMs = SystemClock::elapsedRealtime,
        sendMessage = ::sendMessage,
        // 一覧から外れた動画の波形は捨てる。一覧を更新したあとに呼ばれる
        onUrisReleased = { pruneUnusedWaveforms() }
    )

    /** 一時保存（data/ProjectsController.kt） */
    private val projectsController: ProjectsController = ProjectsController(
        repository = ClipStoreProjects(application),
        scope = viewModelScope,
        timeline = timeline,
        isAdding = { _isAdding.value },
        sendMessage = ::sendMessage
    )

    val clips: StateFlow<List<VlogClip>> get() = timeline.clips
    val selectedIndex: StateFlow<Int> get() = playback.selectedIndex
    val canUndo: StateFlow<Boolean> get() = timeline.canUndo
    val canRedo: StateFlow<Boolean> get() = timeline.canRedo

    /** タイムラインを丸ごと入れ替えた回数（[TimelineStore.replacementCount]） */
    val timelineReplacementCount: StateFlow<Int> get() = timeline.replacementCount

    // 書き出しの実体は VlogExportService（Activity/ViewModelより長生きする）が持つ。
    // ここは ExportStatus を覗くだけ。
    val exportState: StateFlow<ExportState> = ExportStatus.state

    private val _events = Channel<VlogEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /**
     * タイムラインへ動画を読み込んでいる最中か（動画の追加と、起動時の前回の続きの復元）。
     * 画面に「動画を読み込み中…」を出し、追加・書き出し・一時保存の保存と読み出しを止めるのに使う。
     * 読み込みは並行して走りうるので、実行中の件数で数える（更新はメインスレッドだけ。[whileLoadingClips]）。
     */
    private var addingCount = 0
    private val _isAdding = MutableStateFlow(false)
    val isAdding: StateFlow<Boolean> = _isAdding.asStateFlow()

    /**
     * 起動時の復元（[restoreClips]）が、タイムラインを入れ替え終えたか。
     *
     * 復元は前回の動画を1本ずつ開いて確かめてから、タイムラインを丸ごと入れ替える。
     * 確かめている間（クラウド上の動画があると数秒かかる）に追加された動画は、あとから来た
     * 入れ替えで消えていた（追加済みかの判定も、復元前の空のタイムラインで行われていた）。
     * 追加はこれを待ってから始める。画面側のボタンも止めてあるが、ギャラリーをすでに
     * 開いていた場合などは素通りするので、ここで確実に守る。
     */
    private val restoreFinished = CompletableDeferred<Unit>()

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

    /** プレビュー(PlayerView)へ渡すためだけに公開している */
    val player: ExoPlayer get() = playback.player

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

    /** 前回の続き（自動保存）を、いつ書き換えてよいか（AutosavePolicy.kt） */
    private val autosave = AutosavePolicy()

    /**
     * 動画を開けなくなったクリップのid（移動・削除された、権限が取り消されたなど）。
     * タイムラインのタイルに目印を出すのに使う。起動時の復元では開けない動画は落とすが、
     * 使っている間に消された動画はタイムラインに残ったままで、どれが消えたのか見分けられなかった。
     *
     * initより前に宣言する。initで始めるコルーチンは、最初に止まるところまではその場で走るので、
     * 宣言がinitより後ろだと、その間に[refreshMissingClips]へ届いたとき、まだ作られていない
     * フィールドに触れて落ちる（いまは届く経路が無いが、並びだけで決まる落とし穴を残さない）。
     */
    private val _missingClipIds = MutableStateFlow<Set<Long>>(emptySet())
    val missingClipIds: StateFlow<Set<Long>> = _missingClipIds.asStateFlow()

    private var missingCheckJob: Job? = null

    init {
        // 復元してから保存を始める。順番が逆だと、復元前の空リストを
        // 保存してしまい前回の内容が消える。
        viewModelScope.launch {
            restoreClips()

            // 編集内容を自動保存する。collectLatestとdelayの組み合わせで、
            // ひとことを1文字打つたびに書き込むのを避けている。
            timeline.clips.collectLatest { clips ->
                // 開けない動画を落として復元した回は、編集されるまで書き換えない（理由はAutosavePolicy）
                if (!autosave.shouldSave(canUndo = timeline.canUndo.value)) return@collectLatest
                delay(AUTOSAVE_DEBOUNCE_MS)
                // JSONの組み立てとSharedPreferencesの初回読み込み待ちでメインスレッドを塞がない。
                // DefaultではなくIOなのは、SharedPreferencesの初回アクセスがディスクの
                // 読み込み待ちでブロックしうるため（CPU向けの有界プールを塞いでしまう）。
                // ClipStoreの他の経路もすべてIOで揃えてある。
                withContext(Dispatchers.IO) { ClipStore.save(getApplication(), clips) }
            }
        }

        // タイムラインを丸ごと入れ替えたら（一時保存の読み出し・その「もとに戻す」など）、
        // もう使わない動画の波形を捨てる。1本ずつの削除はonUrisReleasedが拾うが、
        // 丸ごとの入れ替えはそこを通らず、前の動画の波形を持ち続けてしまう
        viewModelScope.launch {
            timeline.replacementCount.collect { pruneUnusedWaveforms() }
        }

        // 選択が変わったら、そのクリップの波形を用意する。以前は画面側の
        // LaunchedEffect が担っていたが、画面に波形のMapを持たせないために
        // こちらへ移した。同じURIを選び直しただけでは取り直さない。
        viewModelScope.launch {
            combine(timeline.clips, playback.selectedIndex) { clips, index -> clips.getOrNull(index) }
                .distinctUntilChangedBy { it?.uri }
                .collect { clip -> clip?.let(::requestWaveform) }
        }

        // VlogExportService からの完了・失敗通知をUIのイベントとして中継する
        viewModelScope.launch {
            ExportStatus.events.collect { _events.send(it) }
        }

        // 書き出しが終わったら、動画が開けるかを確かめ直す。開けない動画が混ざっていて
        // 書き出しが断られたとき、どのタイルを外せばよいかを目印で示すため
        viewModelScope.launch {
            ExportStatus.state
                .map { it is ExportState.Running }
                .distinctUntilChanged()
                .drop(1)
                .filter { running -> !running }
                .collect { refreshMissingClips() }
        }
    }

    /** 前回の続きを読み込む。いま実際に読めるものだけが対象 */
    private suspend fun restoreClips() {
        // 読み込み中の扱いにして、入れ替え終えるまで追加・書き出し・一時保存を止める（[restoreFinished]）。
        // 待っている追加が動き出すのは、履歴を空にしたあと。先に動くと、追加の「もとに戻す」まで消える
        val restored = try {
            whileLoadingClips {
                // 連続再生の設定もこの中で読む。外で読んでいた頃は、その読み出し（初回はディスク待ち）の間だけ
                // 読み込み中になっておらず、そこで一時保存を読み出すと、あとから来た復元の入れ替えで
                // 上書きされて履歴も消えた
                playback.setAutoAdvance(ClipStore.restoreAutoAdvance(getApplication()))
                coroutineScope {
                    // 前回、書き出し中に強制終了していた場合の後始末。ギャラリー側（IS_PENDINGのまま
                    // 残った項目）と、cacheDir側（結合途中の動画。数GBになりうる）の両方を掃除する。
                    // 今まさに書き出し中（サービスが同一プロセスで生存中）なら、書き込み中のものを
                    // 消してしまうので触らない。
                    // 復元と並べて、読み込み中（書き出しを始められない間）に済ませる。別に走らせていた
                    // 頃は書き出しの開始と待ち合わせておらず、理屈の上では始まったばかりの書き出しの
                    // 作業ファイルを消しえた（読み込み中に書き出しを断るのは[export]）
                    if (!ExportStatus.isRunning) {
                        launch(Dispatchers.IO) {
                            VlogExporter.cleanupOrphanedPendingFiles(getApplication())
                            VlogExporter.cleanupOrphanedWorkFiles(getApplication())
                        }
                    }
                    ClipStore.restore(getApplication()).also {
                        timeline.replaceAll(it.clips, record = false)
                        // 復元直後を「起点」にする。ここで履歴を消しておかないと、
                        // アプリを開いた直後に「もとに戻す」を押せてしまい、空の状態へ戻ってしまう。
                        timeline.clearHistory()
                        autosave.onRestored(droppedCount = it.dropped)
                    }
                }
            }
        } finally {
            restoreFinished.complete(Unit)
        }

        refreshUnreliableShotTimes()
        // 開けない動画を落とした回は権限を解放しない。落とした動画はタイムラインに
        // 無いので「使われていない」と判断され、一時的に開けなかっただけでも権限を
        // 手放してしまう（自動保存を保留するのと同じ理由。AutosavePolicy）。編集して保存から消えれば、
        // 次の起動で解放される
        if (restored.dropped == 0) releaseUnusedPermissions()

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
                timelineUris = { timeline.current.map { it.uri } }
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
     */
    private fun refreshUnreliableShotTimes() {
        val targets = timeline.current.filter { !it.shotAtReliable && !it.shotAtRefreshed }
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
            timeline.applyRefreshedShotTimes(refreshed)
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
            whileLoadingClips {
                // 前回の続きを入れ替え終えてから追加する（理由は[restoreFinished]）
                restoreFinished.await()
                addClipsNow(uris)
            }
        }
    }

    /** [block]の間、読み込み中として数える（[isAdding]） */
    private suspend fun <T> whileLoadingClips(block: suspend () -> T): T {
        _isAdding.value = ++addingCount > 0
        try {
            return block()
        } finally {
            _isAdding.value = --addingCount > 0
        }
    }

    /**
     * 動画ごとの「同じ動画かを見分ける鍵」（[sameVideoKey]）。追加のたびに求めた分を覚えておく。
     *
     * 追加は「鍵を求めて振り分ける → メタデータを読む → 一覧へ反映する」の順で、読んでいる間に
     * 別の追加が先に反映されうる。反映の直前にもう一度確かめるときに同じ鍵で比べるため、
     * 求めた鍵をここから引く（反映はメインスレッドで、端末への問い合わせをやり直せないため）。
     * 触るのはメインスレッドだけ。1件は文字列2つぶんで、1回の起動で追加する本数ぶんしか増えない
     */
    private val videoKeys = HashMap<Uri, String>()

    private suspend fun addClipsNow(uris: List<Uri>) {
        val context = getApplication<Application>()

        // 追加済み・上限超え・読み込むものに振り分ける（決まりはClipAddition.kt）
        // 同じ動画かは、ギャラリーとファイル選択でのURIの形の違いをそろえた鍵で比べる
        // （data/MediaIdentity.kt）。ファイル選択のURIは鍵を求めるのに端末へ問い合わせるので、
        // バックグラウンドで求める
        //
        // タイムラインにある動画の鍵は、前に求めたものがあればそれを使う。毎回全部を求め直していた頃は、
        // 1本追加するたびに、ファイル選択の動画の本数ぶん端末へ問い合わせていた（最大100本）
        val current = timeline.current
        val knownKeys = HashMap(videoKeys)
        val (requestedKeys, existingKeys) = withContext(Dispatchers.IO) {
            uris.associateWith { knownKeys[it] ?: sameVideoKey(context, it) } to
                current.associate { it.uri to (knownKeys[it.uri] ?: sameVideoKey(context, it.uri)) }
        }
        // 反映の直前の確かめ（TimelineStore.insertByShotAt）でも同じ鍵で比べられるよう覚えておく
        videoKeys.putAll(requestedKeys)
        videoKeys.putAll(existingKeys)
        val plan = planAddition(
            requested = uris,
            existing = existingKeys.values.toHashSet(),
            currentCount = current.size,
            keyOf = requestedKeys::getValue
        )
        if (plan.toLoad.isEmpty()) {
            addSkipMessage(
                alreadyAdded = plan.alreadyAdded, unreadable = 0, overLimit = plan.overLimit
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
            plan.toLoad.mapParallel(METADATA_PARALLELISM) { offset, uri ->
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

        // 鍵を覚えていない動画（この間に一時保存から読み出した分など）はURIそのものを鍵にする。
        // その場合は以前と同じくURIの一致だけで見ることになるが、取り違えて外すことはない
        val result = timeline.insertByShotAt(readable) { uri -> videoKeys[uri] ?: uri.toString() }

        addSkipMessage(
            // alreadyPresent は、メタデータの取得中に別の追加が先に入れてしまった分
            alreadyAdded = plan.alreadyAdded + result.alreadyPresent,
            unreadable = unreadable.size,
            overLimit = plan.overLimit + result.overLimit
        )?.let(::sendMessage)
    }

    // --- 開けなくなった動画 ----------------------------------------------------------------
    // 状態（_missingClipIds・missingCheckJob）は、initより前（ファイルの上の方）で宣言してある

    /**
     * タイムラインの全クリップについて、動画が今も開けるかを確かめ直す。
     * アプリが前面に戻ったとき・再生できなかったとき・書き出しが終わったときに呼ぶ。
     * 1本ずつ実際に開くので、[MISSING_CHECK_PARALLELISM]本ずつ並列に行う。
     */
    fun refreshMissingClips() {
        missingCheckJob?.cancel()
        val context = getApplication<Application>()
        val targets = timeline.current
        missingCheckJob = viewModelScope.launch {
            val readable = withContext(Dispatchers.IO) {
                targets.mapParallel(MISSING_CHECK_PARALLELISM) { _, clip -> isReadable(context, clip.uri) }
            }
            _missingClipIds.value = targets.zip(readable)
                .filterNot { (_, ok) -> ok }
                .mapTo(HashSet()) { (clip, _) -> clip.id }
        }
    }

    // --- 一時保存（実処理は ProjectsController） ------------------------------------------

    val projects: StateFlow<List<SavedProject>> get() = projectsController.projects

    fun refreshProjects() = projectsController.refresh()

    fun saveProject(name: String) = projectsController.save(name)

    fun overwriteProject(id: Long, name: String) = projectsController.overwrite(id, name)

    /** 読み出したあとは、撮影時刻が確かでないクリップを取り直す（復元時と同じ扱い） */
    fun loadProject(id: Long) =
        projectsController.load(id, onLoaded = ::refreshUnreliableShotTimes)

    fun deleteProject(id: Long) = projectsController.delete(id)

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
        combine(timeline.clips, playback.selectedIndex, _waveforms) { clips, index, waveforms ->
            val key = clips.getOrNull(index)?.uri?.toString()
                ?: return@combine SelectedWaveform(waveform = null, isLoading = false)
            if (key in waveforms) SelectedWaveform(waveforms[key], isLoading = false)
            else SelectedWaveform(waveform = null, isLoading = true)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, SelectedWaveform())

    /**
     * 波形をバックグラウンドで用意する。取得済み・取得中のURIは何もしない。
     * 選択中クリップぶんだけ呼ぶ（全件を先読みするとデコードが渋滞して、
     * 肝心の「いま触っているクリップ」の表示が後回しになる）。
     *
     * 取得に失敗した（null）URIは、次に選ばれたときに取り直す。以前は失敗も「取得済み」として
     * 扱っていたため、一時的に読めなかっただけの動画（クラウド上のファイルなど）でも、
     * アプリを再起動するまで「波形を取得できませんでした」のままだった。取り直している間は
     * 失敗の表示のまま、届いたら差し替わる。音声の無い動画は失敗ではない（Waveform.Silent）ので
     * 取り直さない。
     */
    private fun requestWaveform(clip: VlogClip) {
        val key = clip.uri.toString()
        if (_waveforms.value[key] != null) return
        if (waveformJobs[key]?.isActive == true) return

        waveformJobs[key] = viewModelScope.launch {
            val waveform = extractWaveform(getApplication(), clip.uri, clip.durationMs)
            _waveforms.value = _waveforms.value + (key to waveform)
            waveformJobs.remove(key)
        }
    }

    /**
     * タイムラインに無い動画の、波形の取得ジョブとキャッシュを捨てる。
     * ジョブをキャンセルしないと無駄なデコードが完了時まで走り続け、キャッシュを
     * 残したままだと、もう画面に出ないクリップの波形（長い動画だと1本あたり
     * 数万バイト）をアプリが終わるまで抱えたままになる。
     *
     * いまのタイムラインを見て判断するので、呼ぶのは一覧を更新した「あと」
     * （クリップの削除時と、タイムラインを丸ごと入れ替えたとき）。
     */
    private fun pruneUnusedWaveforms() {
        val inUse = timeline.current.mapTo(HashSet()) { it.uri.toString() }
        val unused = (_waveforms.value.keys + waveformJobs.keys).filterNotTo(HashSet()) { it in inUse }
        if (unused.isEmpty()) return
        unused.forEach { waveformJobs.remove(it)?.cancel() }
        _waveforms.value = _waveforms.value - unused
    }

    // --- 編集（実処理は TimelineStore） ---------------------------------------------------
    //
    // 画面からはViewModelだけを見ていればよいよう、編集の窓口はここに残す。
    // 中身は edit/TimelineStore.kt にあり、ここは受け渡しだけを行う。

    fun select(index: Int) = timeline.select(index)

    fun updateTrim(startMs: Long, endMs: Long, previewAtMs: Long = startMs) =
        timeline.updateTrim(startMs, endMs, previewAtMs)

    fun applyTrimPreset(lengthMs: Long) = timeline.applyTrimPreset(lengthMs)

    fun moveTrim(targetStartMs: Long, previewAtMs: Long) =
        timeline.moveTrim(targetStartMs, previewAtMs)

    fun moveSplit(index: Int, newAtMs: Long) = timeline.moveSplit(index, newAtMs)

    fun updateText(text: String) = timeline.updateText(text)

    fun splitTextAtPlayhead() = timeline.splitTextAtPlayhead()

    fun removeSplit(atMs: Long) = timeline.removeSplit(atMs)

    fun toggleClipMute(clipId: Long) = timeline.toggleClipMute(clipId)

    fun moveSelected(offset: Int) = timeline.moveSelected(offset)

    fun removeSelected() = timeline.removeSelected()

    fun removeAll() = timeline.removeAll()

    fun undo() = timeline.undo()

    fun redo() = timeline.redo()

    // --- 再生（実処理は PlaybackController） ----------------------------------------------

    /** 波形をタップしたときの頭出し。トリミング範囲の外へは飛ばさない */
    fun seekWithinTrim(positionMs: Long) = playback.seekWithinTrim(positionMs)

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

    // --- 書き出し -----------------------------------------------------------------------

    /**
     * 書き出しは VlogExportService（フォアグラウンドサービス）に委ねる。
     * viewModelScopeで直接実行しないのは、バックグラウンドに回すとOSに
     * プロセスごと回収されうるため。サービス化してActivity/ViewModelより
     * 長生きさせる。
     *
     * @param includeTitle 先頭のタイトルカード（黒背景＋日付＋効果音）を付けるかどうか。
     *   書き出しボタンのタップ（true）／長押し（false）で呼び分ける。
     * @param customTitleText タイトルカードに焼き込む文言。タイトル作成ダイアログが、既定（先頭クリップの
     *   撮影日）か自由入力のどちらかを毎回渡す。nullのときだけ書き出し側で撮影日（[VlogClip.dateText]）にする
     */
    fun export(includeTitle: Boolean = true, customTitleText: String? = null) {
        // 書き出し中に押し直したとき、何も起きないと「押せていない」のか
        // 「始まっているのか」が画面から分からないので、理由を返す
        if (ExportStatus.isRunning) {
            sendMessage("すでに書き出し中です")
            return
        }
        // 読み込み中（追加・起動時の復元）は始めない。画面のボタンも止めてあるが、通知の許可を聞いてから
        // 始める経路（ActivityLaunchers.kt）は、答えが返った時点でここを直接呼ぶ。許可の画面の間に
        // プロセスが回収されると、戻ったときは復元の最中で、復元に並べて走る作業ファイルの掃除
        // （restoreClips）が、始まったばかりの書き出しの作業ファイルを消しうる
        if (_isAdding.value) {
            sendMessage("動画を読み込み中です。終わってからもう一度お試しください")
            return
        }

        val target = timeline.current
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

    private fun sendMessage(text: String) {
        viewModelScope.launch { _events.send(VlogEvent.Message(text)) }
    }

    override fun onCleared() {
        // 自動保存の待ち（AUTOSAVE_DEBOUNCE_MS）はviewModelScopeごと取り消されるので、
        // 最後の編集をここで書いておく。タイムラインが空だと戻るボタンでアプリが終わるため、
        // 全削除してすぐ閉じると削除が保存されず、次の起動で全部戻ってきていた。
        // apply()で書くのでメインスレッドは塞がない。書いてよいかの判断はAutosavePolicy
        if (autosave.shouldSaveOnExit(canUndo = timeline.canUndo.value)) {
            ClipStore.save(getApplication(), timeline.current)
        }
        // 書き出し自体は VlogExportService で継続させる（ここではキャンセルしない）。
        // ユーザーが画面を閉じてもバックグラウンドで書き出しを終わらせるための挙動。
        playback.release()
        // super.onCleared() は呼ばない。ViewModel側で@EmptySuperが付いており
        // （中身が空であることが保証されている）、呼ぶとlintに警告される。
    }
}
