package app.officehours.alerts

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import app.officehours.MainActivity
import app.officehours.R
import app.officehours.data.AttendanceMath
import app.officehours.data.CredentialStore
import app.officehours.data.OfficeSnapshot
import app.officehours.data.ShiftSettings
import app.officehours.data.formatHoursMinutes
import java.time.Instant
import java.time.LocalDate

object OfficeAlerts {
    const val ACTION_ALERT = "app.officehours.action.ALERT"
    const val CHANNEL_ID = "office_hours_alerts"

    private const val REQUEST_HOURS = 20
    private const val REQUEST_BREAK = 21
    private const val REQUEST_END = 22
    private const val NOTIFICATION_HOURS = 1
    private const val NOTIFICATION_BREAK = 2
    private const val NOTIFICATION_END = 3
    private const val DUE_SLACK_MILLIS = 2_000L
    private const val STALE_MILLIS = 20L * 60L * 1000L
    private val lock = Any()

    fun sync(context: Context) {
        val store = CredentialStore(context)
        sync(context, store.loadSnapshot(), store.loadShift(), store)
    }

    fun sync(
        context: Context,
        snapshot: OfficeSnapshot?,
        shift: ShiftSettings,
        store: CredentialStore,
    ) {
        synchronized(lock) {
            ensureChannel(context)
            val now = System.currentTimeMillis()
            val hoursAt = eventTime(snapshot, shift, now, Event.HOURS)
            val breakAt = eventTime(snapshot, shift, now, Event.BREAK)
            val endAt = eventTime(snapshot, shift, now, Event.END)
            maybeNotifyHours(context, store, snapshot, shift, hoursAt, now)
            maybeNotifyBreak(context, store, snapshot, shift, breakAt, now)
            maybeNotifyEnd(context, store, snapshot, shift, endAt, now)
            schedule(context, hoursAt, now, REQUEST_HOURS)
            schedule(context, breakAt, now, REQUEST_BREAK)
            schedule(context, endAt, now, REQUEST_END)
        }
    }

    private enum class Event { HOURS, BREAK, END }

    private fun eventTime(
        snapshot: OfficeSnapshot?,
        shift: ShiftSettings,
        now: Long,
        event: Event,
    ): Long? {
        if (snapshot == null || !snapshot.hasTimes) return null
        val zone = AttendanceMath.OFFICE_ZONE
        val today = LocalDate.now(zone)
        val snapshotDay = Instant.ofEpochMilli(snapshot.updatedAtEpochMillis).atZone(zone).toLocalDate()
        if (snapshotDay != today) return null
        return when (event) {
            Event.HOURS -> AttendanceMath.hoursDoneEpochMillis(snapshot, now)
            Event.BREAK -> AttendanceMath.breakBudgetZeroEpochMillis(snapshot, now, shift, zone)
            Event.END -> AttendanceMath.shortBeforeEndEpochMillis(snapshot, now, shift, zone)
        }
    }

    private fun maybeNotifyEnd(
        context: Context,
        store: CredentialStore,
        snapshot: OfficeSnapshot?,
        shift: ShiftSettings,
        at: Long?,
        now: Long,
    ) {
        if (at == null || snapshot == null) return
        val today = LocalDate.now(AttendanceMath.OFFICE_ZONE).toString()
        if (store.endNearAlertDate() == today) return
        if (at > now + DUE_SLACK_MILLIS) return
        if (at < now - STALE_MILLIS) {
            store.setEndNearAlertDate(today)
            return
        }
        val shiftEnd = AttendanceMath.shiftEndEpochMillis(now, shift)
        val shortBy = (snapshot.requiredMillis - snapshot.completedAt(shiftEnd)).coerceAtLeast(0L)
        if (shortBy == 0L) return
        val minutesLeft = ((shiftEnd - now) / 60_000L).coerceAtLeast(1L)
        val text = if (snapshot.openSinceEpochMillis != null) {
            "At ${shift.endLabel()} you will be ${formatHoursMinutes(shortBy)} short of ${shift.requiredLabel()}. " +
                "Staying on gets you there around ${doneAround(snapshot, now)}."
        } else {
            "You are signed out and ${formatHoursMinutes(shortBy)} short of ${shift.requiredLabel()}. " +
                "Swipe in now to cut that down before ${shift.endLabel()}."
        }
        notify(
            context = context,
            id = NOTIFICATION_END,
            title = "$minutesLeft min to ${shift.endShortLabel()}, still short",
            text = text,
        )
        store.setEndNearAlertDate(today)
    }

    private fun doneAround(snapshot: OfficeSnapshot, now: Long): String {
        val at = now + snapshot.remainingAt(now)
        return java.time.format.DateTimeFormatter.ofPattern("h:mm a")
            .withZone(AttendanceMath.OFFICE_ZONE)
            .format(Instant.ofEpochMilli(at))
    }

    private fun maybeNotifyHours(
        context: Context,
        store: CredentialStore,
        snapshot: OfficeSnapshot?,
        shift: ShiftSettings,
        at: Long?,
        now: Long,
    ) {
        if (at == null) return
        val today = LocalDate.now(AttendanceMath.OFFICE_ZONE).toString()
        if (store.hoursDoneAlertDate() == today) return
        if (at > now + DUE_SLACK_MILLIS) return
        if (at < now - STALE_MILLIS) {
            store.setHoursDoneAlertDate(today)
            return
        }
        if (snapshot?.remainingAt(now) != 0L) return
        notify(
            context = context,
            id = NOTIFICATION_HOURS,
            title = "${shift.requiredLabel()} done",
            text = "You have finished ${shift.requiredLabel()} in office.",
        )
        store.setHoursDoneAlertDate(today)
    }

    private fun maybeNotifyBreak(
        context: Context,
        store: CredentialStore,
        snapshot: OfficeSnapshot?,
        shift: ShiftSettings,
        at: Long?,
        now: Long,
    ) {
        if (at == null) return
        val today = LocalDate.now(AttendanceMath.OFFICE_ZONE).toString()
        if (store.breakZeroAlertDate() == today) return
        if (at > now + DUE_SLACK_MILLIS) return
        if (at < now - STALE_MILLIS) {
            store.setBreakZeroAlertDate(today)
            return
        }
        if (snapshot == null || snapshot.openSinceEpochMillis != null) return
        if (snapshot.remainingAt(now) == 0L) return
        val allowance = AttendanceMath.shiftBreak(snapshot, now, shift = shift)
        if (allowance > 0L) return
        notify(
            context = context,
            id = NOTIFICATION_BREAK,
            title = "No break left for ${shift.endShortLabel()}",
            text = "Stay in until ${shift.endLabel()} to finish ${shift.requiredLabel()}. Any more break means a later exit.",
        )
        store.setBreakZeroAlertDate(today)
    }

    private fun schedule(context: Context, at: Long?, now: Long, requestCode: Int) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = alarmIntent(context, requestCode)
        alarm.cancel(pending)
        if (at == null || at <= now + DUE_SLACK_MILLIS) return
        try {
            val exact = Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()
            if (exact) {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            }
        } catch (_: SecurityException) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    private fun notify(context: Context, id: Int, title: String, text: String) {
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(id, notification)
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Office hours",
            NotificationManager.IMPORTANCE_HIGH,
        )
        channel.description = "When required office time is done, when there is no break left for the end of the shift, " +
            "and shortly before the shift ends if the day is still short."
        manager.createNotificationChannel(channel)
    }

    private fun alarmIntent(context: Context, requestCode: Int): PendingIntent {
        val intent = Intent(context, AlertReceiver::class.java).setAction(ACTION_ALERT)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
