package com.example.myvlogapp

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

// =====================================================================================
// 並列処理のヘルパー。
//
// 動画のメタデータ取得（[VlogViewModel.addClips]）と、書き出し前に動画の中身を調べる処理
// （com.example.myvlogapp.export.probeClip）が同じ形を別々に書いていたのでまとめた。
// 復元時に動画を開けるかの確認（com.example.myvlogapp.data.ClipStore）でも使う。
// =====================================================================================

/**
 * 同時に走らせる数を[limit]本までに絞って並列に処理する。
 *
 * どの呼び出し元も「動画を1本ずつ開いて調べる重いI/O」で、全部を一斉に始めると
 * スレッドやファイルディスクリプタを食い尽くし、逆に直列にすると待ち時間が本数ぶん伸びる。
 *
 * 結果は受け取った順（添字順）で返る。完了した順ではないので、呼び出し側は添字で
 * 元の要素と対応づけられる。
 *
 * @param limit 同時に走らせる上限。適切な値は呼び出し元ごとに違うので、そちらが持つ
 * @param transform 添字と要素を受け取る。添字を渡すのは、フォールバック値を選択順に
 *   1件ずつずらす用途（[VlogClip.shotAtMillis]）があるため
 */
internal suspend fun <T, R> List<T>.mapParallel(
    limit: Int,
    transform: suspend (index: Int, value: T) -> R
): List<R> = coroutineScope {
    val gate = Semaphore(limit)
    mapIndexed { index, value ->
        async { gate.withPermit { transform(index, value) } }
    }.awaitAll()
}
