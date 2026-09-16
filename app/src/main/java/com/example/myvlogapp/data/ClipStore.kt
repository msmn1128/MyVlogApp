package com.example.myvlogapp.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import com.example.myvlogapp.LOG_TAG
import com.example.myvlogapp.VlogClip
import com.example.myvlogapp.VlogClipKeys
import com.example.myvlogapp.toJson
import com.example.myvlogapp.trimmedDurationMs

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

    /** 一時保存1件ぶんのJSONオブジェクトで使うキー名 */
    private object ProjectKeys {
        const val ID = "id"
        const val NAME = "name"
        const val SAVED_AT = "savedAt"
        const val CLIPS = "clips"
    }

    private fun Context.prefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * suspendにしていないのは、androidx.core.content.editの既定（apply()）が
     * 呼び出しスレッドをブロックせず非同期にコミットするため。restore系がsuspend +
     * Dispatchers.IOなのは、こちらはgetString/getBooleanの読み取り自体はブロッキングであり
     * （初回アクセス時にSharedPreferencesのバックグラウンド読み込みを待つことがある）、
     * Composeの呼び出し元スレッドを塞がないようにするため。書き込みと読み取りとで
     * 方針が違って見えるが、どちらも「呼び出し元スレッドを塞がない」という同じ意図。
     */
    fun save(context: Context, clips: List<VlogClip>) {
        context.prefs().edit {
            putString(KEY_CLIPS, clipsToJson(clips).toString())
        }
    }

    /** クリップ一覧をJSONへ。自動保存と一時保存で同じ形を使う */
    private fun clipsToJson(clips: List<VlogClip>): JSONArray {
        val array = JSONArray()
        clips.forEach { array.put(it.toJson()) }
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
        parseJsonArray(
            context.prefs().getString(KEY_CLIPS, null),
            default = RestoredClips(emptyList(), 0),
            errorMessage = "クリップの復元に失敗しました"
        ) { fromJson(context, it) }
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
                val uri = Uri.parse(json.getString(VlogClipKeys.URI))
                if (!isReadable(context, uri)) {
                    dropped++
                    return@runCatching null
                }
                VlogClip.fromJson(json, id = System.nanoTime() + index)
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

    /**
     * 一時保存一覧の読み込み→書き込みはread-modify-writeなので、保存と削除が
     * ほぼ同時に呼ばれると片方の変更が後勝ちで消えるレースになる。
     * 呼び出し全体をこのMutexで直列化して防ぐ。
     */
    private val projectsMutex = Mutex()

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
        projectsMutex.withLock {
            val projects = readProjects(context)
            if (projects.size >= MAX_PROJECTS) return@withLock false

            val entry = JSONObject()
                .put(ProjectKeys.ID, System.currentTimeMillis())
                .put(ProjectKeys.NAME, name)
                .put(ProjectKeys.SAVED_AT, System.currentTimeMillis())
                .put(ProjectKeys.CLIPS, clipsToJson(clips))

            writeProjects(context, projects + entry)
            true
        }
    }

    /**
     * 既存の保存内容へ上書きする（名前・idはそのまま、保存日時と中身だけ差し替え）。
     * @return 該当するidが無くて上書きできなかった場合は false
     */
    suspend fun overwriteProject(
        context: Context,
        id: Long,
        clips: List<VlogClip>
    ): Boolean = withContext(Dispatchers.IO) {
        projectsMutex.withLock {
            val projects = readProjects(context)
            val index = projects.indexOfFirst { it.optLong(ProjectKeys.ID) == id }
            if (index < 0) return@withLock false

            val updated = JSONObject(projects[index].toString())
                .put(ProjectKeys.SAVED_AT, System.currentTimeMillis())
                .put(ProjectKeys.CLIPS, clipsToJson(clips))

            writeProjects(context, projects.toMutableList().apply { set(index, updated) })
            true
        }
    }

    /** 保存した内容を読み出す。見つからなければ null */
    suspend fun loadProject(context: Context, id: Long): RestoredClips? =
        withContext(Dispatchers.IO) {
            val entry = projectsMutex.withLock {
                readProjects(context).firstOrNull { it.optLong(ProjectKeys.ID) == id }
            } ?: return@withContext null
            runCatching {
                fromJson(context, entry.getJSONArray(ProjectKeys.CLIPS))
            }.getOrElse { e ->
                Log.w(LOG_TAG, "一時保存の読み出しに失敗しました", e)
                null
            }
        }

    suspend fun deleteProject(context: Context, id: Long) = withContext(Dispatchers.IO) {
        projectsMutex.withLock {
            writeProjects(context, readProjects(context).filterNot { it.optLong(ProjectKeys.ID) == id })
        }
    }

    /** 新しいものが上に来る並び。読み出したいのはたいてい直近のもの */
    private fun readProjects(context: Context): List<JSONObject> =
        parseJsonArray(
            context.prefs().getString(KEY_PROJECTS, null),
            default = emptyList(),
            errorMessage = "一時保存の一覧を読めませんでした"
        ) { array ->
            (0 until array.length()).map { array.getJSONObject(it) }
                .sortedByDescending { it.optLong(ProjectKeys.SAVED_AT) }
        }

    /**
     * "rawがnullなら既定値、あればJSONArrayとしてparseしてactionに渡す。
     * 失敗したら既定値にフォールバック"という、復元系の各関数で共通していた形をまとめたもの。
     */
    private fun <T> parseJsonArray(raw: String?, default: T, errorMessage: String, action: (JSONArray) -> T): T {
        if (raw == null) return default
        return runCatching { action(JSONArray(raw)) }.getOrElse { e ->
            Log.w(LOG_TAG, errorMessage, e)
            default
        }
    }

    private fun writeProjects(context: Context, projects: List<JSONObject>) {
        val array = JSONArray()
        projects.forEach { array.put(it) }
        context.prefs().edit { putString(KEY_PROJECTS, array.toString()) }
    }

    private fun JSONObject.toSummary(): SavedProject {
        val clips = optJSONArray(ProjectKeys.CLIPS) ?: JSONArray()
        val totalMs = (0 until clips.length()).sumOf { index ->
            val clip = clips.getJSONObject(index)
            trimmedDurationMs(
                clip.optLong(VlogClipKeys.START_MS),
                clip.optLong(VlogClipKeys.END_MS)
            )
        }
        return SavedProject(
            id = optLong(ProjectKeys.ID),
            name = optString(ProjectKeys.NAME),
            savedAt = optLong(ProjectKeys.SAVED_AT),
            clipCount = clips.length(),
            totalMs = totalMs
        )
    }

    /** いまこのURIを開けるか。権限切れ・移動・削除をまとめて判定できる */
    private fun isReadable(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    }.getOrDefault(false)
}
