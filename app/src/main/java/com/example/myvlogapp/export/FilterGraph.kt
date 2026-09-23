package com.example.myvlogapp.export

import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import com.example.myvlogapp.CANVAS_FPS
import com.example.myvlogapp.CANVAS_HEIGHT
import com.example.myvlogapp.CANVAS_WIDTH
import com.example.myvlogapp.HITOKOTO_FONT_PT
import com.example.myvlogapp.HITOKOTO_LINE_SPACING_PT
import com.example.myvlogapp.TIME_FONT_PT
import com.example.myvlogapp.TIME_MARGIN_PT
import com.example.myvlogapp.TITLE_DATE_FONT_PT
import com.example.myvlogapp.TITLE_DATE_LINE_SPACING_PT
import com.example.myvlogapp.TITLE_DATE_Y_OFFSET_PT
import com.example.myvlogapp.TITLE_DURATION_MS
import com.example.myvlogapp.TITLE_FONT_PT
import com.example.myvlogapp.TITLE_Y_OFFSET_PT
import com.example.myvlogapp.VlogClip

// =====================================================================================
// FFmpegのフィルタグラフ構築。
//
// VlogExporter.kt から切り出したもの。書き出しの地雷はほぼすべてこのファイルに集まる
// （-ss/-tの位置、apad→atrim、setsar=1、expansion=none、Locale.US固定）。
// どれも実機で踏んだ不具合の記録なので、消す前に理由がまだ有効か確かめること。
// =====================================================================================

// --- タイトルカードのフェード -------------------------------------------------------
// buildTitleFilterのalpha式で使う。nは0始まりのフレーム番号。

/** フェードアウトを開始するフレーム番号(0始まり) */
private const val FADE_START_FRAME = 30

/** フェードアウトにかけるフレーム数 */
private const val FADE_FRAME_COUNT = 20

/**
 * タイトルカード・全クリップ・結合をまとめた1本のfilter_complex文字列を組み立てる。
 *
 * @param textFiles 生成した行ごとのテキストファイルをここへ積む（呼び出し元がexport()の
 *   finallyでまとめて掃除するため）
 */
