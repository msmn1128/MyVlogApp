package com.example.myvlogapp // ← ご自身のパッケージ名に合わせて変更してください

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 復元結果。dropped は権限が無くて復元できなかった件数 */
data class RestoredClips(val clips: List<VlogClip>, val dropped: Int)

/**
 * 一時保存した編集内容の見出し。
 *
 * 一覧を出すのに動画そのものを開く必要が無いよう、本数と尺だけ先に持っておく。
 */
data class SavedProject(
    val id: Long,
    val name: String,
    val savedAt: Long,
    val clipCount: Int,
    val totalMs: Long
)

/**
 * クリップ一覧の保存と復元。
 *
 * 動画の実体はコピーせず、URIと編集内容（ひとこと・トリミング位置・並び順）だけを残す。
 * 数GBの動画を複製しないので保存も起動も速い。
 *
 * 復元できるのは、いま実際に読み取れるURIに限られる。
 *  - ギャラリー（MediaStore）のURI : READ_MEDIA_VIDEO を保持している間ずっと読める
 *  - ファイル（SAF）のURI          : takePersistableUriPermission で取った権限が残っている間
 *
 * 権限の一覧と突き合わせるのではなく1件ずつ開いて確かめているのは、この2種類を
 * 同じ判定で扱えるうえ、元の動画が移動・削除された場合も同時に弾けるため。
 */
object ClipStore {

    private const val PREFS_NAME = "vlog_clips"
    private const val KEY_CLIPS = "clips"
    private const val KEY_AUTO_ADVANCE = "auto_advance"
    private const val KEY_PROJECTS = "projects"

    private fun Context.prefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(context: Context, clips: List<VlogClip>) {
        context.prefs().edit {
            putString(KEY_CLIPS, toJson(clips).toString())
        }
    }

    /** クリップ一覧をJSONへ。自動保存と一時保存で同じ形を使う */
    private fun toJson(clips: List<VlogClip>): JSONArray {
        val array = JSONArray()
        clips.forEach { clip ->
            array.put(
                JSONObject().apply {
                    put("uri", clip.uri.toString())
                    put("timeText", clip.timeText)
                    put("dateText", clip.dateText)
                    put("durationMs", clip.durationMs)
                    put("width", clip.width)
                    put("height", clip.height)
                    put("texts", JSONArray().apply {
                        clip.texts.forEach { segment ->
                            put(
                                JSONObject()
                                    .put("startMs", segment.startMs)
                                    .put("text", segment.text)
                            )
                        }
                    })
                    put("startMs", clip.startMs)
                    put("endMs", clip.endMs)
                }
            )
        }
        return array
    }

    /** 連続再生のオン/オフ。既定はオン（これまでの動き） */
    fun saveAutoAdvance(context: Context, enabled: Boolean) {
        context.prefs().edit {
            putBoolean(KEY_AUTO_ADVANCE, enabled)
        }
    }

    suspend fun restoreAutoAdvance(context: Context): Boolean = withContext(Dispatchers.IO) {
        context.prefs().getBoolean(KEY_AUTO_ADVANCE, true)
    }

    suspend fun restore(context: Context): RestoredClips = withContext(Dispatchers.IO) {
        val raw = context.prefs().getString(KEY_CLIPS, null)
            ?: return@withContext RestoredClips(emptyList(), 0)

        runCatching { fromJson(context, JSONArray(raw)) }.getOrElse { e ->
            Log.w(LOG_TAG, "クリップの復元に失敗しました", e)
            RestoredClips(emptyList(), 0)
        }
    }

    /**
     * JSONからクリップ一覧へ。いま読めないURIは落として件数だけ返す。
     *
     * 1件ごとにtry-catchするのは、壊れた・スキーマの古い1件のせいで
     * 残り全件の復元が巻き添えで消えるのを防ぐため。
     */
    private fun fromJson(context: Context, array: JSONArray): RestoredClips {
        var dropped = 0
        val clips = (0 until array.length()).mapNotNull { index ->
            runCatching {
                val json = array.getJSONObject(index)
                val uri = Uri.parse(json.getString("uri"))
                if (!isReadable(context, uri)) {
                    dropped++
                    return@runCatching null
                }
                VlogClip(
                    id = System.nanoTime() + index,
                    uri = uri,
                    timeText = json.getString("timeText"),
                    dateText = json.getString("dateText"),
                    durationMs = json.getLong("durationMs"),
                    width = json.getInt("width"),
                    height = json.getInt("height"),
                    texts = json.readTexts(),
                    startMs = json.getLong("startMs"),
                    endMs = json.getLong("endMs")
                )
            }.getOrElse { e ->
                Log.w(LOG_TAG, "1件のクリップ復元に失敗しました（この1件だけ落とします）", e)
                dropped++
                null
            }
        }
        return RestoredClips(clips, dropped)
    }

