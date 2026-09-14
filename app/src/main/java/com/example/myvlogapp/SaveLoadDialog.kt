package com.example.myvlogapp // ← ご自身のパッケージ名に合わせて変更してください

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
    // 既定の保存名は今日の日付。同じ日に複数回保存したときは "(1)" のように連番を付ける
    var name by remember {
        mutableStateOf(defaultSaveName(System.currentTimeMillis(), projects.map { it.name }))
    }
    var pendingDelete by remember { mutableStateOf<SavedProject?>(null) }

    // 削除だけは「もとに戻す」で戻せないので確認を挟む
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
                    onValueChange = { name = it },
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
                    // 件数は上限20なので、まとめてスクロールできれば足りる
                    Column(
                        modifier = Modifier
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        projects.forEach { project ->
                            SavedProjectRow(
                                project = project,
                                onLoad = { onLoad(project.id) },
                                onOverwrite = { onOverwrite(project) },
                                onDelete = { pendingDelete = project }
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
 * （上書きは確認ダイアログを出さない）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SavedProjectRow(
    project: SavedProject,
    onLoad: () -> Unit,
    onOverwrite: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
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

@Composable
internal fun RemoveAllDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    DestructiveConfirmDialog(
        icon = VlogIcons.DeleteSweep,
        title = "すべて削除しますか",
        message = "タイムラインの動画をすべて外します。「もとに戻す」で元に戻せます。",
        confirmLabel = "すべて削除",
        onDismiss = onDismiss,
        onConfirm = onConfirm
    )
}

/**
 * 取り消せない操作の確認ダイアログ。
 * 「削除しますか」（一時保存の削除）と「すべて削除しますか」で見た目が同じだったのを共通化。
 */
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
