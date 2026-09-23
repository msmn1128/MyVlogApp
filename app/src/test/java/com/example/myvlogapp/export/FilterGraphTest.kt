package com.example.myvlogapp.export

import com.example.myvlogapp.TextSegment
import com.example.myvlogapp.testClip
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FilterGraphTest {

    // 素材上の4000〜9000msを使い、6000msでひとことを切り替えるクリップ
    private val splitClip = testClip(
        id = 1,
        durationMs = 20_000L,
        startMs = 4_000L,
        endMs = 9_000L,
        texts = listOf(TextSegment(0L, "前半"), TextSegment(6_000L, "後半")),
        timeText = "12:34"
    )

    // 音声トラックが無く、先頭から3秒だけ使うクリップ
    private val silentClip = testClip(id = 2, durationMs = 10_000L, startMs = 0L, endMs = 3_000L)

    private fun buildGraph(): String {
        val workDir = Files.createTempDirectory("vlog_graph_test").toFile()
        return try {
            val fonts = ExportFonts(
                File(workDir, "logo.otf"), File(workDir, "time.ttf"),
                hitokotoBaselineShiftPt = 20f, timeBaselineShiftPt = 15f, titleBaselineShiftPt = 12f
            )
            val audioPlan = AudioPlan(
                needsTitleSfxInput = true,
                clipHasRealAudio = listOf(true, false)
            )
            runBlocking {
                buildFilterGraph(
                    listOf(splitClip, silentClip), fonts, "2026/01/01", 667L, workDir, 1L,
                    mutableListOf(), includeTitle = true, audioPlan = audioPlan
                )
            }
        } finally {
            workDir.deleteRecursively()
        }
    }

    @Test
    fun clipInputArgs_seeksToStartLimitsDurationAndDecodesOnOneThread() {
        // -threads 1 はメモリを抑えるため（全クリップを同時に入力するので、デコーダの
        // スレッドごとのバッファが本数ぶん積み上がる）。-i の前に置いて、その入力のデコーダに効かせる
        assertEquals(
            listOf("-threads", "1", "-ss", "4.000", "-t", "5.000", "-i", "saf:1"),
            clipInputArgs(splitClip, "saf:1")
        )
        assertEquals(
            listOf("-threads", "1", "-ss", "0.000", "-t", "3.000", "-i", "saf:2"),
            clipInputArgs(silentClip, "saf:2")
        )
    }

    @Test
    fun clipChain_startsFromZeroAndTrimsByDurationOnly() {
        val graph = buildGraph()

        // 入力0は効果音なので、クリップは1番から
        assertTrue(graph.contains("[1:v]setpts=PTS-STARTPTS,trim=end=5.000,scale="))
        // 実音声は apad→atrim で映像と同じ尺に揃える。素材の音声は映像より
        // わずかに短いことがあり、その差がconcatのセグメントごとに積み上がって
        // 本数が多いほど後半の音がずれていくため
        assertTrue(
            graph.contains("[1:a]asetpts=PTS-STARTPTS,apad,atrim=end=5.000,asetpts=PTS-STARTPTS[a0]")
        )
        assertTrue(graph.contains("[2:v]setpts=PTS-STARTPTS,trim=end=3.000,scale="))
        // 音声の無いクリップは無音で埋める
        assertTrue(graph.contains("anullsrc=r=44100:cl=stereo:d=3.000[a1]"))

        // 開始位置での切り出しは入力側に任せたので、フィルタに残してはいけない
        assertFalse(graph.contains("trim=start="))
        // setpts は各クリップの先頭で行うだけで、末尾には残っていない
        assertFalse(graph.contains(",setpts=PTS-STARTPTS[v"))
    }

    @Test
    fun enableWindows_areRelativeToTheClipStart() {
        val graph = buildGraph()

        // 素材上 4000〜6000 = クリップ先頭から 0〜2秒（次の区間と重ならないよう1ms手前）
        assertTrue(graph.contains(":enable='between(t,0.000,1.999)'"))
        // 素材上 6000〜9000 = クリップ先頭から 2〜5秒（最後の区間は終端まで）
        assertTrue(graph.contains(":enable='between(t,2.000,5.000)'"))
        // 素材の絶対時刻のままの窓が残っていない
        assertFalse(graph.contains("between(t,4.000"))
        assertFalse(graph.contains("between(t,6.000"))
    }

    @Test
    fun hitokotoLines_areAlignedByBaselineNotByTheirOwnHeight() {
        val graph = buildGraph()
        val splitChain = graph.substringAfter("[1:v]").substringBefore("[v0]")
        val hitokotoLayers = splitChain.split(",drawtext=").filter { "/text_" in it }

        // 1行だけの区間は、中央から（ascentとdescentの差の半分＝20pt）下にベースラインを置く。
        // text_h（その行の文字の実際の高さ）で中央を出すと、文字の中身で縦位置が変わる
        assertEquals(2, hitokotoLayers.size)
        hitokotoLayers.forEach { layer ->
            assertTrue(layer, layer.contains(":y=h/2+20-ascent"))
            assertFalse(layer, layer.contains("text_h"))
        }
    }

    @Test
    fun timeAndTitleLines_areAlsoAlignedByBaseline() {
        val graph = buildGraph()

        // 撮影時刻は中央から15pt下にベースライン
        val timeLayer = graph.split(",drawtext=").first { "/time_" in it }
        assertTrue(timeLayer, timeLayer.contains(":y=h/2+15-ascent"))
        // タイトルの文言は、1行目の位置（中央から80pt下）にベースラインまでの12ptを足す
        val titleLayer = graph.split(",drawtext=").first { "/title_" in it }
        assertTrue(titleLayer, titleLayer.contains(":y=h/2+92-ascent"))
        // 「Vlog.」は文言が固定なので text_h 基準のまま
        assertTrue(graph.contains("text='Vlog.'"))
    }

    @Test
    fun singleSpanClip_hasNoEnable() {
        // 区間が1つだけのクリップ（silentClip）のdrawtextにはenableを付けない
        val graph = buildGraph()
        val silentChain = graph.substringAfter("[2:v]").substringBefore("[v1]")
        assertFalse(silentChain.contains("enable="))
    }
}
