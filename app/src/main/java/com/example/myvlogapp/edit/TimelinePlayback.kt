package com.example.myvlogapp.edit

import com.example.myvlogapp.VlogClip

/**
 * [TimelineStore] が再生側に求める操作。実装は [com.example.myvlogapp.playback.PlaybackController]。
 *
 * PlaybackController を直接受け取っていた頃は、中身の ExoPlayer ごと要るため
 * TimelineStore を JVM の単体テストで動かせず、編集・undo/redo が1件もテストで
 * 守られていなかった（選択中クリップのミュートをundoしても音量が戻らない、という
 * 不具合もそのまま残っていた）。ここで切っておけば、テストでは偽物を渡せる。
 *
 * 各操作の意味は PlaybackController 側の同名メソッドを参照。
 * internal にしないのは、実装する PlaybackController が public なため
 * （public なクラスは internal なインターフェースを実装できない）。
 */
interface TimelinePlayback {
    val selectedIndexValue: Int
    val positionMsValue: Long

    fun select(index: Int)
    fun setSelectedIndex(index: Int)
    fun setPositionMs(positionMs: Long)
    fun seekWithoutPause(positionMs: Long)
    fun seekAndPause(positionMs: Long)
    fun pause()
    fun applyVolume()

    fun rebuildPlaylist(clips: List<VlogClip>)
    fun insertIntoPlaylist(insertions: List<Pair<Int, VlogClip>>)
    fun moveItem(from: Int, to: Int)
    fun removeItem(index: Int)
    fun clearItems()
}
