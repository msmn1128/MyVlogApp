package com.example.myvlogapp.data

import android.content.Context
import com.example.myvlogapp.VlogClip

/**
 * 一時保存の保存先。[ProjectsController]はこれ越しに読み書きする。
 *
 * 以前は[ClipStore]（SharedPreferences）を直接呼んでいたため、ProjectsControllerを
 * JVMの単体テストで動かせず、「読み込み中は保存させない」「全部開けない保存は読み出さない」
 * といった判断が1件もテストで守られていなかった。ここで切っておけば、テストでは記憶の中だけで
 * 動く偽物を渡せる。
 */
internal interface ProjectRepository {
    suspend fun list(): List<SavedProject>

    /** @return 実際に保存した名前（同名があれば連番付き）。上限で保存できなければ null */
    suspend fun save(name: String, clips: List<VlogClip>): String?

    /** @return 該当する保存が無くて上書きできなければ false */
    suspend fun overwrite(id: Long, clips: List<VlogClip>): Boolean

    /** @return 見つからない・読めなければ null */
    suspend fun load(id: Long): RestoredClips?

    suspend fun delete(id: Long)
}

/** 本番の保存先。[ClipStore]へそのまま委ねる */
internal class ClipStoreProjects(private val context: Context) : ProjectRepository {
    override suspend fun list() = ClipStore.listProjects(context)
    override suspend fun save(name: String, clips: List<VlogClip>) = ClipStore.saveProject(context, name, clips)
    override suspend fun overwrite(id: Long, clips: List<VlogClip>) = ClipStore.overwriteProject(context, id, clips)
    override suspend fun load(id: Long) = ClipStore.loadProject(context, id)
    override suspend fun delete(id: Long) = ClipStore.deleteProject(context, id)
}
