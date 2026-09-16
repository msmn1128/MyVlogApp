package com.example.myvlogapp

// =====================================================================================
// 定数
//
// VlogModels.kt から分離。書き出しキャンバスのサイズやフォント設定など、
// UI側(MainActivity)とExporter側(VlogExporter)の両方から参照される値をここに集約する。
// =====================================================================================

const val CANVAS_WIDTH = 1920
const val CANVAS_HEIGHT = 1080
const val CANVAS_FPS = 30

/**
 * assets内のフォント置き場（assets/fonts/ 以下）。
 * MainActivity（プレビュー表示）とVlogExporter（書き出し）の両方が同じフォントを
 * 読み込むため、ディレクトリ名をここに集約して片方だけ変わる事故を防ぐ。
 */
const val FONT_ASSET_DIR = "fonts"

/** タイトル「Vlog.」／ ひとこと 用フォント */
const val TITLE_FONT_ASSET = "LogoTypeGothic.otf"

/** 撮影時刻／日付 用フォント */
const val TIME_FONT_ASSET = "MPLUSU-Regular.ttf"

/** タイトルカードの長さ（ミリ秒）。黒背景の尺・フェードのタイミング・効果音の切り詰め先すべての基準 */
const val TITLE_DURATION_MS = 2000L

/** タイトルカードの効果音（assets/sfx/ 以下のファイル名） */
const val TITLE_SFX_ASSET = "title.mp3"

/**
 * 効果音を鳴らすフレーム番号（1始まり）。
 * 例えば21なら、動画の21フレーム目（0始まりのnで数えるとn=20）から効果音が始まる。
 * CANVAS_FPSが30の場合、20/30秒 ≒ 約667ms地点。
 */
const val TITLE_SFX_FRAME_NUMBER = 21

// --- フォントサイズ（1920x1080キャンバス上のpt） -------------------------------------
// UI側とExporter側で数値が散らばると片方だけ変えたときに食い違うため、
// ここ1箇所に集約して両方から参照する。

const val HITOKOTO_FONT_PT = 70f      // ひとこと
const val HITOKOTO_LINE_SPACING_PT = 10f  // ひとことの行間
const val TIME_FONT_PT = 60f          // 撮影時刻
const val TITLE_FONT_PT = 150f        // タイトルカードの「Vlog.」
const val TITLE_DATE_FONT_PT = 50f    // タイトルカードの日付
const val TITLE_DATE_LINE_SPACING_PT = 10f // タイトルカードの文言が複数行になったときの行間

// タイトルカードの縦位置。画面中央からのずれ（マイナスが上、プラスが下）
const val TITLE_Y_OFFSET_PT = -70f
const val TITLE_DATE_Y_OFFSET_PT = 80f

/** 撮影時刻の右余白（キャンバス上のpt） */
const val TIME_MARGIN_PT = 40f

/**
 * プレビューのみに掛かる文字の拡大率。
 * 1.0f が書き出し結果と同じ見え方。編集中に読みづらい場合は 1.2f〜1.5f に上げる。
 * 書き出される動画は変わらない。
 */
const val PREVIEW_FONT_SCALE = 1.0f

const val LOG_TAG = "VlogApp"

/** ひとことの初期値。区間を分割したときの後半にもこれが入る */
const val DEFAULT_HITOKOTO = "ひとこと"

/** これ以上は詰められないひとこと区間の長さ。短すぎる区間は読む前に消えてしまう */
const val MIN_TEXT_SEGMENT_MS = 400L

/**
 * 再生位置の監視間隔（ミリ秒）。MainActivity側のポーリングループと
 * VlogViewModel側のKDocコメントの両方から参照し、値がズレないようにする。
 */
const val PLAYBACK_POLL_INTERVAL_MS = 80L

// --- 波形上での「区切り線に近い」判定(VlogClip.splitPointNear) -------------------------
// 長い動画ほど波形1px当たりの時間が長くなるため、許容幅を尺に比例させて求める。
// 尺をこの値で割った上で、下限・上限でクランプする。

const val SPLIT_TOLERANCE_DIVISOR = 40
const val SPLIT_TOLERANCE_MIN_MS = 200L
const val SPLIT_TOLERANCE_MAX_MS = 1500L
