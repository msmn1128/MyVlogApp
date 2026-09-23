package com.example.myvlogapp

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myvlogapp.ui.screens.GalleryPickerDialog
import com.example.myvlogapp.ui.screens.SaveLoadDialog

/**
 * 動画を選ぶギャラリー・一時保存の一覧、2つのダイアログをまとめたもの。
 * [VlogAppScreen] から切り出したもの。
 *
 * ここではダイアログの開閉状態と結果コールバックだけを受け取り、
 * `rememberLauncherForActivityResult` 自体は [VlogAppScreen] 側に置いたままにしてある。
 * ランチャーをこの関数の中で生成すると、呼び出し位置がisWide分岐の外側であっても
 * このコンポーザブル自体が再生成されるたびにActivityResultRegistryとの紐付けが
 * 作り直されるおそれがあり、権限ダイアログのコールバックが失われるリスクを避けるため。
 */
@Composable
internal fun VlogAppDialogs(
    showGallery: Boolean,
    galleryReloadToken: Int,
    onDismissGallery: () -> Unit,
    onPickFromGallery: (List<Uri>) -> Unit,
    onUseFilePicker: () -> Unit,
    onRequestAccess: () -> Unit,
    showSaves: Boolean,
    onDismissSaves: () -> Unit,
    canSaveProject: Boolean,
    onLoadProject: (Long) -> Unit,
    viewModel: VlogViewModel
) {
    if (showGallery) {
        GalleryPickerDialog(
            reloadToken = galleryReloadToken,
            onDismiss = onDismissGallery,
            onPick = onPickFromGallery,
            onUseFilePicker = onUseFilePicker,
            onRequestAccess = onRequestAccess
        )
    }

    if (showSaves) {
        val projects by viewModel.projects.collectAsStateWithLifecycle()
        // 開くたびに読み直す。保存・削除のたびにViewModel側でも更新される
        LaunchedEffect(Unit) { viewModel.refreshProjects() }

        SaveLoadDialog(
            projects = projects,
            canSave = canSaveProject,
            onSave = viewModel::saveProject,
            onLoad = onLoadProject,
            onOverwrite = { project -> viewModel.overwriteProject(project.id, project.name) },
            onDelete = viewModel::deleteProject,
            onDismiss = onDismissSaves
        )
    }
}
