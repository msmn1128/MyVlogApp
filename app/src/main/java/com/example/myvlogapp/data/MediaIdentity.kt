package com.example.myvlogapp.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore

// =====================================================================================
// 2つのURIが同じ動画を指しているかを見分ける鍵。
//
// 同じ動画でも、アプリ内ギャラリーから選ぶとMediaStoreのURI（content://media/external/video/media/123）、
// 「ファイル」（システムのファイル選択）から選ぶとSAFのURI（content://com.android.externalstorage.documents/...）
// になる。URIの一致だけで「追加済み」を見ていた頃は、両方から同じ動画を選ぶと、知らせも出ずに
// 2本入っていた（実機 SM-F971Q で確認）。
//
// 見分けるのに使うだけで、タイムラインに保存するURIは選ばれた形のまま変えない。ファイル選択を
// 使うのはギャラリーの許可を出していない人で、MediaStoreのURIに置き換えて保存すると、次に開いたとき
// 読めなくなるため。
// =====================================================================================

/**
 * [uri]が指す動画の鍵。同じ動画なら、ギャラリーとファイル選択のどちらから選んでも同じ鍵になる。
 *
 * ファイル選択のURIは、`MediaStore.getMediaUri`でMediaStoreのURIへ直してから鍵にする（端末への
 * 問い合わせなので、メインスレッドで呼ばないこと）。直せないもの（端末の中に無いクラウド上の
 * ファイルなど）は、URIそのものを鍵にする（別の動画と取り違えるより、重複を見逃す方を選ぶ）。
 */
internal fun sameVideoKey(context: Context, uri: Uri): String {
    mediaStoreKey(uri.authority, uri.lastPathSegment)?.let { return it }
    if (runCatching { DocumentsContract.isDocumentUri(context, uri) }.getOrDefault(false)) {
        runCatching { MediaStore.getMediaUri(context, uri) }.getOrNull()
            ?.let { mediaStoreKey(it.authority, it.lastPathSegment) }
            ?.let { return it }
    }
    return uri.toString()
}

/**
 * MediaStoreのURIなら、その番号から作った鍵。そうでなければ null。
 *
 * URIそのものではなく番号で比べるのは、同じ動画でもボリューム名が違うURIがありうるため
 * （ギャラリーの一覧は "external"、getMediaUriが返すのは "external_primary" など）。
 * 番号は端末の中の全ボリュームで重ならない。
 */
internal fun mediaStoreKey(authority: String?, lastPathSegment: String?): String? {
    if (authority != MediaStore.AUTHORITY) return null
    val id = lastPathSegment?.toLongOrNull() ?: return null
    return "media:$id"
}
