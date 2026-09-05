package com.example.myvlogapp // ← ご自身のパッケージ名に合わせて変更してください

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

// =====================================================================================
// 定数
// =====================================================================================

const val CANVAS_WIDTH = 1920
const val CANVAS_HEIGHT = 1080
const val CANVAS_FPS = 30

/** タイトル「Vlog.」／ ひとこと 用フォント */
const val TITLE_FONT_ASSET = "LogoTypeGothic.otf"

/** 撮影時刻／日付 用フォント */
const val TIME_FONT_ASSET = "MPLUSU-Regular.ttf"

/** タイトルカードの効果音（assets/sfx/ 以下のファイル名） */
const val TITLE_SFX_ASSET = "title.mp3"

/**
 * 効果音を鳴らすフレーム番号（1始まり）。
 * 例えば21なら、動画の21フレーム目（0始まりのnで数えるとn=20）から効果音が始まる。
 * CANVAS_FPSが30の場合、20/30秒 ≒ 約667ms地点。
 */
const val TITLE_SFX_FRAME_NUMBER = 21

// --- フォントサイズ（1920x1080キャンバス上のpt） -------------------------------------
// UI側とExporter側で数値が散らばると片方だけ変えたときに食い違うため、
// ここ1箇所に集約して両方から参照する。

const val HITOKOTO_FONT_PT = 70f      // ひとこと
const val HITOKOTO_LINE_SPACING_PT = 10f  // ひとことの行間
const val TIME_FONT_PT = 60f          // 撮影時刻
const val TITLE_FONT_PT = 150f        // タイトルカードの「Vlog.」
const val TITLE_DATE_FONT_PT = 50f    // タイトルカードの日付

// タイトルカードの縦位置。画面中央からのずれ（マイナスが上、プラスが下）
const val TITLE_Y_OFFSET_PT = -70f
const val TITLE_DATE_Y_OFFSET_PT = 80f

/** 撮影時刻の右余白（キャンバス上のpt） */
const val TIME_MARGIN_PT = 40f

/**
 * プレビューのみに掛かる文字の拡大率。
 * 1.0f が書き出し結果と同じ見え方。編集中に読みづらい場合は 1.2f〜1.5f に上げる。
 * 書き出される動画は変わらない。
 */
const val PREVIEW_FONT_SCALE = 1.0f

const val LOG_TAG = "VlogApp"

/** ひとことの初期値。区間を分割したときの後半にもこれが入る */
const val DEFAULT_HITOKOTO = "ひとこと"

/** これ以上は詰められないひとこと区間の長さ。短すぎる区間は読む前に消えてしまう */
const val MIN_TEXT_SEGMENT_MS = 400L

// =====================================================================================
// データモデル
// =====================================================================================

/**
 * タイムライン上の1クリップ。
 * Composeの再コンポーズを正しく発火させるため、全プロパティを val（不変）にして
 * 変更時は copy() で新しいインスタンスを作る。
 */
data class VlogClip(
    val id: Long,               // LazyRowのkey用の安定ID
    val uri: Uri,
    val timeText: String,       // 撮影時刻 "HH:mm"（24時間表記）
    val dateText: String,       // 撮影日 "yyyy/MM/dd"
    val durationMs: Long,
    val width: Int,             // 回転補正済みの実表示幅
    val height: Int,            // 回転補正済みの実表示高さ
    val texts: List<TextSegment> = listOf(TextSegment()),
    val startMs: Long = 0L,
    val endMs: Long = 0L
) {
    val trimmedDurationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
    val isValid: Boolean get() = durationMs > 0 && endMs > startMs

    /** 区切り位置（2番目以降の区間の頭）。波形に紫のラインを引くのに使う */
    val splitPoints: List<Long> get() = texts.drop(1).map { it.startMs }

    /** その位置に出るひとことが何番目の区間か。番号表示と編集対象の特定に使う */
    fun textIndexAt(positionMs: Long): Int =
        texts.indexOfLast { it.startMs <= positionMs }.coerceAtLeast(0)

    /** その位置に出るひとこと */
    fun textAt(positionMs: Long): String = texts[textIndexAt(positionMs)].text

    /**
     * 区切りのうち [positionMs] のすぐ近くにあるもの。無ければ null。
     *
     * 許容幅を尺に比例させているのは、長い動画ほど波形1px当たりの時間が長く、
     * 固定値だと「線の上に置いたつもり」でも外れてしまうため。
     */
    fun splitPointNear(positionMs: Long): Long? = splitPoints
        .minByOrNull { kotlin.math.abs(it - positionMs) }
        ?.takeIf { kotlin.math.abs(it - positionMs) <= splitToleranceMs() }

    private fun splitToleranceMs(): Long = (durationMs / 40).coerceIn(200L, 1500L)

    /**
     * トリミング範囲に実際に映るひとことを、区間ごとに切り出す。
     * 書き出しのdrawtextはこの区間ぶんだけ表示すればよい。
     *
     * トリミングで頭を落としたとき、その手前の区間は尺が無くなるので落とす。
     * ただし「トリム開始時点で出ている文字」は残す（indexOfLastで拾われる区間がそれ）。
     */
    fun visibleTextSpans(): List<TextSpan> {
        val first = textIndexAt(startMs)
        return texts.mapIndexedNotNull { index, segment ->
            if (index < first) return@mapIndexedNotNull null
            val spanStart = maxOf(segment.startMs, startMs)
            val spanEnd = texts.getOrNull(index + 1)?.startMs?.coerceAtMost(endMs) ?: endMs
            if (spanEnd <= spanStart) null
            else TextSpan(number = index + 1, startMs = spanStart, endMs = spanEnd, text = segment.text)
        }
    }
}

