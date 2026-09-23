package com.example.myvlogapp.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ひとこと・タイトルの文言を画像にするときの、行の分け方と帯の置き場所（TextImages.kt）。
 * 描画そのもの（絵文字がカラーで描けるか）は、端末で動く TextImagesTest が確かめる。
 */
class TextImagesLayoutTest {

    /** ひとことと同じ置き方（70pt、行送り80pt、中央）。ベースラインは行の中心から20pt下 */
    private val hitokoto = TextStyleSpec(File("logo.otf"), 70f, 80f, LineAnchor.CENTERED, 0f, 20f)

    /** タイトルの文言と同じ置き方（50pt、行送り60pt、1行目を中央から80pt下に固定）。ベースラインまで12pt */
    private val title = TextStyleSpec(File("time.ttf"), 50f, 60f, LineAnchor.TOP, 80f, 12f)

    /** 帯の中のベースラインを、キャンバス上の位置に直したもの */
    private fun TextStripLayout.absoluteBaselines() = baselines.map { top + it }

    // --- 行の分け方 -------------------------------------------------------------------

    @Test
    fun hitokotoSplitsOnEveryLineBreakKindWithoutLeavingCarriageReturns() {
        assertEquals(
            listOf("一行目", "二行目", "三行目", "四行目"),
            hitokotoLines("一行目\r\n二行目\r三行目\n四行目")
        )
    }

    @Test
    fun blankHitokotoLinesKeepTheirSlotButAreNotDrawn() {
        val lines = hitokotoLines("上\r\n\r\n下")

        assertEquals(3, lines.size)
        assertNull(lines[1])
    }

    @Test
    fun titleSplitsOnEveryLineBreakKindAndDropsBlankLines() {
        assertEquals(listOf("2026/09/22", "旅行"), titleLines("2026/09/22\r\n\r\n旅行"))
    }

    // --- 帯の置き場所 -----------------------------------------------------------------

    @Test
    fun singleLine_hasItsBaselineWhereDrawtextPutIt() {
        // drawtextの頃の y=h/2+20-ascent と同じ、キャンバスの中央から20px下
        val layout = textStripLayout(1, hitokoto)!!

        assertEquals(listOf(560), layout.absoluteBaselines())
    }

    @Test
    fun hitokotoLines_areCenteredAsABlock() {
        // 2行なら行送り80の半分ずつ上下へ（-40, +40）、それぞれにベースラインまでの20を足す
        val layout = textStripLayout(2, hitokoto)!!

        assertEquals(listOf(520, 600), layout.absoluteBaselines())
    }

    @Test
    fun titleLines_keepTheFirstLineInPlaceAndStackDownward() {
        // 1行目は中央から80+12=92下。2行目以降は行送り60ずつ下へ
        assertEquals(listOf(632), textStripLayout(1, title)!!.absoluteBaselines())
        assertEquals(listOf(632, 692), textStripLayout(2, title)!!.absoluteBaselines())
    }

    @Test
    fun baselineIsRoundedTheSameWayAsDrawtext() {
        // drawtextの頃は roundToInt（0.5は切り上げ）で整数にしていた
        val layout = textStripLayout(1, hitokoto.copy(baselineShiftPt = 20.5f))!!

        assertEquals(listOf(561), layout.absoluteBaselines())
    }

    @Test
    fun strip_leavesRoomForEmojiAboveAndBelowTheBaseline() {
        // 絵文字は和文より背が高い（端末の絵文字フォントで上に約0.95、下に約0.25）
        val layout = textStripLayout(1, hitokoto)!!
        val baseline = layout.baselines.single()

        assertTrue(layout.toString(), baseline >= 70)
        assertTrue(layout.toString(), layout.height - baseline >= 70 / 4)
    }

    @Test
    fun strip_isPlacedOnEvenRowsWithAnEvenHeight() {
        // 映像（yuv420）は色の情報を縦2画素で1つ持つので、奇数の位置に重ねると色の境目がにじむ
        listOf(hitokoto, title, hitokoto.copy(baselineShiftPt = 21f)).forEach { style ->
            (1..3).forEach { lines ->
                val layout = textStripLayout(lines, style)!!
                assertEquals(layout.toString(), 0, layout.top % 2)
                assertEquals(layout.toString(), 0, layout.height % 2)
            }
        }
    }

    @Test
    fun strip_staysInsideTheCanvas() {
        // 行数が多いときは、キャンバスからはみ出す分だけ帯を切る（はみ出した行は今と同じく映らない）
        val layout = textStripLayout(30, hitokoto)!!

        assertEquals(0, layout.top)
        assertEquals(1080, layout.height)
    }

    @Test
    fun nothingToDraw_hasNoStrip() {
        assertNull(textStripLayout(0, hitokoto))
        // キャンバスの外にしか行が無い
        assertNull(textStripLayout(1, hitokoto.copy(offsetPt = 2_000f)))
    }
}
