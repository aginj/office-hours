package app.officehours.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.officehours.MainActivity
import app.officehours.R
import app.officehours.alerts.OfficeAlerts
import app.officehours.data.AttendanceMath
import app.officehours.data.CredentialStore
import app.officehours.data.OfficeSnapshot
import app.officehours.data.ShiftSettings
import app.officehours.data.formatHoursMinutes
import app.officehours.data.formatRequiredLabel
import app.officehours.data.rolledForward
import app.officehours.work.RefreshWorker
import java.util.concurrent.TimeUnit

object WidgetUpdater {
    private const val COMPACT_HEIGHT_DP = 100

    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, OfficeWidgetProvider::class.java))
        val store = CredentialStore(context)
        val shift = store.loadShift()
        var snapshot = store.loadSnapshot()
        if (snapshot != null) {
            val rolled = snapshot.rolledForward(System.currentTimeMillis(), shift)
            if (rolled != null) {
                store.saveSnapshot(rolled)
                snapshot = rolled
                RefreshScheduler.refreshIfStale(context)
            }
        }
        ids.forEach { id ->
            manager.updateAppWidget(id, views(context, snapshot, shift, compact(manager.getAppWidgetOptions(id))))
        }
        OfficeAlerts.sync(context, snapshot, shift, store)
    }

    fun markRefreshing(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, OfficeWidgetProvider::class.java))
        val store = CredentialStore(context)
        val snapshot = store.loadSnapshot()
        val shift = store.loadShift()
        ids.forEach { id ->
            val compact = compact(manager.getAppWidgetOptions(id))
            val views = views(context, snapshot, shift, compact)
            if (!compact) views.setTextViewText(R.id.widget_detail, "Refreshing greytHR…")
            views.setTextViewText(R.id.widget_left, "Refreshing…")
            manager.updateAppWidget(id, views)
        }
    }

    private fun compact(options: Bundle?): Boolean {
        val minHeight = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, Int.MAX_VALUE) ?: Int.MAX_VALUE
        return minHeight in 1 until COMPACT_HEIGHT_DP
    }

    private fun views(context: Context, snapshot: OfficeSnapshot?, shift: ShiftSettings, compact: Boolean): RemoteViews {
        val layout = if (compact) R.layout.widget_office_small else R.layout.widget_office
        val views = RemoteViews(context.packageName, layout)
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val refresh = PendingIntent.getBroadcast(
            context,
            2,
            Intent(context, OfficeWidgetProvider::class.java).setAction(OfficeWidgetProvider.ACTION_REFRESH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.widget_root, openApp)
        views.setOnClickPendingIntent(R.id.widget_ring, openApp)
        views.setOnClickPendingIntent(R.id.widget_break, openApp)
        views.setOnClickPendingIntent(R.id.widget_left, openApp)
        views.setOnClickPendingIntent(R.id.widget_refresh, refresh)
        if (!compact) {
            views.setOnClickPendingIntent(R.id.widget_title, openApp)
            views.setOnClickPendingIntent(R.id.widget_detail, openApp)
            views.setTextViewText(R.id.widget_title, "Office · ${formatRequiredLabel(shift.requiredMillis)}")
        }
        views.setTextViewText(R.id.widget_break_label, "Break left for ${shift.endShortLabel()}")

        val now = System.currentTimeMillis()
        val accent = ContextCompat.getColor(context, R.color.widget_accent)
        if (snapshot == null || snapshot.error != null || snapshot.blocked) {
            views.setTextViewText(R.id.widget_break, "—")
            views.setTextViewText(R.id.widget_left, if (compact) context.getString(R.string.widget_signed_out) else "—")
            views.setTextColor(R.id.widget_break, accent)
            views.setImageViewBitmap(R.id.widget_ring, ring(context, 0f, accent, compact))
            if (!compact) {
                views.setTextViewText(
                    R.id.widget_detail,
                    snapshot?.error ?: context.getString(R.string.widget_signed_out),
                )
            }
            return views
        }

        val remaining = snapshot.remainingAt(now)
        val completed = snapshot.completedAt(now)
        val end = AttendanceMath.shiftEndEpochMillis(now, shift)
        val allowance = AttendanceMath.shiftBreak(snapshot, now, shift = shift)
        val started = AttendanceMath.hasStartedWork(snapshot) || snapshot.swipes.isNotEmpty()
        val color = when {
            !started -> accent
            remaining == 0L -> ContextCompat.getColor(context, R.color.status_good)
            now >= end || allowance < 0L -> ContextCompat.getColor(context, R.color.status_bad)
            allowance < 30L * 60L * 1000L -> ContextCompat.getColor(context, R.color.status_warn)
            else -> ContextCompat.getColor(context, R.color.status_good)
        }
        val (label, big) = when {
            !started -> "No swipes yet" to "—"
            now >= end && remaining == 0L -> "${shift.endShortLabel()} shift done" to "Done"
            now >= end -> "Past ${shift.endShortLabel()} · short by" to formatHoursMinutes(remaining)
            remaining == 0L -> "${shift.requiredLabel()} finished" to "Done"
            allowance > 0L -> "Break left for ${shift.endShortLabel()}" to formatHoursMinutes(allowance)
            allowance == 0L -> "No break left for ${shift.endShortLabel()}" to "0m"
            else -> "Short of ${shift.endShortLabel()} by" to formatHoursMinutes(-allowance)
        }
        views.setTextViewText(R.id.widget_break_label, label)
        views.setTextViewText(R.id.widget_break, big)
        views.setTextColor(R.id.widget_break, color)
        views.setTextViewText(
            R.id.widget_left,
            if (remaining == 0L) {
                "In office ${formatHoursMinutes(completed)}"
            } else {
                "${formatHoursMinutes(remaining)} left · in office ${formatHoursMinutes(completed)}"
            },
        )
        val fraction = if (snapshot.requiredMillis > 0L) completed.toFloat() / snapshot.requiredMillis else 0f
        views.setImageViewBitmap(R.id.widget_ring, ring(context, fraction, color, compact))
        if (!compact) views.setTextViewText(R.id.widget_detail, snapshot.detail)
        return views
    }

    private fun ring(context: Context, fraction: Float, color: Int, compact: Boolean): Bitmap {
        val density = context.resources.displayMetrics.density
        val sizePx = ((if (compact) 52 else 104) * density).toInt().coerceAtLeast(32)
        val strokePx = (if (compact) 6f else 11f) * density
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokePx
            strokeCap = Paint.Cap.ROUND
        }
        val inset = strokePx / 2f
        val rect = RectF(inset, inset, sizePx - inset, sizePx - inset)
        paint.color = ContextCompat.getColor(context, R.color.widget_track)
        canvas.drawArc(rect, 0f, 360f, false, paint)
        val sweep = 360f * fraction.coerceIn(0f, 1f)
        if (sweep > 0f) {
            paint.color = color
            canvas.drawArc(rect, -90f, sweep, false, paint)
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = ContextCompat.getColor(context, R.color.widget_text)
            textAlign = Paint.Align.CENTER
            textSize = (if (compact) 13f else 22f) * density
            isFakeBoldText = true
        }
        val percent = "${(fraction * 100f).toInt().coerceIn(0, 100)}%"
        val baseline = sizePx / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(percent, sizePx / 2f, baseline, textPaint)
        return bitmap
    }
}

