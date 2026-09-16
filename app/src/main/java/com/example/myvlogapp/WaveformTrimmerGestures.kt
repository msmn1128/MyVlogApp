package com.example.myvlogapp

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.platform.ViewConfiguration
import kotlin.math.abs
import kotlinx.coroutines.withTimeoutOrNull

// =====================================================================================
// WaveformTrimmer.ktのジェスチャー判定・ドラッグ処理（状態を持たない純粋関数群と、
// AwaitPointerEventScope拡張関数群）をまとめたもの。WaveformTrimmerDrawing.ktが
// 描画部分を切り出しているのと同じ考え方で、Composable本体と分けてある。
// =====================================================================================

/** つまみを掴んだと判定するときの左右判定などに使うenum。WaveformTrimmerDrawing.ktからも参照するためinternal */
internal enum class TrimHandle { Start, End }

/**
 * 選択範囲が全体の尺のこの割合以上あれば、ズームせず全体表示のままにする。
 * 長い動画の一部だけを選んでいるときだけ拡大したいので、大部分を選んでいる
 * ときにまでズームすると逆に見づらくなる。
 */
private const val WAVEFORM_FIT_FULL_THRESHOLD = 0.6

/** ズーム時、選択範囲の前後に確保する余白（選択範囲の長さに対する比率） */
private const val WAVEFORM_FIT_MARGIN_RATIO = 0.5

/** ズーム時に確保する余白の下限。選択範囲が短すぎても手がかりが残るように */
private const val WAVEFORM_FIT_MIN_MARGIN_MS = 300L

/** ズーム時の表示幅の下限。選択範囲がごく短くても波形が潰れないように */
private const val WAVEFORM_FIT_MIN_WINDOW_MS = 3_000L

/**
 * つまみの中心を置ける範囲。
 * 左右をつまみの半分ぶん内側にしてあるので、0%・100%でも端が切れない。
 *
 * [viewStartMs]〜[viewEndMs] が「いま画面に表示している時間範囲」で、
 * クリップ全体ではなくこのビューポートを基準にpx⇔msを変換する。
 * ズームしていない（全体表示の）ときは viewStartMs=0, viewEndMs=durationMs になる。
 */
/** WaveformTrimmerDrawing.ktの描画コードからも参照するためinternal */
internal class TrackMetrics(
    val left: Float,
    val right: Float,
    private val viewStartMs: Long,
    private val viewEndMs: Long
) {
    val width: Float get() = (right - left).coerceAtLeast(1f)
    private val viewSpanMs: Long get() = (viewEndMs - viewStartMs).coerceAtLeast(1L)

    fun msToX(ms: Long): Float =
        left + ((ms - viewStartMs).toFloat() / viewSpanMs) * width

    fun xToMs(x: Float): Long =
        (viewStartMs + ((x - left) / width) * viewSpanMs).toLong().coerceIn(viewStartMs, viewEndMs)

    /** クランプなしでx→msへ線形変換する。トリムつまみ／区間ごと移動が今のビューポート端に
     * 達したときに「どれだけはみ出しているか」を知るために使う（xToMsと違い
     * viewStartMs..viewEndMsへクランプしない） */
    fun extrapolatedMs(x: Float): Long =
        (viewStartMs + ((x - left) / width) * viewSpanMs).toLong()

    /** 1msあたりのpx幅。区間ごと移動のドラッグ量計算に使う */
    val pxPerMs: Float get() = width / viewSpanMs

    companion object {
        /** つまみの半径ぶん内側に縮めたトラック範囲を作る（左右0%・100%でもつまみが切れないように） */
        fun forWidth(totalWidth: Float, handleHalfPx: Float, viewStartMs: Long, viewEndMs: Long) =
            TrackMetrics(
                handleHalfPx,
                (totalWidth - handleHalfPx).coerceAtLeast(handleHalfPx + 1f),
                viewStartMs,
                viewEndMs
            )
    }
}

/**
 * トリムつまみ／区間ごと移動が今ロックされているビューポートの外へ出たら、表示幅
 * （ズーム倍率）は変えずにビューポート自体を指の位置へ追従させてパンする
 * （iOS版WaveformView.panViewportIfNeededと同じ考え方）。再フィット
 * （[fitWaveformViewport]）のような再ズーム・再センタリングはしない
 * （＝操作中に表示が動いて指の下から的がずれる事故を再発させないため）。
 */