internal suspend fun buildFilterGraph(
    clips: List<VlogClip>,
    fonts: ExportFonts,
    titleText: String,
    sfxDelayMs: Long,
    workDir: File,
    id: Long,
    textFiles: MutableList<File>,
    includeTitle: Boolean,
    audioPlan: AudioPlan
): String {
    val graph = mutableListOf<String>()

    if (includeTitle) {
        // --- タイトルカード（黒背景 / TITLE_DURATION_MSぶんの尺 /
        //     FADE_START_FRAME〜FADE_START_FRAME+FADE_FRAME_COUNT-1フレーム目でフェードアウト /
        //     TITLE_SFX_FRAME_NUMBERフレーム目から効果音） ---
        graph += "color=c=black:s=${CANVAS_WIDTH}x$CANVAS_HEIGHT:r=$CANVAS_FPS" +
                ":d=${ffmpegSeconds(TITLE_DURATION_MS)}[vtitlesrc]"
        val titleLines = writeTitleTextFiles(workDir, id, titleText, textFiles)
        graph += "[vtitlesrc]${buildTitleFilter(titleLines, fonts)}[vtitle]"
        graph += if (audioPlan.needsTitleSfxInput) {
            // apadは終端を指定しないと無音を無限に継ぎ足し続ける。
            // 「動画(タイトルの尺)の方が短いから-shortestで自動的に切られるはず」と
            // 考えて頼ると、実機では音声側が先に何時間ぶんもの無音を吐き出そうとして
            // 書き出しが実質ハングする。atrimでタイトルの尺ぴったりに強制的に切ることで、
            // -shortestに頼らず必ず有限時間で終わるようにする。
            "[0:a]adelay=$sfxDelayMs|$sfxDelayMs,apad," +
                    "atrim=0:${ffmpegSeconds(TITLE_DURATION_MS)},asetpts=PTS-STARTPTS[atitle]"
        } else {
            // タイムラインミュート中は効果音の入力自体が無いので、
            // タイトルの尺ぴったりの無音を生成して音声トラックを埋める。
            "anullsrc=r=$AUDIO_SAMPLE_RATE:cl=$AUDIO_CHANNEL_LAYOUT" +
                    ":d=${ffmpegSeconds(TITLE_DURATION_MS)}[atitle]"
        }
    }

    // --- 各クリップ：トリミング → 1920x1080整形 → テロップ焼き込み ---
    clips.forEachIndexed { index, clip ->
        coroutineContext.ensureActive()
        val inputIndex = index + audioPlan.clipInputOffset
        val durationSec = ffmpegSeconds(clip.trimmedDurationMs)
        val spans = writeSpanTextFiles(workDir, id, index, clip, textFiles)
        val timeFile = writeTimeTextFile(workDir, id, index, clip, textFiles)

        // 入力側（[clipInputArgs]）で既に開始位置へシークして長さも絞ってあるので、
        // ここでは時刻を0始まりに直し、trim=endで長さを保証するだけにする。
        // setpts=PTS-STARTPTSを最初に行うため、以降のdrawtextのtは
        // 「クリップ先頭からの経過時間」になる（enable式はそれを前提にしている）。
        graph += "[$inputIndex:v]setpts=PTS-STARTPTS,${trimFilter(durationSec, audio = false)}," +
                "${buildClipFilter(spans, clip.startMs, timeFile, fonts)}[${vTag(index)}]"

        // concatは各セグメントの音声ストリームを明示参照するため、
        // 音声トラックの無い素材でも無音を生成して必ず音声を持たせる。
        //
        // 実音声には apad → atrim を掛けて、映像と寸分違わぬ長さに揃える。素材の
        // 音声トラックは映像より数十ms短いことがよくあり（エンコーダの都合）、
        // その差はconcatのセグメントごとに積み上がって、本数が多いほど後半の音が
        // 前へずれていく。apadで足りない分を無音で埋め、atrimで必ず尺ぴったりに切る
        // （apadは終端を指定しないと無限に無音を継ぎ足すので、atrimと必ず対で使う）。
        graph += if (audioPlan.hasRealAudio(index)) {
            "[$inputIndex:a]asetpts=PTS-STARTPTS,apad," +
                    "${trimFilter(durationSec, audio = true)},asetpts=PTS-STARTPTS" +
                    "[${aTag(index)}]"
        } else {
            "anullsrc=r=$AUDIO_SAMPLE_RATE:cl=$AUDIO_CHANNEL_LAYOUT" +
                    ":d=${ffmpegSeconds(clip.trimmedDurationMs)}[${aTag(index)}]"
        }
    }

    // --- 結合 ---
    // 30fps CFRへの変換は、クリップ個別ではなく結合後の連続した映像に対して
    // 1回だけかける。素材の実フレームレートのばらつきによる複製フレームが
    // 全体に薄く分散され、特定の継ぎ目に集中しなくなる。
    val segmentLabels = buildString {
        if (includeTitle) append("[vtitle][atitle]")
        clips.indices.forEach { append("[${vTag(it)}][${aTag(it)}]") }
    }
    val segmentCount = clips.size + if (includeTitle) 1 else 0
    graph += "${segmentLabels}concat=n=$segmentCount:v=1:a=1[vraw][aout]"
    graph += "[vraw]fps=$CANVAS_FPS[vout]"

    return graph.joinToString(";")
}

/** 結合グラフ内での各クリップの映像/音声ラベル名 */
private fun vTag(index: Int) = "v$index"
private fun aTag(index: Int) = "a$index"

/**
 * 素材から必要な区間だけを読む入力引数（1クリップぶん）。
 *
 * トリミングをフィルタの`trim`だけで行うと、FFmpegは素材の先頭から開始位置までを
 * 全部デコードしてから捨てるため、素材の後ろの方を切り出すほど遅くなる。
 * `-i`の前に`-ss`/`-t`を置く入力側のシークなら、開始位置の手前まで読み飛ばせる
 * （トランスコード時は-ssもフレーム単位で正確）。
 *
 * `-threads 1`（この入力のデコードを1スレッドにする）は、メモリを抑えるため。全クリップを
 * 同時に入力するので、デコーダのスレッドごとのフレームバッファが本数ぶん積み上がる。
 * 実測（4GBのエミュレータ、1080p・100本）で、既定だと約5.1GBで強制終了、1スレッドだと約2.3GBで完走した。
 */
