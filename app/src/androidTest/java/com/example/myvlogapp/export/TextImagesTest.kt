package com.example.myvlogapp.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import com.example.myvlogapp.HITOKOTO_FONT_PT
import com.example.myvlogapp.HITOKOTO_WRAP_WIDTH_PT
import com.example.myvlogapp.TIME_FONT_ASSET
import com.example.myvlogapp.TITLE_FONT_ASSET

/**
 * ひとこと・タイトルの文言を、Androidの描画で画像にする部分（TextImages.kt）。
 * 絵文字をカラーで描けるかは端末のフォント次第なので、JVMではなく端末で確かめる。
 */
@RunWith(AndroidJUnit4::class)
class TextImagesTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val workDir = File(context.cacheDir, "text_images_test").apply { mkdirs() }
    private val workFiles = mutableListOf<File>()
    private val renderer = AndroidTextRenderer(workDir, workFiles)

    private val fonts by lazy {
        val logo = copyFontAsset(context, TITLE_FONT_ASSET)
        val time = copyFontAsset(context, TIME_FONT_ASSET)
        ExportFonts(logo, time, 0f, 0f, 0f).let {
            it.copy(
                hitokotoBaselineShiftPt = baselineShiftPt(logo, 70f),
                titleBaselineShiftPt = baselineShiftPt(time, 50f)
            )
        }
    }

    @After
    fun cleanUp() {
        workDir.deleteRecursively()
    }

    private fun render(lines: List<String?>, style: TextStyleSpec = hitokotoStyle(fonts)): Bitmap {
        val image = renderer.render(lines, style, "test")!!
        return BitmapFactory.decodeFile(image.file.absolutePath)
    }

    @Test
    fun emojiIsDrawnInColor() {
        // drawtextでは消えていた。LogoTypeGothicに無い字は、端末の絵文字フォントで補ってカラーで描く
        val bitmap = render(listOf("😀"))

        assertTrue("色の付いた画素が無い", coloredPixels(bitmap) > 100)
    }

    @Test
    fun plainTextStaysWhite() {
        val bitmap = render(listOf("今日は晴れ"))

        assertTrue("文字が描かれていない", opaquePixels(bitmap) > 100)
        assertEquals(0, coloredPixels(bitmap))
    }

    @Test
    fun tallAndDeepGlyphsFitInsideTheStrip() {
        // 帯の上下の端の行に何か描かれていたら、余白が足りずに切れている
        listOf(hitokotoStyle(fonts), titleStyle(fonts)).forEach { style ->
            val bitmap = render(listOf("漢字😀👨‍👩‍👧🇯🇵gjpqy", "ÅÉ👍🏽gjpqy"), style)

            assertEquals("上端", 0, opaquePixelsInRow(bitmap, 0))
            assertEquals("下端", 0, opaquePixelsInRow(bitmap, bitmap.height - 1))
        }
    }

    @Test
    fun blankSpanMakesNoImage() {
        assertNull(renderer.render(listOf(null, null), hitokotoStyle(fonts), "blank"))
        assertTrue(workFiles.isEmpty())
    }

    @Test
    fun imageIsFullWidthAndRegisteredForCleanup() {
        val image = renderer.render(listOf("旅行"), hitokotoStyle(fonts), "cleanup")!!
        val bitmap = BitmapFactory.decodeFile(image.file.absolutePath)

        assertEquals(1920, bitmap.width)
        assertEquals(textStripLayout(1, hitokotoStyle(fonts))!!.height, bitmap.height)
        assertEquals(listOf(image.file), workFiles)
    }

    // --- 自動の折り返し ------------------------------------------------------------------

    private val longHitokoto = "今日は朝から海へ行って、みんなでバーベキューをしてから花火を見ました🎆"

    private fun wrapPaint() = Paint().apply {
        typeface = Typeface.createFromFile(fonts.logoType)
        textSize = HITOKOTO_FONT_PT
    }

    @Test
    fun aLongHitokotoIsWrappedWithinTheWidthAndKeepsEveryCharacter() {
        val wrapped = wrapLines(listOf(longHitokoto), wrapPaint(), HITOKOTO_WRAP_WIDTH_PT.toInt())

        assertTrue(wrapped.toString(), wrapped.size >= 2)
        wrapped.forEach { line ->
            assertTrue(line!!, wrapPaint().measureText(line) <= HITOKOTO_WRAP_WIDTH_PT)
        }
        // 折り返しで文字が消えたり増えたりしない（絵文字も割れない）
        assertEquals(longHitokoto, wrapped.joinToString(""))
    }

    @Test
    fun shortLinesAndBlankLinesAreLeftAsTheyAre() {
        assertEquals(
            listOf("上", null, "下"),
            wrapLines(listOf("上", null, "下"), wrapPaint(), HITOKOTO_WRAP_WIDTH_PT.toInt())
        )
    }

    @Test
    fun aWrappedHitokotoStaysClearOfTheShootingTime() {
        // 右端の撮影時刻に重ならないよう、左右に(1920-折り返し幅)/2ずつ空く（数pxは文字のにじみの余裕）
        val bitmap = render(listOf(longHitokoto))
        val side = ((1920 - HITOKOTO_WRAP_WIDTH_PT) / 2).toInt() - 4

        assertEquals(0, opaquePixelsInColumns(bitmap, 0 until side))
        assertEquals(0, opaquePixelsInColumns(bitmap, (1920 - side) until 1920))
        // 行が増えたぶん、帯も高くなる
        assertTrue(bitmap.height > render(listOf("旅行")).height)
    }

    private fun opaquePixelsInColumns(bitmap: Bitmap, columns: IntRange) =
        columns.sumOf { x -> (0 until bitmap.height).count { y -> Color.alpha(bitmap.getPixel(x, y)) > 0 } }

    private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    /** 白・灰色ではない（R,G,Bが大きく違う）、見えている画素の数 */
    private fun coloredPixels(bitmap: Bitmap) = pixels(bitmap).count { p ->
        val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
        Color.alpha(p) > 128 && maxOf(r, g, b) - minOf(r, g, b) > 60
    }

    private fun opaquePixels(bitmap: Bitmap) = pixels(bitmap).count { Color.alpha(it) > 0 }

    private fun opaquePixelsInRow(bitmap: Bitmap, y: Int) =
        (0 until bitmap.width).count { Color.alpha(bitmap.getPixel(it, y)) > 0 }
}
