package com.example.myvlogapp.ui.screens

import androidx.compose.runtime.Stable
import kotlinx.coroutines.flow.StateFlow
import com.example.myvlogapp.waveform.SelectedWaveform
import com.example.myvlogapp.waveform.WaveformTrimmerCallbacks

// =====================================================================================
// タイムライン（[EditSection]以下）が VlogViewModel の代わりに受け取るもの。
//
// 画面からViewModelへの直接の依存を無くしつつ、再コンポーズの範囲は今までどおりに保つ。
// 状態を「値」まで上へ上げてしまうと、undo可否が変わったり波形が1本届いたりするたびに
// 画面全体が再コンポーズされる（それが長らくViewModelを直接渡していた理由だった）。
// そこで状態は StateFlow のまま渡し、collect は実際に値を使う葉のComposableに残す。
// 再生位置（positionMs: State<Long>）で以前から使っていた手を、他の状態にも広げただけ。
// =====================================================================================

/**
 * タイムラインが読む状態。
 *
 * `@Stable` を付けるのは、Composeに「同じインスタンスなら勝手に中身が入れ替わらない」と
 * 伝えるため。付けないとこの型はunstable扱いになり、受け取るComposableが毎コンポーズで
 * スキップできなくなって、狙いと逆に再コンポーズが増える。
 */
@Stable
class TimelineState(
    val canUndo: StateFlow<Boolean>,
    val canRedo: StateFlow<Boolean>,
    val autoAdvance: StateFlow<Boolean>,
    val timelineMuted: StateFlow<Boolean>,
    val selectedWaveform: StateFlow<SelectedWaveform>
)

/**
 * タイムラインからの操作。
 *
 * 呼び出し側（VlogAppScreen）で `remember(viewModel)` して1度だけ作ること。
 * 毎コンポーズで作り直すと、インスタンスが変わるたびに下のComposableが
 * 全部再コンポーズされる（`@Stable`にした意味が無くなる）。
 */
@Stable
class TimelineActions(
    val select: (index: Int) -> Unit,
    val removeSelected: () -> Unit,
    val removeAll: () -> Unit,
    val moveSelected: (offset: Int) -> Unit,
    val toggleClipMute: (clipId: Long) -> Unit,
    val setTimelineMuted: (muted: Boolean) -> Unit,
    val setAutoAdvance: (enabled: Boolean) -> Unit,
    val undo: () -> Unit,
    val redo: () -> Unit,
    val applyTrimPreset: (lengthMs: Long) -> Unit,
    val splitTextAtPlayhead: () -> Unit,
    val removeSplit: (atMs: Long) -> Unit,
    val updateText: (text: String) -> Unit,
    /** 波形トリマーのコールバック束。既存の[WaveformTrimmerCallbacks]をそのまま持つ */
    val trimmer: WaveformTrimmerCallbacks
)