internal fun clipInputArgs(clip: VlogClip, source: String): List<String> = listOf(
    "-threads", "1",
    "-ss", ffmpegSeconds(clip.startMs),
    "-t", ffmpegSeconds(clip.trimmedDurationMs),
    "-i", source
)

/**
 * 先頭からの長さで切るtrim/atrimフィルタ。映像と音声で名前が違うだけで形は同じ。
 * 開始位置は入力側のシーク（[clipInputArgs]）で済んでいるので、ここでは終端だけ。
 */
private fun trimFilter(durationSec: String, audio: Boolean): String {
    val name = if (audio) "atrim" else "trim"
    return "$name=end=$durationSec"
}

/**
 * タイトルカードのフィルタ。
 * - 「Vlog.」 [fonts].logoType、[TITLE_FONT_PT]、中央やや上
 * - タイトル文言（既定は撮影日 "yyyy/MM/dd"） [fonts].time、[TITLE_DATE_FONT_PT]、中央やや下。
 *   2行目以降になっても1行目のy座標（[TITLE_DATE_Y_OFFSET_PT]）は動かさず、
 *   下へ[TITLE_DATE_FONT_PT]+[TITLE_DATE_LINE_SPACING_PT]ずつ積む
 *   （中央揃えでブロックごと動かすと自由入力の行数次第で1行目の位置がずれてしまうため）。
 * - [FADE_START_FRAME]フレーム目からフェードアウト開始（nは0始まり）
 *
 * alpha式はシングルクォートで囲まれているため、内部のカンマを
 * バックスラッシュでエスケープしてはいけない（数式が壊れる）。
 */
private fun buildTitleFilter(titleLines: List<File>, fonts: ExportFonts): String {
    val fadeEndFrame = FADE_START_FRAME + FADE_FRAME_COUNT - 1
    val alpha = "if(lt(n,$FADE_START_FRAME),1," +
            "if(between(n,$FADE_START_FRAME,$fadeEndFrame)," +
            "1-(n-${FADE_START_FRAME - 1})/$FADE_FRAME_COUNT,0))"
    val lineHeight = TITLE_DATE_FONT_PT + TITLE_DATE_LINE_SPACING_PT
    val offsets = lineOffsets(titleLines.size, lineHeight, LineAnchor.TOP)
    val logoLayer = drawText(
        fontfile = fonts.logoType,
        fontsizePt = TITLE_FONT_PT,
        x = centeredX(),
        y = centeredY(TITLE_Y_OFFSET_PT),
        text = "Vlog.",
        alpha = alpha
    )
    val titleLayers = titleLines.mapIndexed { lineIndex, file ->
        drawText(
            fontfile = fonts.time,
            fontsizePt = TITLE_DATE_FONT_PT,
            x = centeredX(),
            y = centeredY(TITLE_DATE_Y_OFFSET_PT + offsets[lineIndex]),
            textFile = file,
            alpha = alpha
        )
    }
    return (listOf(logoLayer) + titleLayers).joinToString(",")
}

/** 複数行のdrawtextを縦に積むときのy方向オフセット（pt）の求め方 */
private enum class LineAnchor {
    /** 行の集まり全体を中央に置く（ひとこと用） */
    CENTERED,

    /** 1行目の位置を固定し、以降を下に積む（タイトルカード用） */
    TOP
}

/** [count]行ぶんの縦オフセット（pt）を、行送り[lineHeight]・[anchor]に従って計算する */
private fun lineOffsets(count: Int, lineHeight: Float, anchor: LineAnchor): List<Float> =
    when (anchor) {
        LineAnchor.CENTERED ->
            (0 until count).map { ((it - (count - 1) / 2.0) * lineHeight).toFloat() }
        LineAnchor.TOP -> (0 until count).map { it * lineHeight }
    }