internal fun panViewportIfNeeded(ms: Long, durationMs: Long, lockedViewportState: MutableState<LongRange?>) {
    val locked = lockedViewportState.value ?: return
    val span = locked.last - locked.first
    if (ms < locked.first) {
        val newStart = ms.coerceAtLeast(0L)
        lockedViewportState.value = newStart..(newStart + span)
    } else if (ms > locked.last) {
        val newEnd = ms.coerceAtMost(durationMs)
        lockedViewportState.value = (newEnd - span)..newEnd
    }
}

/**
 * 端に張り付いたまま指を動かさずにいるときの、1ティックあたりの移動量。
 * 今のビューポート幅の2%を、[dragTrimHandle]/[dragBodyOrMove]内のスクロール用
 * コルーチンが約16ms毎に呼ぶ（iOS版WaveformView.edgeScrollTickMsと同じ考え方）。
 */
internal fun edgeScrollTickMs(lockedViewportState: MutableState<LongRange?>, durationMs: Long): Long {
    val span = lockedViewportState.value?.let { it.last - it.first } ?: durationMs
    return (span * 0.02).toLong().coerceAtLeast(1L)
}

/**
 * つまみ（[TrimHandle.Start]/[TrimHandle.End]）を動かした先の候補[ms]を、動画の範囲・
 * MIN_TRIM_MSの制約へクランプする。指でドラッグしているとき（[dragTrimHandle]）と、
 * 端に張り付いたまま自動で進めるとき（WaveformTrimmer内のオートスクロール
 * LaunchedEffect）の両方から呼ぶことで、境界の扱いが2箇所でずれないようにする。
 *
 * coerceIn(min, max)はmin > maxだと例外を投げる。durationMsがMIN_TRIM_MS未満の
 * 極端に短い動画では上限・下限が逆転しうるため、coerceAtLeast(0L)で
 * 「動かせる余地が無ければ現在地のまま」に倒す。
 */
internal fun clampHandleMs(handleKind: TrimHandle, ms: Long, start: Long, end: Long, duration: Long): Long =
    when (handleKind) {
        TrimHandle.Start -> ms.coerceIn(0L, (end - MIN_TRIM_MS).coerceAtLeast(0L))
        TrimHandle.End -> ms.coerceIn((start + MIN_TRIM_MS).coerceAtMost(duration), duration)
    }

/** [computeMoveSpan]の結果。区間ごと移動後の新しい開始・終了位置。 */
internal data class MoveSpanResult(val newStart: Long, val newEnd: Long)

/**
 * 区間ごと移動で、区間開始位置の候補[candidateStart]から新しいstart/endを求める。
 * 区間の幅（[start]〜[end]）は変えず、動画の範囲内に収まるようclampする。
 * 指でドラッグしているとき（[dragBodyOrMove]）と、端に張り付いたまま自動で進める
 * とき（WaveformTrimmer内のオートスクロールLaunchedEffect）の両方から呼ぶ。
 */
internal fun computeMoveSpan(candidateStart: Long, start: Long, end: Long, duration: Long): MoveSpanResult {
    val span = (end - start).coerceAtLeast(0L)
    val maxStart = (duration - span).coerceAtLeast(0L)
    val newStart = candidateStart.coerceIn(0L, maxStart)
    return MoveSpanResult(newStart, newStart + span)
}

/**
 * 現在の選択範囲（[startMs]〜[endMs]）に合わせて波形の表示範囲を決める。
 *
 * 選択範囲が全体の大部分を占めるときは全体表示のまま返し、
 * 一部分だけを選んでいるときは選択範囲＋余白へズームした範囲を返す。
 * 呼び出し側はこれをドラッグ中は据え置き、ドラッグの区切り（プリセット適用・
 * ハンドルを離した瞬間など）でだけ呼び直すことで、操作中に表示が動いて
 * 掴んでいる指の下から的がずれる事故を避けている。
 */
internal fun fitWaveformViewport(startMs: Long, endMs: Long, durationMs: Long): LongRange {
    if (durationMs <= 0L) return 0L..0L
    val selectionSpan = (endMs - startMs).coerceAtLeast(0L)
    if (selectionSpan >= (durationMs * WAVEFORM_FIT_FULL_THRESHOLD).toLong()) return 0L..durationMs

    val margin = (selectionSpan * WAVEFORM_FIT_MARGIN_RATIO).toLong()
        .coerceAtLeast(WAVEFORM_FIT_MIN_MARGIN_MS)
    var viewStart = startMs - margin
    var viewEnd = endMs + margin

    val shortfall = WAVEFORM_FIT_MIN_WINDOW_MS - (viewEnd - viewStart)
    if (shortfall > 0L) {
        viewStart -= shortfall / 2
        viewEnd += shortfall - shortfall / 2
    }

    // 動画の端に近い選択範囲では、片側に伸ばせないぶんを反対側へ回して
    // 表示幅そのものは変えずに全体の範囲内へ収める
    if (viewStart < 0L) {
        viewEnd -= viewStart
        viewStart = 0L
    }
    if (viewEnd > durationMs) {
        viewStart -= (viewEnd - durationMs)
        viewEnd = durationMs
    }
    return viewStart.coerceAtLeast(0L)..viewEnd.coerceAtMost(durationMs)
}

