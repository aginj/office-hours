package app.officehours.alerts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.officehours.data.OfficeRepository

class AlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != OfficeAlerts.ACTION_ALERT) return
        val pending = goAsync()
        val app = context.applicationContext
        Thread {
            try {
                // Pull fresh swipes first so a swipe-out since the last refresh cannot trigger a wrong
                // "hours done" or "no break left" alert. A successful refresh syncs alerts itself.
                val refresh = Thread { runCatching { OfficeRepository(app).refresh() } }
                refresh.isDaemon = true
                refresh.start()
                refresh.join(REFRESH_WAIT_MILLIS)
            } finally {
                runCatching { OfficeAlerts.sync(app) }
                pending.finish()
            }
        }.start()
    }

    private companion object {
        const val REFRESH_WAIT_MILLIS = 8_000L
    }
}