/**
 * 1クリップのフィルタ。
 * - 1920x1080キャンバスに歪みなしで配置（余白は黒帯）、30fps
 * - ひとこと：[fonts].logoType、[HITOKOTO_FONT_PT]、上下左右中央
 *   1行につき1つのdrawtextを積む（このFFmpegビルドにはtext_alignが無いため、
 *   1つのdrawtextに複数行を渡すと左揃えになってしまう）。
 *   縦位置は行ごとの文字の高さ（text_h）ではなくベースラインで揃える（[baselineY]）
 * - 撮影時刻：[fonts].time、[TIME_FONT_PT]、キャンバス右端に配置（縦横問わず同じ位置）
 *
 * @param spans ひとことの区間と、その各行のテキストファイル。
 *   空行はnull（描かずに間隔だけ空ける）。区間が2つ以上ある場合は enable で出し分ける。
 * @param clipStartMs トリミングの開始位置（素材上の絶対位置）。区間の位置はこの絶対位置で
 *   持っているので、enable式で「クリップ先頭からの経過時間」へ直すのに使う
 * @param timeFile 撮影時刻を書き出したテキストファイル（[writeTimeTextFile]）
 */
private fun buildClipFilter(
    spans: List<SpanLines>,
    clipStartMs: Long,
    timeFile: File,
    fonts: ExportFonts
): String {
    // 行の高さぶんだけ上下にずらして、行の集まり全体が画面中央に来るようにする
    val lineHeight = HITOKOTO_FONT_PT + HITOKOTO_LINE_SPACING_PT
    val hitokotoLayers = spans.flatMapIndexed { spanIndex, (span, lineFiles) ->
        // 区間が1つだけなら enable は付けない（式の評価ぶんだけ無駄になる）
        val enable = if (spans.size <= 1) "" else {
            // enable式のtは、入力側のシークとsetpts=PTS-STARTPTSで0始まりになった
            // 「クリップ先頭からの経過時間」。区間のstartMs/endMsは素材上の絶対位置なので、
            // トリミング開始位置を引いて合わせる（引き忘れると判定窓がずれて、
            // ひとことが出なくなる）。
            val from = span.startMs - clipStartMs
            // between は両端を含むので、隣の区間と1ms重ならないよう手前で切る。
            // 重なるとその1フレームだけ前後の文字が二重に焼き付いてしまう。
            val isLast = spanIndex == spans.lastIndex
            val to = (span.endMs - clipStartMs - if (isLast) 0L else 1L).coerceAtLeast(from)
            ":enable='between(t,${ffmpegSeconds(from)},${ffmpegSeconds(to)})'"
        }
        val offsets = lineOffsets(lineFiles.size, lineHeight, LineAnchor.CENTERED)
        lineFiles.mapIndexedNotNull { lineIndex, file ->
            if (file == null) return@mapIndexedNotNull null
            drawText(
                fontfile = fonts.logoType,
                fontsizePt = HITOKOTO_FONT_PT,
                x = centeredX(),
                y = baselineY(offsets[lineIndex] + fonts.hitokotoBaselineShiftPt),
                textFile = file,
                enable = enable
            )
        }
    }

    return buildList {
        add("scale=$CANVAS_WIDTH:$CANVAS_HEIGHT:force_original_aspect_ratio=decrease")
        add("pad=$CANVAS_WIDTH:$CANVAS_HEIGHT:(ow-iw)/2:(oh-ih)/2:black")
        addAll(hitokotoLayers)
        add(
            drawText(
                fontfile = fonts.time,
                fontsizePt = TIME_FONT_PT,
                x = "$CANVAS_WIDTH-text_w-${TIME_MARGIN_PT.toInt()}",
                y = centeredY(0f),
                textFile = timeFile
            )
        )
        // scaleは入力のSAR（画素の縦横比）を引き継ぐため、非正方画素の素材が混ざると
        // タイトルカード（SAR 1:1）や他クリップとSARが食い違い、concatが
        // 「Input link parameters do not match」で書き出しごと失敗する。
        // 1:1の素材には何も起きないので、全クリップで無条件に揃えておく。
        add("setsar=1")
    }.joinToString(",")
}

