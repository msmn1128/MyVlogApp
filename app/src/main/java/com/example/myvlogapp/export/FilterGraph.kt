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
import com.example.myvlogapp.TIME_FONT_PT
import com.example.myvlogapp.TIME_MARGIN_PT
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
//
// ひとこととタイトルの文言は、drawtextではなく画像（TextImages.kt）をoverlayで重ねる。
// drawtextでは絵文字を描けないため。
// =====================================================================================

// --- タイトルカードのフェード -------------------------------------------------------
// nは0始まりのフレーム番号。FADE_START_FRAMEのコマで95%、その20コマ後に0%になる
// （以前の文字ごとのalpha式 1-(n-29)/20 と同じ。エミュレータでコマごとの明るさが±1で一致）。

/** フェードアウトを開始するフレーム番号(0始まり) */
private const val FADE_START_FRAME = 30

/** フェードアウトにかけるフレーム数 */
private const val FADE_FRAME_COUNT = 20

/**
 * タイトルカード・全クリップ・結合をまとめた1本のfilter_complex文字列を組み立てる。
 *
 * @param workFiles 生成した撮影時刻のテキストファイル・文字の画像をここへ積む（呼び出し元が
 *   export()のfinallyでまとめて掃除するため）
 * @param renderText ひとこと・タイトルの文言を画像にする。本番は[AndroidTextRenderer]
 * @param hdrTransfers 各クリップがHDRならその伝達特性（SDRはnull）。HDRのクリップだけ、
 *   先頭でSDRへ変換する（Hdr.kt）
 */
