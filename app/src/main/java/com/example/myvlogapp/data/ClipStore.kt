package com.example.myvlogapp.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
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
import com.example.myvlogapp.mapParallel
import com.example.myvlogapp.nextClipId
import com.example.myvlogapp.toJson
import com.example.myvlogapp.uniqueSaveName
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

    /**
     * 復元のとき、動画を開けるか同時に確かめる本数。ファイルを開いてすぐ閉じるだけなので
     * メタデータの読み取り（4本）より多めにしてある
     */
    private const val READABLE_CHECK_PARALLELISM = 8

    /**
     * 一時保存だけを入れる保存領域。
     *
     * 以前は自動保存（KEY_CLIPS）と同じファイルにあった。SharedPreferencesは1つのファイルを
     * まるごと書き直すため、一時保存が増える（100本の保存が20件で約1MB）と、ひとことを
     * 1文字打つたびの自動保存が、無関係な一時保存ごと書き直してしまう。
     * 別のファイルに分けて、自動保存の書き込みを軽くする。
     */
    private const val PROJECTS_PREFS_NAME = "vlog_projects"

    /** 一時保存1件ぶんのJSONオブジェクトで使うキー名 */
    private object ProjectKeys {
        const val ID = "id"
        const val NAME = "name"
        const val SAVED_AT = "savedAt"
        const val CLIPS = "clips"
    }

    private fun Context.prefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun Context.projectPrefs() = getSharedPreferences(PROJECTS_PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var projectsMigrated = false

    /**
     * 一時保存を、以前の保存領域（自動保存と同じファイル）から新しい保存領域へ移す。1回だけ行う。
     *
     * データを失わないよう、新しい側へ確実に書けた（commit()が成功した）ことを確かめてから、
     * 古い側を消す。途中で失敗しても古い側に残るので、次の起動でやり直せる。
     * 呼び出しはすべてIOスレッド（commit()は書き込み完了を待つため）。
     *
     * apply()ではなくcommit()を使うのは、書けたかどうか（戻り値）を確かめてから古い側を消すため。
     * KTXのedit(commit = true)は戻り値を返さないので使えない。
     */
    @SuppressLint("ApplySharedPref", "UseKtx")
    private fun migrateProjectsIfNeeded(context: Context) {
        if (projectsMigrated) return
        synchronized(this) {
            if (projectsMigrated) return
            val legacy = context.prefs()
            val current = context.projectPrefs()
            when (projectsMigrationFor(legacy.contains(KEY_PROJECTS), current.contains(KEY_PROJECTS))) {
                ProjectsMigration.NOTHING -> Unit
                ProjectsMigration.COPY_AND_REMOVE -> {
                    val raw = legacy.getString(KEY_PROJECTS, null)
                    if (raw != null && current.edit().putString(KEY_PROJECTS, raw).commit()) {
                        legacy.edit().remove(KEY_PROJECTS).commit()
                    }
                }
                ProjectsMigration.REMOVE_LEGACY_ONLY -> legacy.edit().remove(KEY_PROJECTS).commit()
            }
            projectsMigrated = true
        }
    }

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
        val array = parseJsonArray(
            context.prefs().getString(KEY_CLIPS, null),
            default = null,
            errorMessage = "クリップの復元に失敗しました"
        ) { it } ?: return@withContext RestoredClips(emptyList(), 0)
        readableClips(context, array)
    }

    /**
     * JSONからクリップ一覧へ。いま読めないURIは落として件数だけ返す。
     *
     * 1件ごとに失敗を拾うのは、壊れた・スキーマの古い1件のせいで
     * 残り全件の復元が巻き添えで消えるのを防ぐため。
     *
     * 開けるかどうかの確認（[isReadable]）だけは並列に行う。1本ずつ実際に開くので、
     * クラウド上の動画やSDカードが混ざると本数ぶん待たされ、起動しても前回の続きが
     * なかなか出てこない。JSONの読み取りと組み立ては順番どおり（並びと通し番号を保つため）。
     */
    private suspend fun readableClips(context: Context, array: JSONArray): RestoredClips {
        val entries = (0 until array.length()).map { index ->
            runCatching {
                val json = array.getJSONObject(index)
                json to Uri.parse(json.getString(VlogClipKeys.URI))
            }.getOrElse { e ->
                Log.w(LOG_TAG, "1件のクリップ復元に失敗しました（この1件だけ落とします）", e)
                null
            }
        }
        val readable = entries.mapParallel(READABLE_CHECK_PARALLELISM) { _, entry ->
            entry != null && isReadable(context, entry.second)
        }
        val clips = entries.zip(readable).mapNotNull { (entry, isReadable) ->
            if (entry == null || !isReadable) return@mapNotNull null
            runCatching { VlogClip.fromJson(entry.first, id = nextClipId()) }.getOrElse { e ->
                Log.w(LOG_TAG, "1件のクリップ復元に失敗しました（この1件だけ落とします）", e)
                null
            }
        }
        return RestoredClips(clips, dropped = entries.size - clips.size)
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
     *
     * 同じ名前の保存がすでにあれば、「名前 (1)」のように連番を付けて保存する。
     * 判定は一覧を読んでから書き込むまでの間（ロックの中）で行うので、保存ボタンの連打などで
     * ほぼ同時に保存しても、同じ名前が並ばない。
     *
     * @return 実際に保存した名前（重複して連番が付いた場合はその名前）。
     *   上限に達していて保存できなかった場合は null
     */
    suspend fun saveProject(
        context: Context,
        name: String,
        clips: List<VlogClip>
    ): String? = withContext(Dispatchers.IO) {
        projectsMutex.withLock {
            val projects = readProjects(context)
            if (projects.size >= MAX_PROJECTS) return@withLock null

            val savedName = uniqueSaveName(name, projects.map { it.optString(ProjectKeys.NAME) })
            val now = System.currentTimeMillis()
            val entry = JSONObject()
                .put(ProjectKeys.ID, nextProjectId(projects, now))
                .put(ProjectKeys.NAME, savedName)
                .put(ProjectKeys.SAVED_AT, now)
                .put(ProjectKeys.CLIPS, clipsToJson(clips))

            writeProjects(context, projects + entry)
            savedName
        }
    }

    /**
     * 一時保存のid。基本は保存した時刻だが、同じミリ秒の中で2件保存されると衝突する。
     *
     * idは読み出し・上書き・削除の対象を指す唯一の手がかりなので、重複すると
     * [deleteProject]のfilterNotが**両方消す**（消したつもりのない保存が消える）。
     * 既存と重ならないところまでずらして、その形を作らせない。
     */
    private fun nextProjectId(projects: List<JSONObject>, now: Long): Long {
        val taken = projects.mapTo(HashSet()) { it.optLong(ProjectKeys.ID) }
        var candidate = now
        while (candidate in taken) candidate++
        return candidate
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

            // readProjectsは毎回パースし直した新しいオブジェクトを返すので、コピーせず直接書き換えてよい
            val updated = projects[index]
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
            val clips = runCatching { entry.getJSONArray(ProjectKeys.CLIPS) }.getOrElse { e ->
                Log.w(LOG_TAG, "一時保存の読み出しに失敗しました", e)
                return@withContext null
            }
            readableClips(context, clips)
        }

    suspend fun deleteProject(context: Context, id: Long) = withContext(Dispatchers.IO) {
        projectsMutex.withLock {
            writeProjects(context, readProjects(context).filterNot { it.optLong(ProjectKeys.ID) == id })
        }
    }

    /**
     * 一時保存の一覧が参照している動画のURI（文字列）。永続権限を解放してよいかの判断に使う。
     * 壊れた1件があっても、読める分だけは集める。
     */
    internal fun uriStringsInProjects(projects: List<JSONObject>): Set<String> = buildSet {
        projects.forEach { project ->
            val clips = project.optJSONArray(ProjectKeys.CLIPS) ?: return@forEach
            for (index in 0 until clips.length()) {
                clips.optJSONObject(index)
                    ?.optString(VlogClipKeys.URI)
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { add(it) }
            }
        }
    }

    /**
     * ファイル選択（SAF）で取った永続権限のうち、タイムラインにも一時保存にも使われていないものを解放する。
     * 保持数にはアプリごとの上限があり（Android 10以前は128、11以降は512）、解放しないと溜まる一方になる。
     *
     * 一時保存が参照している動画の権限は残す（読み出したときに動画を開けなくなるため）。
     * 一時保存の書き込みと同じロックの中で判断するので、保存の途中を見誤らない。
     *
     * @param isBusy 動画の追加中など、いまの状態では判断できないときはtrue（何もせず戻る）。
     *   追加中の動画はまだタイムラインに入っておらず、権限だけが先に取られているため
     * @param timelineUris 判断の時点でタイムラインにある動画のURI（呼ぶたびに最新を返すこと）
     */
    suspend fun releaseUnreferencedPermissions(
        context: Context,
        isBusy: () -> Boolean,
        timelineUris: () -> Collection<Uri>
    ) = withContext(Dispatchers.IO) {
        projectsMutex.withLock {
            if (isBusy()) return@withLock

            val referenced = HashSet<String>().apply {
                timelineUris().forEach { add(it.toString()) }
                addAll(uriStringsInProjects(readProjects(context)))
            }
            val resolver = context.contentResolver
            resolver.persistedUriPermissions
                .filter { it.isReadPermission && it.uri.toString() !in referenced }
                .forEach { grant ->
                    runCatching {
                        resolver.releasePersistableUriPermission(
                            grant.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                }
        }
    }

    /**
     * 新しいものが上に来る並び。読み出したいのはたいてい直近のもの。
     *
     * 壊れた要素（オブジェクトでないもの）は飛ばして、読める分を返す。1つでも壊れていると
     * 一覧全体が空になり、次の保存で全件が上書きされて消えてしまうため。
     */
    private fun readProjects(context: Context): List<JSONObject> {
        migrateProjectsIfNeeded(context)
        return parseJsonArray(
            context.projectPrefs().getString(KEY_PROJECTS, null),
            default = emptyList(),
            errorMessage = "一時保存の一覧を読めませんでした"
        ) { array ->
            (0 until array.length()).mapNotNull { array.optJSONObject(it) }
                .sortedByDescending { it.optLong(ProjectKeys.SAVED_AT) }
        }
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
        migrateProjectsIfNeeded(context)
        val array = JSONArray()
        projects.forEach { array.put(it) }
        context.projectPrefs().edit { putString(KEY_PROJECTS, array.toString()) }
    }

    private fun JSONObject.toSummary(): SavedProject {
        val clips = optJSONArray(ProjectKeys.CLIPS) ?: JSONArray()
        // getJSONObjectではなくoptJSONObjectで読み、オブジェクトでない要素は尺0として飛ばす。
        // ここで例外を投げると一覧の読み込みごと失敗し、一時保存を開くたびにアプリが落ちる
        // （[readProjects]が壊れた1件を飛ばしているのと同じ理由）
        val totalMs = (0 until clips.length()).sumOf { index ->
            val clip = clips.optJSONObject(index) ?: return@sumOf 0L
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

/** 一時保存を新しい保存領域へ移すときの処理（[ClipStore]の移行で使う） */
internal enum class ProjectsMigration {
    /** 移すものが無い（以前の保存領域に一時保存が無い） */
    NOTHING,

    /** 新しい側へコピーし、書けたことを確かめてから古い側を消す */
    COPY_AND_REMOVE,

    /** 新しい側にすでにある。新しい側を正として、古い側だけを消す */
    REMOVE_LEGACY_ONLY
}

internal fun projectsMigrationFor(legacyExists: Boolean, currentExists: Boolean): ProjectsMigration = when {
    !legacyExists -> ProjectsMigration.NOTHING
    currentExists -> ProjectsMigration.REMOVE_LEGACY_ONLY
    else -> ProjectsMigration.COPY_AND_REMOVE
}