/** [awaitSlopOrRelease] の結果。長押し（動かさず時間切れ）はこれとは別に呼び出し側で判定する */
private sealed interface DragOutcome {
    data object Released : DragOutcome
    data class Dragged(val change: PointerInputChange) : DragOutcome
}

/**
 * 指を離すまで、動くたびに [onMove] を呼び続ける。
 *
 * つまみ・分割ライン・区間ごと移動で共通の骨組み。掴んだ対象ごとに違うのは
 * 「動いたときに何をするか」だけなので、待ち受けと終了条件はここに1本化する。
 *
 * イベントを consume するのは、同じ座標を親（タイムラインの縦スクロールなど）にも
 * 渡してしまうと、なぞっている最中に画面ごとスクロールしてしまうため。
 */
private suspend fun AwaitPointerEventScope.dragUntilRelease(
    pointerId: PointerId,
    onMove: (PointerInputChange) -> Unit
) {
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == pointerId } ?: return
        if (change.changedToUpIgnoreConsumed()) return
        change.consume()
        onMove(change)
    }
}

/**
 * 指を置いたまま動かしたか、動かさずに離したかを判定する。
 *
 * 「動かさず一定時間が経った」（＝長押し）はこの関数だけでは分からない。
 * 呼び出し側で withTimeoutOrNull と組み合わせ、時間切れになったら長押しとして扱う。
 */
private suspend fun AwaitPointerEventScope.awaitSlopOrRelease(
    pointerId: PointerId,
    slopPx: Float,
    initialPosition: Offset
): DragOutcome {
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == pointerId } ?: return DragOutcome.Released
        if (!change.pressed) return DragOutcome.Released
        val dx = change.position.x - initialPosition.x
        val dy = change.position.y - initialPosition.y
        if (abs(dx) > slopPx || abs(dy) > slopPx) {
            return DragOutcome.Dragged(change)
        }
    }
}

/** [hitTestTrim] の結果。ダウン位置が何を掴んだと判定されたか */
internal sealed interface TrimGrab {
    data class Handle(val kind: TrimHandle) : TrimGrab
    data class Split(val index: Int) : TrimGrab
    data object Body : TrimGrab
}

/**
 * 指を置いた位置が、つまみ・分割ライン・本体のどれに最も近いかを判定する純粋関数。
 *
 * つまみと分割ラインのうち、いちばん近いものを探し、どちらの許容範囲にも
 * 入らなければ「本体」として扱う。副作用を持たないため、ドラッグ処理から
 * 独立してテスト・見通しができる。
 */
internal fun hitTestTrim(
    downX: Float,
    track: TrackMetrics,
    startMs: Long,
    endMs: Long,
    texts: List<TextSegment>,
    grabRadiusPx: Float
): TrimGrab {
    val startX = track.msToX(startMs)
    val endX = track.msToX(endMs)
    val distanceToStartHandle = abs(downX - startX)
    val distanceToEndHandle = abs(downX - endX)
    val handleKind = if (distanceToStartHandle <= distanceToEndHandle) {
        TrimHandle.Start
    } else {
        TrimHandle.End
    }
    val nearestHandleDistance = minOf(distanceToStartHandle, distanceToEndHandle)

    var nearestSplit: Int? = null
    var nearestSplitDist = Float.MAX_VALUE
    for (i in 1 until texts.size) {
        val splitX = track.msToX(texts[i].startMs)
        val dist = abs(downX - splitX)
        if (dist < nearestSplitDist) {
            nearestSplitDist = dist
            nearestSplit = i
        }
    }

    val grabbedHandle = nearestHandleDistance <= grabRadiusPx && nearestHandleDistance <= nearestSplitDist
    // valにしてwhenの分岐内でスマートキャストできるようにし、!!を使わずに済ませる
    val splitToGrab: Int? = nearestSplit.takeIf {
        !grabbedHandle && it != null && nearestSplitDist <= grabRadiusPx
    }

    return when {
        grabbedHandle -> TrimGrab.Handle(handleKind)
        splitToGrab != null -> TrimGrab.Split(splitToGrab)
        else -> TrimGrab.Body
    }
}

