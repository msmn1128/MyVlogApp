package com.example.myvlogapp // ← ご自身のパッケージ名に合わせて変更してください

import android.graphics.Rect as AndroidRect
import android.os.Build
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
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
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
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

private enum class TrimHandle { Start, End }

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
    val onScrubEnd: () -> Unit
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
    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f)
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
    var waveformViewport by remember(clipId) {
        mutableStateOf(fitWaveformViewport(startMs, endMs, durationMs))
    }
    LaunchedEffect(clipId, startMs, endMs, durationMs, isInteracting) {
        if (!isInteracting) {
            waveformViewport = fitWaveformViewport(startMs, endMs, durationMs)
        }
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
    val haptics = LocalHapticFeedback.current

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
                                    down.id, grab.kind, grabOffset, track,
                                    startState, endState, durationState
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
                                    down, track, startState, viewConfiguration,
                                    haptics,
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
                        if (scrubbing) latestCallbacks.onScrubEnd()
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
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
                activeHandle = activeHandle,
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

/** [drawWaveformTrimmer] で使う色一式。ジェスチャー判定と分けて描画専用にまとめてある */
private data class WaveformTrimmerColors(
    val active: Color,
    val inactive: Color,
    val handle: Color,
    val grip: Color,
    val playhead: Color,
    val split: Color,
    val splitLabel: Color
)

/**
 * [WaveformTrimmer] のCanvas描画本体。
 *
 * ジェスチャー判定（[WaveformTrimmer] 内のpointerInputブロック）とは独立した
 * 純粋な描画処理なので、読み取り専用の引数だけを受け取る関数として切り出してある。
 */
private fun DrawScope.drawWaveformTrimmer(
    waveform: Waveform?,
    texts: List<TextSegment>,
    durationMs: Long,
    viewport: LongRange,
    startMs: Long,
    endMs: Long,
    positionMs: Long,
    handleHalfPx: Float,
    activeHandle: TrimHandle?,
    activeSplitIndex: Int?,
    isMovingTrim: Boolean,
    textMeasurer: TextMeasurer,
    colors: WaveformTrimmerColors
) {
    if (durationMs <= 0L) return
    val track = TrackMetrics.forWidth(size.width, handleHalfPx, viewport.first, viewport.last)
    val startX = track.msToX(startMs)
    val endX = track.msToX(endMs)

    drawWaveformBars(waveform, track, durationMs, startX, endX, colors)

    // 長押しで区間ごと移動している間は太くして、動かしていることを示す
    val railHeight = if (isMovingTrim) 5.dp.toPx() else 3.dp.toPx()
    drawTrimRails(startX, endX, railHeight, colors.active)

    // 動画は切っていないので、ひとことの切れ目は自分で描かないと分からない
    if (texts.size > 1) {
        drawSegmentSplits(texts, track, startMs, startX, activeSplitIndex, textMeasurer, colors)
    }

    if (positionMs in viewport.first..viewport.last && positionMs in startMs..endMs) {
        drawPlayhead(track.msToX(positionMs), railHeight, colors.playhead)
    }

    drawTrimHandle(
        centerX = startX,
        halfWidth = handleHalfPx,
        grown = activeHandle == TrimHandle.Start,
        fill = colors.handle,
        grip = colors.grip
    )
    drawTrimHandle(
        centerX = endX,
        halfWidth = handleHalfPx,
        grown = activeHandle == TrimHandle.End,
        fill = colors.handle,
        grip = colors.grip
    )
}

/** 波形の棒。読み込み中や無音では、つまめる範囲が分かるよう土台の線だけ引く */
private fun DrawScope.drawWaveformBars(
    waveform: Waveform?,
    track: TrackMetrics,
    durationMs: Long,
    startX: Float,
    endX: Float,
    colors: WaveformTrimmerColors
) {
    val centerY = size.height / 2f
    val amplitudes = waveform?.takeIf { it.hasAudio }?.amplitudes
    if (amplitudes != null && amplitudes.isNotEmpty()) {
        // 各バケットは「クリップ全体」の均等な時間幅を持つ。ズーム時は表示範囲が
        // クリップ全体より狭くなるので、バケットごとの中心時刻をmsToXで変換して
        // 実際の位置に描き直す（ズームしていないときは以前の等間隔配置と一致する）。
        val bucketMs = durationMs.toFloat() / amplitudes.size
        val barWidth = (bucketMs * track.pxPerMs * 0.68f).coerceAtLeast(1f)
        val minHalf = 0.75.dp.toPx()
        val maxHalf = (size.height / 2f - 10.dp.toPx()).coerceAtLeast(minHalf)

        amplitudes.forEachIndexed { index, amplitude ->
            val bucketCenterMs = ((index + 0.5f) * bucketMs).toLong()
            val center = track.msToX(bucketCenterMs)
            if (center < track.left - barWidth || center > track.right + barWidth) return@forEachIndexed
            val half = (amplitude * maxHalf).coerceAtLeast(minHalf)
            drawRoundRect(
                color = if (center in startX..endX) colors.active else colors.inactive,
                topLeft = Offset(center - barWidth / 2f, centerY - half),
                size = Size(barWidth, half * 2f),
                cornerRadius = CornerRadius(barWidth / 2f)
            )
        }
    } else {
        drawLine(colors.inactive, Offset(track.left, centerY), Offset(track.right, centerY), 1.dp.toPx())
    }
}

/** 選択範囲を囲う上下の桟 */
private fun DrawScope.drawTrimRails(startX: Float, endX: Float, railHeight: Float, color: Color) {
    val width = (endX - startX).coerceAtLeast(0f)
    drawRect(color = color, topLeft = Offset(startX, 0f), size = Size(width, railHeight))
    drawRect(color = color, topLeft = Offset(startX, size.height - railHeight), size = Size(width, railHeight))
}

/**
 * ひとことの区切り線と番号。
 * 波形（primary）と同系だが一段濃い紫にして、線と番号を同じ色で揃える。
 */
private fun DrawScope.drawSegmentSplits(
    texts: List<TextSegment>,
    track: TrackMetrics,
    startMs: Long,
    startX: Float,
    activeSplitIndex: Int?,
    textMeasurer: TextMeasurer,
    colors: WaveformTrimmerColors
) {
    // トリム開始位置で実際に表示される区間（トリミングで頭を落とすと、
    // それより手前の区切りは再生されない）。番号はここから描き始める
    val firstVisibleSegmentIndex = texts.indexOfLast { it.startMs <= startMs }.coerceAtLeast(0)

    texts.forEachIndexed { index, segment ->
        val splitX = track.msToX(segment.startMs)
        if (index > 0) {
            drawLine(
                color = colors.split,
                start = Offset(splitX, 0f),
                end = Offset(splitX, size.height),
                strokeWidth = if (index == activeSplitIndex) 4.5.dp.toPx() else 2.5.dp.toPx()
            )
        }
        // トリム範囲より手前の区切りは番号を出さない。表示されない文字だから。
        if (index < firstVisibleSegmentIndex) return@forEachIndexed

        // いま表示中の区間の番号は、実際の区切り位置ではなくトリム開始位置に
        // 追従させる。そうしないとトリムを動かしても左端に張り付いたままになる
        val anchorX = if (index == firstVisibleSegmentIndex) startX else splitX + 3.dp.toPx()
        drawSegmentNumber(
            measurer = textMeasurer,
            number = index + 1,
            anchorX = anchorX,
            fill = colors.split,
            label = colors.splitLabel
        )
    }
}

/** 再生ヘッド。上端の丸で「つまんで動かせる」ことを示す */
private fun DrawScope.drawPlayhead(playheadX: Float, railHeight: Float, color: Color) {
    drawLine(color, Offset(playheadX, railHeight), Offset(playheadX, size.height - railHeight), 2.dp.toPx())
    drawCircle(color = color, radius = 4.dp.toPx(), center = Offset(playheadX, railHeight + 4.dp.toPx()))
}

/**
 * 区間の頭に振る 1, 2, 3… の番号。
 *
 * 波形の棒に重なっても読めるよう、線と同じ紫の丸ピルを敷いてから文字を載せる。
 * 端に寄りすぎた番号は波形の中へ押し戻す（枠外へ出ると切れて読めなくなる）。
 */
private fun DrawScope.drawSegmentNumber(
    measurer: TextMeasurer,
    number: Int,
    anchorX: Float,
    fill: Color,
    label: Color
) {
    val layout = measurer.measure(
        text = number.toString(),
        style = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Bold)
    )
    val paddingX = 4.dp.toPx()
    val paddingY = 1.dp.toPx()
    val badgeWidth = layout.size.width + paddingX * 2f
    val badgeHeight = layout.size.height + paddingY * 2f
    val left = anchorX.coerceIn(0f, (size.width - badgeWidth).coerceAtLeast(0f))
    val top = 2.dp.toPx()

    drawRoundRect(
        color = fill,
        topLeft = Offset(left, top),
        size = Size(badgeWidth, badgeHeight),
        cornerRadius = CornerRadius(badgeHeight / 2f)
    )
    drawText(
        textLayoutResult = layout,
        color = label,
        topLeft = Offset(left + paddingX, top + paddingY)
    )
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
private class TrackMetrics(
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
 */
private suspend fun AwaitPointerEventScope.dragTrimHandle(
    downId: PointerId,
    handleKind: TrimHandle,
    grabOffset: Float,
    track: TrackMetrics,
    latestStart: State<Long>,
    latestEnd: State<Long>,
    latestDuration: State<Long>,
    onTrimChange: (startMs: Long, endMs: Long, seekMs: Long) -> Unit
) {
    dragUntilRelease(downId) { change ->
        val ms = track.xToMs(change.position.x - grabOffset)
        // coerceIn(min, max)はmin > maxだと例外を投げる。
        // durationMsがMIN_TRIM_MS未満の極端に短い動画では
        // 上限・下限が逆転しうるため、coerceAtLeast(0L)で
        // 「動かせる余地が無ければ現在地のまま」に倒す。
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
    viewConfiguration: ViewConfiguration,
    haptics: HapticFeedback,
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
                val originalStart = latestStart.value
                val pxPerMs = track.pxPerMs
                val anchorX = down.position.x

                dragUntilRelease(down.id) { change ->
                    val deltaMs = ((change.position.x - anchorX) / pxPerMs).toLong()
                    val targetStart = originalStart + deltaMs
                    callbacks.onTrimMove(targetStart, targetStart)
                }
            }
        }
    }
}

/** 縦長の丸ピル＋中央の滑り止め2本。掴んでいる間は少しだけ太らせる */
private fun DrawScope.drawTrimHandle(
    centerX: Float,
    halfWidth: Float,
    grown: Boolean,
    fill: Color,
    grip: Color
) {
    val half = if (grown) halfWidth * 1.35f else halfWidth
    drawRoundRect(
        color = fill,
        topLeft = Offset(centerX - half, 0f),
        size = Size(half * 2f, size.height),
        cornerRadius = CornerRadius(half)
    )

    val gripHalfHeight = size.height * 0.16f
    val gripGap = half * 0.42f
    val gripWidth = (half * 0.22f).coerceAtLeast(1f)
    listOf(-gripGap, gripGap).forEach { dx ->
        drawRoundRect(
            color = grip,
            topLeft = Offset(centerX + dx - gripWidth / 2f, size.height / 2f - gripHalfHeight),
            size = Size(gripWidth, gripHalfHeight * 2f),
            cornerRadius = CornerRadius(gripWidth / 2f)
        )
    }
}
