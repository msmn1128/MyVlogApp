package com.example.myvlogapp // ← ご自身のパッケージ名に合わせて変更してください

import android.graphics.Rect as AndroidRect
import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

// =====================================================================================
// 波形トリマー（[WaveformTrimmer]）とその周辺一式。
//
// MainActivity.kt から切り出したもの。VlogClip/TextSegmentとCompose標準APIだけで
// 完結しており、画面全体の構成には依存しないため独立したファイルに置ける。
// 波形自体の見た目（[TimelineDivider]など操作バーの部品）や、ひとことの区切りバッジ
// （[splitMarkerColor]/[SegmentBadge]など）は他の画面でも使う共通部品なので、
// あちらは引き続き MainActivity.kt 側に残してある。
// =====================================================================================

/** トリミングつまみの幅 */
private val TRIM_HANDLE_WIDTH = 11.dp

/** つまみを掴んだと判定する距離。指の腹の太さを見込んで広めに取る */
private val TRIM_GRAB_RADIUS = 30.dp

/**
 * これ以上は詰められない長さ。0にできてしまうと書き出しが通らなくなる。
 * TrimSection（MainActivity.kt）でも「トリミング可能かどうか」の判定に使うため公開している。
 */
const val MIN_TRIM_MS = 300L

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
 * トリムつまみ／区間ごと移動をこの幅だけ端に寄せたまま指を動かさずに保持していると、
 * 波形が自動で連続スクロールし続ける（iOS版WaveformView.edgeScrollZoneと同じ値・考え方）。
 */
private val EDGE_SCROLL_ZONE = 24.dp

/** WaveformTrimmerDrawing.ktの描画コードからも参照するためinternal */
internal enum class TrimHandle { Start, End }

/**
 * [WaveformTrimmer] のコールバック群。
 * 状態を表す引数（waveform/texts/durationMsなど）と分けて1つにまとめることで、
 * シグネチャの引数の数を減らしている。
 */
data class WaveformTrimmerCallbacks(
    val onTrimChange: (Long, Long, Long) -> Unit,
    val onTrimMove: (Long, Long) -> Unit,
    val onSplitMove: (Int, Long) -> Unit,
    val onSeek: (Long) -> Unit,
    val onScrubStart: () -> Unit,
    val onScrubEnd: () -> Unit,
    // つまみ/分割線/本体のどれを掴んでいてもドラッグ中は必ず呼ばれる。
    // ドラッグ中だけSeekParametersを緩めてカクつきを減らすためのフック
    // （再生の一時停止／再開を伴うonScrubStart/onScrubEndとは別軸）。
    val onDragStart: () -> Unit,
    val onDragEnd: () -> Unit
)

/**
 * 波形そのものがトリミングバーを兼ねる。
 *
 * 別途スライダーを置いていた頃は、波形で位置を読んでから下のスライダーへ視線と指を
 * 移す必要があった。掴む場所と見る場所が同じなら、その往復が要らない。
 *
 * - 左右の端のつまみをドラッグ → 開始・終了位置を変更（掴んだ側の映像がプレビューに出る）
 * - 内側をなぞる / タップ → その位置へ頭出し（シークバーを兼ねる）
 *
 * つまみを掴んだかどうかは指を置いた瞬間に決まり、以後そのまま最後まで同じ役割で動く。
 * 途中で判定が切り替わると、トリミングのつもりが再生位置だけ動いた、という事故になる。
 *
 * つまみが画面端で切れないよう、トラックはつまみの半分ぶん内側に取ってある。
 */
