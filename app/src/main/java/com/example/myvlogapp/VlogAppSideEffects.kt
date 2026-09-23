package com.example.myvlogapp

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.myvlogapp.export.VlogEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * Toast通知・戻るボタン・バックグラウンド時の一時停止・再生位置ポーリングをまとめたもの。
 * [VlogAppScreen] から切り出したもの。
 */
@Composable
internal fun VlogAppSideEffects(viewModel: VlogViewModel, clips: List<VlogClip>) {
    val context = LocalContext.current

    // Toastなどの一過性イベント
    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is VlogEvent.Message -> Toast.makeText(context, event.text, Toast.LENGTH_LONG).show()
            }
        }
    }

    // 戻るボタンでActivityが終了するとViewModelごと破棄され、読み込んだ動画が消える。
    // タイムラインにクリップがある間は、ホームボタンと同じ「バックグラウンドへ回す」動きにして、
    // 戻ってきたときに作業を続けられるようにする。
    val activity = context as? Activity
    BackHandler(enabled = clips.isNotEmpty()) {
        activity?.moveTaskToBack(true)
    }

    // アプリが背面に回ったら再生を止める。前面に戻ったら、動画が開けるかを確かめ直す
    // （背面にいる間にギャラリーなど別のアプリで動画を消された、権限を取り消された場合に気付くため）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> viewModel.pause()
                Lifecycle.Event.ON_START -> viewModel.refreshMissingClips()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 再生位置の更新とトリミング範囲の連続再生。
    //
    // 回すのは「前面」かつ「再生中」のときだけ。
    //  - repeatOnLifecycle：バックグラウンドに回しても（BackHandlerでmoveTaskToBackした
    //    場合など）ループ自体はComposition生存中ずっと動き続けてしまうため
    //  - collectLatest(isPlaying)：一時停止中は再生位置が進まず、ポーリングしても
    //    毎回同じ値を読んで捨てるだけの空振りになるため。再生が止まると
    //    collectLatestが内側のループごとキャンセルし、再生を押すとまた始まる
    //
    // 一時停止中の位置あわせはポーリングではなく、シーク系の操作
    // （PlaybackControllerのseekWithoutPause/seekAndPause）が直接行っている。
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.isPlaying.collectLatest { playing ->
                if (!playing) return@collectLatest
                while (true) {
                    delay(PLAYBACK_POLL_INTERVAL_MS)
                    viewModel.refreshPlaybackProgress()
                }
            }
        }
    }
}
