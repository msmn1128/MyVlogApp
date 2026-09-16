package com.example.myvlogapp.export

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
import com.example.myvlogapp.VlogClip

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
        // 完了/中止/失敗の結果通知は進行中の通知（NOTIFICATION_ID）とは別IDにする。
        // stopSelf()で進行中の通知は消えるが、結果通知は別IDなので残り続け、
        // アプリを完全に閉じていても（ExportStatus.emitのToast購読者がいなくても）
        // ユーザーに結果が伝わる（iOS版のローカル通知相当）。
        private const val RESULT_NOTIFICATION_ID = 2
        private const val ACTION_CANCEL = "com.example.myvlogapp.action.CANCEL_EXPORT"

        /**
         * [start]から[onStartCommand]まで、書き出し内容をIntentを経由せず直接受け渡すための
         * 保留状態。1つのdata classにまとめてあるのは、書き出しオプションが増えるたびに
         * ここ・[start]の引数・[onStartCommand]の読み出し・[VlogExporter.export]呼び出しの
         * 4箇所を機械的に増やす作業を、フィールド追加1箇所で済ませるため。
         */
        private data class PendingExport(
            val clips: List<VlogClip>,
            val includeTitle: Boolean,
            val muted: Boolean,
            val customTitleText: String?
        )

        private var pendingExport: PendingExport? = null

        fun start(
            context: Context,
            clips: List<VlogClip>,
            includeTitle: Boolean = true,
            muted: Boolean = false,
            customTitleText: String? = null
        ) {
            pendingExport = PendingExport(clips, includeTitle, muted, customTitleText)
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

        val pending = pendingExport
        pendingExport = null
        if (pending == null || pending.clips.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val (clips, includeTitle, muted, customTitleText) = pending

        startForegroundWithNotification("準備中...")
        ExportStatus.setRunning("準備中...")

        exportJob = serviceScope.launch {
            try {
                val name = VlogExporter.export(
                    context = applicationContext,
                    clips = clips,
                    includeTitle = includeTitle,
                    muted = muted,
                    customTitleText = customTitleText,
                    onProgress = { message ->
                        ExportStatus.setRunning(message)
                        updateNotification(message)
                    }
                )
                val message = "ギャラリーに保存しました\n$name"
                ExportStatus.emit(VlogEvent.Message(message))
                notifyResult("書き出し完了", message)
            } catch (e: CancellationException) {
                ExportStatus.emit(VlogEvent.Message("書き出しを中止しました"))
                notifyResult("書き出しを中止しました", "書き出しを中止しました")
            } catch (e: Exception) {
                val message = e.message ?: "書き出しに失敗しました"
                ExportStatus.emit(VlogEvent.Message(message))
                notifyResult("書き出しに失敗しました", message)
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

    /// 完了・中止・失敗の結果を、進行中の通知とは別の通知として出す。
    /// setOngoing(true)の進行中通知はstopSelf()で消えてしまうため、それとは
    /// 独立に結果だけを伝える（Activityが破棄されていても届く）。
    private fun notifyResult(title: String, message: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(RESULT_NOTIFICATION_ID, notification)
    }
}