@Composable
fun WaveformTrimmer(
    clipId: Long,
    waveform: Waveform?,
    isLoading: Boolean,
    texts: List<TextSegment>,
    durationMs: Long,
    startMs: Long,
    endMs: Long,
    positionMs: Long,
    enabled: Boolean,
    callbacks: WaveformTrimmerCallbacks,
    modifier: Modifier = Modifier
) {
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    val handleColor = MaterialTheme.colorScheme.primary
    val gripColor = MaterialTheme.colorScheme.onPrimary
    val playheadColor = MaterialTheme.colorScheme.tertiary
    val splitColor = splitMarkerColor()
    val splitLabelColor = onSplitMarkerColor()

    // 区切りの番号を描くのに使う。Canvasの中では作れないのでここで用意しておく
    val textMeasurer = rememberTextMeasurer()

    // ドラッグ中のつまみ・分割ライン。掴んでいる側を一回り大きく／太く描いて、
    // どれを動かしているのかを指の下からでも分かるようにする
    var activeHandle by remember { mutableStateOf<TrimHandle?>(null) }
    var activeSplitIndex by remember { mutableStateOf<Int?>(null) }
    var isMovingTrim by remember { mutableStateOf(false) }
    // 「いま操作中か」はこの3つのフラグだけで判定している。もしstartMs/endMsを
    // 動かす新しいジェスチャーを増やすときは、その間もこのどれかをtrueにしないと、
    // 下のLaunchedEffectが操作中と気付けずズームが指の下で動いてしまう。
    val isInteracting = activeHandle != null || activeSplitIndex != null || isMovingTrim

    // 波形の表示範囲（ズーム）。選択範囲を掴んで動かしている最中はここを据え置き、
    // 操作の区切り（プリセット適用・つまみを離した瞬間など）でだけ選択範囲に
    // フィットさせる。操作中にも追従させると、表示が動いて指の下から的がずれてしまう。
    //
    // 以前はLaunchedEffect(clipId, startMs, endMs, durationMs, isInteracting)で
    // 「操作中でなければ計算し直す」形にしていたが、これはコルーチンの起動・
    // キャンセルを経由するため、キー変化のタイミング次第で更新が1フレーム遅れたり
    // 取りこぼされたりする余地があった（iOS版で同種の設計が実際に「自動ズームが
    // 発動しないことがある」不具合を起こし、「操作中でなければ毎回計算し直す」
    // 方式へ作り直した実績がある）。ここでも同じ考え方で、操作中でなければ
    // 毎回のコンポジションでcomputedし直し、操作中だけSideEffectで値を据え置く
    // （SideEffectは非同期のLaunchedEffectと違い、コンポジションのたびに同期的に
    // 実行されるため、キー変化を取りこぼす余地がない）。
    // lockedViewportStateは生のMutableStateとして持っておき、dragTrimHandle/
    // dragBodyOrMoveなどトップレベルのジェスチャー関数からも直接読み書きできるようにする
    // （トリムつまみ・区間ごと移動が今のビューポート端に達したときにパンさせるため）。
    // Composable本体では従来通りlockedViewportとしてby委譲で扱う。
    val lockedViewportState = remember(clipId) { mutableStateOf<LongRange?>(null) }
    var lockedViewport by lockedViewportState
    val computedViewport = fitWaveformViewport(startMs, endMs, durationMs)
    val waveformViewport = if (isInteracting) (lockedViewport ?: computedViewport) else computedViewport
    SideEffect {
        lockedViewport = if (isInteracting) (lockedViewport ?: computedViewport) else null
    }
    val viewportState = rememberUpdatedState(waveformViewport)

    // pointerInputのラムダは長く生き続けるので、最新値はrememberUpdatedState経由で読む。
    // 直接キャプチャすると、ドラッグ中ずっと掴んだ瞬間の値を見続けてしまう。
    // State自体（startState等）はドラッグ処理を切り出したトップレベル関数に渡し、
    // そちらでも「ドラッグ中ずっと最新値を読み続ける」性質を保つのに使う。
    val startState = rememberUpdatedState(startMs)
    val endState = rememberUpdatedState(endMs)
    val durationState = rememberUpdatedState(durationMs)
    val callbacksState = rememberUpdatedState(callbacks)
    val latestStart by startState
    val latestEnd by endState
    val latestDuration by durationState
    val latestTexts by rememberUpdatedState(texts)
    // 6個のコールバックそれぞれをrememberUpdatedStateしていたのを、
    // データクラスであるcallbacks自体を1回rememberUpdatedStateする形に集約
    val latestCallbacks by callbacksState

    val density = LocalDensity.current
    val handleHalfPx = with(density) { TRIM_HANDLE_WIDTH.toPx() / 2f }
    val grabRadiusPx = with(density) { TRIM_GRAB_RADIUS.toPx() }
    val edgeScrollZonePx = with(density) { EDGE_SCROLL_ZONE.toPx() }
    val haptics = LocalHapticFeedback.current

    // つまみ／区間ごと移動がビューポート端に張り付いている間、指を動かさなくても
    // 波形を連続でパンさせ続けるための状態。AwaitPointerEventScopeは
    // @RestrictsSuspensionでcoroutineScope/launch/delayを直接呼べないため、
    // ジェスチャー側（dragTrimHandle/dragBodyOrMove）はこのフラグを立てるだけにし、
    // 実際に毎フレーム進める処理は下のLaunchedEffect（制限のない通常のコルーチン）で行う
    // （iOS版のTask+Task.sleepループに相当）。
    val isPinnedAtLeftEdgeState = remember(clipId) { mutableStateOf(false) }
    val isPinnedAtRightEdgeState = remember(clipId) { mutableStateOf(false) }
    LaunchedEffect(clipId) {
        while (true) {
            delay(16L)
            val pinnedLeft = isPinnedAtLeftEdgeState.value
            val pinnedRight = isPinnedAtRightEdgeState.value
            if (!pinnedLeft && !pinnedRight) continue
            val direction = if (pinnedLeft) -1L else 1L
            val tickMs = edgeScrollTickMs(lockedViewportState, latestDuration)
            when {
                activeHandle == TrimHandle.Start -> {
                    val newMs = (latestStart + direction * tickMs)
                        .coerceIn(0L, (latestEnd - MIN_TRIM_MS).coerceAtLeast(0L))
                    panViewportIfNeeded(newMs, latestDuration, lockedViewportState)
                    latestCallbacks.onTrimChange(newMs, latestEnd, newMs)
                }
                activeHandle == TrimHandle.End -> {
                    val newMs = (latestEnd + direction * tickMs).coerceIn(
                        (latestStart + MIN_TRIM_MS).coerceAtMost(latestDuration), latestDuration
                    )
                    panViewportIfNeeded(newMs, latestDuration, lockedViewportState)
                    latestCallbacks.onTrimChange(latestStart, newMs, newMs)
                }
                isMovingTrim -> {
                    val span = (latestEnd - latestStart).coerceAtLeast(0L)
                    val maxStart = (latestDuration - span).coerceAtLeast(0L)
                    val newStart = (latestStart + direction * tickMs).coerceIn(0L, maxStart)
                    val newEnd = newStart + span
                    panViewportIfNeeded(newStart, latestDuration, lockedViewportState)
                    panViewportIfNeeded(newEnd, latestDuration, lockedViewportState)
                    latestCallbacks.onTrimMove(newStart, newStart)
                }
            }
        }
    }

    // 左右どちらかのつまみが画面のヘリに近いと、掴んだつもりがOSの「戻る」スワイプに
    // 奪われる端末がある。波形トリマー全体（左端〜右端）をジェスチャー除外領域として
    // 申告し、この範囲では常に自前のタッチ処理を優先させる。選択中クリップが変わって
    // 表示が消えるときは除外を解除しないと、別の場所にまで戻るジェスチャーが効かなくなる。
    //
    // systemGestureExclusionRects はAPI 29以降にしか無い。minSdkは24なので、
    // 直に呼ぶと Android 9 以下で NoSuchMethodError で落ちる。
    // ジェスチャーナビゲーション自体がAPI 29からの機能なので、それ未満では何もしない。
    val view = LocalView.current
    val supportsGestureExclusion = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    DisposableEffect(view, supportsGestureExclusion) {
        onDispose {
            if (supportsGestureExclusion) view.systemGestureExclusionRects = emptyList()
        }
    }

    Box(
        modifier = modifier
            .onGloballyPositioned { coordinates ->
                if (!supportsGestureExclusion) return@onGloballyPositioned
                val bounds = coordinates.boundsInWindow()
                view.systemGestureExclusionRects = listOf(
                    AndroidRect(
                        bounds.left.toInt(),
                        bounds.top.toInt(),
                        bounds.right.toInt(),
                        bounds.bottom.toInt()
                    )
                )
            }
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput

                awaitEachGesture {
                    // 掴んでいる最中に指以外の理由でジェスチャーが打ち切られることがある
                    // （親の縦スクロールに主導権を奪われる、書き出し開始で enabled が
                    // 変わって pointerInput が作り直される、クリップの選択が変わって
                    // このコンポーザブルごと消えるなど）。
                    // その場合ここのコルーチンはキャンセルされて以降の行が実行されないため、
                    // 強調表示のフラグとスクラブ終了通知は必ず finally で戻す。
                    // 戻し忘れると、つまみが太ったまま固まる／指を離しても再生が再開しない、
                    // といった状態が画面に残り続ける。
                    var scrubbing = false
                    try {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        latestCallbacks.onDragStart()
                        val viewport = viewportState.value
                        val track = TrackMetrics.forWidth(
                            size.width.toFloat(), handleHalfPx, viewport.first, viewport.last
                        )

                        when (
                            val grab = hitTestTrim(
                                down.position.x, track, latestStart, latestEnd,
                                latestTexts, grabRadiusPx
                            )
                        ) {
                            // --- 端のつまみ：即ドラッグで伸縮 ---
                            is TrimGrab.Handle -> {
                                activeHandle = grab.kind
                                val startX = track.msToX(latestStart)
                                val endX = track.msToX(latestEnd)
                                val grabOffset =
                                    if (grab.kind == TrimHandle.Start) down.position.x - startX
                                    else down.position.x - endX

                                dragTrimHandle(
                                    down.id, grab.kind, grabOffset, track, lockedViewportState,
                                    startState, endState, durationState, edgeScrollZonePx,
                                    isPinnedAtLeftEdgeState, isPinnedAtRightEdgeState
                                ) { s, e, seek -> latestCallbacks.onTrimChange(s, e, seek) }
                            }

                            // --- 分割ライン：即ドラッグで移動 ---
                            is TrimGrab.Split -> {
                                activeSplitIndex = grab.index
                                val splitX = track.msToX(latestTexts[grab.index].startMs)
                                val grabOffset = down.position.x - splitX

                                dragSplitLine(
                                    down.id, grab.index, grabOffset, track
                                ) { index, ms -> latestCallbacks.onSplitMove(index, ms) }
                            }

                            // --- 本体：すぐ動かせば従来通りなぞって頭出し、
                            //     長押ししてから動かせば区間ごと移動 ---
                            TrimGrab.Body -> {
                                // scrubbingは「戻り値で受け取る」のでは駄目で、開始した時点で
                                // ここへ反映させる必要がある。ドラッグ中にキャンセルされると
                                // dragBodyOrMoveは値を返さないまま抜けるため、戻り値方式だと
                                // finallyのonScrubEnd()が呼ばれず、指を離しても再生が
                                // 再開しないまま固まってしまう。
                                dragBodyOrMove(
                                    down, track, startState, endState, durationState,
                                    lockedViewportState, viewConfiguration,
                                    haptics, edgeScrollZonePx,
                                    isPinnedAtLeftEdgeState, isPinnedAtRightEdgeState,
                                    onScrubbingChange = { scrubbing = it },
                                    onMovingTrimChange = { isMovingTrim = it },
                                    callbacks = latestCallbacks
                                )
                            }
                        }
                    } finally {
                        activeHandle = null
                        activeSplitIndex = null
                        isMovingTrim = false
                        isPinnedAtLeftEdgeState.value = false
                        isPinnedAtRightEdgeState.value = false
                        if (scrubbing) latestCallbacks.onScrubEnd()
                        latestCallbacks.onDragEnd()
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // つまみを掴んだ瞬間に太さが一段階で切り替わらないよう、太さそのものを補間する
        val startHandleScale by animateFloatAsState(
            targetValue = if (activeHandle == TrimHandle.Start) 1.35f else 1f,
            label = "startHandleScale"
        )
        val endHandleScale by animateFloatAsState(
            targetValue = if (activeHandle == TrimHandle.End) 1.35f else 1f,
            label = "endHandleScale"
        )
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawWaveformTrimmer(
                waveform = waveform,
                texts = texts,
                durationMs = durationMs,
                viewport = waveformViewport,
                startMs = startMs,
                endMs = endMs,
                positionMs = positionMs,
                handleHalfPx = handleHalfPx,
                startHandleScale = startHandleScale,
                endHandleScale = endHandleScale,
                activeSplitIndex = activeSplitIndex,
                isMovingTrim = isMovingTrim,
                textMeasurer = textMeasurer,
                colors = WaveformTrimmerColors(
                    active = activeColor,
                    inactive = inactiveColor,
                    handle = handleColor,
                    grip = gripColor,
                    playhead = playheadColor,
                    split = splitColor,
                    splitLabel = splitLabelColor
                )
            )
        }

        // 波形が出せないときの注記。つまみと桟は描いたままにしてあるので、
        // 読み込みが終わっていなくてもトリミングはできる。
        when {
            isLoading -> WaveformNote {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(6.dp))
                NoteText("波形を読み込み中…")
            }

            waveform == null -> WaveformNote { NoteText("波形を取得できませんでした") }

            !waveform.hasAudio -> WaveformNote { NoteText("音声なし") }
        }
    }
}