/**
 * drawtextフィルタ1つぶんの式を組み立てる。
 * fontfile/fontsize/fontcolor/x/yの並びと書式を1箇所に集約し、
 * タイトル・ひとこと・時刻の見た目が食い違わないようにする。
 *
 * @param text テキストを直接埋め込む場合。引用符・コロン・バックスラッシュ等のエスケープは
 *   行わないので、固定の英数字リテラル（"Vlog."）専用。任意の文字列は[textFile]を使うこと。
 *   [textFile]と排他。
 * @param textFile 別ファイルの内容を読ませる場合（改行や引用符を含むテキスト用）。[text]と排他。
 * @param enable 出し分け条件。付けない場合は空文字列のまま。
 */
private fun drawText(
    fontfile: File,
    fontsizePt: Float,
    x: String,
    y: String,
    text: String? = null,
    textFile: File? = null,
    color: String = "white",
    alpha: String? = null,
    enable: String = ""
): String {
    val content = if (textFile != null) "textfile='${textFile.absolutePath}'" else "text='$text'"
    val alphaPart = if (alpha != null) ":alpha='$alpha'" else ""
    return "drawtext=fontfile='${fontfile.absolutePath}'" +
            ":$content" +
            // expansion=none で %{...}（strftimeやメタデータの展開）を止める。
            // 既定のnormalのままだと、ひとことにたまたま "%" が入っているだけで
            // 展開を試みて表示が壊れたり、パースエラーで書き出しごと失敗したりする。
            // 以前は文字列側で "%" を "%%" に置換して逃げていたが、
            // 展開機能自体を切れば置換は要らない（=置換漏れの余地も無くなる）。
            ":expansion=none" +
            ":fontsize=${fontsizePt.toInt()}:fontcolor=$color" +
            ":x=$x:y=$y$alphaPart$enable"
}

/** 横方向の中央揃え式。text_wを使うので文字数やフォントサイズが変わっても中央のまま。 */
private fun centeredX() = "(w-text_w)/2"

/**
 * 画面中央から上下にずらしたy座標式をつくる。
 * text_h（その行の実際の文字高さ）を使って中央を出しているので、
 * フォントサイズを変えても縦位置がずれない。
 */
private fun centeredY(offsetPt: Float): String {
    val offset = offsetPt.toInt()
    return when {
        offset == 0 -> "(h-text_h)/2"
        offset > 0 -> "(h-text_h)/2+$offset"
        else -> "(h-text_h)/2-${-offset}"
    }
}

/**
 * ベースラインを「画面中央から[baselineFromCenterPt]下」に置くy座標式。
 *
 * [centeredY]のようにtext_h（その行の文字の実際の高さ）で中央を出すと、縦位置が
 * 文字の中身で変わる。「ー」だけの行は低く、「漢字」の行は高く測られるので、
 * 複数行では行ごとに上下へずれ、区間が切り替わると文字が上下に跳ね、
 * フォントの行の箱で並べているプレビューとも合わない。
 *
 * このFFmpegビルド(6.x)のdrawtextは、yの位置からその行の文字の最大の高さ（ascent）だけ
 * 下にベースラインを置く。y = 目標のベースライン − ascent とすれば、文字の中身に
 * 関係なくベースラインが目標の位置に来る（y_align=fontはFFmpeg 7以降で、6.xには無い）。
 */
private fun baselineY(baselineFromCenterPt: Float): String {
    val offset = baselineFromCenterPt.roundToInt()
    val sign = if (offset < 0) "-" else "+"
    return "h/2$sign${abs(offset)}-ascent"
}

/**
 * FFmpegに渡す秒数の文字列表現（例: "12.345"）。
 * Double.toString() は小数点にカンマを使うロケールの端末で "1,5" を生成し、
 * FFmpegが解釈できないため、必ず Locale.US 固定で組み立てる。
 */
private fun ffmpegSeconds(millis: Long): String =
    String.format(Locale.US, "%.3f", millis / 1000.0)