internal suspend fun buildFilterGraph(
    clips: List<VlogClip>,
    fonts: ExportFonts,
    titleText: String,
    sfxDelayMs: Long,
    workDir: File,
    id: Long,
    workFiles: MutableList<File>,
    includeTitle: Boolean,
    audioPlan: AudioPlan,
    renderText: TextRenderer,
    hdrTransfers: List<HdrTransfer?> = List(clips.size) { null }
): String {
    val graph = mutableListOf<String>()

    if (includeTitle) {
        // --- タイトルカード（黒背景 / TITLE_DURATION_MSぶんの尺 /
        //     FADE_START_FRAMEから黒へフェードアウト / TITLE_SFX_FRAME_NUMBERフレーム目から効果音） ---
        graph += "color=c=black:s=${CANVAS_WIDTH}x$CANVAS_HEIGHT:r=$CANVAS_FPS" +
                ":d=${ffmpegSeconds(TITLE_DURATION_MS)}[vtitlesrc]"
        graph += buildTitleGraph(titleText, fonts, renderText, id)
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
        val timeFile = writeTimeTextFile(workDir, id, index, clip, workFiles)

        // 入力側（[clipInputArgs]）で既に開始位置へシークして長さも絞ってあるので、
        // ここでは時刻を0始まりに直し、trim=endで長さを保証するだけにする。
        // setpts=PTS-STARTPTSを最初に行うため、以降のoverlayのenable式のtは
        // 「クリップ先頭からの経過時間」になる（enable式はそれを前提にしている）。
        // HDRのクリップは、文字の焼き込みより前にSDRへ変換する（Hdr.kt）。変換は1画素ずつの
        // 浮動小数点の計算で重いので、先に出力の大きさまで縮めてから行う（4Kなら計算する画素が
        // 4分の1になる。エミュレータの4K・3秒のHLGで、書き出し全体が28秒→20秒。SDRは10秒）
        val toSdr = hdrTransfers[index]?.let { "${fitToCanvasFilter()},${hdrToSdrFilter(it)}," }.orEmpty()
        val canvasTag = "c${index}_0"
        graph += "[$inputIndex:v]setpts=PTS-STARTPTS,${trimFilter(durationSec, audio = false)}," +
                "$toSdr${fitToCanvasFilter()},pad=$CANVAS_WIDTH:$CANVAS_HEIGHT:(ow-iw)/2:(oh-ih)/2:black[$canvasTag]"
        val withHitokoto = addHitokotoOverlays(graph, index, clip, canvasTag, fonts, renderText, id)
        graph += "[$withHitokoto]${buildTimeAndSarFilter(timeFile, fonts)}[${vTag(index)}]"

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
 * タイトルカード。[vtitlesrc]（黒）から[vtitle]までを組み立てる。
 * - 「Vlog.」 [ExportFonts.logoType]、[TITLE_FONT_PT]、中央やや上。文言が固定の英数字なのでdrawtextのまま。
 *   text_h基準の中央でも位置は変わらない
 * - タイトル文言（既定は撮影日 "yyyy/MM/dd"）：画像にして重ねる（置き方は[titleStyle]）
 * - 最後にカード全体を黒へフェードアウトする。以前は文字ごとにalphaで消していたが、背景が黒なので
 *   見た目は同じで、文言の画像にalphaを持ち込まずに済む
 *   （fadeのstart_frameは「まだ100%のコマ」なので、95%にしたいコマの1つ手前を渡す）
 */
private fun buildTitleGraph(
    titleText: String,
    fonts: ExportFonts,
    renderText: TextRenderer,
    id: Long
): List<String> = buildList {
    val logo = drawText(
        fontfile = fonts.logoType,
        fontsizePt = TITLE_FONT_PT,
        x = centeredX(),
        y = centeredY(TITLE_Y_OFFSET_PT),
        text = "Vlog."
    )
    add("[vtitlesrc]$logo[vtitle_logo]")
    var current = "vtitle_logo"
    renderText.render(titleLines(titleText), titleStyle(fonts), "title_$id")?.let { image ->
        add("${movieSource(image.file)}[ttitle]")
        add("[$current][ttitle]${overlayFilter(image, enable = "")}[vtitle_text]")
        current = "vtitle_text"
    }
    add("[$current]fade=t=out:start_frame=${FADE_START_FRAME - 1}:nb_frames=$FADE_FRAME_COUNT[vtitle]")
}

/**
 * ひとことを区間ごとに画像にして、[canvasTag]の映像へ重ねる。
 * 空の区間は重ねない（drawtextの頃に空行を描かなかったのと同じ）。
 *
 * クリップの途中でひとことを変えている場合は、区間ごとの画像を enable で出し分ける。
 * 動画は切らないので、分割してもクリップは1本のまま（つなぎ目が生まれない）。
 *
 * @return 重ね終えた映像のラベル
 */
private fun addHitokotoOverlays(
    graph: MutableList<String>,
    clipIndex: Int,
    clip: VlogClip,
    canvasTag: String,
    fonts: ExportFonts,
    renderText: TextRenderer,
    id: Long
): String {
    val spans = clip.visibleTextSpans()
    var current = canvasTag
    spans.forEachIndexed { spanIndex, span ->
        val image = renderText.render(
            hitokotoLines(span.text), hitokotoStyle(fonts), "text_${id}_${clipIndex}_$spanIndex"
        ) ?: return@forEachIndexed
        // 区間が1つだけなら enable は付けない（式の評価ぶんだけ無駄になる）
        val enable = if (spans.size <= 1) "" else {
            // enable式のtは、入力側のシークとsetpts=PTS-STARTPTSで0始まりになった
            // 「クリップ先頭からの経過時間」。区間のstartMs/endMsは素材上の絶対位置なので、
            // トリミング開始位置を引いて合わせる（引き忘れると判定窓がずれて、
            // ひとことが出なくなる）。
            val from = span.startMs - clip.startMs
            // between は両端を含むので、隣の区間と1ms重ならないよう手前で切る。
            // 重なるとその1フレームだけ前後の文字が二重に焼き付いてしまう。
            val isLast = spanIndex == spans.lastIndex
            val to = (span.endMs - clip.startMs - if (isLast) 0L else 1L).coerceAtLeast(from)
            ":enable='between(t,${ffmpegSeconds(from)},${ffmpegSeconds(to)})'"
        }
        val imageTag = "t${clipIndex}_$spanIndex"
        val next = "c${clipIndex}_${spanIndex + 1}"
        graph += "${movieSource(image.file)}[$imageTag]"
        graph += "[$current][$imageTag]${overlayFilter(image, enable)}[$next]"
        current = next
    }
    return current
}

/**
 * 文字の画像を読む生成フィルタ。追加の-iにしないのは、入力の番号（タイトル効果音・各クリップ）を
 * ずらさずに済むため（-iで渡すと、区間の数だけ各クリップの入力番号が変わる）。
 */
private fun movieSource(file: File) = "movie=${quotedPath(file)}"

/**
 * 文字の画像を重ねる。画像は1枚きり（1コマ）なので、eof_action=repeatで最後まで出し続ける
 * （既定値だが、1枚の画像を重ね続けるのはこれに頼っているので明示する）。
 */
private fun overlayFilter(image: TextImage, enable: String) =
    "overlay=x=0:y=${image.top}:eof_action=repeat$enable"

/**
 * 撮影時刻（drawtextのまま）と、SARの揃え。
 * - 撮影時刻：[ExportFonts.time]、[TIME_FONT_PT]、キャンバス右端に配置（縦横問わず同じ位置）。
 *   縦位置はベースラインで揃える。プレビューはフォントの行の箱で上下中央に置いているので、
 *   text_h基準のままだとプレビューより数px上に出ていた
 */
private fun buildTimeAndSarFilter(timeFile: File, fonts: ExportFonts): String = listOf(
    drawText(
        fontfile = fonts.time,
        fontsizePt = TIME_FONT_PT,
        x = "$CANVAS_WIDTH-text_w-${TIME_MARGIN_PT.toInt()}",
        y = baselineY(fonts.timeBaselineShiftPt),
        textFile = timeFile
    ),
    // scaleは入力のSAR（画素の縦横比）を引き継ぐため、非正方画素の素材が混ざると
    // タイトルカード（SAR 1:1）や他クリップとSARが食い違い、concatが
    // 「Input link parameters do not match」で書き出しごと失敗する。
    // 1:1の素材には何も起きないので、全クリップで無条件に揃えておく。
    "setsar=1"
).joinToString(",")

/**
 * drawtextフィルタ1つぶんの式を組み立てる。
 * fontfile/fontsize/fontcolor/x/yの並びと書式を1箇所に集約する。
 *
 * @param text テキストを直接埋め込む場合。引用符・コロン・バックスラッシュ等のエスケープは
 *   行わないので、固定の英数字リテラル（"Vlog."）専用。任意の文字列は[textFile]を使うこと。
 *   [textFile]と排他。
 * @param textFile 別ファイルの内容を読ませる場合（引用符などを含みうるテキスト用）。[text]と排他。
 */
private fun drawText(
    fontfile: File,
    fontsizePt: Float,
    x: String,
    y: String,
    text: String? = null,
    textFile: File? = null,
    color: String = "white"
): String {
    val content = if (textFile != null) "textfile=${quotedPath(textFile)}" else "text='$text'"
    return "drawtext=fontfile=${quotedPath(fontfile)}" +
            ":$content" +
            // expansion=none で %{...}（strftimeやメタデータの展開）を止める。
            // 既定のnormalのままだと、たまたま "%" が入っているだけで
            // 展開を試みて表示が壊れたり、パースエラーで書き出しごと失敗したりする。
            ":expansion=none" +
            ":fontsize=${fontsizePt.toInt()}:fontcolor=$color" +
            ":x=$x:y=$y"
}

/**
 * フィルタグラフに書くファイルのパス（単引用符で囲む）。
 * 単引用符の中では単引用符そのものを書けない（エスケープの規則が二段階あり、中身次第で壊れる）。
 * 作業フォルダやfilesDirのパスに単引用符は入らないが、万一入っていたら壊れたグラフを
 * FFmpegに渡さず、ここで止める。
 */
private fun quotedPath(file: File): String {
    val path = file.absolutePath
    require('\'' !in path) { "フィルタグラフに書けないパスです: $path" }
    return "'$path'"
}

/** キャンバス（1920x1080）に歪みなく収まる大きさへ縮める（余白はあとでpadが黒で埋める） */
private fun fitToCanvasFilter() =
    "scale=$CANVAS_WIDTH:$CANVAS_HEIGHT:force_original_aspect_ratio=decrease"

/** 横方向の中央揃え式。text_wを使うので文字数やフォントサイズが変わっても中央のまま。 */
private fun centeredX() = "(w-text_w)/2"

/**
 * 画面中央から上下にずらしたy座標式をつくる（「Vlog.」用）。
 * text_h（その行の実際の文字高さ）で中央を出すので、文言が固定のときだけ使う。
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
 * ベースラインを「画面中央から[baselineFromCenterPt]下」に置くy座標式（撮影時刻用）。
 *
 * [centeredY]のようにtext_h（その行の文字の実際の高さ）で中央を出すと、縦位置が
 * 文字の中身で変わり、フォントの行の箱で並べているプレビューとも合わない。
 *
 * このFFmpegビルド(6.x)のdrawtextは、yの位置からその行の文字の最大の高さ（ascent）だけ
 * 下にベースラインを置く。y = 目標のベースライン − ascent とすれば、文字の中身に
 * 関係なくベースラインが目標の位置に来る（y_align=fontはFFmpeg 7以降で、6.xには無い）。
 * ひとこと・タイトルの文言の画像（[textStripLayout]）も、同じ位置にベースラインを置いている。
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
