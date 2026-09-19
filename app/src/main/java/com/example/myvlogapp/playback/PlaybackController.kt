package com.example.myvlogapp.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.example.myvlogapp.PLAY_AT_END_TOLERANCE_MS
import com.example.myvlogapp.PLAYBACK_POLL_INTERVAL_MS
import com.example.myvlogapp.VlogClip

// =====================================================================================
// プレビュー再生。
//
// VlogViewModel から切り出したもの。ExoPlayerの保持・プレイリストの同期・再生位置の監視・
// トリミング終端での停止／自動遷移といった「再生まわり」だけをここに集める。
// クリップ一覧そのものは引き続き ViewModel が持ち、ここへは [clips] で覗かせる
// （再生側から一覧を書き換えることは無い。選択位置と再生位置だけがここの持ち物）。
// =====================================================================================

/** 再生を押したとき、どこから再生するか */
internal enum class PlayFrom {
    /** いまの位置から（途中で止めていた、または次のクリップへ進める） */
    CURRENT_POSITION,

    /** タイムラインの先頭のクリップから（連続再生で最後のクリップの終わりで止まっていた） */
    TIMELINE_START,

    /** 選択中のクリップの頭から（連続再生オフで、クリップの終わりで止まっていた） */
    SELECTED_CLIP_START
}

/**
 * クリップの終わりで止まっているときに再生を押すと、そのまま再生してもトリミング終端の監視が
 * 直ちにまた止めてしまい、「再生できない」ように見える。終わりで止まっているときだけ、頭出しする。
 *
 * - 終わりで止まっていない → いまの位置から
 * - 連続再生オンで、最後のクリップではない → いまの位置から（次のクリップへ進む）
 * - 連続再生オンで、最後のクリップ → タイムラインの先頭から（最後で止めたあとの、再生のやり直し）
 * - 連続再生オフ → 選択中のクリップの頭から（1本ずつ見直す使い方）
 *
 * @param playerEnded プレイヤーが最後まで進んで STATE_ENDED になっている（最後のクリップのみ）
 */
internal fun playFromWhere(
    isLastClip: Boolean,
    autoAdvance: Boolean,
    positionMs: Long,
    clipEndMs: Long,
    playerEnded: Boolean
): PlayFrom {
    val atEnd = positionMs >= clipEndMs - PLAY_AT_END_TOLERANCE_MS || (isLastClip && playerEnded)
    return when {
        !atEnd -> PlayFrom.CURRENT_POSITION
        autoAdvance && !isLastClip -> PlayFrom.CURRENT_POSITION
        autoAdvance -> PlayFrom.TIMELINE_START
        else -> PlayFrom.SELECTED_CLIP_START
    }
}

/**
 * プレビュー再生の受け持ち。
 *
 * @param clips いまのタイムライン。呼ぶたびに最新を返すこと
 *   （このクラスは一覧を保持せず、判断のたびに読みに行く）
 */
@OptIn(UnstableApi::class)
class PlaybackController(context: Context, private val clips: () -> List<VlogClip>) {

