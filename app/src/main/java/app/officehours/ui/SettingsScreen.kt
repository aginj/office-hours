package app.officehours.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.officehours.data.ShiftSettings
import app.officehours.data.formatShiftTime
import java.time.LocalTime

/** Body of the shift bottom sheet. */
@Composable
fun ShiftSettingsContent(
    shift: ShiftSettings,
    busy: Boolean,
    onSave: (ShiftSettings) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var start by remember(shift) { mutableStateOf(shift.start) }
    var end by remember(shift) { mutableStateOf(shift.end) }
    var requiredHours by remember(shift) {
        mutableStateOf((shift.requiredMillis / 3_600_000L).toString())
    }
    var requiredMinutes by remember(shift) {
        mutableStateOf(((shift.requiredMillis / 60_000L) % 60L).toString())
    }
    var error by remember { mutableStateOf<String?>(null) }

    fun pickTime(initial: LocalTime, onPicked: (LocalTime) -> Unit) {
        TimePickerDialog(
            context,
            { _, hour, minute -> onPicked(LocalTime.of(hour, minute)) },
            initial.hour,
            initial.minute,
            false,
        ).show()
    }

    fun submit() {
        val hours = requiredHours.trim().toIntOrNull()
        val minutes = requiredMinutes.trim().toIntOrNull()
        error = when {
            hours == null || minutes == null -> "Enter required hours and minutes as numbers."
            hours < 0 || minutes !in 0..59 -> "Minutes need to be between 0 and 59."
            hours == 0 && minutes == 0 -> "Required time has to be more than zero."
            hours > 16 -> "Required time is too long."
            !end.isAfter(start) -> "Shift end needs to be after shift start."
            (hours * 60 + minutes) > (end.toSecondOfDay() - start.toSecondOfDay()) / 60 ->
                "Required time cannot be longer than the shift itself."
            else -> null
        }
        if (error != null) return
        onSave(
            ShiftSettings(
                startMinutes = start.hour * 60 + start.minute,
                endMinutes = end.hour * 60 + end.minute,
                requiredMillis = hours!! * 3_600_000L + minutes!! * 60_000L,
            ),
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Shift", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onReset, enabled = !busy) { Text("Reset to 9–6, 7h") }
        }
        Text(
            "Today, the week view, the widget, and alerts all follow these times. They stay on this phone.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!error.isNullOrBlank()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(error!!, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            SettingRow(
                label = "Start",
                value = formatShiftTime(start),
                enabled = !busy,
                onClick = { pickTime(start) { start = it } },
                modifier = Modifier.weight(1f),
            )
            SettingRow(
                label = "End",
                value = formatShiftTime(end),
                enabled = !busy,
                onClick = { pickTime(end) { end = it } },
                modifier = Modifier.weight(1f),
            )
        }
        Text("Required time", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = requiredHours,
                onValueChange = { requiredHours = it.filter(Char::isDigit).take(2) },
                modifier = Modifier.weight(1f),
                label = { Text("Hours") },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(
                value = requiredMinutes,
                onValueChange = { requiredMinutes = it.filter(Char::isDigit).take(2) },
                modifier = Modifier.weight(1f),
                label = { Text("Minutes") },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                Icons.Default.Notifications,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.height(18.dp),
            )
            Text(
                "Alerts: when required time is done, when break for ${formatShiftTime(end)} runs out, " +
                    "and 15 minutes before ${formatShiftTime(end)} if the day is still short.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(2.dp))
        Button(onClick = ::submit, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("Save shift")
        }
    }
}

@Composable
private fun SettingRow(
    label: String,
    value: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            Text(value, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleLarge)
        }
    }
}
