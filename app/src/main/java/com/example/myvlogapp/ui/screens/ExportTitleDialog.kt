package com.example.myvlogapp.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * 書き出しボタン（タップ＝タイトルあり）を押した直後に出す、タイトルカード文言の選択ダイアログ。
 *
 * 上の選択肢は先頭クリップの撮影日（既定で選択済み）、下は自由入力。
 * 自由入力欄は書き出し結果に焼き込む日付と同じフォント（[timeFontFamily]）で表示し、
 * 2行目以降になっても1行目の位置は動かず下に伸びる（[OutlinedTextField]は上辺基準で
 * 高さが伸びる既定動作のため、ここでは行の上端をRowの先頭に揃えるだけでよい）。
 *
 * 選択状態は`customText`1つだけで表す（null＝日付を選択中、非null＝自由入力を選択中）。
 * 自由入力欄への入力自体が選択を兼ねるので、別建てのラジオ選択肢の状態は持たない。
 *
 * @param onConfirm 「書き出し」タップ時に呼ばれる。自由入力が空/未選択なら[defaultDateText]を渡す
 *   （フォールバックの判定はここ1箇所だけで行い、呼び出し元やExporter側では持たない）。
 */
@Composable
internal fun TitleCreationDialog(
    defaultDateText: String,
    timeFontFamily: FontFamily,
    onDismiss: () -> Unit,
    onConfirm: (titleText: String) -> Unit
) {
    var customText by remember { mutableStateOf<String?>(null) }
    val isCustomSelected = customText != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("タイトル作成") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().selectableGroup()) {
                RadioOptionRow(
                    selected = !isCustomSelected,
                    onClick = { customText = null }
                ) {
                    Text(defaultDateText, fontFamily = timeFontFamily)
                }
                RadioOptionRow(
                    selected = isCustomSelected,
                    onClick = { if (!isCustomSelected) customText = "" },
                    verticalAlignment = Alignment.Top
                ) {
                    OutlinedTextField(
                        value = customText.orEmpty(),
                        onValueChange = { customText = it },
                        placeholder = { Text("タイトルを入力") },
                        textStyle = LocalTextStyle.current.copy(fontFamily = timeFontFamily),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(customText?.takeIf { it.isNotBlank() } ?: defaultDateText)
            }) { Text("書き出し") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        }
    )
}

/** ラジオボタン1個＋その選択肢の中身、という行の共通レイアウト。 */
@Composable
private fun RadioOptionRow(
    selected: Boolean,
    onClick: () -> Unit,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 8.dp),
        verticalAlignment = verticalAlignment
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        content()
    }
}