/**
 * 端のつまみをドラッグしている間、指の位置をトリム開始・終了位置へ変換して通知し続ける。
 *
 * [latestEnd]/[latestDuration] を [State] のまま受け取っているのは、ドラッグ中に
 * 外側から渡ってくる値（例えば他の変更でstartMs/endMsが変わる）を毎回読み直すため。
 * 呼び出し時点のLong値を渡してしまうと、掴んだ瞬間の値のまま固定されてしまう。
 *
 * [track]はジェスチャー開始時点のleft/right/handleHalfPxを固定値として使い回すが、
 * viewStartMs/viewEndMs（ズーム範囲）は[lockedViewportState]がパンで書き換わるたびに
 * 作り直す。つまみが今のビューポート端をはみ出したら[panViewportIfNeeded]で
 * ビューポート自体を追従させ、波形が指に付いてくるように見せる。
 */
internal suspend fun AwaitPointerEventScope.dragTrimHandle(
    downId: PointerId,
    handleKind: TrimHandle,
    grabOffset: Float,
    track: TrackMetrics,
    lockedViewportState: MutableState<LongRange?>,
    latestStart: State<Long>,
    latestEnd: State<Long>,
    latestDuration: State<Long>,
    edgeScrollZonePx: Float,
    isPinnedAtLeftEdgeState: MutableState<Boolean>,
    isPinnedAtRightEdgeState: MutableState<Boolean>,
    onTrimChange: (startMs: Long, endMs: Long, seekMs: Long) -> Unit
) {
    // coerceIn(min, max)はmin > maxだと例外を投げる。
    // durationMsがMIN_TRIM_MS未満の極端に短い動画では
    // 上限・下限が逆転しうるため、coerceAtLeast(0L)で
    // 「動かせる余地が無ければ現在地のまま」に倒す。
    dragUntilRelease(downId) { change ->
        val rawX = change.position.x - grabOffset
        val locked = lockedViewportState.value
        if (locked != null) {
            val extrapolated = TrackMetrics(track.left, track.right, locked.first, locked.last)
                .extrapolatedMs(rawX)
                .coerceIn(0L, latestDuration.value)
            panViewportIfNeeded(extrapolated, latestDuration.value, lockedViewportState)
        }
        val currentTrack = lockedViewportState.value?.let {
            TrackMetrics(track.left, track.right, it.first, it.last)
        } ?: track
        val ms = currentTrack.xToMs(rawX)
        val next = clampHandleMs(handleKind, ms, latestStart.value, latestEnd.value, latestDuration.value)
        when (handleKind) {
            TrimHandle.Start -> onTrimChange(next, latestEnd.value, next)
            TrimHandle.End -> onTrimChange(latestStart.value, next, next)
        }
        // 指を動かさなくても、つまみがビューポート端に張り付いている間は波形が
        // 連続でパンし続ける（実際に毎フレーム進める処理はComposable側の
        // LaunchedEffectが担う。ここではそのトリガーとなるフラグを立てるだけ）。
        isPinnedAtLeftEdgeState.value = rawX <= track.left + edgeScrollZonePx
        isPinnedAtRightEdgeState.value = rawX >= track.right - edgeScrollZonePx
    }
}

/** 分割ラインをドラッグしている間、指の位置を区切り位置へ変換して通知し続ける */
internal suspend fun AwaitPointerEventScope.dragSplitLine(
    downId: PointerId,
    index: Int,
    grabOffset: Float,
    track: TrackMetrics,
    onSplitMove: (index: Int, ms: Long) -> Unit
) {
    dragUntilRelease(downId) { change ->
        val ms = track.xToMs(change.position.x - grabOffset)
        onSplitMove(index, ms)
    }
}

/**
 * 波形本体を掴んだときの処理。すぐ動かせば従来通りなぞって頭出し（スクラブ）、
 * 動かさず長押ししてから動かせば区間ごと移動に切り替える。
 *
 * @param onScrubbingChange スクラブ（なぞって頭出し）を始めた時点で true を通知する。
 *   戻り値ではなくコールバックで即時に伝えるのは、ドラッグ中にジェスチャーごと
 *   キャンセルされるとこの関数は値を返さないまま抜けるため。戻り値方式だと
 *   呼び出し元のfinallyが [WaveformTrimmerCallbacks.onScrubEnd] を呼べず、
 *   指を離しても再生が再開しない状態が残る。
 */
