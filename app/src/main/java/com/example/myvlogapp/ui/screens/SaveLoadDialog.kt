package com.example.myvlogapp.ui.screens

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myvlogapp.data.SavedProject
import com.example.myvlogapp.defaultSaveName
import com.example.myvlogapp.formatSavedAt
import com.example.myvlogapp.formatSeconds
import com.example.myvlogapp.ui.components.CompactIconButton
import com.example.myvlogapp.ui.components.VlogIcons

// =====================================================================================
// MainActivity.kt から切り出した、一時保存の保存・読み出しダイアログ一式。
// =====================================================================================

/**
 * 一時保存の保存と読み出し。
 *
 * 自動保存とは別枠で、名前を付けた編集内容を何本か残せる。
 * ここでも動画そのものはコピーせず、URIと編集内容だけを持つので保存は一瞬で終わる。
 *
 * 保存と読み出しを1枚にまとめてあるのは、「いまの内容を置いてから別のを開く」という
 * 使い方がひと続きの動作になるため（別々の画面だと行き来が要る）。
 */
@Composable
internal fun SaveLoadDialog(
    projects: List<SavedProject>,
    canSave: Boolean,
    onSave: (String) -> Unit,
    onLoad: (Long) -> Unit,
    onOverwrite: (SavedProject) -> Unit,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    // 既定の保存名は今日の日付。同じ日に複数回保存したときは "(1)" のように連番を付ける。
    // 一覧は開いた直後に非同期で読み込まれるため、既定名は一覧の最新状態から都度求め、
    // ユーザーが打ち替えた場合だけその文字列を優先する（初回表示で一覧が空でも連番が付く）。
    var editedName by remember { mutableStateOf<String?>(null) }
    val name = editedName ?: defaultSaveName(System.currentTimeMillis(), projects.map { it.name })
    var pendingDelete by remember { mutableStateOf<SavedProject?>(null) }
    var pendingOverwrite by remember { mutableStateOf<SavedProject?>(null) }

    // 上書きも「もとに戻す」では戻せない（戻せるのはタイムラインの編集だけで、
    // 上書きされた保存の中身は失われる）。長押しでの誤操作を防ぐため確認を挟む
    pendingOverwrite?.let { target ->
        DestructiveConfirmDialog(
            icon = VlogIcons.File,
            title = "上書きしますか",
            message = "「${target.name}」を、いまの編集内容で上書きします。元の保存内容には戻せません。",
            confirmLabel = "上書き",
            onDismiss = { pendingOverwrite = null },
            onConfirm = {
                onOverwrite(target)
                pendingOverwrite = null
            }
        )
    }

    // 削除も「もとに戻す」で戻せないので確認を挟む
    pendingDelete?.let { target ->
        DestructiveConfirmDialog(
            icon = VlogIcons.Delete,
            title = "削除しますか",
            message = "「${target.name}」を削除します。元には戻せません。",
            confirmLabel = "削除",
            onDismiss = { pendingDelete = null },
            onConfirm = {
                onDelete(target.id)
                pendingDelete = null
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(VlogIcons.File, contentDescription = null) },
        title = { Text("編集内容の保存") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { editedName = it },
                    label = { Text("保存名") },
                    singleLine = true,
                    enabled = canSave,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = { onSave(name) },
                    enabled = canSave,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("この内容を保存") }

                Spacer(Modifier.height(14.dp))
                HorizontalDivider()
                Spacer(Modifier.height(10.dp))

                Text("保存した内容", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))

                if (projects.isEmpty()) {
                    Text(
                        "まだありません",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    // 件数は上限20なので、まとめてスクロールできれば足りる。
                    // LazyColumn + animateItem()で、削除した行が瞬時に消えず
                    // フェードアウトしながら後続の行が詰まるようにする
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 220.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(projects, key = { it.id }) { project ->
                            SavedProjectRow(
                                project = project,
                                onLoad = { onLoad(project.id) },
                                onOverwrite = { pendingOverwrite = project },
                                onDelete = { pendingDelete = project },
                                modifier = Modifier.animateItem()
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("閉じる") }
        }
    )
}

/**
 * 保存1件ぶんの行。タップで「読み出す」、長押しで「上書き保存」を兼ねる
 * （上書きは元に戻せないので、呼び出し側で確認ダイアログを挟む）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SavedProjectRow(
    project: SavedProject,
    onLoad: () -> Unit,
    onOverwrite: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClickLabel = "読み出す",
                onLongClickLabel = "上書き保存",
                onLongClick = onOverwrite,
                onClick = onLoad
            )
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    project.name,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${project.clipCount}本・${formatSeconds(project.totalMs)}" +
                            "　${formatSavedAt(project.savedAt)}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            CompactIconButton(
                icon = VlogIcons.Delete,
                contentDescription = "「${project.name}」を削除",
                enabled = true,
                onClick = onDelete,
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

/** 取り消せない操作（一時保存の削除・上書き）の確認ダイアログ。 */
@Composable
private fun DestructiveConfirmDialog(
    icon: ImageVector,
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(icon, contentDescription = null) },
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        }
    )
}
