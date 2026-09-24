package com.example.myvlogapp.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import java.io.File
import kotlin.math.ceil
import kotlin.math.roundToInt
import com.example.myvlogapp.CANVAS_HEIGHT
import com.example.myvlogapp.CANVAS_WIDTH
import com.example.myvlogapp.HITOKOTO_FONT_PT
import com.example.myvlogapp.HITOKOTO_LINE_SPACING_PT
import com.example.myvlogapp.TITLE_DATE_FONT_PT
import com.example.myvlogapp.TITLE_DATE_LINE_SPACING_PT
import com.example.myvlogapp.TITLE_DATE_Y_OFFSET_PT

// =====================================================================================
// ひとことと、タイトルカードの文言を画像にする。
//
// 以前はFFmpegのdrawtextで焼き込んでいたが、絵文字が書き出しから消えていた。drawtextは
// - 指定した1つのフォントしか使えず、LogoTypeGothicに無い字を端末の絵文字フォントで補えない
// - 端末の絵文字フォント（カラーの画像でできた形式）を描けない
// - 👨‍👩‍👧・👍🏽・国旗のような、複数の文字を組み合わせる絵文字を組み立てられない（同梱の6.0）
// ため。Androidの文字の描画（プレビューのComposeと同じ仕組み）なら3つとも済むので、
// 文字はここで透明なPNGに描き、FFmpegはそれを重ねるだけにする（FilterGraph.kt）。
//
// 位置（行ごとのベースライン・中央揃え・行間）は、drawtextの頃とまったく同じ数値で決める。
// 撮影時刻と「Vlog.」は固定の英数字で絵文字が入らないので、drawtextのまま。
// =====================================================================================

/**
 * 帯の上下に取る余白（文字の大きさに対する比率）。ベースラインから上へ、下へ。
 * 絵文字は和文より背が高く（端末の絵文字フォントで上に約0.95、下に約0.25）、これを割ると
 * 帯の端で切れる。
 */
private const val STRIP_ABOVE_BASELINE_EM = 1.2f
private const val STRIP_BELOW_BASELINE_EM = 0.6f

/** 複数行を縦に積むときのy方向オフセット（pt）の求め方 */
internal enum class LineAnchor {
    /** 行の集まり全体を中央に置く（ひとこと用） */
    CENTERED,

    /** 1行目の位置を固定し、以降を下に積む（タイトルカード用） */
    TOP
}

/** [count]行ぶんの縦オフセット（pt）を、行送り[lineHeight]・[anchor]に従って計算する */
internal fun lineOffsets(count: Int, lineHeight: Float, anchor: LineAnchor): List<Float> =
    when (anchor) {
        LineAnchor.CENTERED ->
            (0 until count).map { ((it - (count - 1) / 2.0) * lineHeight).toFloat() }
        LineAnchor.TOP -> (0 until count).map { it * lineHeight }
    }

/**
 * ひとことの行。空行はnull（描かずに位置だけ残す）。
 *
 * split("\n")ではなくlines()で分ける（\r\n・\rでも区切る）。Composeのプレビューは
 * 単独の\rを改行にしないので、PreviewPaneも同じ区切り方に揃えてある。
 */
internal fun hitokotoLines(text: String): List<String?> = text.lines().map { it.takeUnless(String::isBlank) }

/**
 * タイトルカードの文言の行。空行は詰める（ひとことと違って区間ごとに出し分けることが無く、
 * 空行のぶんだけ間隔を空けておく理由が無いため）。
 */
internal fun titleLines(text: String): List<String> = text.lines().filter { it.isNotBlank() }

/**
 * 文字の見た目と置き方。
 *
 * @param offsetPt 画面中央からのずれ。[LineAnchor.CENTERED]なら行の集まりの中心、
 *   [LineAnchor.TOP]なら1行目の位置
 * @param baselineShiftPt 行の中心からベースラインまで（[baselineShiftPt]で測った値）
 */
internal data class TextStyleSpec(
    val font: File,
    val sizePt: Float,
    val lineHeightPt: Float,
    val anchor: LineAnchor,
    val offsetPt: Float,
    val baselineShiftPt: Float
)

/** ひとこと：[ExportFonts.logoType]、上下左右中央 */
internal fun hitokotoStyle(fonts: ExportFonts) = TextStyleSpec(
    font = fonts.logoType,
    sizePt = HITOKOTO_FONT_PT,
    lineHeightPt = HITOKOTO_FONT_PT + HITOKOTO_LINE_SPACING_PT,
    anchor = LineAnchor.CENTERED,
    offsetPt = 0f,
    baselineShiftPt = fonts.hitokotoBaselineShiftPt
)

/**
 * タイトルカードの文言：[ExportFonts.time]、中央やや下。2行目以降になっても1行目の位置は
 * 動かさず、下へ積む（中央揃えでブロックごと動かすと、行数次第で1行目の位置がずれるため）
 */
