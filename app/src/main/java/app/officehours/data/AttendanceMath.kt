package app.officehours.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object AttendanceMath {
    const val REQUIRED_MILLIS: Long = ShiftSettings.DEFAULT_REQUIRED_MILLIS
    val OFFICE_ZONE: ZoneId = ZoneId.of("Asia/Kolkata")

    fun shiftBreak(
        snapshot: OfficeSnapshot,
        nowEpochMillis: Long,
        zone: ZoneId = OFFICE_ZONE,
        shift: ShiftSettings = ShiftSettings.DEFAULT,
    ): Long {
        val now = Instant.ofEpochMilli(nowEpochMillis).atZone(zone)
        val shiftStart = now.toLocalDate().atTime(shift.start).atZone(zone).toInstant()
        val shiftEnd = now.toLocalDate().atTime(shift.end).atZone(zone).toInstant()
        val signedIn = snapshot.openSinceEpochMillis != null
        val windowStart = if (!signedIn && now.toInstant().isBefore(shiftStart)) shiftStart else now.toInstant()
        val untilEnd = (shiftEnd.toEpochMilli() - windowStart.toEpochMilli()).coerceAtLeast(0L)
        return snapshot.completedAt(nowEpochMillis) + untilEnd - snapshot.requiredMillis
    }

    fun shiftEndEpochMillis(
        nowEpochMillis: Long,
        shift: ShiftSettings,
        zone: ZoneId = OFFICE_ZONE,
    ): Long {
        val now = Instant.ofEpochMilli(nowEpochMillis).atZone(zone)
        return now.toLocalDate().atTime(shift.end).atZone(zone).toInstant().toEpochMilli()
    }

    fun hasStartedWork(snapshot: OfficeSnapshot): Boolean {
        return snapshot.settledMillis > 0L ||
            snapshot.openSinceEpochMillis != null ||
            snapshot.swipes.any { it.direction == Direction.IN }
    }

    fun hoursDoneEpochMillis(snapshot: OfficeSnapshot, nowEpochMillis: Long): Long? {
        if (!hasStartedWork(snapshot)) return null
        val remaining = snapshot.remainingAt(nowEpochMillis)
        if (remaining > 0L) {
            if (snapshot.openSinceEpochMillis == null) return null
            return nowEpochMillis + remaining
        }
        val extra = (snapshot.completedAt(nowEpochMillis) - snapshot.requiredMillis).coerceAtLeast(0L)
        if (snapshot.openSinceEpochMillis != null) {
            return nowEpochMillis - extra
        }
        val lastOut = snapshot.swipes.lastOrNull { it.direction == Direction.OUT }?.at?.toEpochMilli()
        return (lastOut ?: nowEpochMillis) - extra
    }

    fun breakBudgetZeroEpochMillis(
        snapshot: OfficeSnapshot,
        nowEpochMillis: Long,
        shift: ShiftSettings,
        zone: ZoneId = OFFICE_ZONE,
    ): Long? {
        if (!hasStartedWork(snapshot)) return null
        if (snapshot.openSinceEpochMillis != null) return null
        if (snapshot.remainingAt(nowEpochMillis) == 0L) return null
        val shiftEnd = shiftEndEpochMillis(nowEpochMillis, shift, zone)
        if (nowEpochMillis >= shiftEnd) return null
        return shiftEnd - snapshot.remainingAt(nowEpochMillis)
    }

    /** Millis the first swipe-in was after shift start, or 0 when on time or no swipes. */
    fun lateStartMillis(snapshot: OfficeSnapshot, shift: ShiftSettings, zone: ZoneId = OFFICE_ZONE): Long {
        val firstIn = snapshot.swipes.firstOrNull { it.direction == Direction.IN }?.at ?: return 0L
        val shiftStart = firstIn.atZone(zone).toLocalDate().atTime(shift.start).atZone(zone).toInstant()
        return (firstIn.toEpochMilli() - shiftStart.toEpochMilli()).coerceAtLeast(0L)
    }

    /** When the current break started, or null when signed in or no swipes. */
    fun onBreakSinceEpochMillis(snapshot: OfficeSnapshot): Long? {
        if (snapshot.openSinceEpochMillis != null) return null
        return snapshot.swipes.lastOrNull { it.direction == Direction.OUT }?.at?.toEpochMilli()
    }

    /**
     * Time to warn that the shift end is near and the required hours will not be done.
     * Fires [leadMillis] before shift end when the day is projected short.
     */
    fun shortBeforeEndEpochMillis(
        snapshot: OfficeSnapshot,
        nowEpochMillis: Long,
        shift: ShiftSettings,
        zone: ZoneId = OFFICE_ZONE,
        leadMillis: Long = END_WARNING_LEAD_MILLIS,
    ): Long? {
        if (!hasStartedWork(snapshot)) return null
        val shiftEnd = shiftEndEpochMillis(nowEpochMillis, shift, zone)
        val warnAt = shiftEnd - leadMillis
        if (nowEpochMillis >= shiftEnd) return null
        val projected = snapshot.completedAt(shiftEnd)
        if (projected >= snapshot.requiredMillis) return null
        return warnAt
    }

    fun snapshot(
        punches: List<Punch>,
        now: Instant = Instant.now(),
        zone: ZoneId = OFFICE_ZONE,
        requiredMillis: Long = REQUIRED_MILLIS,
    ): OfficeSnapshot {
        val normalized = normalize(punches, now)
        val pairs = normalized.pairs
        var settled = 0L
        var openSince: Instant? = null
        for ((at, direction) in pairs) {
            when (direction) {
                Direction.IN -> if (openSince == null) openSince = at
                Direction.OUT -> {
                    val start = openSince
                    if (start != null && at.isAfter(start)) {
                        settled += at.toEpochMilli() - start.toEpochMilli()
                        openSince = null
                    }
                }
            }
        }
        val firstIn = pairs.firstOrNull { it.second == Direction.IN }?.first
        val signedIn = openSince != null
        val clock = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()).withZone(zone)
        val detail = when {
            signedIn && firstIn != null -> "Signed in · first in ${clock.format(firstIn)}"
            !signedIn && firstIn != null -> "Signed out · first in ${clock.format(firstIn)}"
            else -> "No office swipes yet today"
        }
        return OfficeSnapshot(
            settledMillis = settled,
            openSinceEpochMillis = openSince?.toEpochMilli(),
            requiredMillis = requiredMillis,
            updatedAtEpochMillis = now.toEpochMilli(),
            detail = detail,
            swipes = pairs.map { (at, direction) -> Punch(at, direction) },
            warning = normalized.warning,
        )
    }

    fun historyFromPunches(
        punches: List<Punch>,
        todaySnapshot: OfficeSnapshot,
        today: LocalDate,
        start: LocalDate,
        end: LocalDate,
        now: Instant,
        zone: ZoneId,
        shift: ShiftSettings,
        markers: Map<LocalDate, DayMark> = emptyMap(),
    ): AttendanceHistory {
        val byDate = punches.groupBy { punch -> punch.at.atZone(zone).toLocalDate() }
        val days = mutableListOf<DaySummary>()
        var date = start
        while (!date.isAfter(end)) {
            val mark = markers[date]
            val dayPunches = byDate[date].orEmpty()
            days += if (date == today) {
                DaySummary(
                    epochDay = date.toEpochDay(),
                    inOfficeMillis = todaySnapshot.settledMillis,
                    firstInEpochMillis = todaySnapshot.swipes
                        .firstOrNull { it.direction == Direction.IN }
                        ?.at
                        ?.toEpochMilli(),
                    swipeCount = todaySnapshot.swipes.size,
                    openSinceEpochMillis = todaySnapshot.openSinceEpochMillis,
                    marker = mark?.marker,
                    markerLabel = mark?.label,
                )
            } else {
                // Use the whole day's punches so a late swipe-out still counts. If the day never
                // closed (missing OUT), count only up to shift end rather than until midnight.
                val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant()
                val asOf = if (dayEnd.isAfter(now)) now else dayEnd
                val daySnapshot = snapshot(dayPunches, asOf, zone, shift.requiredMillis)
                val openSince = daySnapshot.openSinceEpochMillis
                val countUntil = if (openSince != null) {
                    val shiftEnd = date.atTime(shift.end).atZone(zone).toInstant().toEpochMilli()
                    minOf(asOf.toEpochMilli(), maxOf(shiftEnd, openSince))
                } else {
                    asOf.toEpochMilli()
                }
                DaySummary(
                    epochDay = date.toEpochDay(),
                    inOfficeMillis = daySnapshot.completedAt(countUntil),
                    firstInEpochMillis = daySnapshot.swipes
                        .firstOrNull { it.direction == Direction.IN }
                        ?.at
                        ?.toEpochMilli(),
                    swipeCount = daySnapshot.swipes.size,
                    openSinceEpochMillis = null,
                    marker = mark?.marker,
                    markerLabel = mark?.label,
                )
            }
            date = date.plusDays(1)
        }
        return AttendanceHistory(
            startEpochDay = start.toEpochDay(),
            endEpochDay = end.toEpochDay(),
            days = days,
            updatedAtEpochMillis = now.toEpochMilli(),
        )
    }

    private data class Normalized(
        val pairs: List<Pair<Instant, Direction>>,
        val warning: String?,
    )

    private fun normalize(punches: List<Punch>, now: Instant): Normalized {
        val sorted = punches
            .filter { !it.at.isAfter(now.plusSeconds(120)) }
            .distinct()
            .sortedBy { it.at }
            .collapseDoubleTaps()
        if (sorted.isEmpty()) return Normalized(emptyList(), null)
        val anyKnown = sorted.any { it.direction != null }
        if (!anyKnown) {
            val pairs = sorted.mapIndexed { index, punch ->
                punch.at to if (index % 2 == 0) Direction.IN else Direction.OUT
            }
            return Normalized(
                pairs,
                "greytHR did not say which swipes were in or out, so they were paired by order. " +
                    "If today looks wrong, check the swipes on greytHR.",
            )
        }
        val pairs = sorted.mapNotNull { punch -> punch.direction?.let { punch.at to it } }
        val clock = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()).withZone(OFFICE_ZONE)
        var warning: String? = null
        var previous: Direction? = null
        for ((at, direction) in pairs) {
            if (previous == null && direction == Direction.OUT) {
                warning = "The first swipe today (${clock.format(at)}) is an OUT with no IN before it. " +
                    "greytHR may have missed a swipe; hours could be low."
                break
            }
            if (previous == direction) {
                val word = if (direction == Direction.IN) "IN" else "OUT"
                warning = "Two $word swipes in a row at ${clock.format(at)}. " +
                    "A swipe may be missing on greytHR, so hours could be off."
                break
            }
            previous = direction
        }
        if (warning == null && sorted.size != pairs.size) {
            warning = "Some swipes had no direction and were ignored. Check greytHR if hours look off."
        }
        return Normalized(pairs, warning)
    }

    /**
     * Two swipes in the same direction within [DOUBLE_TAP_SECONDS] are one tap at the reader that
     * registered twice. Keep the first so hours are not inflated and no false warning is raised.
     */
    private fun List<Punch>.collapseDoubleTaps(): List<Punch> {
        val out = mutableListOf<Punch>()
        for (punch in this) {
            val last = out.lastOrNull()
            if (last != null && last.direction == punch.direction && punch.direction != null &&
                punch.at.epochSecond - last.at.epochSecond <= DOUBLE_TAP_SECONDS
            ) {
                continue
            }
            out += punch
        }
        return out
    }

    private const val DOUBLE_TAP_SECONDS = 90L
    const val END_WARNING_LEAD_MILLIS: Long = 15L * 60L * 1000L
}

