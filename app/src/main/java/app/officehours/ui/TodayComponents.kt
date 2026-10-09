package app.officehours.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.officehours.data.AttendanceMath
import app.officehours.data.Direction
import app.officehours.data.OfficeSnapshot
import app.officehours.data.ShiftSettings
import app.officehours.data.formatClock
import app.officehours.data.formatHoursMinutes
import java.time.Instant
import java.time.format.DateTimeFormatter

@Composable
fun ProgressRing(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
    stroke: Dp = 18.dp,
    track: Color = MaterialTheme.colorScheme.surfaceVariant,
    content: @Composable BoxScope.() -> Unit,
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing),
        label = "ring",
    )
    val ringColor by animateColorAsState(color, tween(500), label = "ringColor")
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val strokePx = stroke.toPx()
            val inset = strokePx / 2f
            val arcSize = Size(size.width - strokePx, size.height - strokePx)
            val topLeft = Offset(inset, inset)
            drawArc(
                color = track,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )
            if (animated > 0f) {
                drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = 360f * animated,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
            }
        }
        content()
    }
}

@Composable
fun HeroCard(
    snapshot: OfficeSnapshot,
    shift: ShiftSettings,
    status: DayStatus,
    now: Long,
    onBreakSince: Long?,
) {
    val remaining = snapshot.remainingAt(now)
    val completed = snapshot.completedAt(now)
    val fraction = if (snapshot.requiredMillis > 0L) completed.toFloat() / snapshot.requiredMillis else 0f
    val signedIn = snapshot.openSinceEpochMillis != null
    val ringColor = if (status == DayStatus.NONE) MaterialTheme.colorScheme.primary else status.color()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PresencePill(
                    signedIn = signedIn,
                    hasSwipes = snapshot.swipes.isNotEmpty(),
                    onBreakMillis = onBreakSince?.let { (now - it).coerceAtLeast(0L) },
                )
                Text(
                    "Target ${shift.requiredLabel()}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ProgressRing(
                progress = fraction,
                color = ringColor,
                modifier = Modifier.size(236.dp),
                stroke = 20.dp,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val (big, small) = splitClock(remaining)
                    Text(
                        if (remaining == 0L) "Done" else big,
                        style = MaterialTheme.typography.displayMedium,
                        fontSize = 40.sp,
                        lineHeight = 44.sp,
                        textAlign = TextAlign.Center,
                    )
                    if (remaining != 0L) {
                        Text(
                            small,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (remaining == 0L) "${shift.requiredLabel()} finished" else "remaining",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                MiniStat(label = "In office", value = formatHoursMinutes(completed))
                MiniStat(label = "Progress", value = "${snapshot.progressAt(now)}%")
                MiniStat(
                    label = if (signedIn) "Done around" else "If in now",
                    value = if (remaining == 0L) {
                        "Done"
                    } else {
                        DateTimeFormatter.ofPattern("h:mm a").withZone(AttendanceMath.OFFICE_ZONE)
                            .format(Instant.ofEpochMilli(now + remaining))
                    },
                )
            }
        }
    }
}

/** Seconds while the break is under an hour so the pill visibly ticks; hours and minutes after. */
private fun formatLiveBreak(millis: Long): String {
    val totalSeconds = millis.coerceAtLeast(0L) / 1000L
    if (totalSeconds < 3600L) {
        return "${totalSeconds / 60L}m ${(totalSeconds % 60L).toString().padStart(2, '0')}s"
    }
    return formatHoursMinutes(millis)
}

private fun splitClock(millis: Long): Pair<String, String> {
    val totalSeconds = millis.coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return "${hours}h ${minutes.toString().padStart(2, '0')}m" to "${seconds.toString().padStart(2, '0')}s"
}

@Composable
private fun MiniStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun PresencePill(signedIn: Boolean, hasSwipes: Boolean, onBreakMillis: Long?) {
    val color = when {
        signedIn -> OfficeStyle.status.good
        hasSwipes -> OfficeStyle.status.warn
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = when {
        signedIn -> "Signed in"
        hasSwipes && onBreakMillis != null -> "On break · ${formatLiveBreak(onBreakMillis)}"
        hasSwipes -> "Signed out"
        else -> "No swipes yet"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, color = color, style = MaterialTheme.typography.labelLarge)
    }
}

