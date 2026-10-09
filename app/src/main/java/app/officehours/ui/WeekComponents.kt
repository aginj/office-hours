package app.officehours.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.officehours.data.AttendanceHistory
import app.officehours.data.AttendanceMath
import app.officehours.data.DayMarker
import app.officehours.data.DaySummary
import app.officehours.data.Direction
import app.officehours.data.OfficeSnapshot
import app.officehours.data.ShiftSettings
import app.officehours.data.formatHoursMinutes
import app.officehours.data.formatHoursMinutesCompact
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private data class DayView(
    val date: LocalDate,
    val isToday: Boolean,
    val hours: Long,
    val firstIn: Instant?,
    val status: DayStatus,
    val marker: DayMarker?,
    val markerLabel: String?,
    val hasSwipes: Boolean,
)

private fun dayView(
    date: LocalDate,
    today: LocalDate,
    history: AttendanceHistory?,
    snapshot: OfficeSnapshot,
    shift: ShiftSettings,
    now: Long,
): DayView {
    val stored = history?.day(date)
    if (date == today) {
        return DayView(
            date = date,
            isToday = true,
            hours = snapshot.completedAt(now),
            firstIn = snapshot.swipes.firstOrNull { it.direction == Direction.IN }?.at,
            status = todayStatus(snapshot, shift, now).let { status ->
                if (status == DayStatus.NONE && stored?.marker != null && stored.marker != DayMarker.WEEK_OFF) DayStatus.OFF else status
            },
            marker = stored?.marker,
            markerLabel = stored?.markerLabel,
            hasSwipes = snapshot.swipes.isNotEmpty(),
        )
    }
    if (stored == null) {
        return DayView(date, false, 0L, null, DayStatus.NONE, null, null, false)
    }
    return DayView(
        date = date,
        isToday = false,
        hours = stored.inOfficeMillis,
        firstIn = stored.firstInEpochMillis?.let { Instant.ofEpochMilli(it) },
        status = pastDayStatus(stored, shift, today),
        marker = stored.marker,
        markerLabel = stored.markerLabel,
        hasSwipes = stored.hasSwipes,
    )
}