/** 波形に重ねる小さな注記の下地 */
@Composable
private fun WaveformNote(content: @Composable RowScope.() -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(
                MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.9f),
                RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
        content = content
    )
}

@Composable
private fun NoteText(text: String) {
    Text(text, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

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
private fun panViewportIfNeeded(ms: Long, durationMs: Long, lockedViewportState: MutableState<LongRange?>) {
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
private fun edgeScrollTickMs(lockedViewportState: MutableState<LongRange?>, durationMs: Long): Long {
    val span = lockedViewportState.value?.let { it.last - it.first } ?: durationMs
    return (span * 0.02).toLong().coerceAtLeast(1L)
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
private fun fitWaveformViewport(startMs: Long, endMs: Long, durationMs: Long): LongRange {
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
private sealed interface TrimGrab {
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
private fun hitTestTrim(
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
private suspend fun AwaitPointerEventScope.dragTrimHandle(
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
        when (handleKind) {
            TrimHandle.Start -> {
                val next = ms.coerceIn(0L, (latestEnd.value - MIN_TRIM_MS).coerceAtLeast(0L))
                onTrimChange(next, latestEnd.value, next)
            }
            TrimHandle.End -> {
                val next = ms.coerceIn(
                    (latestStart.value + MIN_TRIM_MS).coerceAtMost(latestDuration.value),
                    latestDuration.value
                )
                onTrimChange(latestStart.value, next, next)
            }
        }
        // 指を動かさなくても、つまみがビューポート端に張り付いている間は波形が
        // 連続でパンし続ける（実際に毎フレーム進める処理はComposable側の
        // LaunchedEffectが担う。ここではそのトリガーとなるフラグを立てるだけ）。
        isPinnedAtLeftEdgeState.value = rawX <= track.left + edgeScrollZonePx
        isPinnedAtRightEdgeState.value = rawX >= track.right - edgeScrollZonePx
    }
}

/** 分割ラインをドラッグしている間、指の位置を区切り位置へ変換して通知し続ける */
private suspend fun AwaitPointerEventScope.dragSplitLine(
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
private suspend fun AwaitPointerEventScope.dragBodyOrMove(
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
                val span = (latestEnd.value - latestStart.value).coerceAtLeast(0L)
                val maxStart = (latestDuration.value - span).coerceAtLeast(0L)
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
                    val newStart = currentTrack.extrapolatedMs(rawX).coerceIn(0L, maxStart)
                    val newEnd = newStart + span
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
