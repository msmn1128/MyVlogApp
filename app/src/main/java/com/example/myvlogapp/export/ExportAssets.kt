package com.example.myvlogapp.export

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import java.io.File
import kotlin.math.roundToLong
import com.example.myvlogapp.CANVAS_FPS
import com.example.myvlogapp.FONT_ASSET_DIR
import com.example.myvlogapp.LOG_TAG
import com.example.myvlogapp.TITLE_SFX_FRAME_NUMBER

// =====================================================================================
// 書き出しに使う素材（フォント・効果音）の展開。
//
// VlogExporter.kt から切り出したもの。FFmpegはassetsを直接読めないため、
// 素材を内部ストレージへ展開してからパスで渡す。
// FONT_ASSET_DIRはVlogConstants.ktで定義（MainActivity側のプレビュー表示と共有するため）。
// =====================================================================================

private const val SFX_ASSET_DIR = "sfx"

/**
 * タイトルカード・各クリップ両方で使うフォント一式。
 *
 * @param hitokotoBaselineShiftPt ひとことの行の中心からベースラインまでの距離（[baselineShiftPt]）。
 *   drawtextはフォントの指標を式から読めないので、書き出しの前にAndroid側で測って渡す
 */
internal data class ExportFonts(
    val logoType: File,
    val time: File,
    val hitokotoBaselineShiftPt: Float
)

/**
 * 行の中心から、ベースラインまで下へ何ptあるか（フォントのascent/descentから求める）。
 *
 * Composeのプレビューは、1行の箱の中でフォントのascent〜descentを上下中央に置く
 * （LineHeightStyle.Alignment.Center）。書き出しでもその行の中心から同じ距離に
 * ベースラインを置けば、文字の中身に関係なく同じ高さに並ぶ。
 * FreeType（drawtext）もAndroidも、文字サイズをem＝[sizePt]pxとして扱うので値は揃う。
 */
internal fun baselineShiftPt(fontFile: File, sizePt: Float): Float {
    val metrics = Paint().apply {
        typeface = Typeface.createFromFile(fontFile)
        textSize = sizePt
    }.fontMetrics
    // ascentは上向きが負の値。中心 = (ascent + descent) / 2 の位置なので、そこからベースラインまで
    return -(metrics.ascent + metrics.descent) / 2f
}

/**
 * FFmpegはassetsを直接読めないため、素材を内部ストレージへ展開する。
 *
 * 毎回上書きするのが重要。「すでに在れば使い回す」にすると、assetsの素材を
 * 差し替えても端末に残った古い実体が使われ続け、意図した素材にならない。
 * 数MB程度のコピーはエンコード時間に比べれば無視できる。
 */
private fun copyAsset(context: Context, assetPath: String, destDir: String, destName: String): File {
    val dir = File(context.filesDir, destDir).apply { mkdirs() }
    val file = File(dir, destName)
    try {
        context.assets.open(assetPath).use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
    } catch (e: Exception) {
        // 例外そのものはユーザーに見せても意味が無いが、
        // 「配置したはずなのに読めない」ときの原因追跡にはログが要る
        Log.w(LOG_TAG, "アセットを展開できませんでした: assets/$assetPath", e)
        throw VlogExportException("素材を読み込めません: assets/$assetPath を配置してください")
    }
    // 実際に使われた素材の大きさをログに残す。
    // assets側のファイルサイズと一致していれば取り違えは起きていない。
    Log.i(LOG_TAG, "アセット展開: $assetPath / ${file.length()} bytes")
    return file
}

internal fun copyFontAsset(context: Context, assetName: String): File =
    copyAsset(context, "$FONT_ASSET_DIR/$assetName", FONT_ASSET_DIR, assetName)

internal fun copySfxAsset(context: Context, assetName: String): File =
    copyAsset(context, "$SFX_ASSET_DIR/$assetName", SFX_ASSET_DIR, assetName)

/**
 * タイトルカードの効果音を鳴らし始めるタイミング（ミリ秒）。
 * [TITLE_SFX_FRAME_NUMBER] は1始まりのフレーム番号なので、0始まりのnに直してから
 * 経過時間へ変換する。
 */
internal fun titleSfxDelayMs(): Long {
    val n = (TITLE_SFX_FRAME_NUMBER - 1).coerceAtLeast(0)
    return (n * 1000.0 / CANVAS_FPS).roundToLong()
}