@Composable
fun WeekCard(
    history: AttendanceHistory?,
    snapshot: OfficeSnapshot,
    shift: ShiftSettings,
    now: Long,
) {
    val zone = AttendanceMath.OFFICE_ZONE
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val monday = today.with(DayOfWeek.MONDAY)
    val days = (0..4).map { offset -> dayView(monday.plusDays(offset.toLong()), today, history, snapshot, shift, now) }
    val loaded = history != null && history.covers(monday)
    val worked = days.filter { it.hasSwipes }
    val total = worked.sumOf { it.hours }
    val average = if (worked.isEmpty()) 0L else total / worked.size
    val clock = DateTimeFormatter.ofPattern("h:mm").withZone(zone)

    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Column {
                    Text("This week", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${DateTimeFormatter.ofPattern("d MMM").format(monday)} – ${DateTimeFormatter.ofPattern("d MMM").format(monday.plusDays(4))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (worked.isNotEmpty()) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(formatHoursMinutes(total), style = MaterialTheme.typography.titleMedium)
                        Text(
                            "avg ${formatHoursMinutes(average)} · ${worked.size} day${if (worked.size == 1) "" else "s"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (!loaded) {
                Text(
                    "Pull down to load this week's hours.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                WeekBars(days = days, shift = shift)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    days.forEach { day ->
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                when {
                                    day.marker != null && !day.hasSwipes -> day.marker.shortLabel()
                                    day.hasSwipes -> formatHoursMinutesCompact(day.hours)
                                    else -> "—"
                                },
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = if (day.marker != null && !day.hasSwipes) day.marker.color() else MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                            )
                            Text(
                                day.firstIn?.let { clock.format(it) } ?: " ",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Legend(
                    items = listOf(
                        DayStatus.DONE.color() to "Done",
                        DayStatus.TIGHT.color() to "Tight",
                        DayStatus.SHORT.color() to "Short",
                        OfficeStyle.status.leave to "Leave",
                        OfficeStyle.status.holiday to "Holiday",
                    ),
                )
            }
        }
    }
}

@Composable
private fun WeekBars(days: List<DayView>, shift: ShiftSettings) {
    val chartHeight = 120.dp
    val maxFraction = 1.2f
    val targetLine = MaterialTheme.colorScheme.outline
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height(chartHeight),
        ) {
            Canvas(Modifier.matchParentSize()) {
                val y = size.height * (1f - 1f / maxFraction)
                drawLine(
                    color = targetLine,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
                )
            }
            Row(
                modifier = Modifier.matchParentSize(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                days.forEach { day ->
                    val fraction = when {
                        day.hasSwipes -> (day.hours.toFloat() / shift.requiredMillis).coerceIn(0.02f, maxFraction)
                        day.marker != null && day.marker != DayMarker.WEEK_OFF -> 1f
                        else -> 0.02f
                    }
                    val animated by animateFloatAsState(
                        targetValue = fraction / maxFraction,
                        animationSpec = tween(700, easing = FastOutSlowInEasing),
                        label = "bar",
                    )
                    val color = when {
                        day.hasSwipes -> day.status.color()
                        day.marker != null -> day.marker.color().copy(alpha = 0.35f)
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(0.62f)
                                .fillMaxHeight(animated)
                                .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp, bottomStart = 4.dp, bottomEnd = 4.dp))
                                .background(color)
                                .then(
                                    if (day.isToday) {
                                        Modifier.border(
                                            2.dp,
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                                            RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp, bottomStart = 4.dp, bottomEnd = 4.dp),
                                        )
                                    } else {
                                        Modifier
                                    },
                                ),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            days.forEach { day ->
                Text(
                    day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (day.isToday) FontWeight.Bold else FontWeight.Medium,
                    color = if (day.isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Legend(items: List<Pair<Color, String>>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items.forEach { (color, label) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(color),
                )
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun MonthCard(
    history: AttendanceHistory?,
    snapshot: OfficeSnapshot,
    shift: ShiftSettings,
    now: Long,
) {
    val zone = AttendanceMath.OFFICE_ZONE
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val month = YearMonth.from(today)
    val first = month.atDay(1)
    val loaded = history != null && history.covers(first)
    val days = (1..month.lengthOfMonth()).map { dayOfMonth ->
        dayView(month.atDay(dayOfMonth), today, history, snapshot, shift, now)
    }
    val worked = days.filter { it.hasSwipes }
    val done = days.count { it.status == DayStatus.DONE }
    val short = days.count { it.status == DayStatus.SHORT && !it.isToday }
    val leave = days.count { it.marker == DayMarker.LEAVE && !it.hasSwipes }
    val holidays = days.count { it.marker == DayMarker.HOLIDAY && !it.hasSwipes }
    val average = if (worked.isEmpty()) 0L else worked.sumOf { it.hours } / worked.size

    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Column {
                    Text(
                        month.month.getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + month.year,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        if (worked.isEmpty()) "No office days yet" else "avg ${formatHoursMinutes(average)} on ${worked.size} office day${if (worked.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!loaded) {
                Text(
                    "Pull down to load this month.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                MonthGrid(days = days, first = first)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SummaryChip(count = done, label = "done", color = DayStatus.DONE.color(), container = DayStatus.DONE.containerColor(), modifier = Modifier.weight(1f))
                    SummaryChip(count = short, label = "short", color = DayStatus.SHORT.color(), container = DayStatus.SHORT.containerColor(), modifier = Modifier.weight(1f))
                    SummaryChip(count = leave, label = "leave", color = OfficeStyle.status.leave, container = OfficeStyle.status.leaveContainer, modifier = Modifier.weight(1f))
                    SummaryChip(count = holidays, label = "holiday", color = OfficeStyle.status.holiday, container = OfficeStyle.status.holidayContainer, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SummaryChip(count: Int, label: String, color: Color, container: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(container)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(count.toString(), style = MaterialTheme.typography.titleLarge, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MonthGrid(days: List<DayView>, first: LocalDate) {
    val leading = (first.dayOfWeek.value + 6) % 7
    val cells: List<DayView?> = List(leading) { null } + days
    val rows = cells.chunked(7)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            DayOfWeek.values().forEach { dow ->
                Text(
                    dow.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        rows.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEach { cell ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (cell != null) MonthCell(cell)
                    }
                }
                repeat(7 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun MonthCell(day: DayView) {
    val weekend = isWeekend(day.date)
    val future = day.status == DayStatus.NONE && !day.isToday && !day.hasSwipes && day.marker == null
    val textColor = when {
        day.isToday -> MaterialTheme.colorScheme.onPrimary
        future || weekend -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        else -> MaterialTheme.colorScheme.onSurface
    }
    val dot = when {
        day.hasSwipes -> day.status.color()
        day.marker != null && day.marker != DayMarker.WEEK_OFF -> day.marker.color()
        else -> Color.Transparent
    }
    Column(
        modifier = Modifier.padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(
            Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(if (day.isToday) MaterialTheme.colorScheme.primary else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                day.date.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 13.sp,
                fontWeight = if (day.isToday) FontWeight.Bold else FontWeight.Normal,
                color = textColor,
            )
        }
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dot),
        )
    }
}
