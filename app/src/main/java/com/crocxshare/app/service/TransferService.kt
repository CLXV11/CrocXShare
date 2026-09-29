package com.crocxshare.app.service

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.crocxshare.app.MainActivity
import com.crocxshare.app.R
import com.crocxshare.app.data.HistoryStore
import com.crocxshare.app.transfer.TransferStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service keeping transfers alive while the UI is gone.
 * A 1 GB transfer never restarts just because an Activity was closed.
 */
class TransferService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lastNotifiedStatus: TransferStatus? = null

    private val cancelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            (application as com.crocxshare.app.CrocXApp).container.transfer.cancel()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        registerReceiver(cancelReceiver, IntentFilter(ACTION_CANCEL))
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(cancelReceiver) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification("CrocXShare", getString(R.string.transfer_active), 0, 0.0))
        scope.launch {
            val app = application as com.crocxshare.app.CrocXApp
            var idleTicks = 0
            while (isActive) {
                delay(800)
                val s = app.container.transfer.state.value
                if (s.active && (s.status == TransferStatus.TRANSFERRING ||
                        s.status == TransferStatus.NEGOTIATING || s.status == TransferStatus.WAITING_ACCEPT)) {
                    idleTicks = 0
                    val pct = if (s.totalBytes > 0) (s.transferredBytes * 100 / s.totalBytes).toInt() else 0
                    val title = if (s.direction == com.crocxshare.app.transfer.TransferDirection.SEND)
                        getString(R.string.sending_to, s.peerName) else getString(R.string.receiving_from, s.peerName)
                    val text = HistoryStore.formatSize(s.transferredBytes) + " / " +
                        HistoryStore.formatSize(s.totalBytes) + "  •  " +
                        HistoryStore.formatSize(s.currentSpeedBps.toLong()) + "/s"
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIF_ID, buildNotification(title, text, pct, s.currentSpeedBps))
                } else if (s.status != lastNotifiedStatus && (s.status == TransferStatus.COMPLETED ||
                        s.status == TransferStatus.FAILED || s.status == TransferStatus.CANCELLED)) {
                    lastNotifiedStatus = s.status
                    idleTicks = 0
                    val text = when (s.status) {
                        TransferStatus.COMPLETED -> getString(R.string.transfer_done)
                        TransferStatus.FAILED -> getString(R.string.transfer_failed, s.message)
                        else -> getString(R.string.transfer_cancelled)
                    }
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIF_ID, buildNotification("CrocXShare", text, 100, 0.0))
                } else {
                    idleTicks++
                    if (idleTicks > 5) { stopSelf(); break }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(title: String, text: String, progress: Int, speed: Double): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (progress in 1..99) {
            b.setProgress(100, progress, false)
            val pi = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_CANCEL).setPackage(packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            b.addAction(0, getString(R.string.notif_cancel), pi)
        }
        return b.build()
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, getString(R.string.channel_transfers),
            NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "transfers"
        private const val NOTIF_ID = 42
        private const val ACTION_CANCEL = "com.crocxshare.app.action.CANCEL_TRANSFER"
    }
}
