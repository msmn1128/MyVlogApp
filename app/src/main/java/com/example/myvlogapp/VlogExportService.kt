package com.example.myvlogapp // ← ご自身のパッケージ名に合わせて変更してください

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * VLOG書き出しをフォアグラウンドサービスとして実行する。
 *
 * 経緯：以前はViewModelのviewModelScopeで直接実行していたが、
 * フォアグラウンドサービスでもWorkManagerでもなかったため、
 * アプリをバックグラウンドに回すとOSがプロセスごと回収することがあった。
 * その場合try-finallyが実行されないまま打ち切られ、MediaStoreに
 * IS_PENDINGの壊れた動画が残ってしまう（[VlogExporter.cleanupOrphanedPendingFiles]
 * で次回起動時に掃除はするが、そもそも回収されにくくする方が先）。
 * フォアグラウンドサービス化してOSに「ユーザーが注視している処理」だと
 * 伝えることで、Activity/ViewModelが破棄されても処理を続けられるようにする。
 *
 * VlogClipはUriを含みプロセス内でしか意味を持たないため、Intentへ
 * シリアライズせず [pendingClips] へ直接渡してから起動する（この設計は
 * サービスと呼び出し元が常に同一プロセスであることが前提）。
 */
class VlogExportService : Service() {

    companion object {
        private const val CHANNEL_ID = "vlog_export"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_CANCEL = "com.example.myvlogapp.action.CANCEL_EXPORT"

        private var pendingClips: List<VlogClip>? = null
        private var pendingIncludeTitle: Boolean = true
        private var pendingMuted: Boolean = false

        fun start(
            context: Context,
            clips: List<VlogClip>,
            includeTitle: Boolean = true,
            muted: Boolean = false
        ) {
            pendingClips = clips
            pendingIncludeTitle = includeTitle
            pendingMuted = muted
            ContextCompat.startForegroundService(
                context,
                Intent(context, VlogExportService::class.java)
            )
        }

        /**
         * 実行中でなければ何もしない。
         *
         * 書き出しが終わった直後（サービスは既に停止済み）に「中止」を押すと、
         * バックグラウンド状態のアプリからの新規startServiceになり、
         * Android 8以降のバックグラウンド起動制限下ではIllegalStateExceptionが
         * 投げられうる。runCatchingで包むのは、その場合も「中止できなかった」だけで
         * アプリ全体を落とさないようにするため。
         */
        fun cancel(context: Context) {
            if (!ExportStatus.isRunning) return
            runCatching {
                context.startService(
                    Intent(context, VlogExportService::class.java).setAction(ACTION_CANCEL)
                )
            }
        }
    }

    // Activityに依存させないため、ViewModelScopeではなく自前のスコープを持つ
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var exportJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            VlogExporter.cancel()
            // 実行中なら、中止されたジョブのfinallyがstopSelfまで面倒を見る。
            // 実行中でない場合（書き出しが終わった直後に「中止」を押した等）は、
            // このIntentのせいで起動しただけのサービスが何もせず残り続けてしまうため、
            // ここで自分で畳む。
            val job = exportJob
            if (job?.isActive == true) job.cancel() else stopSelf()
            return START_NOT_STICKY
        }

        // 既に実行中なら多重起動しない（連打・二重タップ対策）
        if (exportJob?.isActive == true) return START_NOT_STICKY

        val clips = pendingClips
        pendingClips = null
        val includeTitle = pendingIncludeTitle
        val muted = pendingMuted
        if (clips.isNullOrEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundWithNotification("準備中...")
        ExportStatus.setRunning("準備中...")

        exportJob = serviceScope.launch {
            try {
                val name = VlogExporter.export(
                    context = applicationContext,
                    clips = clips,
                    includeTitle = includeTitle,
                    muted = muted,
                    onProgress = { message ->
                        ExportStatus.setRunning(message)
                        updateNotification(message)
                    }
                )
                ExportStatus.emit(VlogEvent.Message("ギャラリーに保存しました\n$name"))
            } catch (e: CancellationException) {
                ExportStatus.emit(VlogEvent.Message("書き出しを中止しました"))
            } catch (e: Exception) {
                ExportStatus.emit(VlogEvent.Message(e.message ?: "書き出しに失敗しました"))
            } finally {
                ExportStatus.setIdle()
                // stopForeground(true)相当。onDestroyに任せず自分で止める
                // （サービスが仕事を終えたのに通知が残り続けるのを防ぐ）
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        exportJob?.cancel()
        serviceScope.cancel()
        ExportStatus.setIdle()
        super.onDestroy()
    }

    private fun createChannel() {
        // NotificationChannelはAPI 26以降の概念。minSdkは24なので、
        // それ未満の端末では通知チャンネル自体を作らず素通りする
        // （NotificationCompat.Builderは26未満ではチャンネルIDを無視して動く）。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID, "VLOG書き出し", NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(message: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VLOGを書き出し中")
            .setContentText(message)
            // アプリ独自のアイコンはアダプティブアイコン形式で通知アイコンに使えないため、
            // システム標準の進行系アイコンを使う
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun startForegroundWithNotification(message: String) {
        val notification = buildNotification(message)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(message: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(message))
    }
}
