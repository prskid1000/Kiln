package app.kiln.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.PowerManager
import app.kiln.ui.MainActivity

/**
 * Foreground service held while an agent run is active, so a build-and-verify
 * loop keeps going with the screen off (the work itself runs in the app's
 * coroutines; this keeps the process foreground and the CPU awake).
 */
class RunService : Service() {
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(i: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Agent runs", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Kiln is working")
            .setContentText("Building and testing your app")
            .setContentIntent(open).setOngoing(true).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (wake == null) wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kiln:run").apply { acquire(60 * 60_000L) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wake?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    companion object { const val CHANNEL = "kiln_runs" }
}
