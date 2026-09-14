package com.example.myvlogapp // ← ご自身のパッケージ名に合わせて変更してください

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// =====================================================================================
// WaveformTrimmer.kt からの切り出し。[WaveformTrimmer] のCanvas描画部分（DrawScope拡張
// 関数群）だけをまとめたもの。ジェスチャー判定（ヒットテスト・ドラッグ処理）とは独立した
// 純粋な描画処理で、読み取り専用の引数だけを受け取るため、単独ファイルへ置ける。
// =====================================================================================

/** [drawWaveformTrimmer] で使う色一式。ジェスチャー判定と分けて描画専用にまとめてある */
internal data class WaveformTrimmerColors(
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
internal fun DrawScope.drawWaveformTrimmer(
    waveform: Waveform?,
    texts: List<TextSegment>,
    durationMs: Long,
    viewport: LongRange,
    startMs: Long,
    endMs: Long,
    positionMs: Long,
    handleHalfPx: Float,
    startHandleScale: Float,
    endHandleScale: Float,
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
        half = handleHalfPx * startHandleScale,
        fill = colors.handle,
        grip = colors.grip
    )
    drawTrimHandle(
        centerX = endX,
        half = handleHalfPx * endHandleScale,
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

/** 縦長の丸ピル＋中央の滑り止め2本。掴んでいる間は少しだけ太らせる */
private fun DrawScope.drawTrimHandle(
    centerX: Float,
    half: Float,
    fill: Color,
    grip: Color
) {
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