internal suspend fun AwaitPointerEventScope.dragBodyOrMove(
    down: PointerInputChange,
    track: TrackMetrics,
    latestStart: State<Long>,
    latestEnd: State<Long>,
    latestDuration: State<Long>,
    lockedViewportState: MutableState<LongRange?>,
    viewConfiguration: ViewConfiguration,
    haptics: HapticFeedback,
    edgeScrollZonePx: Float,
    isPinnedAtLeftEdgeState: MutableState<Boolean>,
    isPinnedAtRightEdgeState: MutableState<Boolean>,
    onScrubbingChange: (Boolean) -> Unit,
    onMovingTrimChange: (Boolean) -> Unit,
    callbacks: WaveformTrimmerCallbacks
) {
    val outcome = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
        awaitSlopOrRelease(down.id, viewConfiguration.touchSlop, down.position)
    }

    when (outcome) {
        is DragOutcome.Dragged -> {
            // すぐ動いた＝なぞって頭出し（従来のシーク）
            onScrubbingChange(true)
            callbacks.onScrubStart()
            callbacks.onSeek(track.xToMs(outcome.change.position.x))
            dragUntilRelease(outcome.change.id) { change ->
                callbacks.onSeek(track.xToMs(change.position.x))
            }
        }

        DragOutcome.Released -> {
            // 動かさず離した＝タップ。その場へ頭出し
            callbacks.onSeek(track.xToMs(down.position.x))
        }

        null -> {
            // 動かさず一定時間経過＝長押し。まだ指が乗っていれば区間ごと移動へ切り替える
            val stillDown = currentEvent.changes.firstOrNull { it.id == down.id }?.pressed == true
            if (!stillDown) {
                callbacks.onSeek(track.xToMs(down.position.x))
            } else {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onMovingTrimChange(true)
                // 区間開始位置の今の画面上のxと、実際に指を置いた位置との差をgrabOffsetとして
                // 固定する（つまみのgrabOffsetと同じ考え方）。以後はこのオフセットと現在の
                // 指の位置・現在のビューポートだけから区間位置を求める。
                //
                // 以前は「掴んだ瞬間のstart位置＋指の移動量(px→ms換算)」という、タッチダウン
                // 時点を基準にした差分方式だったが、これは今のビューポート（オートスクロール
                // でパンされ続ける）を一切見ないため、オートスクロールが指の位置と無関係に
                // 区間を進め続けている間に指がわずかでも動く（実機のタッチ座標は完全静止して
                // いても微小に揺れる）と、タッチダウン基準の差分が「ほぼ元の位置」を指して
                // しまい、オートスクロールの進みを毎フレーム引き戻す→ガタつく、という
                // 不具合を起こしていた。dragTrimHandleと同じ方式に変えることで、
                // オートスクロールでビューポートが動くのと歩調を合わせて指が動かなくても
                // 一貫した位置が出るようにする。
                val grabOffset = down.position.x - track.msToX(latestStart.value)

                dragUntilRelease(down.id) { change ->
                    val rawX = change.position.x - grabOffset
                    val currentTrack = lockedViewportState.value?.let {
                        TrackMetrics(track.left, track.right, it.first, it.last)
                    } ?: track
                    val (newStart, newEnd) = computeMoveSpan(
                        currentTrack.extrapolatedMs(rawX), latestStart.value, latestEnd.value, latestDuration.value
                    )
                    panViewportIfNeeded(newStart, latestDuration.value, lockedViewportState)
                    panViewportIfNeeded(newEnd, latestDuration.value, lockedViewportState)
                    callbacks.onTrimMove(newStart, newStart)
                    // 指を動かさなくても、区間が端に張り付いている間は波形が連続で
                    // パンし続ける（実際に毎フレーム進める処理はComposable側の
                    // LaunchedEffectが担う。ここではそのトリガーとなるフラグを立てるだけ）。
                    // 区間ごと移動は左右どちらの端がビューポート外に張り付くか分からないので、
                    // 実際に描画される位置（パン後のトラックでmsToXした位置）で両方判定する
                    val pannedTrack = lockedViewportState.value?.let {
                        TrackMetrics(track.left, track.right, it.first, it.last)
                    } ?: track
                    isPinnedAtLeftEdgeState.value = pannedTrack.msToX(newStart) <= track.left + edgeScrollZonePx
                    isPinnedAtRightEdgeState.value = pannedTrack.msToX(newEnd) >= track.right - edgeScrollZonePx
                }
            }
        }
    }
}