    /** タイムラインの動画は自動再生しない */
    val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        playWhenReady = false
        repeatMode = Player.REPEAT_MODE_OFF
    }

    private val _selectedIndex = MutableStateFlow(0)
    val selectedIndex: StateFlow<Int> = _selectedIndex.asStateFlow()

    /** 選択中クリップの再生位置。波形の再生ヘッド表示に使う */
    private val _playbackPositionMs = MutableStateFlow(0L)
    val playbackPositionMs: StateFlow<Long> = _playbackPositionMs.asStateFlow()

    /**
     * クリップの終わりまで来たら次へ進むか、そこで止まるか。
     * オフだと1本ずつ見直しながら編集できる。設定の保存・復元は呼び出し側（ViewModel）が行う。
     */
    private val _autoAdvance = MutableStateFlow(true)
    val autoAdvance: StateFlow<Boolean> = _autoAdvance.asStateFlow()

    /**
     * タイムライン全体のミュート。プレビュー（ExoPlayerの音量）と書き出しの両方に効く。
     * クリップ個別のミュート（[VlogClip.isMuted]）とは独立していて、こちらがonの間は
     * 個別の設定に関わらず全クリップとタイトル効果音が無音になる。
     */
    private val _timelineMuted = MutableStateFlow(false)
    val timelineMuted: StateFlow<Boolean> = _timelineMuted.asStateFlow()

    /**
     * いま実際に音と映像が進んでいるか。
     *
     * 画面側の再生位置ポーリング（MainActivity）を、再生中だけに絞るために公開している。
     * `playWhenReady`ではなく`isPlaying`なのは、バッファ待ちで止まっている間は位置が
     * 進まず、ポーリングしても意味が無いため。
     */
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    val selectedIndexValue: Int get() = _selectedIndex.value
    val positionMsValue: Long get() = _playbackPositionMs.value
    val isTimelineMuted: Boolean get() = _timelineMuted.value

    init {
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val index = player.currentMediaItemIndex
                val clip = clips().getOrNull(index) ?: return
                _selectedIndex.value = index
                applyVolume()
                // 自動遷移すると次のクリップは0秒から始まってしまうため、
                // トリミング開始位置へ合わせ直す
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && clip.startMs > 0) {
                    player.seekTo(index, clip.startMs)
                }
            }

            /**
             * 最後まで再生し終えたら、最後のクリップの終わりで止める（先頭へは戻らない）。
             *
             * enforceTrimBounds では拾えないケースがある。トリミング終端が動画の
             * 実際の末尾と一致していると、[PLAYBACK_POLL_INTERVAL_MS]間隔の監視が
             * 終端に気付くより先にExoPlayer側が STATE_ENDED まで進み、isPlaying が
             * false になって監視が素通りしてしまうため。
             */
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState != Player.STATE_ENDED) return
                player.playWhenReady = false
                stopAtTimelineEnd()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
            }
        })
    }

    // --- ExoPlayer操作の共通化 ------------------------------------------------------------
    //
    // 「シークして表示上の再生位置も合わせる」処理が各操作に散らばっていたのをまとめたもの。
    // 一時停止を伴うか（ユーザー操作で位置を動かすとき）伴わないか（自動再生を続けたまま
    // 頭出しするとき）で2種類に分けてある。
    //
    // どちらも呼び出し側は直前に必ず_selectedIndex.valueを目的のインデックスへ
    // 合わせてから呼んでいるため、引数でインデックスを受け取らず内部で読む形にして
    // 呼び出し側の重複（`seekAndSync(_selectedIndex.value, x)`のようなくり返し）を無くしている。

    /** シークして表示位置も合わせる。再生中でも止めない（自動遷移など再生を継続したい場面用） */
    fun seekWithoutPause(positionMs: Long) {
        player.seekTo(_selectedIndex.value, positionMs)
        _playbackPositionMs.value = positionMs
    }

    /** 再生を止めてからシークする。ユーザーがトリミング等で位置を直接動かす操作用 */
    fun seekAndPause(positionMs: Long) {
        player.playWhenReady = false
        seekWithoutPause(positionMs)
    }

    /** 波形をタップしたときの頭出し。トリミング範囲の外へは飛ばさない */
    fun seekWithinTrim(positionMs: Long) {
        val clip = clips().getOrNull(_selectedIndex.value) ?: return
        seekWithoutPause(positionMs.coerceIn(clip.startMs, clip.endMs))
    }

    /**
     * ExoPlayerのプレイリストを準備する共通の後始末。
     * setMediaItems/addMediaItems はどちらもバッファを含め状態を作り直すため、
     * 続けてprepareし、意図せず再生が始まらないようplayWhenReadyも明示的に止めておく。
     */
    private fun preparePaused() {
        player.prepare()
        player.playWhenReady = false
    }

    /** プレイリストを丸ごと差し替える（一時保存の読み込み・復元・undo/redoでの入れ替え用） */
    fun rebuildPlaylist(clips: List<VlogClip>) {
        player.setMediaItems(clips.map { MediaItem.fromUri(it.uri) })
        preparePaused()
    }

    /**
     * 既存の再生位置を保ったまま、撮影日時順で決まった位置へ挿入する（動画追加用）。
     * [insertions] は (挿入先のindex, クリップ) のペアを昇順（indexが小さい順）で渡す。
     * 昇順に1件ずつ入れていけば後続の挿入先indexは崩れない。
     */
    fun insertIntoPlaylist(insertions: List<Pair<Int, VlogClip>>) {
        insertions.forEach { (index, clip) ->
            player.addMediaItems(index, listOf(MediaItem.fromUri(clip.uri)))
        }
        preparePaused()
    }

    /** 並べ替え。プレビューと順番をずらさないよう、一覧の移動と対で呼ぶ */
    fun moveItem(from: Int, to: Int) {
        player.moveMediaItem(from, to)
        _selectedIndex.value = to
    }

    fun removeItem(index: Int) = player.removeMediaItem(index)

    fun clearItems() {
        player.clearMediaItems()
        _selectedIndex.value = 0
        _playbackPositionMs.value = 0L
    }

    /** 再生位置は動かさずに選択位置だけを置き直す（削除後の詰め直しなど） */
    fun setSelectedIndex(index: Int) {
        _selectedIndex.value = index
    }

    /** 表示上の再生位置だけを置き直す（クリップが1本も無くなったときなど） */
    fun setPositionMs(positionMs: Long) {
        _playbackPositionMs.value = positionMs
    }

    fun select(index: Int) {
        val target = clips().getOrNull(index) ?: return
        _selectedIndex.value = index
        applyVolume()
        seekAndPause(target.startMs)
    }

    /** 再生中の音量を、タイムライン全体のミュートと選択中クリップ個別のミュートから合わせ直す */
    fun applyVolume() {
        val clipMuted = clips().getOrNull(_selectedIndex.value)?.isMuted ?: false
        player.volume = if (_timelineMuted.value || clipMuted) 0f else 1f
    }

    /**
     * 最後のクリップの再生が終わったところで止める（先頭のクリップへは戻らない）。
     * 最後のコマを出したままにする。最後まで再生し終えたときの共通処理。
     *
     * [enforceTrimBounds]の監視とExoPlayerのSTATE_ENDEDリスナーの両方から
     * 呼ばれうる（同じ「最後まで再生し終えた」を別経路で検知しているため）。
     * 既にそこで止まっていれば何もしないことで、二重の呼び出しがあっても
     * 無駄なシークを起こさないようにする。
     *
     * 「そこで止まっている」の判定に[PLAY_AT_END_TOLERANCE_MS]の幅を持たせているのは、
     * 止めた位置が必ずしも[VlogClip.endMs]ちょうどにならないため。endMsは
     * メタデータから取った尺、実際に止まれる位置はExoPlayerが持つ尺で決まり、
     * 両者は数ミリ秒ずれることがある（[PLAY_AT_END_TOLERANCE_MS]のKDoc参照）。
     * ちょうど一致で見ていると、その場合だけこの早期returnが永久に効かず、
     * 呼ばれるたびに同じ場所へシークし直していた。
     */
    private fun stopAtTimelineEnd() {
        val current = clips()
        val lastIndex = current.lastIndex
        val last = current.lastOrNull() ?: return
        if (_selectedIndex.value == lastIndex &&
            !player.playWhenReady &&
            player.currentPosition >= last.endMs - PLAY_AT_END_TOLERANCE_MS
        ) {
            return
        }
        _selectedIndex.value = lastIndex
        seekAndPause(last.endMs)
    }

    // --- 再生位置の監視 -------------------------------------------------------------------

    /**
     * 画面から[PLAYBACK_POLL_INTERVAL_MS]間隔で呼ばれる。
     * 再生位置の更新とトリミング終端の監視をまとめて行う。
     */
    fun refreshProgress() {
        if (!isInteractiveSeeking && player.currentMediaItemIndex == _selectedIndex.value) {
            _playbackPositionMs.value = player.currentPosition
        }
        enforceTrimBounds()
    }

    /**
     * トリミング終端に達したら次のクリップの開始位置へ進める。
     * 最後のクリップの終端に達したら、そこで停止する（先頭へは戻らない）。
     *
     * selectedIndexではなく再生中のインデックスを見るのは、
     * ExoPlayerが自動遷移した直後の一瞬だけ両者がずれるため。
     */
    private fun enforceTrimBounds() {
        if (!player.isPlaying) return
        val current = clips()
        val index = player.currentMediaItemIndex
        val clip = current.getOrNull(index) ?: return
        if (player.currentPosition < clip.endMs) return

        val next = index + 1
        when {
            // 連続再生オフ：いまのクリップの終わりで止め、最後のコマを出したままにする
            !_autoAdvance.value -> {
                _selectedIndex.value = index
                seekAndPause(clip.endMs)
            }

            next in current.indices -> {
                _selectedIndex.value = next
                seekWithoutPause(current[next].startMs)
            }

            // 連続再生で最後のクリップまで再生し終えたら、そこで止める（先頭へは戻らない）
            else -> stopAtTimelineEnd()
        }
    }

    /**
     * pauseAtEndOfMediaItems も合わせて切り替える。
     * [PLAYBACK_POLL_INTERVAL_MS]間隔の監視だけだと、トリミング終端が動画の
     * 実際の末尾と一致している場合にExoPlayerの自動遷移が先に走ってしまい、
     * オフにしても次が流れることがある。
     */
    fun setAutoAdvance(enabled: Boolean) {
        _autoAdvance.value = enabled
        player.pauseAtEndOfMediaItems = !enabled
    }

    /**
     * タイムライン全体のミュートを切り替える。プレビュー中の音量を直接動かすのに加え、
     * 現在の状態は書き出し（VlogViewModel.export）が読んで出力音声にも反映する
     * （起動時の引き継ぎはしない）。
     */
    fun setTimelineMuted(muted: Boolean) {
        _timelineMuted.value = muted
        applyVolume()
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

    /**
     * 波形をドラッグしている間（トリム端／分割線／本体のどれでも）だけ
     * シークを近似（キーフレーム近傍）にして、毎フレームのseekToによる
     * カクつきを減らす。既定のEXACTだと1回ごとに正確な位置までデコードし直すため重い。
     */
    fun beginInteractiveSeek() {
        isInteractiveSeeking = true
        player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
    }

    /** 指を離したらEXACTへ戻し、最後に一度だけ正確な位置へ合わせ直す */
    fun endInteractiveSeek() {
        isInteractiveSeeking = false
        player.setSeekParameters(SeekParameters.EXACT)
        seekWithoutPause(_playbackPositionMs.value)
    }

    /**
     * ドラッグ中は[refreshProgress]による上書きを止めるためのフラグ。
     *
     * seekTo()は非同期で、呼んだ直後のplayer.currentPositionはまだ古い値を
     * 返すことがある。80ms間隔のポーリングがちょうどその隙間に当たると、
     * なぞっている指に追従して置いたはずの再生位置がプレイヤー側の古い値で
     * 上書きされ、シークのピンが指の動きと無関係に後ろへ戻って見える
     * （＝「ぴょんぴょん跳ねる」）。ドラッグ中はポーリングでの上書きだけを止め、
     * 位置そのものは指の動きに合わせてseekWithinTrim等が直接更新し続ける。
     */
    private var isInteractiveSeeking = false

    // --- 再生／一時停止 -------------------------------------------------------------------

    fun pause() {
        player.playWhenReady = false
    }

    /**
     * 再生／一時停止の切り替え（プレビューのタップ）。
     *
     * クリップの終わりで止まっているときは、そのまま再生しても直ちにまた止まって
     * 「動かない」ように見えてしまうので、[playFromWhere]に従って頭出ししてから再生する。
     */
    fun togglePlayback() {
        if (player.isPlaying) {
            player.pause()
            return
        }
        val current = clips()
        val index = _selectedIndex.value
        val clip = current.getOrNull(index) ?: return

        when (
            playFromWhere(
                isLastClip = index == current.lastIndex,
                autoAdvance = _autoAdvance.value,
                positionMs = _playbackPositionMs.value,
                clipEndMs = clip.endMs,
                playerEnded = player.playbackState == Player.STATE_ENDED
            )
        ) {
            PlayFrom.CURRENT_POSITION -> Unit
            PlayFrom.TIMELINE_START -> {
                _selectedIndex.value = 0
                applyVolume()
                seekWithoutPause(current.first().startMs)
            }
            PlayFrom.SELECTED_CLIP_START -> seekWithoutPause(clip.startMs)
        }
        player.play()
    }

    fun release() = player.release()
}
