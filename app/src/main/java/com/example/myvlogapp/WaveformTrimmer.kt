package com.example.myvlogapp

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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

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
 * トリムつまみ／区間ごと移動をこの幅だけ端に寄せたまま指を動かさずに保持していると、
 * 波形が自動で連続スクロールし続ける（iOS版WaveformView.edgeScrollZoneと同じ値・考え方）。
 */
private val EDGE_SCROLL_ZONE = 24.dp

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
                    val newMs = clampHandleMs(
                        TrimHandle.Start, latestStart + direction * tickMs, latestStart, latestEnd, latestDuration
                    )
                    panViewportIfNeeded(newMs, latestDuration, lockedViewportState)
                    latestCallbacks.onTrimChange(newMs, latestEnd, newMs)
                }
                activeHandle == TrimHandle.End -> {
                    val newMs = clampHandleMs(
                        TrimHandle.End, latestEnd + direction * tickMs, latestStart, latestEnd, latestDuration
                    )
                    panViewportIfNeeded(newMs, latestDuration, lockedViewportState)
                    latestCallbacks.onTrimChange(latestStart, newMs, newMs)
                }
                isMovingTrim -> {
                    val (newStart, newEnd) = computeMoveSpan(
                        latestStart + direction * tickMs, latestStart, latestEnd, latestDuration
                    )
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

