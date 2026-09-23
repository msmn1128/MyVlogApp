package com.example.myvlogapp.export

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKitConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext
import com.example.myvlogapp.HITOKOTO_FONT_PT
import com.example.myvlogapp.TIME_FONT_PT
import com.example.myvlogapp.TITLE_DATE_FONT_PT
import com.example.myvlogapp.LOG_TAG
import com.example.myvlogapp.MAX_CLIPS
import com.example.myvlogapp.mapParallel
import com.example.myvlogapp.TIME_FONT_ASSET
import com.example.myvlogapp.TITLE_DURATION_MS
import com.example.myvlogapp.TITLE_FONT_ASSET
import com.example.myvlogapp.TITLE_SFX_ASSET
import com.example.myvlogapp.VlogClip
import com.example.myvlogapp.data.isReadable

class VlogExportException(message: String) : Exception(message)

/** 書き出し前に、各クリップの動画の中身（[probeClip]）を同時に調べる本数の上限 */
private const val CLIP_PROBE_PARALLELISM = 4

/**
 * 区切りごとの書き出しで、行ごとのテキストなどのファイル名に入れる番号を区切りごとに変えるための倍率。
 * 書き出しの番号（開始時刻のミリ秒）にこれを掛けて区切りの番号を足す（区切りは最大[MAX_CLIPS]個）
 */
private const val SEGMENT_ID_SCALE = 1000L

/** 書き出し中の中間ファイル置き場（cacheDir以下） */
private const val WORK_DIRECTORY = "vlog_work"

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

    /**
     * 区切りごとの書き出し（Segments.kt）で、[clipRange]のクリップだけを受け持つ計画。
     * 区切りの中では添字が0から振り直されるので、各クリップの有無もその範囲だけに切り出す。
     *
     * @param includesTitle この区切りにタイトルカードが入るか（入るのは最初の区切りだけ）。
     *   タイトル効果音の-iは、入る区切りでだけ足す
     */
    fun forSegment(clipRange: IntRange, includesTitle: Boolean) = AudioPlan(
        needsTitleSfxInput = needsTitleSfxInput && includesTitle,
        clipHasRealAudio = clipHasRealAudio.slice(clipRange)
    )

    companion object {
        /**
         * 書き出しの設定と、調べた各クリップの中身（[probes]、[clips]と同じ並び）から組み立てる。
         * 実音声を使うのは、ミュートされておらず、音声トラックがあるクリップだけ。
         */
        fun of(
            clips: List<VlogClip>,
            probes: List<ClipProbe>,
            includeTitle: Boolean,
            muted: Boolean
        ): AudioPlan = AudioPlan(
            needsTitleSfxInput = includeTitle && !muted,
            clipHasRealAudio = clips.zip(probes) { clip, probe ->
                !clip.isSilentInExport(muted) && probe.hasAudioTrack
            }
        )
    }
}

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
 *
 * 工程ごとの中身は同じパッケージの別ファイルにある。ここに残すのは手順だけ：
 * - FFmpegCapabilities.kt : 使えるエンコーダ・フィルタの判定と出力フォーマット
 * - FilterGraph.kt        : filter_complex の組み立て（FFmpegの地雷はほぼここ）
 * - ExportTextFiles.kt    : drawtextへ渡す行ごとのテキストファイル
 * - ExportAssets.kt       : フォント・効果音のassetsからの展開
 * - FFmpegRunner.kt       : 実行・進捗・中止
 * - GalleryOutput.kt      : MediaStoreへの保存
 */
object VlogExporter {

