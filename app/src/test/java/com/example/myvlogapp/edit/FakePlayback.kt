package com.example.myvlogapp.edit

import com.example.myvlogapp.VlogClip

/**
 * PlaybackController のうち、TimelineStore から見える状態だけを同じ規則で持つ偽物。
 * プレイリストの中身と自動遷移は持たない（ここでのテストの対象外）。
 */
internal class FakePlayback(private val clips: () -> List<VlogClip>) : TimelinePlayback {
    override var selectedIndexValue = 0
    override var positionMsValue = 0L
    var isPaused = true
    /** 実物と同じく、選択中クリップのミュートから決める */
    var volume = 1f

    override fun select(index: Int) {
        val target = clips().getOrNull(index) ?: return
        selectedIndexValue = index
        applyVolume()
        seekAndPause(target.startMs)
    }

    override fun setSelectedIndex(index: Int) { selectedIndexValue = index }
    override fun setPositionMs(positionMs: Long) { positionMsValue = positionMs }
    override fun seekWithoutPause(positionMs: Long) { positionMsValue = positionMs }

    override fun seekAndPause(positionMs: Long) {
        isPaused = true
        positionMsValue = positionMs
    }

    override fun pause() { isPaused = true }

    override fun applyVolume() {
        volume = if (clips().getOrNull(selectedIndexValue)?.isMuted == true) 0f else 1f
    }

    override fun rebuildPlaylist(clips: List<VlogClip>) = Unit
    override fun insertIntoPlaylist(insertions: List<Pair<Int, VlogClip>>) = Unit
    override fun moveItem(from: Int, to: Int) { selectedIndexValue = to }
    override fun removeItem(index: Int) = Unit

    override fun clearItems() {
        selectedIndexValue = 0
        positionMsValue = 0L
    }
}
