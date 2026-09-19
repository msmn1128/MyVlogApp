package com.example.myvlogapp.edit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// =====================================================================================
// 「もとに戻す / やり直す」。
//
// VlogViewModel から切り出したもの。スナップショットの型を問わない純粋な仕組みで、
// Android にもExoPlayerにも依存しないため、そのままJVM単体テストにかけられる
// （切り出す前は ViewModel の中にあり、まとめ方の境界条件を直接テストできなかった）。
// =====================================================================================

/** 履歴に積む上限。1件あたりクリップ一覧の参照コピーなので軽い */
const val HISTORY_LIMIT = 50

/**
 * 同種の連続編集をひとつの履歴にまとめる時間。
 * スライダーを1回ドラッグしただけで数十件積まれると、「もとに戻す」を
 * 何度押しても元に戻らなくなるため。
 */
const val HISTORY_COALESCE_MS = 900L

/**
 * 履歴コピーの粒度を決めるタグ。
 * 同じタグの編集が[HISTORY_COALESCE_MS]以内に連続した場合はひとつの履歴にまとめる。
 * "trim:0"のような文字列連結にしないのは、タイプミスが「まとまるはずが別々に積まれる」
 * 「別操作なのにまとまってしまう」という気付きにくいバグに直結するため。型で表す。
 */
sealed interface EditTag {
    data class Trim(val clipIndex: Int) : EditTag
    data class TrimMove(val clipIndex: Int) : EditTag
    data class SplitMove(val clipIndex: Int, val segmentIndex: Int) : EditTag
    data class Text(val clipIndex: Int, val segmentIndex: Int) : EditTag
}

/**
 * 元に戻す・やり直すの履歴。
 *
 * スナップショットの中身（[S]）には関与しない。タイムラインの状態を丸ごと1つの値として
 * 受け取り、積んで、取り出すだけ。
 *
 * @param elapsedMs 単調増加の現在時刻（ミリ秒）。まとめ判定にしか使わない。
 *   実機では`SystemClock.elapsedRealtime`、テストでは好きな値を渡せるよう引数にしてある
 *   （壁時計だと時刻調整で巻き戻る可能性があるため、単調増加のものを渡すこと）。
 */
class EditHistory<S : Any>(
    private val elapsedMs: () -> Long,
    private val limit: Int = HISTORY_LIMIT,
    private val coalesceWindowMs: Long = HISTORY_COALESCE_MS
) {
    private val undoStack = ArrayDeque<S>()
    private val redoStack = ArrayDeque<S>()
    private var lastTag: EditTag? = null
    private var lastAt = 0L

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    /**
     * 変更を加える「直前」に呼ぶ。
     *
     * @param snapshot 変更前の状態
     * @param tag 同じタグの編集が [coalesceWindowMs] 以内に続いた場合はまとめる。
     *   スライダーのドラッグや文字入力のように連続で飛んでくる編集に付ける。
     *   nullを渡すと必ず1件として積まれる（追加・削除・並べ替えなど一発で完結する操作）。
     */
    fun record(snapshot: S, tag: EditTag? = null) {
        val now = elapsedMs()
        if (tag != null && tag == lastTag && now - lastAt < coalesceWindowMs) {
            lastAt = now
            return
        }

        undoStack.addLast(snapshot)
        if (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()

        lastTag = tag
        lastAt = now
        refreshFlags()
    }

    /**
     * ひとつ前の状態を取り出す。無ければ null。
     * @param current いまの状態（「やり直す」で戻れるよう、こちらへ積む）
     */
    fun undo(current: S): S? = step(from = undoStack, to = redoStack, current = current)

    /** [undo]の逆。無ければ null */
    fun redo(current: S): S? = step(from = redoStack, to = undoStack, current = current)

    /** undo/redoは互いに鏡像の処理なので1つにまとめてある。行き先のスタックだけが違う */
    private fun step(from: ArrayDeque<S>, to: ArrayDeque<S>, current: S): S? {
        val target = from.removeLastOrNull() ?: return null
        to.addLast(current)
        resetCoalescing()
        refreshFlags()
        return target
    }

    /**
     * 履歴を空にする。復元直後など「ここを起点にしたい」場面で呼ぶ
     * （消しておかないと、アプリを開いた直後に「もとに戻す」で空の状態へ戻れてしまう）。
     */
    fun clear() {
        undoStack.clear()
        redoStack.clear()
        resetCoalescing()
        refreshFlags()
    }

    private fun resetCoalescing() {
        lastTag = null
        lastAt = 0L
    }

    private fun refreshFlags() {
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }
}
