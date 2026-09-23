package com.example.myvlogapp

import androidx.compose.ui.unit.dp

// =====================================================================================
// 画面だけで使う寸法と配分。
//
// 書き出しと共有する数値（キャンバスの大きさ・文字の大きさなど）は VlogConstants.kt に置く。
// ここにあるのはdp（Composeの画面の単位）や画面の配分で、書き出し側からは使わない。
// 1箇所に集めておくのは、MainActivity・PreviewSection・TimelineSection・ToolbarButtons が
// 同じ値を使っていて、散らすと片方だけ変えて見た目が揃わなくなるため。
// =====================================================================================

/**
 * 操作バーのボタン1個の大きさ。Materialの推奨に合わせて48dp。
 * 横幅の狭い端末ではみ出す分は、操作バー自体の横スクロールで吸収する。
 */
internal val TOOLBAR_BUTTON_SIZE = 48.dp

/** 操作バーのアイコンサイズ */
internal val TOOLBAR_ICON_SIZE = 20.dp

/** セクション間の余白。プレビュー/タイムライン/ひとこと欄の区切りで共通に使う */
internal val SECTION_GAP = 12.dp

/**
 * これより縦に短い画面では、キーボードを出している間タイムラインを畳む（VlogAppScreen）。
 * MaterialのウィンドウサイズクラスでcompactにあたるHeightの境目。
 */
internal val COMPACT_HEIGHT = 480.dp

// タイムラインの欄を広げるとき（TimelineFit.kt）に、削る側へ残す比率の下限。
// 縦1カラムのプレビューは全体の2割、横2ペインのひとこと欄は右ペイン（タイムライン0.40＋ひとこと0.20）の2割
internal const val MIN_PREVIEW_WEIGHT = 0.20f
internal const val MIN_WIDE_EDITOR_WEIGHT = 0.12f

/** 波形の高さ。つまみを指で掴める大きさが要るので、表示だけだった頃より厚くしてある */
internal val WAVEFORM_HEIGHT = 76.dp

// トリミングつまみの寸法・ズームの余白など、波形トリマー固有の定数は
// WaveformTrimmer.kt側に集約してある（MIN_TRIM_MSだけはTrimSectionでも使うため公開）。
