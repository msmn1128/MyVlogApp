package com.example.myvlogapp.waveform

import androidx.compose.ui.semantics.CustomAccessibilityAction
import java.util.Locale
import com.example.myvlogapp.TextSegment

// =====================================================================================
// 波形トリマー（[WaveformTrimmer]）を、TalkBackなどの読み上げで操作できるようにするもの。
//
// 波形は自前で描いた部品で、指で掴んで動かすことしかできない。読み上げの情報が何も無いと、
// TalkBackの利用者はトリミングも区切りの移動もできなかった。波形全体を1つの項目として、
// いまの状態を説明文で伝え、動かす操作をカスタムアクション（TalkBackのアクション一覧）として出す。
// 範囲の制約（最短の長さ・動画の長さ）は、指で動かすときと同じ関数を通して守る。
// =====================================================================================

/** 読み上げの操作で1回に動かす量 */
internal const val ACCESSIBILITY_STEP_MS = 500L

/**
 * 読み上げ用の説明文。例：「波形。使っている範囲 0:00.0〜0:06.0（6.0秒）。ひとことの区切り 2か所（0:02.0、0:04.0）」
 * 0.5秒ずつ動かせるので、秒は小数1桁まで読む（「m:ss」だと動かしても数字が変わらないことがある）。
 */
internal fun trimmerDescription(
    startMs: Long,
    endMs: Long,
    texts: List<TextSegment>,
    note: String?
): String = buildString {
    append("波形。使っている範囲 ${spokenTime(startMs)}〜${spokenTime(endMs)}")
    append("（${spokenSeconds(endMs - startMs)}秒）")
    val splits = texts.drop(1).map { it.startMs }
    if (splits.isNotEmpty()) {
        append("。ひとことの区切り ${splits.size}か所（${splits.joinToString("、") { spokenTime(it) }}）")
    }
    if (note != null) append("。$note")
}

/**
 * 読み上げのアクション一覧。開始・終わり・範囲ごと・各区切りを、前後へ[ACCESSIBILITY_STEP_MS]ずつ動かす。
 * 動かせない（すでに端にある）ものも一覧には出す。押しても何も変わらないだけで、
 * 一覧の並びが状態によって変わるより分かりやすいため。
 */
internal fun trimmerActions(
    startMs: Long,
    endMs: Long,
    durationMs: Long,
    texts: List<TextSegment>,
    callbacks: WaveformTrimmerCallbacks
): List<CustomAccessibilityAction> {
    val step = ACCESSIBILITY_STEP_MS
    val stepLabel = "${spokenSeconds(step)}秒"

    fun moveHandle(kind: TrimHandle, delta: Long): Boolean {
        val next = clampHandleMs(
            kind, (if (kind == TrimHandle.Start) startMs else endMs) + delta, startMs, endMs, durationMs
        )
        when (kind) {
            TrimHandle.Start -> callbacks.onTrimChange(next, endMs, next)
            TrimHandle.End -> callbacks.onTrimChange(startMs, next, next)
        }
        return true
    }

    fun moveRange(delta: Long): Boolean {
        val moved = computeMoveSpan(startMs + delta, startMs, endMs, durationMs)
        callbacks.onTrimMove(moved.newStart, moved.newStart)
        return true
    }

    return buildList {
        add(CustomAccessibilityAction("開始を${stepLabel}前へ") { moveHandle(TrimHandle.Start, -step) })
        add(CustomAccessibilityAction("開始を${stepLabel}後ろへ") { moveHandle(TrimHandle.Start, step) })
        add(CustomAccessibilityAction("終わりを${stepLabel}前へ") { moveHandle(TrimHandle.End, -step) })
        add(CustomAccessibilityAction("終わりを${stepLabel}後ろへ") { moveHandle(TrimHandle.End, step) })
        add(CustomAccessibilityAction("範囲ごと${stepLabel}前へ") { moveRange(-step) })
        add(CustomAccessibilityAction("範囲ごと${stepLabel}後ろへ") { moveRange(step) })
        // 区切りの移動範囲（前後の区切り・トリム範囲）は、受け取る側（TimelineStore.moveSplit）が守る
        for (index in 1 until texts.size) {
            val at = texts[index].startMs
            add(CustomAccessibilityAction("区切り${index}を${stepLabel}前へ") {
                callbacks.onSplitMove(index, at - step); true
            })
            add(CustomAccessibilityAction("区切り${index}を${stepLabel}後ろへ") {
                callbacks.onSplitMove(index, at + step); true
            })
        }
    }
}

/** 「1:05.5」のような読み上げ用の時刻。数字の字形が変わらないようLocale.USで組み立てる */
private fun spokenTime(ms: Long): String {
    val tenths = (ms + 50) / 100
    return String.format(Locale.US, "%d:%02d.%d", tenths / 600, tenths / 10 % 60, tenths % 10)
}

/** 「6.0」のような秒数（小数1桁） */
private fun spokenSeconds(ms: Long): String = String.format(Locale.US, "%.1f", ms / 1000.0)
