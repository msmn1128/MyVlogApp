package com.example.myvlogapp.export

import com.example.myvlogapp.TextSegment
import com.example.myvlogapp.VlogClip
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

    // 音声トラックが無く、先頭から3秒だけ使うクリップ。ひとことは空のまま
    private val silentClip = testClip(id = 2, durationMs = 10_000L, startMs = 0L, endMs = 3_000L)

    private fun fonts(workDir: File) = ExportFonts(
        File(workDir, "logo.otf"), File(workDir, "time.ttf"),
        hitokotoBaselineShiftPt = 20f, timeBaselineShiftPt = 15f, titleBaselineShiftPt = 12f
    )

    /**
     * Androidの描画の代わり。本物と同じく、全行が空なら画像を作らず、帯の位置は[textStripLayout]で決める。
     * 実際にPNGは書かない（グラフの組み立てはファイルの中身を読まない）
     */
    private fun fakeRenderer(workDir: File) = TextRenderer { lines, style, name ->
        if (lines.all { it == null }) null
        else TextImage(File(workDir, "$name.png"), textStripLayout(lines.size, style)!!.top)
    }

    private fun buildGraph(
        clips: List<VlogClip> = listOf(splitClip, silentClip),
        includeTitle: Boolean = true,
        titleText: String = "2026/01/01",
        hdrTransfers: List<HdrTransfer?> = List(clips.size) { null },
        workDir: File = Files.createTempDirectory("vlog_graph_test").toFile()
    ): String = try {
        val audioPlan = AudioPlan(
            needsTitleSfxInput = includeTitle,
            clipHasRealAudio = clips.map { it.id != silentClip.id }
        )
        runBlocking {
            buildFilterGraph(
                clips, fonts(workDir), titleText, 667L, workDir, 1L, mutableListOf(),
                includeTitle = includeTitle, audioPlan = audioPlan,
                renderText = fakeRenderer(workDir), hdrTransfers = hdrTransfers
            )
        }
    } finally {
        workDir.deleteRecursively()
    }

    /** グラフのうち、[from]から[to]の手前まで（1クリップぶんの映像の組み立て） */
    private fun String.chain(from: String, to: String) = substringAfter(from).substringBefore(to)

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
    fun hitokotoSpans_areOverlaidAsImagesAtTheirStripPosition() {
        val graph = buildGraph()
        val splitChain = graph.chain("[1:v]", "[v0]")
        val top = textStripLayout(1, hitokotoStyle(fonts(File("/w"))))!!.top

        // 区間ごとに画像を読み、キャンバスへ順に重ねる（絵文字を描くため、drawtextは使わない）
        assertTrue(splitChain, splitChain.contains("/text_1_0_0.png'[t0_0]"))
        assertTrue(splitChain, splitChain.contains("[c0_0][t0_0]overlay=x=0:y=$top:eof_action=repeat:enable="))
        assertTrue(splitChain, splitChain.contains("/text_1_0_1.png'[t0_1]"))
        assertTrue(splitChain, splitChain.contains("[c0_1][t0_1]overlay=x=0:y=$top:eof_action=repeat:enable="))
        // 重ね終えた映像に撮影時刻を描く
        assertTrue(splitChain, splitChain.contains("[c0_2]drawtext="))
        assertFalse(splitChain, splitChain.contains("/text_1_0_0.txt"))
    }

    @Test
    fun emptyHitokoto_isNotOverlaid() {
        // 空の区間は画像を作らない（drawtextの頃に空行を描かなかったのと同じ）
        val silentChain = buildGraph().chain("[2:v]", "[v1]")

        assertFalse(silentChain, silentChain.contains("overlay="))
        assertFalse(silentChain, silentChain.contains("movie="))
        assertTrue(silentChain, silentChain.contains("[c1_0]drawtext="))
    }

    @Test
    fun singleSpanClip_hasNoEnable() {
        // 区間が1つだけのクリップは、画像をクリップの最後まで出すだけで enable を付けない
        val single = testClip(id = 3, texts = listOf(TextSegment(0L, "旅行")))
        val chain = buildGraph(clips = listOf(single), includeTitle = false).chain("[0:v]", "[v0]")

        assertTrue(chain, chain.contains("overlay="))
        assertFalse(chain, chain.contains("enable="))
    }

    @Test
    fun timeIsStillDrawnAsTextAlignedByBaseline() {
        val graph = buildGraph()

        // 撮影時刻は固定の英数字なのでdrawtextのまま。中央から15pt下にベースライン
        val timeLayer = graph.split("drawtext=").first { "/time_" in it }
        assertTrue(timeLayer, timeLayer.contains(":y=h/2+15-ascent"))
        assertTrue(timeLayer, timeLayer.contains(":expansion=none"))
    }

    @Test
    fun titleCard_overlaysTheTextImageAndFadesTheWholeCardToBlack() {
        val graph = buildGraph()
        val titleChain = graph.chain("[vtitlesrc]", "[vtitle]")
        val top = textStripLayout(1, titleStyle(fonts(File("/w"))))!!.top

        // 「Vlog.」は固定の英数字なのでdrawtextのまま。文字ごとのalphaはもう使わない
        assertTrue(titleChain, titleChain.contains("text='Vlog.'"))
        assertFalse(titleChain, titleChain.contains("alpha="))
        // 文言は画像にして重ねる（自由入力に絵文字が入りうるため）
        assertTrue(titleChain, titleChain.contains("/title_1.png'[ttitle]"))
        assertTrue(titleChain, titleChain.contains("[vtitle_logo][ttitle]overlay=x=0:y=$top:eof_action=repeat[vtitle_text]"))
        // 背景が黒なので、カード全体を黒へフェードすれば以前の文字のalphaと同じ見た目になる
        // （n=30で95%、n=49で0%。start_frameは「まだ100%のコマ」なので1つ手前の29）
        assertTrue(graph.contains("[vtitle_text]fade=t=out:start_frame=29:nb_frames=20[vtitle]"))
    }

    @Test
    fun onlyHdrClipsAreConvertedToSdrBeforeScalingAndText() {
        val graph = buildGraph(includeTitle = false, hdrTransfers = listOf(HdrTransfer.HLG, null))
        val hdrChain = graph.chain("[0:v]", "[v0]")
        val sdrChain = graph.chain("[1:v]", "[v1]")

        // HDR（HLG）のクリップは、先に出力の大きさまで縮めてからSDRへ変換し、そのあと文字を重ねる
        assertTrue(hdrChain, hdrChain.contains("zscale=tin=arib-std-b67"))
        assertTrue(hdrChain, hdrChain.indexOf("scale=1920:1080") < hdrChain.indexOf("zscale="))
        assertTrue(hdrChain, hdrChain.indexOf("tonemap=") < hdrChain.indexOf("overlay="))
        assertTrue(hdrChain, hdrChain.indexOf("tonemap=") < hdrChain.indexOf("drawtext="))
        // SDRのクリップは何もしない
        assertFalse(sdrChain, sdrChain.contains("zscale"))
        assertFalse(sdrChain, sdrChain.contains("tonemap"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun pathWithASingleQuote_isRefusedInsteadOfWritingABrokenGraph() {
        // 単引用符の中では単引用符を書けない。壊れたグラフをFFmpegに渡さず、組み立ての時点で止める
        buildGraph(workDir = Files.createTempDirectory("vlog'graph").toFile())
    }
}
