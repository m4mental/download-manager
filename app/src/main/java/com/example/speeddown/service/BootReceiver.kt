package com.example.speeddown.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.speeddown.MainActivity
import com.example.speeddown.data.DownloadStatus
import com.example.speeddown.data.DownloadStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val store = DownloadStore.getInstance(context)
                    val all = store.getAll()
                    val hasPending = all.any { it.status == DownloadStatus.PAUSED || it.status == DownloadStatus.QUEUED }
                    if (!hasPending) return@launch

                    val resumeAllIntent = Intent(context, DownloadService::class.java).apply {
                        action = DownloadService.ACTION_RESUME_ALL
                    }

                    if (Build.VERSION.SDK_INT >= 35) {
                        // On API 35+, post a notification with resume-all PendingIntent; do not start the service
                        postBootResumeNotification(context, resumeAllIntent)
                    } else {
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                context.startForegroundService(resumeAllIntent)
                            } else {
                                context.startService(resumeAllIntent)
                            }
                        } catch (e: Throwable) {
                            // Catch ForegroundServiceStartNotAllowedException (API 31+) during service start
                            postBootResumeNotification(context, resumeAllIntent)
                        }
                    }
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    private fun postBootResumeNotification(context: Context, resumeAllIntent: Intent) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val pendingIntent = PendingIntent.getService(
            context,
            2001,
            resumeAllIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, DownloadService.CHANNEL_ID)
            .setContentTitle("SpeedDown")
            .setContentText("Downloads pending. Tap to resume.")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_play, "Resume Downloads", pendingIntent)
            .setAutoCancel(true)
            .build()
        nm.notify(DownloadService.NOTIFICATION_ID + 1, notification)
    }
}
