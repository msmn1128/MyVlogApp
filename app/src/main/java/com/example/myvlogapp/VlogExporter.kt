package com.example.myvlogapp // ← ご自身のパッケージ名に合わせて変更してください

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToLong

class VlogExportException(message: String) : Exception(message)

/**
 * VLOG書き出しパイプライン。
 *
 * 設計方針：
 * - 全工程をFFmpegで統一する（Media3 Transformerは生成/操作スレッドの制約が厳しく、
 *   コールバックとコルーチンの混在でクラッシュしやすいため使わない）
 * - FFmpegには文字列コマンドではなく引数配列を渡す
 *   （パスに空白や記号が含まれてもシェル的な分割事故が起きない）
 * - 完成品は MediaStore 経由でギャラリーに保存する
 *   （getExternalFilesDir はアプリ専用領域でユーザーから見えないため）
 */
object VlogExporter {

    /**
     * 単一パスで書き出せるクリップ数の目安上限。
     *
     * 全クリップをFFmpegへ同時に`-i`入力するため、本数が増えるほど
     * 開くファイルディスクリプタ数・filter_complexのコマンド長が増える。
     * 際限なく許すと、上限超過時にFFmpeg側の分かりにくいエラーで
     * 失敗するだけになるため、ここで先に分かりやすいメッセージを出す。
     */
    private const val MAX_EXPORT_CLIPS = 50

    // --- タイトルカードのフェード -------------------------------------------------------
    // buildTitleFilterのalpha式で使う。nは0始まりのフレーム番号。

    /** フェードアウトを開始するフレーム番号(0始まり) */
    private const val FADE_START_FRAME = 30

    /** フェードアウトにかけるフレーム数 */
    private const val FADE_FRAME_COUNT = 20

    // --- 音声フォーマット -----------------------------------------------------------------
    // 無音クリップの補完(anullsrc)と実際のエンコード出力(-ar/-ac/-b:a)の両方でこの値を使う。
    // 片方だけ変えると無音クリップだけサンプルレートが食い違うため、必ずここを経由する。

    private const val AUDIO_SAMPLE_RATE = 44100
    private const val AUDIO_CHANNEL_LAYOUT = "stereo" // anullsrcの cl= 用
    private const val AUDIO_CHANNELS = 2              // -ac 用
    private const val AUDIO_BITRATE = "128k"

    /** h264_mediacodec（ハードウェアエンコーダ）使用時のビットレート */
    private const val MEDIACODEC_BITRATE = "5M"

    /** ギャラリー保存先のサブフォルダ名(Movies/以下)。孤児ファイル掃除の検索条件とも一致させる */
    private const val OUTPUT_SUBDIRECTORY = "MyVlogApp"

    // FONT_ASSET_DIRはVlogConstants.ktで定義（MainActivity側のプレビュー表示と共有するため）
    private const val SFX_ASSET_DIR = "sfx"

    private const val LOG_CHUNK_SIZE = 3000
    private const val ERROR_SNIPPET_MAX_CHARS = 400
    private const val ERROR_HIT_LINE_LIMIT = 3

    private const val COPY_BUFFER_SIZE = 64 * 1024

