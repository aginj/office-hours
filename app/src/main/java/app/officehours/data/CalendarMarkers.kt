package app.officehours.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pulls holiday, leave, and week-off days out of a greytHR leave-calendar response.
 *
 * The exact shape differs between tenants, so this walks the whole document and treats any object
 * that has a date (or a from/to range) plus text mentioning holiday / leave / week off as a marker.
 */
object CalendarMarkers {
    private val DATE_KEYS = listOf(
        "date", "day", "entryDate", "leaveDate", "holidayDate", "attendanceDate", "calendarDate",
    )
    private val FROM_KEYS = listOf("fromDate", "startDate", "from", "start")
    private val TO_KEYS = listOf("toDate", "endDate", "to", "end")
    private val LABEL_KEYS = listOf(
        "name", "title", "holidayName", "leaveTypeName", "leaveType", "description", "reason", "remarks", "type", "category",
    )
    private val FORMATS = listOf(
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH),
    )

    fun parse(body: String, start: LocalDate, end: LocalDate): Map<LocalDate, DayMark> {
        val trimmed = body.trim()
        val root: Any = when {
            trimmed.startsWith("{") -> runCatching { JSONObject(trimmed) }.getOrNull() ?: return emptyMap()
            trimmed.startsWith("[") -> runCatching { JSONArray(trimmed) }.getOrNull() ?: return emptyMap()
            else -> return emptyMap()
        }
        val out = linkedMapOf<LocalDate, DayMark>()
        walk(root, start, end, out, depth = 0)
        return out
    }

    private fun walk(node: Any?, start: LocalDate, end: LocalDate, out: MutableMap<LocalDate, DayMark>, depth: Int) {
        if (depth > 8) return
        when (node) {
            is JSONObject -> {
                markerFrom(node, start, end)?.let { (dates, mark) ->
                    dates.forEach { date -> out.putIfAbsent(date, mark) }
                }
                node.keys().forEach { key -> walk(node.opt(key), start, end, out, depth + 1) }
            }
            is JSONArray -> {
                for (index in 0 until node.length()) walk(node.opt(index), start, end, out, depth + 1)
            }
        }
    }

    private fun markerFrom(item: JSONObject, start: LocalDate, end: LocalDate): Pair<List<LocalDate>, DayMark>? {
        val kind = classify(item) ?: return null
        val dates = datesOf(item, start, end)
        if (dates.isEmpty()) return null
        val label = LABEL_KEYS.firstNotNullOfOrNull { key ->
            item.optString(key).takeIf { it.isNotBlank() && it != "null" && !it.equals(kind.name, true) }
        }
        return dates to DayMark(kind, label?.take(40))
    }

    private fun classify(item: JSONObject): DayMarker? {
        // Only text values decide the kind. A key such as "isLeave": false or "leaveBalance": 12
        // must not turn a working day into leave, so keys are used only alongside a true flag.
        val text = buildString {
            item.keys().forEach { key ->
                when (val value = item.opt(key)) {
                    is String -> append(value.lowercase(Locale.US)).append(' ')
                    is Boolean -> if (value) append(key.lowercase(Locale.US)).append(' ')
                    else -> Unit
                }
            }
        }
        if ("present" in text || "working day" in text || "workingday" in text) return null
        return when {
            "holiday" in text -> DayMarker.HOLIDAY
            "weekoff" in text || "week off" in text || "weekly off" in text || "week-off" in text -> DayMarker.WEEK_OFF
            "leave" in text && "balance" !in text && "calendarshortlist" !in text -> DayMarker.LEAVE
            else -> null
        }
    }

    private fun datesOf(item: JSONObject, start: LocalDate, end: LocalDate): List<LocalDate> {
        val single = DATE_KEYS.firstNotNullOfOrNull { key -> parseDate(item.opt(key)) }
        if (single != null) {
            return if (single.isBefore(start) || single.isAfter(end)) emptyList() else listOf(single)
        }
        val from = FROM_KEYS.firstNotNullOfOrNull { key -> parseDate(item.opt(key)) } ?: return emptyList()
        val to = TO_KEYS.firstNotNullOfOrNull { key -> parseDate(item.opt(key)) } ?: from
        if (to.isBefore(from) || to.toEpochDay() - from.toEpochDay() > 62) return emptyList()
        val dates = mutableListOf<LocalDate>()
        var cursor = from
        while (!cursor.isAfter(to)) {
            if (!cursor.isBefore(start) && !cursor.isAfter(end)) dates += cursor
            cursor = cursor.plusDays(1)
        }
        return dates
    }

    private fun parseDate(value: Any?): LocalDate? {
        if (value == null || value == JSONObject.NULL) return null
        if (value is Number) {
            val raw = value.toLong()
            if (raw <= 0L) return null
            val millis = if (raw > 10_000_000_000L) raw else raw * 1000L
            return java.time.Instant.ofEpochMilli(millis).atZone(AttendanceMath.OFFICE_ZONE).toLocalDate()
        }
        val text = value.toString().trim()
        if (text.length < 8) return null
        for (format in FORMATS) {
            runCatching { LocalDate.parse(text, format) }.getOrNull()?.let { return it }
        }
        return runCatching { java.time.OffsetDateTime.parse(text).atZoneSameInstant(AttendanceMath.OFFICE_ZONE).toLocalDate() }.getOrNull()
    }
}