    /**
     * @param includeTitle 先頭にタイトルカード（黒背景＋日付＋効果音）を付けるかどうか。
     *   falseのときは全クリップを結合するだけで、タイトルカードもその効果音も含めない。
     * @param muted タイムライン全体のミュート。trueのときは各クリップの音声
     *   （[VlogClip.isMuted] の状態に関わらず全て）とタイトルカードの効果音を無音にする。
     * @param customTitleText タイトルカードに焼き込む文言。nullなら先頭クリップの
     *   撮影日（[VlogClip.dateText]）を使う。改行を含む場合は複数行として焼き込み、
     *   1行目の位置は変えずに下へ積む（FilterGraph.ktのbuildTitleFilter参照）。
     * @param onProgress 進捗の通知。呼び出しスレッドは決まっていない（FFmpegの統計
     *   コールバックのスレッドから直接呼ばれることがある）ので、実装側はどのスレッドから
     *   呼ばれても安全なようにしておくこと。進捗率が分からない工程では第2引数がnullになる。
     * @return ギャラリーに保存された表示名
     */
    suspend fun export(
        context: Context,
        clips: List<VlogClip>,
        includeTitle: Boolean = true,
        muted: Boolean = false,
        customTitleText: String? = null,
        onProgress: (message: String, progress: Float?) -> Unit
    ): String = withContext(Dispatchers.IO) {
        resetCancelRequest()
        require(clips.isNotEmpty()) { "クリップがありません" }
        // 追加時に上限を守っているが、上限を設ける前の保存データを復元した場合などに超えうる
        if (clips.size > MAX_CLIPS) {
            throw VlogExportException(
                "クリップが多すぎます（上限${MAX_CLIPS}本、現在${clips.size}本）。" +
                        "クリップを減らしてください"
            )
        }
        requireAllReadable(context, clips)
        requireFreeSpace(context, clips, includeTitle)

        // 作業ファイルはcacheDirに置く（OSが必要に応じて掃除してくれる領域）
        val workDir = workDir(context).apply { mkdirs() }
        val id = System.currentTimeMillis()
        // 書き出した動画自体の作成日時。タイトルカードの日付（撮影日）とは別に、
        // 書き出しを始めた現在時刻を、MP4のメタデータとギャラリーの撮影日時の両方へ入れる。
        val createdAtMillis = id
        val mergedFile = File(workDir, "merged_$id.mp4")
        // 行ごとのテキスト・フィルタグラフ・区切りの中間ファイルなど、終わったら消すもの
        val workFiles = mutableListOf<File>()

        try {
            requireDrawtext()
            val logoType = copyFontAsset(context, TITLE_FONT_ASSET)
            val time = copyFontAsset(context, TIME_FONT_ASSET)
            val fonts = ExportFonts(
                logoType = logoType,
                time = time,
                hitokotoBaselineShiftPt = baselineShiftPt(logoType, HITOKOTO_FONT_PT),
                timeBaselineShiftPt = baselineShiftPt(time, TIME_FONT_PT),
                titleBaselineShiftPt = baselineShiftPt(time, TITLE_DATE_FONT_PT)
            )

            // 各クリップの音声の有無とHDRかを、1本1回ずつ開いて調べる（ClipProbe.kt）。
            // 直列に回すと「準備中...」が本数ぶん伸びるので並列に。一斉に開かないよう本数は絞る
            val probes = clips.mapParallel(CLIP_PROBE_PARALLELISM) { _, clip -> probeClip(context, clip.uri) }
            // includeTitle・muted・クリップ個別isMutedの組み合わせ判定を先に1箇所へ
            // まとめておく。以降はこのAudioPlanを読むだけで、下の処理は分岐を持たない。
            val audioPlan = AudioPlan.of(clips, probes, includeTitle, muted)
            // HDRで撮ったクリップはSDRへ変換する（Hdr.kt）
            val hdrTransfers = probes.map { it.hdrTransfer }
            if (hdrTransfers.any { it != null }) {
                Log.i(LOG_TAG, "HDRのクリップ: ${hdrTransfers.withIndex().filter { it.value != null }.map { "${it.index + 1}本目=${it.value}" }}")
            }
            val titleSfx = if (audioPlan.needsTitleSfxInput) {
                copySfxAsset(context, TITLE_SFX_ASSET)
            } else null

            onProgress("書き出し中...", null)
            coroutineContext.ensureActive()

            // タイトルカードの文言は自由入力があればそちらを優先する（空/未入力のときの
            // フォールバックはTitleCreationDialog側で解決済み）。ファイル名はこの文言とは無関係に、
            // 書き出しを始めた現在の日付から作る。
            val titleText = customTitleText ?: clips.first().dateText
            val totalDurationMs = passDurationMs(clips, includeTitle)
            val creationTime = arrayOf("-metadata", "creation_time=${creationTimeMetadata(createdAtMillis)}")

            val segments = planSegments(clips.size)
            if (segments.size == 1) {
                // タイトルカード＋全クリップを、仮想タイムライン上に隙間なく並べて
                // 1回のFFmpeg呼び出しで結合・エンコードする。
                //
                // クリップごとに個別エンコードして結合し直すと同じ映像を2回圧縮することになるため、
                // 生の素材から直接1回だけエンコードする。30fps変換も結合後の連続した1本の
                // 映像に対して1回で完結する。
                encodePass(
                    context, clips, fonts, titleText, includeTitle, audioPlan, hdrTransfers, titleSfx,
                    workDir, passId = id, workFiles = workFiles, output = mergedFile,
                    outputArgs = aacAudioArgs() + creationTime,
                    progressOffsetMs = 0L, overallDurationMs = totalDurationMs, onProgress = onProgress
                )
            } else {
                // 本数が多いときは区切りごとに書き出してからつなぐ（理由はSegments.kt）。
                // 映像の圧縮はここでの1回だけで、つなぐときは再圧縮しない
                var doneMs = 0L
                val segmentFiles = segments.mapIndexed { index, range ->
                    coroutineContext.ensureActive()
                    val segmentClips = clips.slice(range)
                    val withTitle = includeTitle && index == 0
                    // 中間ファイルは.mov。Matroska（.mkv）はファイルの冒頭に映像の設定情報（SPS/PPS）を
                    // 書く必要があるが、h264_mediacodecは最初のコマを圧縮するまでそれを出さないため、
                    // 書き始めで失敗する（エミュレータで確認）。.movは目次を最後に書くので間に合い、PCMも入る
                    val file = File(workDir, "segment_${id}_$index.mov").also { workFiles += it }
                    encodePass(
                        context, segmentClips, fonts, titleText, withTitle,
                        audioPlan.forSegment(range, includesTitle = withTitle),
                        hdrTransfers.slice(range), titleSfx,
                        workDir, passId = id * SEGMENT_ID_SCALE + index, workFiles = workFiles,
                        output = file, outputArgs = pcmAudioArgs(),
                        progressOffsetMs = doneMs, overallDurationMs = totalDurationMs,
                        onProgress = onProgress
                    )
                    doneMs += passDurationMs(segmentClips, withTitle)
                    file
                }

                onProgress("仕上げ中...", null)
                val listFile = File(workDir, "concat_$id.txt")
                    .apply { writeText(concatListText(segmentFiles.map { it.absolutePath }), Charsets.UTF_8) }
                    .also { workFiles += it }
                runFFmpegWithProgress(
                    arrayOf(
                        "-f", "concat", "-safe", "0", "-i", listFile.absolutePath,
                        "-map", "0:v", "-map", "0:a",
                        // 映像は区切りで圧縮済みなので、そのままつなぐ（2回圧縮しない）
                        "-c:v", "copy",
                        *aacAudioArgs(),
                        *creationTime,
                        "-y", mergedFile.absolutePath
                    ),
                    totalDurationMs,
                    // つなぐだけで数秒で終わるので、進捗率は出さない（区切りの進捗から0%へ戻って見える）
                    onProgress = { _, _ -> }
                )
                // つないだ時点で中間ファイルは要らない。finallyまで残すと、ギャラリーへのコピー中も
                // 出来上がりと同じくらいの大きさ（100本で数百MB〜）を余計に抱えたままになる
                segmentFiles.forEach { it.delete() }
            }

            onProgress("保存中...", null)
            try {
                saveToGallery(context, mergedFile, createdAtMillis)
            } catch (e: IOException) {
                // ギャラリーへのコピーの途中で容量が尽きると「write failed: ENOSPC」のような文言になる
                if (isNoSpaceError(e.message)) throw VlogExportException(RAN_OUT_OF_SPACE_MESSAGE)
                throw e
            }
        } finally {
            // 成功・失敗・キャンセルいずれでも作業ファイルを掃除する
            mergedFile.delete()
            workFiles.forEach { it.delete() }
        }
    }

