package com.example.myvlogapp.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * パープルを基調にしたカラーパレット。
 *
 * ダイナミックカラー（端末の壁紙から色を作る機能）は使わない。
 * 使うと端末ごとに配色が変わってしまい、「パープル基調」という指定を守れないため。
 *
 * ダーク側は純黒(#000000)ではなく、わずかに紫を含んだ濃灰(#141218)を土台にしている。
 * 純黒だとカードとの境目が消え、有機ELのスミアリング（スクロール時の残像）も出やすい。
 * 明度差はM3のトーン間隔に沿わせてあるので、面が重なっても階層が読み取れる。
 */

// --- ライト ---------------------------------------------------------------------------

val LightPrimary = Color(0xFF6B3FD4)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFE9DDFF)
val LightOnPrimaryContainer = Color(0xFF23005C)

val LightSecondary = Color(0xFF625B71)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFE8DEF8)
val LightOnSecondaryContainer = Color(0xFF1E192B)

val LightTertiary = Color(0xFF7D5260)
val LightOnTertiary = Color(0xFFFFFFFF)
val LightTertiaryContainer = Color(0xFFFFD8E4)
val LightOnTertiaryContainer = Color(0xFF31101D)

val LightError = Color(0xFFBA1A1A)
val LightOnError = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFFFDAD6)
val LightOnErrorContainer = Color(0xFF410002)

val LightBackground = Color(0xFFFDF7FF)
val LightOnBackground = Color(0xFF1D1B20)
val LightSurface = Color(0xFFFDF7FF)
val LightOnSurface = Color(0xFF1D1B20)
val LightSurfaceVariant = Color(0xFFE7E0EB)
val LightOnSurfaceVariant = Color(0xFF49454E)

val LightSurfaceDim = Color(0xFFDED8E1)
val LightSurfaceBright = Color(0xFFFDF7FF)
val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
val LightSurfaceContainerLow = Color(0xFFF7F2FA)
val LightSurfaceContainer = Color(0xFFF3EDF7)
val LightSurfaceContainerHigh = Color(0xFFECE6F0)
val LightSurfaceContainerHighest = Color(0xFFE6E0E9)

val LightOutline = Color(0xFF7A757F)
val LightOutlineVariant = Color(0xFFCAC4CF)
val LightInverseSurface = Color(0xFF322F35)
val LightInverseOnSurface = Color(0xFFF5EFF7)
val LightInversePrimary = Color(0xFFCFBCFF)

// --- ダーク ---------------------------------------------------------------------------

val DarkPrimary = Color(0xFFCFBCFF)
val DarkOnPrimary = Color(0xFF390094)
val DarkPrimaryContainer = Color(0xFF5228BB)
val DarkOnPrimaryContainer = Color(0xFFE9DDFF)

val DarkSecondary = Color(0xFFCCC2DC)
val DarkOnSecondary = Color(0xFF332D41)
val DarkSecondaryContainer = Color(0xFF4A4458)
val DarkOnSecondaryContainer = Color(0xFFE8DEF8)

val DarkTertiary = Color(0xFFEFB8C8)
val DarkOnTertiary = Color(0xFF492532)
val DarkTertiaryContainer = Color(0xFF633B48)
val DarkOnTertiaryContainer = Color(0xFFFFD8E4)

val DarkError = Color(0xFFFFB4AB)
val DarkOnError = Color(0xFF690005)
val DarkErrorContainer = Color(0xFF93000A)
val DarkOnErrorContainer = Color(0xFFFFDAD6)

val DarkBackground = Color(0xFF141218)
val DarkOnBackground = Color(0xFFE6E0E9)
val DarkSurface = Color(0xFF141218)
val DarkOnSurface = Color(0xFFE6E0E9)
val DarkSurfaceVariant = Color(0xFF49454E)
val DarkOnSurfaceVariant = Color(0xFFCAC4CF)

val DarkSurfaceDim = Color(0xFF141218)
val DarkSurfaceBright = Color(0xFF3B383E)
val DarkSurfaceContainerLowest = Color(0xFF0F0D13)
val DarkSurfaceContainerLow = Color(0xFF1D1B20)
val DarkSurfaceContainer = Color(0xFF211F26)
val DarkSurfaceContainerHigh = Color(0xFF2B2930)
val DarkSurfaceContainerHighest = Color(0xFF36343B)

val DarkOutline = Color(0xFF948F99)
val DarkOutlineVariant = Color(0xFF49454E)
val DarkInverseSurface = Color(0xFFE6E0E9)
val DarkInverseOnSurface = Color(0xFF322F35)
val DarkInversePrimary = Color(0xFF6B3FD4)

// --- ひとことの区切り ------------------------------------------------------------------
// 波形の上に引く区切り線と番号の色。M3のスキームには入れず定数で持つのは、
// primary（紫）と同じ色にすると波形に埋もれて「どこで切れているか」が読めないため。

/** ライト：波形（LightPrimary）より一段濃い紫 */
val LightSplitMarker = Color(0xFF4A0E86)

/** ダーク：濃い紫は背景(#36343B)に沈むので、同系色のまま明度だけ上げる */
val DarkSplitMarker = Color(0xFFC77DFF)

/** 区切りに振る番号の文字色（上の色を下地にしたときに読める側） */
val LightOnSplitMarker = Color(0xFFFFFFFF)
val DarkOnSplitMarker = Color(0xFF2C0060)