/** The "leave at shift end" card with its status colour and the per-situation detail rows. */
@Composable
fun StatusCard(
    snapshot: OfficeSnapshot,
    shift: ShiftSettings,
    status: DayStatus,
    now: Long,
    breakTakenMillis: Long,
    onBreakSince: Long?,
) {
    val remaining = snapshot.remainingAt(now)
    val allowance = AttendanceMath.shiftBreak(snapshot, now, shift = shift)
    val shiftEnd = AttendanceMath.shiftEndEpochMillis(now, shift)
    val shiftOver = now >= shiftEnd
    val signedIn = snapshot.openSinceEpochMillis != null
    val late = AttendanceMath.lateStartMillis(snapshot, shift)

    val headline: String
    val detail: String
    val number: String
    when {
        status == DayStatus.NONE -> {
            number = "—"
            headline = "No swipes yet"
            detail = "Once greytHR records a swipe-in, this shows how much break fits before ${shift.endLabel()}."
        }
        shiftOver && remaining == 0L -> {
            number = "Done"
            headline = "Shift ended at ${shift.endLabel()}"
            detail = "You already finished ${shift.requiredLabel()}."
        }
        shiftOver -> {
            number = formatHoursMinutes(remaining)
            headline = "Past ${shift.endLabel()}, still short"
            detail = if (signedIn) {
                "Stay on and you will be done around ${clockAt(now + remaining)}."
            } else {
                "You are signed out. Swipe in to finish ${shift.requiredLabel()} today."
            }
        }
        remaining == 0L -> {
            number = "Done"
            headline = "${shift.requiredLabel()} finished"
            detail = "Leave whenever you like."
        }
        allowance > 0L -> {
            number = formatClock(allowance)
            headline = "Break you can still take"
            detail = "Use this much more and you can still finish ${shift.requiredLabel()} by ${shift.endLabel()}."
        }
        allowance == 0L -> {
            number = "0m"
            headline = "No break left"
            detail = "Stay in until ${shift.endLabel()} to finish ${shift.requiredLabel()}. Any break means a later exit."
        }
        else -> {
            number = formatHoursMinutes(-allowance)
            headline = "Short if you leave at ${shift.endLabel()}"
            detail = "Even with no more break, ${shift.endLabel()} is this far short of ${shift.requiredLabel()}."
        }
    }

    val container by animateColorAsState(status.containerColor(), tween(500), label = "statusContainer")
    val accent = status.color()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(accent),
                )
                Text(
                    "Leave at ${shift.endLabel()}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                number,
                style = MaterialTheme.typography.displayMedium,
                fontSize = 38.sp,
                lineHeight = 42.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(headline, style = MaterialTheme.typography.titleMedium)
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)

            val rows = buildList {
                if (!signedIn && onBreakSince != null && remaining > 0L) {
                    val onBreak = (now - onBreakSince).coerceAtLeast(0L)
                    val usage = when {
                        shiftOver -> "the ${shift.endShortLabel()} exit is already gone"
                        allowance + onBreak <= 0L -> "this break alone used up the ${shift.endShortLabel()} allowance"
                        else -> "${formatHoursMinutes(onBreak)} of the ${formatHoursMinutes(allowance + onBreak)} you had for ${shift.endShortLabel()}"
                    }
                    add("On break since ${clockAt(onBreakSince)} · $usage")
                }
                if (late > 0L) {
                    add("Started ${formatHoursMinutes(late)} after ${shift.startLabel()} · that took ${formatHoursMinutes(late)} off the break budget")
                }
                if (signedIn) {
                    val nowDone = snapshot.completedAt(now)
                    add(
                        if (remaining == 0L) {
                            "Swipe out now: ${formatHoursMinutes(nowDone)} in office, ${formatHoursMinutes(nowDone - snapshot.requiredMillis)} over"
                        } else {
                            "Swipe out now: ${formatHoursMinutes(nowDone)} in office, ${formatHoursMinutes(remaining)} short"
                        },
                    )
                }
                add(
                    if (breakTakenMillis == 0L) {
                        "Break taken today: none · shift ${shift.rangeLabel()}"
                    } else {
                        "Break taken today: ${formatHoursMinutes(breakTakenMillis)} · shift ${shift.rangeLabel()}"
                    },
                )
            }
            if (rows.isNotEmpty()) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                )
                rows.forEach { row ->
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            Modifier
                                .padding(top = 7.dp)
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onSurfaceVariant),
                        )
                        Text(
                            row,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun clockAt(epochMillis: Long): String =
    DateTimeFormatter.ofPattern("h:mm a").withZone(AttendanceMath.OFFICE_ZONE).format(Instant.ofEpochMilli(epochMillis))

@Composable
fun WarningBanner(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = OfficeStyle.status.warnContainer),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = OfficeStyle.status.warn)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
fun StatTile(icon: ImageVector, label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(18.dp)) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            }
            Column {
                Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                Text(value, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

data class Stay(val inAt: Instant, val outAt: Instant?)

fun staysOf(snapshot: OfficeSnapshot): List<Stay> {
    val stays = mutableListOf<Stay>()
    var open: Instant? = null
    for (swipe in snapshot.swipes.sortedBy { it.at }) {
        when (swipe.direction) {
            Direction.IN -> if (open == null) open = swipe.at
            Direction.OUT -> {
                val start = open
                if (start != null && swipe.at.isAfter(start)) {
                    stays.add(Stay(start, swipe.at))
                    open = null
                }
            }
            null -> Unit
        }
    }
    if (open != null) {
        stays.add(Stay(open, null))
    } else if (stays.isEmpty() && snapshot.openSinceEpochMillis != null) {
        stays.add(Stay(Instant.ofEpochMilli(snapshot.openSinceEpochMillis), null))
    }
    return stays
}

fun breakMillisOf(stays: List<Stay>): Long {
    var total = 0L
    for (index in 1 until stays.size) {
        val previousOut = stays[index - 1].outAt ?: continue
        val gap = stays[index].inAt.toEpochMilli() - previousOut.toEpochMilli()
        if (gap > 0L) total += gap
    }
    return total
}

/** Vertical timeline of the day's stays, with the break gaps drawn between them. */
@Composable
fun SwipeTimeline(stays: List<Stay>, now: Long) {
    val clock = DateTimeFormatter.ofPattern("h:mm a").withZone(AttendanceMath.OFFICE_ZONE)
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Text("Swipes", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            if (stays.isEmpty()) {
                Text(
                    "No office swipes yet today. Pull down to refresh.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                return@Column
            }
            stays.forEachIndexed { index, stay ->
                val endMillis = stay.outAt?.toEpochMilli() ?: now
                val duration = (endMillis - stay.inAt.toEpochMilli()).coerceAtLeast(0L)
                TimelineRow(
                    marker = TimelineMarker.IN,
                    title = "In",
                    time = clock.format(stay.inAt),
                    trailing = null,
                    connectorSolid = true,
                )
                TimelineSegment(label = "In office · ${formatHoursMinutes(duration)}", solid = true)
                if (stay.outAt != null) {
                    TimelineRow(
                        marker = TimelineMarker.OUT,
                        title = "Out",
                        time = clock.format(stay.outAt),
                        trailing = null,
                        connectorSolid = false,
                    )
                } else {
                    TimelineRow(
                        marker = TimelineMarker.NOW,
                        title = "Still in",
                        time = clock.format(Instant.ofEpochMilli(now)),
                        trailing = formatClock(duration),
                        connectorSolid = false,
                    )
                }
                val next = stays.getOrNull(index + 1)
                if (next != null && stay.outAt != null) {
                    val gap = (next.inAt.toEpochMilli() - stay.outAt.toEpochMilli()).coerceAtLeast(0L)
                    TimelineSegment(label = "Break · ${formatHoursMinutes(gap)}", solid = false)
                } else if (next == null && stay.outAt != null) {
                    val gap = (now - stay.outAt.toEpochMilli()).coerceAtLeast(0L)
                    TimelineSegment(label = "Out since · ${formatHoursMinutes(gap)}", solid = false, last = true)
                }
            }
        }
    }
}

private enum class TimelineMarker { IN, OUT, NOW }

@Composable
private fun TimelineRow(
    marker: TimelineMarker,
    title: String,
    time: String,
    trailing: String?,
    connectorSolid: Boolean,
) {
    val good = OfficeStyle.status.good
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
            when (marker) {
                TimelineMarker.IN -> Box(
                    Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(good),
                )
                TimelineMarker.OUT -> Box(
                    Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .border(2.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
                )
                TimelineMarker.NOW -> Box(
                    Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(good.copy(alpha = 0.25f))
                        .border(2.dp, good, CircleShape),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(title, modifier = Modifier.width(60.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(time, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Spacer(Modifier.weight(1f))
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.labelLarge, color = good)
        }
    }
}

@Composable
private fun TimelineSegment(label: String, solid: Boolean, last: Boolean = false) {
    val color = if (solid) OfficeStyle.status.good else MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(24.dp)
                .fillMaxHeight()
                .heightIn(min = 28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight(),
            ) {
                val effect = if (solid) null else PathEffect.dashPathEffect(floatArrayOf(6f, 8f))
                drawLine(
                    color = color,
                    start = Offset(size.width / 2f, 0f),
                    end = Offset(size.width / 2f, size.height),
                    strokeWidth = size.width,
                    cap = StrokeCap.Round,
                    pathEffect = effect,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            modifier = Modifier.padding(vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = if (solid) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (last) Spacer(Modifier.height(0.dp))
}
