package com.example.myvlogapp // ← ご自身のパッケージ名に合わせて変更してください

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** 書き出しの進行状態。UIはこれを見るだけでよい */
sealed interface ExportState {
    data object Idle : ExportState
    data class Running(val message: String) : ExportState
}

/** Toastなど「1回だけ通知したい」イベント */
sealed interface VlogEvent {
    data class Message(val text: String) : VlogEvent
}

/**
 * 書き出しの進行状態を持つ場所。
 *
 * ViewModelではなくプロセス全体で共有するシングルトンに置く理由：
 * 書き出し本体は [VlogExportService]（フォアグラウンドサービス）で動いており、
 * アプリをバックグラウンドに回してもActivity/ViewModelより長生きする。
 * ViewModelはここを覗くだけの購読者にすることで、画面が作り直されても
 * 進行中の書き出しの状態を取りこぼさない。
 */
object ExportStatus {
    private val _state = MutableStateFlow<ExportState>(ExportState.Idle)
    val state: StateFlow<ExportState> = _state.asStateFlow()

    /**
     * 保存完了・失敗などの一過性メッセージ。
     *
     * replay=0なので、購読者（ViewModel）がいない間に飛んだメッセージは配られずに消える。
     * これは意図した挙動で、replayを持たせるとアプリを開き直したときに前回の
     * 「保存しました」がもう一度Toastで出てしまう。extraBufferCapacity=1は、
     * 購読者の処理が一瞬遅れてもtryEmitが取りこぼさないための余裕。
     * 書き出し完了そのものは通知（[VlogExportService]）でも伝わる。
     */
    private val _events = MutableSharedFlow<VlogEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<VlogEvent> = _events.asSharedFlow()

    val isRunning: Boolean get() = _state.value is ExportState.Running

    fun setRunning(message: String) {
        _state.value = ExportState.Running(message)
    }

    fun setIdle() {
        _state.value = ExportState.Idle
    }

    fun emit(event: VlogEvent) {
        _events.tryEmit(event)
    }
}
