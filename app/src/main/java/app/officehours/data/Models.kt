package app.officehours.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class Direction {
    IN,
    OUT,
}

data class Punch(
    val at: Instant,
    val direction: Direction?,
)

data class OfficeSnapshot(
    val settledMillis: Long,
    val openSinceEpochMillis: Long?,
    val requiredMillis: Long,
    val updatedAtEpochMillis: Long,
    val detail: String,
    val error: String? = null,
    val blocked: Boolean = false,
    val swipes: List<Punch> = emptyList(),
    val warning: String? = null,
) {
    fun completedAt(nowEpochMillis: Long): Long {
        val running = openSinceEpochMillis?.let { start ->
            (nowEpochMillis - start).coerceAtLeast(0L)
        } ?: 0L
        return settledMillis + running
    }

    fun remainingAt(nowEpochMillis: Long): Long {
        return (requiredMillis - completedAt(nowEpochMillis)).coerceAtLeast(0L)
    }

    fun progressAt(nowEpochMillis: Long): Int {
        if (requiredMillis <= 0L) return 0
        return ((completedAt(nowEpochMillis) * 100L) / requiredMillis).toInt().coerceIn(0, 100)
    }

    val hasTimes: Boolean
        get() = !blocked && error == null
}

data class SavedLogin(
    val company: String,
    val username: String,
    val password: String,
)

data class SavedSession(
    val companyBase: String,
    val host: String,
    val accessToken: String?,
)

data class ShiftSettings(
    val startMinutes: Int = DEFAULT_START_MINUTES,
    val endMinutes: Int = DEFAULT_END_MINUTES,
    val requiredMillis: Long = DEFAULT_REQUIRED_MILLIS,
) {
    val start: LocalTime
        get() = LocalTime.of(startMinutes / 60, startMinutes % 60)

    val end: LocalTime
        get() = LocalTime.of(endMinutes / 60, endMinutes % 60)

    fun startLabel(): String = formatShiftTime(start)

    fun endLabel(): String = formatShiftTime(end)

    fun endShortLabel(): String = formatShiftTimeShort(end)

    fun requiredLabel(): String = formatRequiredLabel(requiredMillis)

    fun rangeLabel(): String = "${startLabel()}–${endLabel()}"

    companion object {
        const val DEFAULT_START_MINUTES: Int = 9 * 60
        const val DEFAULT_END_MINUTES: Int = 18 * 60
        const val DEFAULT_REQUIRED_MILLIS: Long = 7L * 60L * 60L * 1000L
        val DEFAULT: ShiftSettings = ShiftSettings()
    }
}

enum class DayMarker {
    HOLIDAY,
    LEAVE,
    WEEK_OFF,
}

data class DaySummary(
    val epochDay: Long,
    val inOfficeMillis: Long,
    val firstInEpochMillis: Long?,
    val swipeCount: Int,
    val openSinceEpochMillis: Long? = null,
    val marker: DayMarker? = null,
    val markerLabel: String? = null,
) {
    val date: LocalDate
        get() = LocalDate.ofEpochDay(epochDay)

    val hasSwipes: Boolean
        get() = swipeCount > 0 || inOfficeMillis > 0L || openSinceEpochMillis != null
}

data class AttendanceHistory(
    val startEpochDay: Long,
    val endEpochDay: Long,
    val days: List<DaySummary>,
    val updatedAtEpochMillis: Long,
) {
    fun day(date: LocalDate): DaySummary? = days.firstOrNull { it.epochDay == date.toEpochDay() }

    fun covers(date: LocalDate): Boolean {
        val epochDay = date.toEpochDay()
        return epochDay in startEpochDay..endEpochDay
    }
}

data class AttendanceLoad(
    val today: OfficeSnapshot,
    val history: AttendanceHistory,
)

fun formatShiftTime(time: LocalTime): String =
    DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()).format(time)

fun formatShiftTimeShort(time: LocalTime): String {
    val pattern = if (time.minute == 0) "h a" else "h:mm a"
    return DateTimeFormatter.ofPattern(pattern, Locale.getDefault()).format(time)
}

fun formatRequiredLabel(millis: Long): String {
    val totalMinutes = millis.coerceAtLeast(0L) / 60_000L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return when {
        minutes != 0L -> "${hours}h ${minutes.toString().padStart(2, '0')}m"
        hours == 1L -> "1 hour"
        else -> "$hours hours"
    }
}
