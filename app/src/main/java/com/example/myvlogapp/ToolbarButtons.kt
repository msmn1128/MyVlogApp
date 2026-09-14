package com.example.myvlogapp // ← ご自身のパッケージ名に合わせて変更してください

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.example.myvlogapp.ui.theme.DarkOnSplitMarker
import com.example.myvlogapp.ui.theme.DarkSplitMarker
import com.example.myvlogapp.ui.theme.LightOnSplitMarker
import com.example.myvlogapp.ui.theme.LightSplitMarker

// =====================================================================================
// MainActivity.kt から切り出した、操作バー・一時保存一覧など複数画面で共通に使う
// 小さなUI部品一式。TimelineToolbar固有ではなく、SavedProjectRow・EditorPaneの
// 区間バッジのように別の場所でも使うため独立したファイルに置いてある。
// =====================================================================================

/**
 * ひとことの区切りに使う紫。
 *
 * 波形のprimaryと同じ色にすると切れ目が埋もれて読めないため、
 * ライトでは一段濃く、ダークでは（濃い紫が背景に沈むので）同系色で明るくする。
 */
@Composable
fun splitMarkerColor(): Color =
    if (isSystemInDarkTheme()) DarkSplitMarker else LightSplitMarker

/** [splitMarkerColor] を下地にしたときの文字色。WaveformTrimmer.kt側からも使うため公開している */
@Composable
fun onSplitMarkerColor(): Color =
    if (isSystemInDarkTheme()) DarkOnSplitMarker else LightOnSplitMarker

/**
 * 分割済みの区間を示す紫のバッジ。
 * タイムラインのクリップタイル（"1-3"のような区間数）と、
 * 「ひとこと」入力欄（"2"のような編集中の区間番号）の2箇所で共通の見た目を使う。
 */
@Composable
internal fun SegmentBadge(
    text: String,
    fontSize: TextUnit,
    horizontalPadding: Dp
) {
    Text(
        text,
        fontSize = fontSize,
        fontWeight = FontWeight.Bold,
        color = onSplitMarkerColor(),
        modifier = Modifier
            .background(splitMarkerColor(), RoundedCornerShape(50))
            .padding(horizontal = horizontalPadding, vertical = 1.dp)
    )
}

/** タイムライン操作バーの仕切り */
@Composable
internal fun TimelineDivider() {
    Box(
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .width(1.dp)
            .height(18.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

/**
 * 操作バーのボタンの土台。円形の当たり判定＋背景色だけを担い、
 * 中身（アイコンと色）はCompactIconButton/TimelineToggleButtonそれぞれに任せる。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolbarButtonBox(
    background: Color,
    role: Role,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .size(TOOLBAR_BUTTON_SIZE)
            .clip(CircleShape)
            .background(background)
            .combinedClickable(
                enabled = enabled,
                role = role,
                onClickLabel = contentDescription,
                onLongClickLabel = onLongClickLabel,
                onLongClick = onLongClick,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center,
        content = content
    )
}

/**
 * 操作バー用の小さめアイコンボタン。タイムラインの操作バー以外（一時保存一覧の行など）でも使う。
 *
 * IconButtonは48dp固定で、並べると横幅の狭い端末で見出しごと押し出されてしまう。
 */
@Composable
internal fun CompactIconButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null
) {
    // 有効/無効はundo/redoなど編集のたびに切り替わるため、色の濃淡を補間する
    val contentAlpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.38f,
        label = "compactIconButtonAlpha"
    )
    // 長押しの操作（すべて削除など）は確認ダイアログを出さない代わりに、
    // 効いた瞬間が分かるようアイコンを一瞬だけ弾ませる。1件ずつの削除で
    // タイルがふわっと消えるのと動きの質を揃えて、操作の一体感を出すため
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val wrappedOnLongClick = onLongClick?.let { longClick ->
        {
            scope.launch {
                scale.snapTo(0.8f)
                scale.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow))
            }
            longClick()
        }
    }
    ToolbarButtonBox(
        background = Color.Transparent,
        role = Role.Button,
        contentDescription = contentDescription,
        enabled = enabled,
        onClick = onClick,
        onLongClick = wrappedOnLongClick,
        onLongClickLabel = onLongClickLabel,
        modifier = modifier
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint.copy(alpha = contentAlpha),
            modifier = Modifier
                .size(TOOLBAR_ICON_SIZE)
                .scale(scale.value)
        )
    }
}

/**
 * トリミングのプリセットボタン（「2s」「4s」）。
 * 他の操作バーボタンが正円のアイコンなのに対し、こちらは文字ラベルなので
 * 横幅がラベルぶん伸びる楕円にしてある。
 */
@Composable
internal fun TrimPresetButton(
    label: String,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    // CompactIconButtonと同じく、有効/無効の切り替わりを色の濃淡で補間する
    val contentAlpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.38f,
        label = "trimPresetButtonAlpha"
    )
    Box(
        modifier = Modifier
            .height(TOOLBAR_BUTTON_SIZE)
            .clip(RoundedCornerShape(50))
            .border(1.dp, tint.copy(alpha = contentAlpha), RoundedCornerShape(50))
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = contentDescription,
                onClick = onClick
            )
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = tint.copy(alpha = contentAlpha)
        )
    }
}

/**
 * オン/オフを持つ操作バーのボタン。
 *
 * 他がすべて「押したら1回起きる」動作なので、状態を持つこれだけは
 * オンのとき下地を塗って区別する（M3のicon toggle buttonと同じ見せ方）。
 */
@Composable
internal fun TimelineToggleButton(
    icon: ImageVector,
    checked: Boolean,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 背景・アイコン色・アイコンそのもの（ミュート⇔ミュート解除など）の
    // どれも真偽値の即切り替えだったため、色はクロスフェード、アイコンは
    // AnimatedContentでフェード入れ替えする
    val background by animateColorAsState(
        targetValue = if (checked && enabled) MaterialTheme.colorScheme.primaryContainer
        else Color.Transparent,
        label = "timelineToggleButtonBackground"
    )
    val tint by animateColorAsState(
        targetValue = when {
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            checked -> MaterialTheme.colorScheme.onPrimaryContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "timelineToggleButtonTint"
    )
    ToolbarButtonBox(
        background = background,
        role = Role.Switch,
        contentDescription = contentDescription,
        enabled = enabled,
        onClick = onClick,
        modifier = modifier
    ) {
        AnimatedContent(
            targetState = icon,
            label = "timelineToggleButtonIcon",
            transitionSpec = { fadeIn() togetherWith fadeOut() }
        ) { currentIcon ->
            Icon(
                imageVector = currentIcon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(TOOLBAR_ICON_SIZE)
            )
        }
    }
}
