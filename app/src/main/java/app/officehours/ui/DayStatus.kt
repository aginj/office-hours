package app.officehours.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import app.officehours.data.AttendanceMath
import app.officehours.data.DayMarker
import app.officehours.data.DaySummary
import app.officehours.data.OfficeSnapshot
import app.officehours.data.ShiftSettings
import java.time.DayOfWeek
import java.time.LocalDate

/** One colour-coded reading of a day. */
enum class DayStatus {
    /** Required time finished. */
    DONE,
    /** Still comfortably on track for a shift-end exit. */
    GOOD,
    /** Little or no break budget left. */
    TIGHT,
    /** Will be, or was, short at shift end. */
    SHORT,
    /** Leave or holiday. */
    OFF,
    /** Nothing recorded yet, or a day that has not come. */
    NONE,
}

private const val TIGHT_BUDGET_MILLIS = 30L * 60L * 1000L

fun todayStatus(snapshot: OfficeSnapshot, shift: ShiftSettings, now: Long): DayStatus {
    if (!AttendanceMath.hasStartedWork(snapshot) && snapshot.swipes.isEmpty()) return DayStatus.NONE
    val remaining = snapshot.remainingAt(now)
    if (remaining == 0L) return DayStatus.DONE
    val shiftEnd = AttendanceMath.shiftEndEpochMillis(now, shift)
    if (now >= shiftEnd) return DayStatus.SHORT
    val allowance = AttendanceMath.shiftBreak(snapshot, now, shift = shift)
    return when {
        allowance < 0L -> DayStatus.SHORT
        allowance < TIGHT_BUDGET_MILLIS -> DayStatus.TIGHT
        else -> DayStatus.GOOD
    }
}

fun pastDayStatus(day: DaySummary, shift: ShiftSettings, today: LocalDate): DayStatus {
    if (day.marker == DayMarker.HOLIDAY || day.marker == DayMarker.LEAVE) {
        return if (day.hasSwipes && day.inOfficeMillis >= shift.requiredMillis) DayStatus.DONE else DayStatus.OFF
    }
    if (day.date.isAfter(today)) return DayStatus.NONE
    if (!day.hasSwipes) return DayStatus.NONE
    return if (day.inOfficeMillis >= shift.requiredMillis) DayStatus.DONE else DayStatus.SHORT
}

fun isWeekend(date: LocalDate): Boolean =
    date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY

@Composable
fun DayStatus.color(): Color {
    val status = OfficeStyle.status
    return when (this) {
        DayStatus.DONE, DayStatus.GOOD -> status.good
        DayStatus.TIGHT -> status.warn
        DayStatus.SHORT -> status.bad
        DayStatus.OFF -> status.leave
        DayStatus.NONE -> MaterialTheme.colorScheme.outlineVariant
    }
}

@Composable
fun DayStatus.containerColor(): Color {
    val status = OfficeStyle.status
    return when (this) {
        DayStatus.DONE, DayStatus.GOOD -> status.goodContainer
        DayStatus.TIGHT -> status.warnContainer
        DayStatus.SHORT -> status.badContainer
        DayStatus.OFF -> status.leaveContainer
        DayStatus.NONE -> MaterialTheme.colorScheme.surfaceVariant
    }
}

@Composable
fun DayMarker.color(): Color = when (this) {
    DayMarker.HOLIDAY -> OfficeStyle.status.holiday
    DayMarker.LEAVE -> OfficeStyle.status.leave
    DayMarker.WEEK_OFF -> MaterialTheme.colorScheme.outline
}

@Composable
fun DayMarker.containerColor(): Color = when (this) {
    DayMarker.HOLIDAY -> OfficeStyle.status.holidayContainer
    DayMarker.LEAVE -> OfficeStyle.status.leaveContainer
    DayMarker.WEEK_OFF -> MaterialTheme.colorScheme.surfaceVariant
}

fun DayMarker.shortLabel(): String = when (this) {
    DayMarker.HOLIDAY -> "Holiday"
    DayMarker.LEAVE -> "Leave"
    DayMarker.WEEK_OFF -> "Off"
}