    // --- 一時保存 -----------------------------------------------------------------------
    //
    // 自動保存（KEY_CLIPS）が「アプリを閉じても続きから編集できる」ための1枠なのに対し、
    // こちらは名前を付けて何本も残せる枠。編集を分岐させたいとき用。
    // ここでも動画の実体はコピーせず、URIと編集内容だけを持つ。

    /** 保存できる本数の上限。SharedPreferencesに全件を1文字列で持つので際限なくは増やさない */
    const val MAX_PROJECTS = 20

    suspend fun listProjects(context: Context): List<SavedProject> = withContext(Dispatchers.IO) {
        readProjects(context).map { it.toSummary() }
    }

    /**
     * いまの編集内容を名前を付けて残す。
     * @return 上限に達していて保存できなかった場合は false
     */
    suspend fun saveProject(
        context: Context,
        name: String,
        clips: List<VlogClip>
    ): Boolean = withContext(Dispatchers.IO) {
        val projects = readProjects(context)
        if (projects.size >= MAX_PROJECTS) return@withContext false

        val entry = JSONObject()
            .put("id", System.currentTimeMillis())
            .put("name", name)
            .put("savedAt", System.currentTimeMillis())
            .put("clips", toJson(clips))

        writeProjects(context, projects + entry)
        true
    }

    /** 保存した内容を読み出す。見つからなければ null */
    suspend fun loadProject(context: Context, id: Long): RestoredClips? =
        withContext(Dispatchers.IO) {
            val entry = readProjects(context).firstOrNull { it.optLong("id") == id }
                ?: return@withContext null
            runCatching {
                fromJson(context, entry.getJSONArray("clips"))
            }.getOrElse { e ->
                Log.w(LOG_TAG, "一時保存の読み出しに失敗しました", e)
                null
            }
        }

    suspend fun deleteProject(context: Context, id: Long) = withContext(Dispatchers.IO) {
        writeProjects(context, readProjects(context).filterNot { it.optLong("id") == id })
    }

    /** 新しいものが上に来る並び。読み出したいのはたいてい直近のもの */
    private fun readProjects(context: Context): List<JSONObject> {
        val raw = context.prefs().getString(KEY_PROJECTS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { array.getJSONObject(it) }
                .sortedByDescending { it.optLong("savedAt") }
        }.getOrElse { e ->
            Log.w(LOG_TAG, "一時保存の一覧を読めませんでした", e)
            emptyList()
        }
    }

    private fun writeProjects(context: Context, projects: List<JSONObject>) {
        val array = JSONArray()
        projects.forEach { array.put(it) }
        context.prefs().edit { putString(KEY_PROJECTS, array.toString()) }
    }

    private fun JSONObject.toSummary(): SavedProject {
        val clips = optJSONArray("clips") ?: JSONArray()
        val totalMs = (0 until clips.length()).sumOf { index ->
            val clip = clips.getJSONObject(index)
            (clip.optLong("endMs") - clip.optLong("startMs")).coerceAtLeast(0L)
        }
        return SavedProject(
            id = optLong("id"),
            name = optString("name"),
            savedAt = optLong("savedAt"),
            clipCount = clips.length(),
            totalMs = totalMs
        )
    }

    /**
     * ひとことの区間を読む。
     *
     * 区間を持たせる前のバージョンで保存された分は "userText" しか無いので、
     * その1件を先頭区間として読み直す（更新しても前回の続きが消えない）。
     */
    private fun JSONObject.readTexts(): List<TextSegment> {
        val array = optJSONArray("texts")
            ?: return listOf(TextSegment(0L, optString("userText", DEFAULT_HITOKOTO)))

        // 昇順に直してから返す。区間の判定（textIndexAt / visibleTextSpans）は
        // 「前から順に並んでいる」前提で書かれているので、並びが崩れていると
        // ひとことが拾えない区間ができ、書き出しから文字が消える。
        val segments = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            TextSegment(
                startMs = item.optLong("startMs"),
                text = item.optString("text", DEFAULT_HITOKOTO)
            )
        }.sortedBy { it.startMs }

        // 先頭が0から始まらないと textAt が拾えない区間ができてしまう
        return segments.takeIf { it.isNotEmpty() && it.first().startMs == 0L }
            ?: listOf(TextSegment())
    }

    /** いまこのURIを開けるか。権限切れ・移動・削除をまとめて判定できる */
    private fun isReadable(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    }.getOrDefault(false)
}