internal fun titleStyle(fonts: ExportFonts) = TextStyleSpec(
    font = fonts.time,
    sizePt = TITLE_DATE_FONT_PT,
    lineHeightPt = TITLE_DATE_FONT_PT + TITLE_DATE_LINE_SPACING_PT,
    anchor = LineAnchor.TOP,
    offsetPt = TITLE_DATE_Y_OFFSET_PT,
    baselineShiftPt = fonts.titleBaselineShiftPt
)

/**
 * 帯の置き場所と、帯の中での各行のベースライン。
 * @param top キャンバス上の帯の上端（px）
 * @param baselines 各行（空行も含む）のベースラインの、帯の上端からの位置（px）
 */
internal data class TextStripLayout(val top: Int, val height: Int, val baselines: List<Int>)

/**
 * 横1920 × 必要な高さだけの帯の位置を決める。全画面の画像にしないのは、区切り1つに最大10本×
 * 区間数ぶんの画像がメモリに載るため（全画面だと1枚約8MB、帯なら1〜2MB）。
 *
 * 各行のベースラインは、drawtextの頃の `y=h/2±N-ascent`（N＝オフセット＋ベースラインまでの距離を
 * 四捨五入）と同じ位置。帯がキャンバスから完全に外れるときは null。
 */
internal fun textStripLayout(lineCount: Int, style: TextStyleSpec): TextStripLayout? {
    if (lineCount <= 0) return null
    val offsets = lineOffsets(lineCount, style.lineHeightPt, style.anchor)
    val absolute = offsets.map { CANVAS_HEIGHT / 2 + (style.offsetPt + it + style.baselineShiftPt).roundToInt() }

    val top = evenFloor(absolute.min() - ceil(style.sizePt * STRIP_ABOVE_BASELINE_EM).toInt()).coerceAtLeast(0)
    val bottom = evenCeil(absolute.max() + ceil(style.sizePt * STRIP_BELOW_BASELINE_EM).toInt())
        .coerceAtMost(CANVAS_HEIGHT)
    if (bottom <= top) return null
    return TextStripLayout(top = top, height = bottom - top, baselines = absolute.map { it - top })
}

// 帯の位置と高さは偶数にそろえる。映像（yuv420）は色の情報を縦2画素で1つ持つので、
// 奇数の位置に重ねると色の境目が1画素ずれてにじむ
private fun evenFloor(value: Int) = Math.floorDiv(value, 2) * 2
private fun evenCeil(value: Int) = evenFloor(value + 1)

/** 文字を描いた帯の画像と、キャンバス上の置き場所（上端のy） */
internal data class TextImage(val file: File, val top: Int)

/**
 * 行を帯の画像にする。全行が空なら null（重ねない）。
 * [name]は拡張子を除いたファイル名（書き出しの中で重ならないもの）。
 *
 * FilterGraphはこれを引数で受け取る。JVMの単体テストではAndroidの描画を使えないので、偽物を渡す。
 */
internal fun interface TextRenderer {
    fun render(lines: List<String?>, style: TextStyleSpec, name: String): TextImage?
}

/**
 * 本物の[TextRenderer]。作ったPNGは[workFiles]へ積む（書き出しのfinallyでまとめて消すため）。
 *
 * 書体は`Typeface.createFromFile`で作る。こうして作った書体は、無い字を端末のフォント
 * （絵文字フォントを含む）で補うので、絵文字もプレビューと同じくカラーで描かれる。
 */
internal class AndroidTextRenderer(
    private val workDir: File,
    private val workFiles: MutableList<File>
) : TextRenderer {

    private val typefaces = HashMap<File, Typeface>()

    override fun render(lines: List<String?>, style: TextStyleSpec, name: String): TextImage? {
        if (lines.all { it == null }) return null
        val layout = textStripLayout(lines.size, style) ?: return null

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            typeface = typefaces.getOrPut(style.font) { Typeface.createFromFile(style.font) }
            // drawtextのfontsizeと同じく、文字の大きさ（em）をpxで指定する（baselineShiftPtと同じ前提）
            textSize = style.sizePt
            color = Color.WHITE
            // はみ出す長い行は、drawtextの頃と同じく左右が均等に切れる
            textAlign = Paint.Align.CENTER
        }
        val bitmap = createBitmap(CANVAS_WIDTH, layout.height)
        try {
            val canvas = Canvas(bitmap)
            lines.forEachIndexed { index, line ->
                if (line != null) {
                    canvas.drawText(line, CANVAS_WIDTH / 2f, layout.baselines[index].toFloat(), paint)
                }
            }
            val file = File(workDir, "$name.png").also { workFiles += it }
            val written = file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!written) throw VlogExportException("文字の画像を作れませんでした")
            return TextImage(file, layout.top)
        } finally {
            bitmap.recycle()
        }
    }
}
