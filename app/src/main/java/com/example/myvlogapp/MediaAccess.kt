package com.example.myvlogapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

// =====================================================================================
// メディア権限
//
// GalleryPicker.kt から分離。Composeに依存しない権限判定だけをここに集める。
// =====================================================================================

/** この端末で動画一覧に必要な権限。Android 14以降は「選択した項目のみ」も含む */
val mediaPermissions: Array<String> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
    )
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
        arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
    else ->
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

private fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/**
 * 動画を一覧できる状態か。
 *
 * 「選択した項目のみ許可」の場合は READ_MEDIA_VIDEO が拒否のままになるため、
 * どれか1つでも許可されていれば一覧できると判断する。
 */
fun hasMediaAccess(context: Context): Boolean = mediaPermissions.any { context.hasPermission(it) }

/**
 * 「選択した項目のみ許可」の状態か（Android 14以降）。
 *
 * この状態では一部の動画しか一覧に出ないため、対象を選び直す導線が必要になる。
 * 権限をもう一度リクエストすると、システムの選択画面が再表示される。
 */
fun hasPartialMediaAccess(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            context.hasPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) &&
            !context.hasPermission(Manifest.permission.READ_MEDIA_VIDEO)