    /**
     * 1回分のFFmpeg呼び出し。1回で書き出すときは全クリップ、区切りごとに書き出すときは
     * その区切りのクリップだけを受け持つ。
     *
     * @param passId 行ごとのテキスト・フィルタグラフのファイル名に入れる番号。区切りごとに変える
     * @param outputArgs 映像の引数のあとに付ける引数（音声の形式・メタデータ）
     */
    private suspend fun encodePass(
        context: Context,
        clips: List<VlogClip>,
        fonts: ExportFonts,
        titleText: String,
        includeTitle: Boolean,
        audioPlan: AudioPlan,
        hdrTransfers: List<HdrTransfer?>,
        titleSfx: File?,
        workDir: File,
        passId: Long,
        workFiles: MutableList<File>,
        output: File,
        outputArgs: Array<String>,
        progressOffsetMs: Long,
        overallDurationMs: Long,
        onProgress: (message: String, progress: Float?) -> Unit
    ) {
        val filterGraph = buildFilterGraph(
            clips, fonts, titleText, titleSfxDelayMs(), workDir, passId, workFiles,
            includeTitle, audioPlan, hdrTransfers
        )
        // フィルタグラフは引数で渡さずファイルで渡す。本数が多いとグラフが数百KBに
        // なりうる（1クリップ約0.7〜1.5KB。100本で約70KB）。ファイルなら
        // 引数の長さに縛られず、コマンドのログも肥大しない。
        val graphFile = File(workDir, "graph_$passId.txt")
            .apply { writeText(filterGraph, Charsets.UTF_8) }
            .also { workFiles += it }
        Log.d(
            LOG_TAG,
            "filter_complex (${filterGraph.length}文字): ${filterGraph.take(COMMAND_LOG_MAX_CHARS)}"
        )

        // 入力はタイトル効果音を含めるときだけ 0=タイトル効果音、1..N=各クリップ
        // （SAF経由）。含めないときは効果音の-iを省き、0..N-1=各クリップになる。
        // タイトルの映像(color=)や無音クリップの音声(anullsrc=)は実体ファイルを
        // 要求しない生成フィルタなので、追加の-iは不要。
        //
        // 組み立てるのはFFmpegを走らせる直前。getSafParameterForReadは呼んだ時点で
        // 動画を開き、FFmpegが閉じるまで持ち続ける。フィルタグラフを組んでいる途中で
        // 中止・失敗すると誰も閉じず、書き出しのたびに最大で本数ぶん開きっぱなしになる。
        val inputs = buildList {
            if (audioPlan.needsTitleSfxInput) titleSfx?.let { addAll(listOf("-i", it.absolutePath)) }
            clips.forEach { clip ->
                addAll(clipInputArgs(clip, FFmpegKitConfig.getSafParameterForRead(context, clip.uri)))
            }
        }.toTypedArray()

        runFFmpegWithProgress(
            arrayOf(
                *inputs,
                "-filter_complex_script", graphFile.absolutePath,
                "-map", "[vout]", "-map", "[aout]",
                // fpsフィルタで既にCFR化済みなので、-rによる二重指定はしない
                *videoEncodeArgs(),
                *outputArgs,
                "-y", output.absolutePath
            ),
            passDurationMs(clips, includeTitle),
            onProgress,
            progressOffsetMs,
            overallDurationMs
        )
    }