/**
 * クリップの途中でひとことを差し替えるための1区間。
 *
 * 動画そのものは分割しない。文字だけを時間で切り替えるので、
 * カット点でつなぎ目が生まれず、書き出しも1本のままで済む。
 *
 * [startMs] は動画内の絶対位置（トリミング位置と同じ基準）。先頭は必ず 0。
 */
data class TextSegment(
    val startMs: Long = 0L,
    val text: String = DEFAULT_HITOKOTO
)

/** [VlogClip.visibleTextSpans] の結果。number は画面に出す 1,2,3… の通し番号 */
data class TextSpan(
    val number: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String
)

data class VideoMeta(
    val timeText: String,
    val dateText: String,
    val durationMs: Long,
    val width: Int,
    val height: Int
)

/** 書き出しの進行状態。UIはこれを見るだけでよい */
sealed interface ExportState {
    data object Idle : ExportState
    data class Running(val message: String) : ExportState
}

/** Toastなど「1回だけ通知したい」イベント */
sealed interface VlogEvent {
    data class Message(val text: String) : VlogEvent
}

// =====================================================================================
// メタデータ取得
// =====================================================================================

/**
 * 動画から撮影日時・長さ・解像度を取得する。
 *
 * 撮影日時メタデータ（creation_time）は、SNS経由で共有された動画や
 * PCで変換した動画では失われていることが多いため、
 * 取得できない場合は MediaStore の追加日時にフォールバックする。
 */
fun getVideoMetadata(context: Context, uri: Uri): VideoMeta {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)

        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: 0L

        val shotAtMillis = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
            ?.let { parseCreationTime(it) }
            ?: queryMediaStoreDateMillis(context, uri)

        // 生の幅・高さは回転情報(90/270度)を反映していないため補正する。
        // 縦持ち撮影の動画は内部的に横長のままrotation=90が入っていることが多い。
        val rawWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            ?.toIntOrNull() ?: CANVAS_WIDTH
        val rawHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            ?.toIntOrNull() ?: CANVAS_HEIGHT
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            ?.toIntOrNull() ?: 0
        val (width, height) =
            if (rotation == 90 || rotation == 270) rawHeight to rawWidth else rawWidth to rawHeight

        VideoMeta(
            timeText = formatTime(shotAtMillis),
            dateText = formatDate(shotAtMillis),
            durationMs = durationMs,
            width = width.coerceAtLeast(1),
            height = height.coerceAtLeast(1)
        )
    } catch (e: Exception) {
        VideoMeta(formatTime(null), formatDate(null), 0L, CANVAS_WIDTH, CANVAS_HEIGHT)
    } finally {
        runCatching { retriever.release() }
    }
}

private fun parseCreationTime(raw: String): Long? = runCatching {
    SimpleDateFormat("yyyyMMdd'T'HHmmss.SSS'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .parse(raw)?.time
}.getOrNull()

private fun queryMediaStoreDateMillis(context: Context, uri: Uri): Long? = runCatching {
    context.contentResolver.query(
        uri, arrayOf(MediaStore.MediaColumns.DATE_ADDED), null, null, null
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getLong(0) * 1000L else null
    }
}.getOrNull()

/** 撮影時刻 "HH:mm"（24時間表記） */
private fun formatTime(millis: Long?): String =
    millis?.let { SimpleDateFormat("HH:mm", Locale.getDefault()).format(it) } ?: "00:00"

/** 撮影日 "yyyy/MM/dd" */
private fun formatDate(millis: Long?): String =
    millis?.let { SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(it) }
        ?: SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(System.currentTimeMillis())

// =====================================================================================
// 表示ヘルパー
// =====================================================================================

/** 一時保存の日時表示 "M/d HH:mm"。保存名の既定値と一覧の見出しで同じ形にする */
fun formatSavedAt(millis: Long): String =
    SimpleDateFormat("M/d HH:mm", Locale.getDefault()).format(millis)

/** 尺の表示 "m:ss"。タイムラインとギャラリーで表記を揃えるためここに1本だけ置く */
fun formatSeconds(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