data class DayMark(val marker: DayMarker, val label: String?)

fun OfficeSnapshot.isFreshFor(nowEpochMillis: Long, zone: ZoneId = AttendanceMath.OFFICE_ZONE): Boolean {
    if (!hasTimes) return true
    val today = Instant.ofEpochMilli(nowEpochMillis).atZone(zone).toLocalDate()
    val fetchedOn = Instant.ofEpochMilli(updatedAtEpochMillis).atZone(zone).toLocalDate()
    return fetchedOn == today
}

fun OfficeSnapshot.rolledForward(nowEpochMillis: Long, shift: ShiftSettings): OfficeSnapshot? {
    if (isFreshFor(nowEpochMillis)) return null
    return OfficeSnapshot(
        settledMillis = 0,
        openSinceEpochMillis = null,
        requiredMillis = shift.requiredMillis,
        updatedAtEpochMillis = nowEpochMillis,
        detail = "No office swipes yet today",
    )
}

fun formatClock(millis: Long): String {
    val totalSeconds = millis.coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return "%dh %02dm %02ds".format(hours, minutes, seconds)
}

fun formatHoursMinutes(millis: Long): String {
    val totalMinutes = millis.coerceAtLeast(0L) / 60000L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return "${hours}h ${minutes.toString().padStart(2, '0')}m"
}

fun formatHoursMinutesCompact(millis: Long): String {
    val totalMinutes = millis.coerceAtLeast(0L) / 60000L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return "${hours}h${minutes.toString().padStart(2, '0')}m"
}
