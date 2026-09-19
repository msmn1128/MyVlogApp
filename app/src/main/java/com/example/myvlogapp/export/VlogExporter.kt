package com.example.myvlogapp.export

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaExtractor
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToLong
import com.example.myvlogapp.CANVAS_FPS
import com.example.myvlogapp.CANVAS_HEIGHT
import com.example.myvlogapp.CANVAS_WIDTH
import com.example.myvlogapp.FONT_ASSET_DIR
import com.example.myvlogapp.HITOKOTO_FONT_PT
import com.example.myvlogapp.HITOKOTO_LINE_SPACING_PT
import com.example.myvlogapp.LOG_TAG
import com.example.myvlogapp.MAX_CLIPS
import com.example.myvlogapp.TIME_FONT_ASSET
import com.example.myvlogapp.TIME_FONT_PT
import com.example.myvlogapp.TIME_MARGIN_PT
import com.example.myvlogapp.TITLE_DATE_FONT_PT
import com.example.myvlogapp.TITLE_DATE_LINE_SPACING_PT
import com.example.myvlogapp.TITLE_DATE_Y_OFFSET_PT
import com.example.myvlogapp.TITLE_DURATION_MS
import com.example.myvlogapp.TITLE_FONT_ASSET
import com.example.myvlogapp.TITLE_FONT_PT
import com.example.myvlogapp.TITLE_SFX_ASSET
import com.example.myvlogapp.TITLE_SFX_FRAME_NUMBER
import com.example.myvlogapp.TITLE_Y_OFFSET_PT
import com.example.myvlogapp.TextSpan
import com.example.myvlogapp.VlogClip
import com.example.myvlogapp.waveform.findAudioTrackIndex

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

    /** 書き出し前に、各クリップの音声トラックの有無を同時に調べる本数の上限（[AudioPlan.build]） */
    private const val AUDIO_PROBE_PARALLELISM = 4

    /**
     * ログに残すFFmpegのコマンド・フィルタグラフの最大文字数。
     * 本数が多い（[MAX_CLIPS]本）と1行が数百KBになって、Logcatを埋めてしまう。
     */
    private const val COMMAND_LOG_MAX_CHARS = 2000

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
    private const val MEDIACODEC_BITRATE = "12M"

    /** ギャラリー保存先のサブフォルダ名(Movies/以下)。孤児ファイル掃除の検索条件とも一致させる */
    private const val OUTPUT_SUBDIRECTORY = "MyVlogApp"

    // FONT_ASSET_DIRはVlogConstants.ktで定義（MainActivity側のプレビュー表示と共有するため）
    private const val SFX_ASSET_DIR = "sfx"

    private const val LOG_CHUNK_SIZE = 3000
    private const val ERROR_SNIPPET_MAX_CHARS = 400
    private const val ERROR_HIT_LINE_LIMIT = 3

    private const val COPY_BUFFER_SIZE = 64 * 1024

    /**
     * @param includeTitle 先頭にタイトルカード（黒背景＋日付＋効果音）を付けるかどうか。
     *   falseのときは全クリップを結合するだけで、タイトルカードもその効果音も含めない。
     * @param muted タイムライン全体のミュート。trueのときは各クリップの音声
     *   （[VlogClip.isMuted] の状態に関わらず全て）とタイトルカードの効果音を無音にする。
     * @param customTitleText タイトルカードに焼き込む文言。nullなら先頭クリップの
     *   撮影日（[VlogClip.dateText]）を使う。改行を含む場合は複数行として焼き込み、
     *   1行目の位置は変えずに下へ積む（[buildTitleFilter]参照）。
     * @param onProgress 進捗テキスト（UIスレッドで呼ばれる）
     * @return ギャラリーに保存された表示名
     */
    suspend fun export(
        context: Context,
        clips: List<VlogClip>,
        includeTitle: Boolean = true,
        muted: Boolean = false,
        customTitleText: String? = null,
        onProgress: suspend (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        require(clips.isNotEmpty()) { "クリップがありません" }
        // 追加時に上限を守っているが、上限を設ける前の保存データを復元した場合などに超えうる
        if (clips.size > MAX_CLIPS) {
            throw VlogExportException(
                "クリップが多すぎます（上限${MAX_CLIPS}本、現在${clips.size}本）。" +
                        "クリップを減らしてください"
            )
        }

        // 作業ファイルはcacheDirに置く（OSが必要に応じて掃除してくれる領域）
        val workDir = File(context.cacheDir, "vlog_work").apply { mkdirs() }
        val id = System.currentTimeMillis()
        // 書き出した動画自体の作成日時。タイトルカードの日付（撮影日）とは別に、
        // 書き出しを始めた現在時刻を、MP4のメタデータとギャラリーの撮影日時の両方へ入れる。
        val createdAtMillis = id
        val mergedFile = File(workDir, "merged_$id.mp4")
        val textFiles = mutableListOf<File>()

        try {
            requireDrawtext()
            val fonts = ExportFonts(
                logoType = copyFontAsset(context, TITLE_FONT_ASSET),
                time = copyFontAsset(context, TIME_FONT_ASSET)
            )

            // includeTitle・muted・クリップ個別isMutedの組み合わせ判定を先に1箇所へ
            // まとめておく。以降はこのAudioPlanを読むだけで、下の処理は分岐を持たない。
            val audioPlan = AudioPlan.build(context, clips, includeTitle, muted)
            val titleSfx = if (audioPlan.needsTitleSfxInput) {
                copySfxAsset(context, TITLE_SFX_ASSET)
            } else null

            onProgress("書き出し中...")
            coroutineContext.ensureActive()

            // タイトルカードの文言は自由入力があればそちらを優先する（空/未入力のときの
            // フォールバックはTitleCreationDialog側で解決済み）。ファイル名はこの文言とは無関係に、
            // 書き出しを始めた現在の日付から作る。
            val titleText = customTitleText ?: clips.first().dateText
            val sfxDelayMs = titleSfxDelayMs()

            // タイトルカード＋全クリップを、仮想タイムライン上に隙間なく並べて
            // 1回のFFmpeg呼び出しで結合・エンコードする。
            //
            // クリップごとに個別エンコードして結合し直すと同じ映像を2回圧縮することになるため、
            // 生の素材から直接1回だけエンコードする。30fps変換も結合後の連続した1本の
            // 映像に対して1回で完結する。
            //
            // 入力はタイトル効果音を含めるときだけ 0=タイトル効果音、1..N=各クリップ
            // （SAF経由）。含めないときは効果音の-iを省き、0..N-1=各クリップになる。
            // タイトルの映像(color=)や無音クリップの音声(anullsrc=)は実体ファイルを
            // 要求しない生成フィルタなので、追加の-iは不要。
            val inputs = buildList {
                titleSfx?.let { addAll(listOf("-i", it.absolutePath)) }
                clips.forEach { clip ->
                    addAll(clipInputArgs(clip, FFmpegKitConfig.getSafParameterForRead(context, clip.uri)))
                }
            }.toTypedArray()

            val filterGraph = buildFilterGraph(
                clips, fonts, titleText, sfxDelayMs, workDir, id, textFiles,
                includeTitle, audioPlan
            )
            // フィルタグラフは引数で渡さずファイルで渡す。本数が多いとグラフが数百KBに
            // なりうる（1クリップ約0.7〜1.5KB。100本で約70KB）。ファイルなら
            // 引数の長さに縛られず、コマンドのログも肥大しない。
            val graphFile = File(workDir, "graph_$id.txt")
                .apply { writeText(filterGraph, Charsets.UTF_8) }
                .also { textFiles += it }
            Log.d(
                LOG_TAG,
                "filter_complex (${filterGraph.length}文字): ${filterGraph.take(COMMAND_LOG_MAX_CHARS)}"
            )
            val totalDurationMs = (if (includeTitle) TITLE_DURATION_MS else 0L) +
                    clips.sumOf { it.trimmedDurationMs }

            runFFmpegWithProgress(
                arrayOf(
                    *inputs,
                    "-filter_complex_script", graphFile.absolutePath,
                    "-map", "[vout]", "-map", "[aout]",
                    // fpsフィルタで既にCFR化済みなので、-rによる二重指定はしない
                    *videoEncodeArgs(),
                    "-metadata", "creation_time=${creationTimeMetadata(createdAtMillis)}",
                    "-y", mergedFile.absolutePath
                ),
                totalDurationMs,
                onProgress
            )

            onProgress("保存中...")
            saveToGallery(context, mergedFile, buildDisplayName(context, createdAtMillis), createdAtMillis)
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
        // Android 10ではIS_PENDINGなアイテムがそもそもクエリに出てこないため、
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

        val hasDrawtext = filters.contains("drawtext")
        val caps = when {
            // GPL版に含まれるソフトウェアH.264エンコーダ。品質・互換性ともに最良。
            encoders.contains("libx264") -> Capabilities("libx264", emptyList(), hasDrawtext)

            // 端末のハードウェアエンコーダ。libx264が無いビルドでの代替。
            // ビットレート指定が無いと極端に低品質になるため明示する。
            encoders.contains("h264_mediacodec") ->
                Capabilities("h264_mediacodec", listOf("-b:v", MEDIACODEC_BITRATE), hasDrawtext)

            // 最後の手段。mp4に入るが圧縮効率は落ちる。
            else -> Capabilities("mpeg4", listOf("-q:v", "3"), hasDrawtext)
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
    internal data class ExportFonts(val logoType: File, val time: File)

    /** [VlogClip.visibleTextSpans] 1件と、その各行のテキストファイルの組 */
    private data class SpanLines(val span: TextSpan, val lineFiles: List<File?>)

    /**
     * includeTitle・タイムラインミュート・クリップ個別ミュートの組み合わせから、
     * 実際にどう音声を組み立てるかを1回で決めておくもの。
     *
     * これが無いと「タイトル効果音の-iを足すか」「各クリップのinputIndexがいくつずれるか」
     * 「各クリップを実音声にするか無音にするか」の3つの分岐がbuildFilterGraph内に
     * ばらばらに散り、書き出しオプションが増えるたびに複数箇所を同時に直す必要が出る。
     */
    internal class AudioPlan(
        val needsTitleSfxInput: Boolean,
        private val clipHasRealAudio: List<Boolean>
    ) {
        /** タイトル効果音の-iを入れる分だけ、各クリップの-i入力インデックスが後ろへずれる */
        val clipInputOffset: Int get() = if (needsTitleSfxInput) 1 else 0

        fun hasRealAudio(clipIndex: Int): Boolean = clipHasRealAudio[clipIndex]

        companion object {
            /**
             * 音声トラックの有無はクリップごとに MediaExtractor で開いて調べるため、
             * 直列に回すと「準備中...」が本数ぶん伸びる。動画追加時のメタデータ
             * 取得（VlogViewModel.addClips）と同じく、呼び出し元のIOスレッドで並列に調べる。
             * 本数が多いときに一斉に開かないよう、同時に開く数は[AUDIO_PROBE_PARALLELISM]に絞る。
             */
            suspend fun build(
                context: Context,
                clips: List<VlogClip>,
                includeTitle: Boolean,
                muted: Boolean
            ): AudioPlan = AudioPlan(
                needsTitleSfxInput = includeTitle && !muted,
                clipHasRealAudio = coroutineScope {
                    val gate = Semaphore(AUDIO_PROBE_PARALLELISM)
                    clips.map { clip ->
                        async {
                            !clip.isSilentInExport(muted) &&
                                    gate.withPermit { hasAudioTrack(context, clip.uri) }
                        }
                    }.awaitAll()
                }
            )
        }
    }

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
            graph += if (audioPlan.hasRealAudio(index)) {
                "[$inputIndex:a]asetpts=PTS-STARTPTS,${trimFilter(durationSec, audio = true)}" +
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
            else writeTextFile(
                workDir, "text_${id}_${clipIndex}_${spanIndex}_$lineIndex.txt", line, textFiles
            )
        }
        SpanLines(span, lineFiles)
    }

    /**
     * drawtextのtextfile=に読ませる1行ぶんのファイルを書く。
     * 書いたファイルは[textFiles]へ積む（呼び出し元がexport()のfinallyで掃除するため）。
     */
    private fun writeTextFile(
        workDir: File,
        name: String,
        text: String,
        textFiles: MutableList<File>
    ): File = File(workDir, name)
        .apply { writeText(text.escapePercentExpansion(), Charsets.UTF_8) }
        .also { textFiles += it }

    /**
     * タイトルカードの文言（既定は撮影日、自由入力ならその文言）を改行ごとに
     * 行単位のテキストファイルへ書き出す。空行は詰めて無視する
     * （タイトルはSpanLinesと違って行位置をenableで出し分ける必要が無く、
     * 空行のぶんだけ間隔を空けておく理由が無いため）。
     */
    private fun writeTitleTextFiles(
        workDir: File,
        id: Long,
        titleText: String,
        textFiles: MutableList<File>
    ): List<File> = titleText.split("\n")
        .filter { it.isNotBlank() }
        .mapIndexed { lineIndex, line ->
            writeTextFile(workDir, "title_${id}_$lineIndex.txt", line, textFiles)
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
     * 撮影時刻（[VlogClip.timeText]）を1行のテキストファイルへ書き出す。
     *
     * ひとことやタイトルと同じtextfile経由にしているのは、text=に直接埋め込むと
     * フィルタグラフとdrawtextの二段階で引用符・コロン・バックスラッシュが解釈され、
     * エスケープの正しさが文字列の中身に左右されるため（ファイルなら中身は解釈されない）。
     *
     * @param textFiles 生成したファイルをここへ積む（呼び出し元が掃除するため）
     */
    private fun writeTimeTextFile(
        workDir: File,
        id: Long,
        clipIndex: Int,
        clip: VlogClip,
        textFiles: MutableList<File>
    ): File = writeTextFile(workDir, "time_${id}_$clipIndex.txt", clip.timeText, textFiles)

    /**
     * 1クリップのフィルタ。
     * - 1920x1080キャンバスに歪みなしで配置（余白は黒帯）、30fps
     * - ひとこと：[fonts].logoType、[HITOKOTO_FONT_PT]、上下左右中央
     *   1行につき1つのdrawtextを積む（このFFmpegビルドにはtext_alignが無いため、
     *   1つのdrawtextに複数行を渡すと左揃えになってしまう）
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
                    y = centeredY(offsets[lineIndex]),
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
            extractor.findAudioTrackIndex() != null
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
        Log.d(LOG_TAG, "ffmpeg ${args.joinToString(" ").take(COMMAND_LOG_MAX_CHARS)}")
        val completion = CompletableDeferred<FFmpegSession>()
        val callerContext = coroutineContext
        var lastPercent = -1

        FFmpegKit.executeWithArgumentsAsync(
            args,
            { session -> completion.complete(session) },
            { /* ログはセッション完了後にまとめて参照するのでここでは何もしない */ },
            { statistics ->
                if (totalDurationMs > 0 && callerContext.isActive) {
                    val percent = (statistics.time / totalDurationMs.toDouble() * 100)
                        .toInt().coerceIn(0, 100)
                    if (percent != lastPercent) {
                        lastPercent = percent
                        // 「中止」を押した直後は、この統計コールバックが飛んでくる頃には
                        // callerContextのJobが既にキャンセル済みのことがある。その状態で
                        // runBlockingを呼ぶとCancellationExceptionがFFmpegKit側の
                        // コールバックスレッドへ投げ出され、キャッチされずにアプリごと
                        // 落ちうる。進捗表示は落としても実害が無いので握り潰す。
                        runCatching {
                            runBlocking(callerContext) { onProgress("書き出し中... $percent%") }
                        }
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
     * 保存するファイル名を決める。書き出しを始めた現在の日付から作り、
     * 「Vlog_2026-08-24.mp4」のような形にする。時刻はファイル名に入れず、
     * 動画自体の作成日時（メタデータ）だけが持つ。
     * タイトルカードの文言（撮影日や自由入力）は使わない。自由入力は改行やパス区切り文字などを
     * 含みうるため、ファイル名にはしないほうが安全で、書き出しの日付なら常に安全な文字だけで済む。
     *
     * 日付の区切りにハイフンを使うのは、ファイル名にスラッシュを含められないため
     * （パス区切りと解釈されて保存に失敗する）。
     *
     * 同じ日に複数回書き出したときは「Vlog_2026-08-24 (1).mp4」のように連番を付ける。
     */
    private fun buildDisplayName(context: Context, createdAtMillis: Long): String {
        val base = "Vlog_${fileNameDate(createdAtMillis)}"
        val taken = existingDisplayNames(context, base)

        var candidate = "$base.mp4"
        var index = 1
        while (candidate in taken) {
            candidate = "$base ($index).mp4"
            index++
        }
        return candidate
    }

    /** ファイル名用の日付 "yyyy-MM-dd"（端末のローカル日付。Locale.USで数字の字形を固定） */
    internal fun fileNameDate(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(millis)

    /**
     * すでに保存済みの、同じ名前で始まる動画の一覧。
     *
     * 自分のアプリが作ったファイルは権限なしで参照できる。
     * 取得に失敗しても空集合を返せば連番なしの名前になるだけで、
     * MediaStore側でも名前の重複は解決されるため書き出し自体は成功する。
     */
    private fun existingDisplayNames(context: Context, base: String): Set<String> = runCatching {
        val escapedBase = base.replace("\\", "\\\\").replace("_", "\\_").replace("%", "\\%")
        context.contentResolver.query(
            videoCollection(),
            arrayOf(MediaStore.Video.Media.DISPLAY_NAME),
            "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ? ESCAPE '\\'",
            arrayOf("$escapedBase%"),
            null
        )?.use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }.orEmpty()
    }.getOrDefault(emptySet())

    private fun videoCollection() =
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /**
     * MP4のcreation_time用のISO 8601表記（UTC）。
     * 指定しないとメタデータが空（MP4の起点である1904/01/01と読める）になり、
     * 書き出した動画を再び取り込んだときに撮影日時が1904年になってしまう。
     */
    internal fun creationTimeMetadata(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(millis)

    /**
     * 完成した動画をギャラリー（Movies/[OUTPUT_SUBDIRECTORY]）へ保存する。
     * @param createdAtMillis 動画の作成日時。ギャラリーが並び替えや日付表示に使う撮影日時へ入れる。
     */
    private suspend fun saveToGallery(
        context: Context,
        source: File,
        displayName: String,
        createdAtMillis: Long
    ): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.DATE_TAKEN, createdAtMillis)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(
                MediaStore.Video.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_MOVIES}/$OUTPUT_SUBDIRECTORY"
            )
            // 書き込み終わりで0に戻すまで、他アプリからは見えない（＝途中まで書けた
            // 壊れた動画がギャラリーに並ばない）
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val uri = resolver.insert(videoCollection(), values)
            ?: throw VlogExportException("ギャラリーへの保存に失敗しました")

        // コピー自体は中断ポイントを持たない同期I/Oなので、途中でキャンセルされても
        // 素通りしてコピーが完了してしまう（「中止した」のに保存済みになる不整合）。
        // バッファ単位でensureActive()を挟み、キャンセル時は挿入済みのMediaStore行を消す。
        // キャンセル以外の失敗（空き容量不足のIOExceptionなど）でも同様に消す。
        // 消さないとIS_PENDINGのまま残り、次回起動時の掃除まで壊れた項目が居座る。
        try {
            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> copyCancellably(input, output) }
            } ?: throw VlogExportException("ギャラリーへの書き込みに失敗しました")
        } catch (e: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }

        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
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