    /** 1回分の書き出しの長さ（タイトルカード＋各クリップのトリム後の長さ） */
    private fun passDurationMs(clips: List<VlogClip>, includeTitle: Boolean): Long =
        (if (includeTitle) TITLE_DURATION_MS else 0L) + clips.sumOf { it.trimmedDurationMs }

    /**
     * 書き出しに要る空き容量があるかを、始める前に確かめる（見積もりはExportSpace.kt）。
     * 作業フォルダ（cacheDir）とギャラリーの保存先は、ふつう同じ領域にあるので、作業フォルダ側の
     * 空きで判断する。
     *
     * 空きは getAllocatableBytes で読む。usableSpace は、システムが必要に応じて消せる
     * 他のアプリのキャッシュを空きに数えないので、実際には書き出せるのに断ってしまうことがある。
     */
    private fun requireFreeSpace(context: Context, clips: List<VlogClip>, includeTitle: Boolean) {
        val required = requiredFreeBytes(
            durationMs = passDurationMs(clips, includeTitle),
            segmented = planSegments(clips.size).size > 1
        )
        val storage = context.getSystemService(StorageManager::class.java)
        val available = runCatching {
            storage.getAllocatableBytes(storage.getUuidForPath(context.cacheDir))
        }.getOrElse { context.cacheDir.usableSpace }
        if (available < required) throw VlogExportException(notEnoughSpaceMessage(required, available))
    }