object RefreshScheduler {
    private const val WORK_NAME = "office-hours-refresh"
    private const val ONCE_NAME = "office-hours-now"

    fun ensure(context: Context, immediate: Boolean = false) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        val work = WorkManager.getInstance(context)
        work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        if (immediate) {
            val once = OneTimeWorkRequestBuilder<RefreshWorker>()
                .setConstraints(constraints)
                .build()
            work.enqueueUniqueWork(ONCE_NAME, ExistingWorkPolicy.KEEP, once)
        }
        scheduleTick(context)
    }

    fun refreshNow(context: Context) {
        enqueueRefresh(context, ExistingWorkPolicy.REPLACE)
    }

    fun refreshIfStale(context: Context) {
        enqueueRefresh(context, ExistingWorkPolicy.KEEP)
    }

    private fun enqueueRefresh(context: Context, policy: ExistingWorkPolicy) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val once = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(ONCE_NAME, policy, once)
    }

    fun scheduleTick(context: Context) {
        val ids = AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, OfficeWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        alarm.set(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + 60_000L,
            tickIntent(context),
        )
    }

    fun cancel(context: Context) {
        val work = WorkManager.getInstance(context)
        work.cancelUniqueWork(WORK_NAME)
        work.cancelUniqueWork(ONCE_NAME)
        context.getSystemService(AlarmManager::class.java)?.cancel(tickIntent(context))
    }

    private fun tickIntent(context: Context): PendingIntent {
        val intent = Intent(context, OfficeWidgetProvider::class.java).setAction(OfficeWidgetProvider.ACTION_TICK)
        return PendingIntent.getBroadcast(
            context,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