    /**
     * @param onProgress 進捗テキスト（UIスレッドで呼ばれる）
     * @return ギャラリーに保存された表示名
     */
    suspend fun export(
        context: Context,
        clips: List<VlogClip>,
        onProgress: suspend (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        require(clips.isNotEmpty()) { "クリップがありません" }
        if (clips.size > MAX_EXPORT_CLIPS) {
            throw VlogExportException(
                "クリップが多すぎます（上限${MAX_EXPORT_CLIPS}本、現在${clips.size}本）。" +
                        "クリップを減らしてください"
            )
        }

        // 作業ファイルはcacheDirに置く（OSが必要に応じて掃除してくれる領域）
        val workDir = File(context.cacheDir, "vlog_work").apply { mkdirs() }
        val id = System.currentTimeMillis()
        val mergedFile = File(workDir, "merged_$id.mp4")
        val textFiles = mutableListOf<File>()

        try {
            requireDrawtext()
            val fonts = ExportFonts(
                logoType = copyFontAsset(context, TITLE_FONT_ASSET),
                time = copyFontAsset(context, TIME_FONT_ASSET)
            )
            val titleSfx = copySfxAsset(context, TITLE_SFX_ASSET)

            onProgress("書き出し中...")
            coroutineContext.ensureActive()

            val firstDate = clips.first().dateText
            val sfxDelayMs = titleSfxDelayMs()

            // タイトルカード＋全クリップを、仮想タイムライン上に隙間なく並べて
            // 1回のFFmpeg呼び出しで結合・エンコードする。
            //
            // 以前はクリップごとに個別エンコードしたファイルを作り、それを
            // 再度concatで結合し直す2段構成だった。同じ映像を2回圧縮することになり
            // 画質のロスが重なるうえ、フレームレート変換の帳尻合わせが複雑になっていた。
            // 生の素材から直接1回だけエンコードすることで、圧縮は1回で済み、
            // 30fps変換も結合後の連続した1本の映像に対して1回で完結する。
            //
            // 入力は 0=タイトル効果音、1..N=各クリップ（SAF経由）。
            // タイトルの映像(color=)や無音クリップの音声(anullsrc=)は実体ファイルを
            // 要求しない生成フィルタなので、追加の-iは不要。
            val safInputs = clips.map { FFmpegKitConfig.getSafParameterForRead(context, it.uri) }
            val inputs = arrayOf("-i", titleSfx.absolutePath) +
                    safInputs.flatMap { listOf("-i", it) }.toTypedArray()

            val filterGraph = buildFilterGraph(
                context, clips, fonts, firstDate, sfxDelayMs, workDir, id, textFiles
            )
            val totalDurationMs = TITLE_DURATION_MS + clips.sumOf { it.trimmedDurationMs }

            runFFmpegWithProgress(
                arrayOf(
                    *inputs,
                    "-filter_complex", filterGraph,
                    "-map", "[vout]", "-map", "[aout]",
                    // fpsフィルタで既にCFR化済みなので、-rによる二重指定はしない
                    *videoEncodeArgs(),
                    "-y", mergedFile.absolutePath
                ),
                totalDurationMs,
                onProgress
            )

            onProgress("保存中...")
            saveToGallery(context, mergedFile, buildDisplayName(context, firstDate))
        } finally {
            // 成功・失敗・キャンセルいずれでも作業ファイルを掃除する
            mergedFile.delete()
            textFiles.forEach { it.delete() }
        }
    }

    /** 実行中のFFmpeg処理を中断する */
    fun cancel() = FFmpegKit.cancel()

    /**
     * 前回起動時に書き出し中に強制終了（OSによるプロセス回収、強制停止、
     * クラッシュ等）した場合、保存処理の途中でIS_PENDINGのまま更新されない
     * 壊れた動画がMediaStoreに残ることがある。次回起動時に自分のフォルダ
     * （Movies/[OUTPUT_SUBDIRECTORY]）配下だけを見て、それを消す。
     *
     * IS_PENDINGなアイテムは自分のアプリ以外からは検索できない仕組み
     * （スコープドストレージ）なので、他アプリの保存中ファイルを誤って
     * 巻き込むことはない。
     *
     * 呼び出し側は、実行中の書き出しが無いこと（[ExportStatus.isRunning] が
     * false）を確認してから呼ぶこと。同一プロセス内でサービスが書き込み中の
     * ファイルを、起動直後の掃除で消してしまう事故を防ぐため。
     */
    fun cleanupOrphanedPendingFiles(context: Context) {
        // QUERY_ARG_MATCH_PENDING / MATCH_INCLUDE はAPI 30以降でしか効かない。
        // それ未満ではIS_PENDINGなアイテムがそもそもクエリに出てこないため、
        // 掃除のしようがなく実行しても意味が無い（実害はないが早期returnする）。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        val resolver = context.contentResolver
        val args = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
            )
            putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf("${Environment.DIRECTORY_MOVIES}/$OUTPUT_SUBDIRECTORY%")
            )
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }

        runCatching {
            resolver.query(
                videoCollection(),
                arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.IS_PENDING),
                args,
                null
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val pendingColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.IS_PENDING)
                while (cursor.moveToNext()) {
                    if (cursor.getInt(pendingColumn) != 1) continue
                    val uri = ContentUris.withAppendedId(videoCollection(), cursor.getLong(idColumn))
                    resolver.delete(uri, null, null)
                    Log.i(LOG_TAG, "強制終了で残った書き出し途中の動画を削除しました: $uri")
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------
    // 環境判定
    //
    // ffmpeg-kitはビルド種類（min / full / full-gpl / フォーク各種）によって
    // 使えるエンコーダやフィルタが異なる。依存を差し替えるたびにコードを直さずに
    // 済むよう、実際に何が使えるかを起動後1回だけ調べて使い分ける。
    // ---------------------------------------------------------------------------------

    private data class Capabilities(
        val videoEncoder: String,
        val extraVideoArgs: List<String>,
        val hasDrawtext: Boolean
    )

    private val capabilities: Capabilities by lazy {
        val encoders = runCatching {
            FFmpegKit.execute("-hide_banner -encoders").allLogsAsString.orEmpty()
        }.getOrDefault("")
        val filters = runCatching {
            FFmpegKit.execute("-hide_banner -filters").allLogsAsString.orEmpty()
        }.getOrDefault("")

        val caps = when {
            // GPL版に含まれるソフトウェアH.264エンコーダ。品質・互換性ともに最良。
            encoders.contains("libx264") ->
                Capabilities("libx264", emptyList(), filters.contains("drawtext"))

            // 端末のハードウェアエンコーダ。libx264が無いビルドでの代替。
            // ビットレート指定が無いと極端に低品質になるため明示する。
            encoders.contains("h264_mediacodec") ->
                Capabilities(
                    "h264_mediacodec", listOf("-b:v", MEDIACODEC_BITRATE), filters.contains("drawtext")
                )

            // 最後の手段。mp4に入るが圧縮効率は落ちる。
            else ->
                Capabilities("mpeg4", listOf("-q:v", "3"), filters.contains("drawtext"))
        }

        Log.i(
            LOG_TAG,
            "FFmpeg機能判定: encoder=${caps.videoEncoder} drawtext=${caps.hasDrawtext}"
        )
        caps
    }

    /**
     * 映像エンコード用の共通引数。
     *
     * -r（フレームレート強制）を付けないのは、結合後の連続した映像に対して
     * 呼び出し元がすでに`fps`フィルタでCFR化しているため。クリップ個別に-rを
     * 掛けていた頃は、素材の実フレームレートとの差分の帳尻合わせがクリップ末尾
     * （＝つなぎ目）に集中してしまい、継ぎ目で一瞬止まって見える原因になっていた。
     */
    private fun videoEncodeArgs(): Array<String> = arrayOf(
        "-c:v", capabilities.videoEncoder,
        *capabilities.extraVideoArgs.toTypedArray(),
        "-pix_fmt", "yuv420p",
        "-c:a", "aac", "-ar", "$AUDIO_SAMPLE_RATE", "-ac", "$AUDIO_CHANNELS", "-b:a", AUDIO_BITRATE
    )

    private fun requireDrawtext() {
        if (!capabilities.hasDrawtext) {
            throw VlogExportException(
                "このFFmpegビルドにはdrawtextフィルタが含まれておらず、文字を焼き込めません。" +
                        "freetypeを含むビルド（full / full-gpl）に差し替えてください。"
            )
        }
    }

    // ---------------------------------------------------------------------------------
    // フィルタグラフ構築
    // ---------------------------------------------------------------------------------

    /** タイトルカード・各クリップ両方で使うフォント一式 */
    private data class ExportFonts(val logoType: File, val time: File)

    /** [VlogClip.visibleTextSpans] 1件と、その各行のテキストファイルの組 */
    private data class SpanLines(val span: TextSpan, val lineFiles: List<File?>)

    /**
     * タイトルカード・全クリップ・結合をまとめた1本のfilter_complex文字列を組み立てる。
     *
     * @param textFiles 生成した行ごとのテキストファイルをここへ積む（呼び出し元がexport()の
     *   finallyでまとめて掃除するため）
     */
    private suspend fun buildFilterGraph(
        context: Context,
        clips: List<VlogClip>,
        fonts: ExportFonts,
        firstDate: String,
        sfxDelayMs: Long,
        workDir: File,
        id: Long,
        textFiles: MutableList<File>
    ): String {
        val graph = mutableListOf<String>()

        // --- タイトルカード（黒背景 / TITLE_DURATION_MSぶんの尺 /
        //     FADE_START_FRAME〜FADE_START_FRAME+FADE_FRAME_COUNT-1フレーム目でフェードアウト /
        //     TITLE_SFX_FRAME_NUMBERフレーム目から効果音） ---
        graph += "color=c=black:s=${CANVAS_WIDTH}x$CANVAS_HEIGHT:r=$CANVAS_FPS" +
                ":d=${ffmpegSeconds(TITLE_DURATION_MS)}[vtitlesrc]"
        graph += "[vtitlesrc]${buildTitleFilter(firstDate, fonts)}[vtitle]"
        // apadは終端を指定しないと無音を無限に継ぎ足し続ける。
        // 「動画(タイトルの尺)の方が短いから-shortestで自動的に切られるはず」と考えて頼ると、
        // 実機では音声側が先に何時間ぶんもの無音を吐き出そうとして書き出しが
        // 実質ハングする。atrimでタイトルの尺ぴったりに強制的に切ることで、
        // -shortestに頼らず必ず有限時間で終わるようにする。
        graph += "[0:a]adelay=$sfxDelayMs|$sfxDelayMs,apad," +
                "atrim=0:${ffmpegSeconds(TITLE_DURATION_MS)},asetpts=PTS-STARTPTS[atitle]"

        // --- 各クリップ：トリミング → 1920x1080整形 → テロップ焼き込み ---
        clips.forEachIndexed { index, clip ->
            coroutineContext.ensureActive()
            val inputIndex = index + 1
            val startSec = ffmpegSeconds(clip.startMs)
            val endSec = ffmpegSeconds(clip.startMs + clip.trimmedDurationMs)
            val spans = writeSpanTextFiles(workDir, id, index, clip, textFiles)

            // trimのみ（setpts無し）だと、切り出し後もtが素材の絶対時刻のまま
            // drawtextに渡る。区間出し分けのenable式(buildClipFilter内)がこの
            // 絶対時刻を前提にしているため、setpts=PTS-STARTPTSは全フィルタの
            // 最後（concatへ渡す直前）で1回だけ行う。
            graph += "[$inputIndex:v]${trimFilter(startSec, endSec, audio = false)}," +
                    "${buildClipFilter(spans, clip, fonts)}," +
                    "setpts=PTS-STARTPTS[${vTag(index)}]"

            // concatは各セグメントの音声ストリームを明示参照するため、
            // 音声トラックの無い素材でも無音を生成して必ず音声を持たせる。
            graph += if (hasAudioTrack(context, clip.uri)) {
                "[$inputIndex:a]${trimFilter(startSec, endSec, audio = true)}," +
                        "asetpts=PTS-STARTPTS[${aTag(index)}]"
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
            append("[vtitle][atitle]")
            clips.indices.forEach { append("[${vTag(it)}][${aTag(it)}]") }
        }
        graph += "${segmentLabels}concat=n=${clips.size + 1}:v=1:a=1[vraw][aout]"
        graph += "[vraw]fps=$CANVAS_FPS[vout]"

        return graph.joinToString(";")
    }

    /** 結合グラフ内での各クリップの映像/音声ラベル名 */
    private fun vTag(index: Int) = "v$index"
    private fun aTag(index: Int) = "a$index"

    /** trim/atrimフィルタの文字列。映像と音声で名前が違うだけで形は同じ */
    private fun trimFilter(startSec: String, endSec: String, audio: Boolean): String {
        val name = if (audio) "atrim" else "trim"
        return "$name=start=$startSec:end=$endSec"
    }

    /**
     * 1クリップぶんの区間（[VlogClip.visibleTextSpans]）ごとに、行単位のテキストファイルを書き出す。
     *
     * drawtextのtext_alignはFFmpeg 7.0以降の機能で、このビルド(6.x)には無い。
     * 複数行を中央揃えにするため、1行につき1つのdrawtextとして描く。
     * textfile経由なのは、改行やコロン・カンマを含んでも構文が壊れないため。
     *
     * クリップの途中でひとことを変えている場合は、区間ごとにこの一式を作る。
     * 動画は切らずに drawtext の enable で出し分けるので、
     * 分割してもクリップは1本のまま（つなぎ目が生まれない）。
     *
     * @param textFiles 生成したファイルをここへ積む（呼び出し元が掃除するため）
     */
    private fun writeSpanTextFiles(
        workDir: File,
        id: Long,
        clipIndex: Int,
        clip: VlogClip,
        textFiles: MutableList<File>
    ): List<SpanLines> = clip.visibleTextSpans().mapIndexed { spanIndex, span ->
        val lineFiles = span.text.split("\n").mapIndexed { lineIndex, line ->
            // 空行にdrawtextを掛けるとエラーになるので、位置だけ確保して描かない
            if (line.isBlank()) null
            else File(workDir, "text_${id}_${clipIndex}_${spanIndex}_$lineIndex.txt")
                .apply { writeText(line.escapePercentExpansion(), Charsets.UTF_8) }
        }
        textFiles += lineFiles.filterNotNull()
        SpanLines(span, lineFiles)
    }

    /**
     * タイトルカードのフィルタ。
     * - 「Vlog.」 [fonts].logoType、[TITLE_FONT_PT]、中央やや上
     * - 日付 "yyyy/MM/dd" [fonts].time、[TITLE_DATE_FONT_PT]、中央やや下
     * - [FADE_START_FRAME]フレーム目からフェードアウト開始（nは0始まり）
     *
     * alpha式はシングルクォートで囲まれているため、内部のカンマを
     * バックスラッシュでエスケープしてはいけない（数式が壊れる）。
     */
    private fun buildTitleFilter(dateText: String, fonts: ExportFonts): String {
        val fadeEndFrame = FADE_START_FRAME + FADE_FRAME_COUNT - 1
        val alpha = "if(lt(n,$FADE_START_FRAME),1," +
                "if(between(n,$FADE_START_FRAME,$fadeEndFrame)," +
                "1-(n-${FADE_START_FRAME - 1})/$FADE_FRAME_COUNT,0))"
        return listOf(
            "drawtext=fontfile='${fonts.logoType.absolutePath}':text='Vlog.'" +
                    ":fontsize=${TITLE_FONT_PT.toInt()}:fontcolor=white" +
                    ":x=(w-text_w)/2:y=${centeredY(TITLE_Y_OFFSET_PT)}:alpha='$alpha'",
            "drawtext=fontfile='${fonts.time.absolutePath}':text='${escapeForDrawtext(dateText)}'" +
                    ":fontsize=${TITLE_DATE_FONT_PT.toInt()}:fontcolor=white" +
                    ":x=(w-text_w)/2:y=${centeredY(TITLE_DATE_Y_OFFSET_PT)}:alpha='$alpha'"
        ).joinToString(",")
    }

    /**
     * 1クリップのフィルタ。
     * - 1920x1080キャンバスに歪みなしで配置（余白は黒帯）、30fps
     * - ひとこと：[fonts].logoType、[HITOKOTO_FONT_PT]、上下左右中央
     *   1行につき1つのdrawtextを積む（このFFmpegビルドにはtext_alignが無いため、
     *   1つのdrawtextに複数行を渡すと左揃えになってしまう）
     * - 撮影時刻：[fonts].time、[TIME_FONT_PT]、映像が実際に映っている領域の右端に配置
     *
     * 縦動画を横長キャンバスに収めると左右に大きな黒帯ができるため、
     * キャンバス右端を基準にすると時刻が黒帯の中に浮いてしまう。
     * そこで実際の映像右端座標を計算して基準にする。
     *
     * @param spans ひとことの区間と、その各行のテキストファイル。
     *   空行はnull（描かずに間隔だけ空ける）。区間が2つ以上ある場合は enable で出し分ける。
     */
    private fun buildClipFilter(
        spans: List<SpanLines>,
        clip: VlogClip,
        fonts: ExportFonts
    ): String {
        val scale = minOf(
            CANVAS_WIDTH.toDouble() / clip.width,
            CANVAS_HEIGHT.toDouble() / clip.height
        )
        val visibleRightEdge = ((CANVAS_WIDTH + clip.width * scale) / 2).toInt()

        // 行の高さぶんだけ上下にずらして、行の集まり全体が画面中央に来るようにする
        val lineHeight = HITOKOTO_FONT_PT + HITOKOTO_LINE_SPACING_PT
        val hitokotoLayers = spans.flatMapIndexed { spanIndex, (span, lineFiles) ->
            // 区間が1つだけなら enable は付けない（式の評価ぶんだけ無駄になる）
            val enable = if (spans.size <= 1) "" else {
                // enable式のtはクリップ先頭からの経過時間ではなく、素材動画の絶対時刻のまま
                // フィルタに渡ってくる（trimフィルタが単体ではPTSをリセットしないため。
                // setpts=PTS-STARTPTSは全フィルタの最後、concatへ渡す直前で1回だけ行っており、
                // それより前のこのdrawtext enable判定の時点ではtは絶対時刻のまま）。
                // そのためspan.startMs/endMsをそのまま使う。ここでclip.startMsを
                // 引いてしまうと、前トリムした分だけenableの判定窓がずれて
                // どのフレームとも一致しなくなり、ひとことが丸ごと出なくなる。
                val from = span.startMs
                // between は両端を含むので、隣の区間と1ms重ならないよう手前で切る。
                // 重なるとその1フレームだけ前後の文字が二重に焼き付いてしまう。
                val isLast = spanIndex == spans.lastIndex
                val to = (span.endMs - if (isLast) 0L else 1L).coerceAtLeast(from)
                ":enable='between(t,${ffmpegSeconds(from)},${ffmpegSeconds(to)})'"
            }
            lineFiles.mapIndexedNotNull { lineIndex, file ->
                if (file == null) return@mapIndexedNotNull null
                val offset = (lineIndex - (lineFiles.size - 1) / 2.0) * lineHeight
                "drawtext=fontfile='${fonts.logoType.absolutePath}'" +
                        ":textfile='${file.absolutePath}'" +
                        ":fontsize=${HITOKOTO_FONT_PT.toInt()}:fontcolor=white" +
                        ":x=(w-text_w)/2:y=${centeredY(offset.toFloat())}$enable"
            }
        }

        return buildList {
            add("scale=$CANVAS_WIDTH:$CANVAS_HEIGHT:force_original_aspect_ratio=decrease")
            add("pad=$CANVAS_WIDTH:$CANVAS_HEIGHT:(ow-iw)/2:(oh-ih)/2:black")
            addAll(hitokotoLayers)
            add(
                "drawtext=fontfile='${fonts.time.absolutePath}'" +
                        ":text='${escapeForDrawtext(clip.timeText)}'" +
                        ":fontsize=${TIME_FONT_PT.toInt()}:fontcolor=white@0.85" +
                        ":x=$visibleRightEdge-text_w-${TIME_MARGIN_PT.toInt()}:y=(h-text_h)/2"
            )
        }.joinToString(",")
    }

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

    private fun escapeForDrawtext(text: String) = text
        .replace("\\", "\\\\")
        .replace(":", "\\:")
        .replace("'", "\\'")
        .escapePercentExpansion()

    /**
     * drawtextの %{...} 展開（strftimeやメタデータなど）を無効化する。
     *
     * text= 経由かtextfile= 経由かに関わらず、読み込んだ文字列に対して効いてしまうため、
     * ひとことにたまたま "%" が含まれているだけでも展開を試みて表示が壊れたり
     * フィルタのパースエラーで書き出しごと失敗したりする。%を%%にすると無効化できる。
     */
    private fun String.escapePercentExpansion() = replace("%", "%%")

    /** 動画に音声トラックが存在するか（[Waveform.hasAudio]と同じ判定方法） */
    private fun hasAudioTrack(context: Context, uri: Uri): Boolean {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            (0 until extractor.trackCount).any { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "音声トラックの有無を判定できませんでした（無音として扱います）: $uri", e)
            false
        } finally {
            extractor.release()
        }
    }

    // ---------------------------------------------------------------------------------
    // ユーティリティ
    // ---------------------------------------------------------------------------------

    /**
     * FFmpegに渡す秒数の文字列表現（例: "12.345"）。
     * Double.toString() は小数点にカンマを使うロケールの端末で "1,5" を生成し、
     * FFmpegが解釈できないため、必ず Locale.US 固定で組み立てる。
     */
    private fun ffmpegSeconds(millis: Long): String =
        String.format(Locale.US, "%.3f", millis / 1000.0)

    /**
     * FFmpegを実行し、経過時間から進捗率（%）を算出してonProgressに渡す。
     *
     * statisticsコールバックはFFmpegKit側の別スレッドから呼ばれるため、
     * onProgress（呼び出し元のコルーチンコンテキストを前提とするsuspend関数）を
     * 呼ぶにはrunBlockingで橋渡しする。パーセント値が変わったときだけ呼ぶことで、
     * 呼び出し頻度（1秒間に何度も飛んでくる）による無駄な更新を減らす。
     */
    private suspend fun runFFmpegWithProgress(
        args: Array<String>,
        totalDurationMs: Long,
        onProgress: suspend (String) -> Unit
    ) {
        Log.d(LOG_TAG, "ffmpeg ${args.joinToString(" ")}")
        val completion = CompletableDeferred<FFmpegSession>()
        val callerContext = coroutineContext
        var lastPercent = -1

        FFmpegKit.executeWithArgumentsAsync(
            args,
            { session -> completion.complete(session) },
            { /* ログはセッション完了後にまとめて参照するのでここでは何もしない */ },
            { statistics ->
                if (totalDurationMs > 0) {
                    val percent = (statistics.time / totalDurationMs.toDouble() * 100)
                        .toInt().coerceIn(0, 100)
                    if (percent != lastPercent) {
                        lastPercent = percent
                        runBlocking(callerContext) { onProgress("書き出し中... $percent%") }
                    }
                }
            }
        )

        val session = completion.await()
        when {
            ReturnCode.isSuccess(session.returnCode) -> Unit
            ReturnCode.isCancel(session.returnCode) -> throw VlogExportException("書き出しを中止しました")
            else -> {
                val log = session.allLogsAsString.orEmpty()
                logFfmpegOutput("書き出しに失敗しました", "${session.returnCode}", log)
                throw VlogExportException("書き出しに失敗しました\n${extractReason(log)}")
            }
        }
    }

    /**
     * Logcatは1行あたりの長さに上限があり、FFmpegの出力は途中で切れてしまう。
     * 分割して全文を出し、目印で挟んで探しやすくする。
     */
    private fun logFfmpegOutput(errorMessage: String, code: String, log: String) {
        Log.e(LOG_TAG, "$errorMessage / code=$code")
        Log.e(LOG_TAG, "--- FFmpeg出力ここから ---")
        log.chunked(LOG_CHUNK_SIZE).forEach { Log.e(LOG_TAG, it) }
        Log.e(LOG_TAG, "--- FFmpeg出力ここまで ---")
    }

    /** FFmpegの出力からエラーらしい行だけ拾ってToastに出す */
    private fun extractReason(log: String): String {
        val keywords = listOf(
            "error", "invalid", "no such", "unable", "failed", "permission denied", "conversion failed"
        )
        val hits = log.lineSequence()
            .map { it.trim() }
            .filter { line -> line.isNotEmpty() && keywords.any { line.contains(it, ignoreCase = true) } }
            .distinct()
            .toList()
        return if (hits.isEmpty()) log.takeLast(ERROR_SNIPPET_MAX_CHARS).trim()
        else hits.takeLast(ERROR_HIT_LINE_LIMIT).joinToString("\n").take(ERROR_SNIPPET_MAX_CHARS)
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
            throw VlogExportException("素材を読み込めません: assets/$assetPath を配置してください")
        }
        // 実際に使われた素材の大きさをログに残す。
        // assets側のファイルサイズと一致していれば取り違えは起きていない。
        Log.i(LOG_TAG, "アセット展開: $assetPath / ${file.length()} bytes")
        return file
    }

    private fun copyFontAsset(context: Context, assetName: String): File =
        copyAsset(context, "$FONT_ASSET_DIR/$assetName", FONT_ASSET_DIR, assetName)

    private fun copySfxAsset(context: Context, assetName: String): File =
        copyAsset(context, "$SFX_ASSET_DIR/$assetName", SFX_ASSET_DIR, assetName)

    /**
     * タイトルカードの効果音を鳴らし始めるタイミング（ミリ秒）。
     * [TITLE_SFX_FRAME_NUMBER] は1始まりのフレーム番号なので、0始まりのnに直してから
     * 経過時間へ変換する。
     */
    private fun titleSfxDelayMs(): Long {
        val n = (TITLE_SFX_FRAME_NUMBER - 1).coerceAtLeast(0)
        return (n * 1000.0 / CANVAS_FPS).roundToLong()
    }

    /**
     * 保存するファイル名を決める。「Vlog_2026-08-24.mp4」の形。
     *
     * 日付の区切りにハイフンを使うのは、ファイル名にスラッシュを含められないため
     * （パス区切りと解釈されて保存に失敗する）。
     *
     * 同じ日に複数回書き出したときは「Vlog_2026-08-24 (1).mp4」のように連番を付ける。
     */
    private fun buildDisplayName(context: Context, dateText: String): String {
        val base = "Vlog_${dateText.replace("/", "-")}"
        val taken = existingDisplayNames(context, base)

        var candidate = "$base.mp4"
        var index = 1
        while (candidate in taken) {
            candidate = "$base ($index).mp4"
            index++
        }
        return candidate
    }

    /**
     * すでに保存済みの、同じ名前で始まる動画の一覧。
     *
     * 自分のアプリが作ったファイルは権限なしで参照できる。
     * 取得に失敗しても空集合を返せば連番なしの名前になるだけで、
     * MediaStore側でも名前の重複は解決されるため書き出し自体は成功する。
     */
    private fun existingDisplayNames(context: Context, base: String): Set<String> = runCatching {
        context.contentResolver.query(
            videoCollection(),
            arrayOf(MediaStore.Video.Media.DISPLAY_NAME),
            "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?",
            arrayOf("$base%"),
            null
        )?.use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }.orEmpty()
    }.getOrDefault(emptySet())

    private fun videoCollection() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

    /** 完成した動画をギャラリー（Movies/[OUTPUT_SUBDIRECTORY]）へ保存する */
    private suspend fun saveToGallery(context: Context, source: File, displayName: String): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_MOVIES}/$OUTPUT_SUBDIRECTORY"
                )
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val uri = resolver.insert(videoCollection(), values)
            ?: throw VlogExportException("ギャラリーへの保存に失敗しました")

        // コピー自体は中断ポイントを持たない同期I/Oなので、途中でキャンセルされても
        // 素通りしてコピーが完了してしまう（「中止した」のに保存済みになる不整合）。
        // バッファ単位でensureActive()を挟み、キャンセル時は挿入済みのMediaStore行を消す。
        try {
            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> copyCancellably(input, output) }
            } ?: throw VlogExportException("ギャラリーへの書き込みに失敗しました")
        } catch (e: CancellationException) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        return displayName
    }

    private suspend fun copyCancellably(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        while (true) {
            coroutineContext.ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
        }
    }
}