    /**
     * 全クリップの動画が今も開けるかを、書き出しを始める前に確かめる。
     *
     * 編集中に動画が移動・削除された、権限が取り消されたなどで開けない動画が混ざっていると、
     * FFmpegが「saf:2.unknown: Invalid data found when processing input」のような
     * どの動画が原因か分からないメッセージで失敗していた。何本目のどの動画かを伝えて断る。
     */
    private fun requireAllReadable(context: Context, clips: List<VlogClip>) {
        val missing = clips.withIndex().filterNot { (_, clip) -> isReadable(context, clip.uri) }
        if (missing.isEmpty()) return
        val which = missing.joinToString("、") { (index, clip) -> "${index + 1}本目（${clip.timeText}）" }
        throw VlogExportException(
            "${which}の動画が見つかりません。移動・削除されたか、アクセス権限が取り消されています。" +
                    "タイムラインから外してから書き出してください"
        )
    }

    /** 実行中のFFmpeg処理を中断する。実体は FFmpegRunner.kt */
    fun cancel() = cancelRunningFFmpeg()

    private fun workDir(context: Context) = File(context.cacheDir, WORK_DIRECTORY)

    // ---------------------------------------------------------------------------------
    // 強制終了されたときの後始末
    //
    // 書き出し中にプロセスごと回収されると、MediaStore（IS_PENDINGのまま残った項目）と
    // cacheDir（結合途中の動画）の2箇所にゴミが残る。どちらもVlogViewModelのinitから、
    // 書き出しが走っていないときだけ呼ぶ。
    // ---------------------------------------------------------------------------------

    /**
     * 前回の書き出しが強制終了（OSによるプロセス回収、強制停止、クラッシュ等）で
     * 打ち切られた場合、cacheDirに結合途中の動画（merged_*.mp4）やフィルタグラフが残る。
     * [export]のfinallyはその回に作ったファイルしか消さないため、打ち切られた回の分は
     * 誰も片付けない。
     *
     * 中間ファイルは本数と尺に比例して大きく、100本の書き出しでは数GBになりうる。
     * cacheDirなのでOSはいずれ回収するが、それまで端末の空き容量を占め続ける。
     * [cleanupOrphanedPendingFiles]がMediaStore側で行っているのと同じ後始末を、
     * こちらでも起動時に行う。
     *
     * 呼び出し側は、実行中の書き出しが無いこと（[ExportStatus.isRunning] が false）を
     * 確認してから呼ぶこと。いま書き込み中のファイルを消してしまわないため。
     */
    fun cleanupOrphanedWorkFiles(context: Context) {
        runCatching {
            val files = workDir(context).listFiles() ?: return
            var bytes = 0L
            var deleted = 0
            files.forEach { file ->
                val size = file.length()
                if (file.delete()) {
                    bytes += size
                    deleted++
                }
            }
            if (deleted > 0) {
                Log.i(LOG_TAG, "強制終了で残った作業ファイルを削除しました: ${deleted}件 / ${bytes}バイト")
            }
        }
    }

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
}
