package app.officehours.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

class CredentialStore(context: Context) {
    private val appContext = context.applicationContext
    private val secrets: SharedPreferences = createSecrets()
    private val state: SharedPreferences =
        appContext.getSharedPreferences(STATE_FILE, Context.MODE_PRIVATE)

    fun saveLogin(company: String, username: String, password: String) {
        secrets.edit()
            .putString(KEY_COMPANY, company.trim())
            .putString(KEY_USERNAME, username.trim())
            .putString(KEY_PASSWORD, password)
            .apply()
    }

    fun loadLogin(): SavedLogin? {
        val company = secrets.getString(KEY_COMPANY, null) ?: return null
        val username = secrets.getString(KEY_USERNAME, null) ?: return null
        val password = secrets.getString(KEY_PASSWORD, null) ?: return null
        if (company.isBlank() || username.isBlank() || password.isEmpty()) return null
        return SavedLogin(company, username, password)
    }

    fun saveSession(session: SavedSession) {
        secrets.edit()
            .putString(KEY_BASE, session.companyBase)
            .putString(KEY_HOST, session.host)
            .putString(KEY_TOKEN, session.accessToken)
            .apply()
    }

    fun loadSession(): SavedSession? {
        val base = secrets.getString(KEY_BASE, null) ?: return null
        val host = secrets.getString(KEY_HOST, null) ?: return null
        if (base.isBlank() || host.isBlank()) return null
        return SavedSession(base, host, secrets.getString(KEY_TOKEN, null))
    }

    fun clearSession() {
        secrets.edit().remove(KEY_BASE).remove(KEY_HOST).remove(KEY_TOKEN).apply()
    }

    fun setBlocked(message: String) {
        secrets.edit().putString(KEY_BLOCKED, message).apply()
    }

    fun blockedMessage(): String? = secrets.getString(KEY_BLOCKED, null)?.takeIf { it.isNotBlank() }

    fun clearBlocked() {
        secrets.edit().remove(KEY_BLOCKED).apply()
    }

    fun saveSnapshot(snapshot: OfficeSnapshot) {
        val json = JSONObject()
            .put("settledMillis", snapshot.settledMillis)
            .put("requiredMillis", snapshot.requiredMillis)
            .put("updatedAtEpochMillis", snapshot.updatedAtEpochMillis)
            .put("detail", snapshot.detail)
            .put("blocked", snapshot.blocked)
        if (snapshot.openSinceEpochMillis == null) {
            json.put("openSinceEpochMillis", JSONObject.NULL)
        } else {
            json.put("openSinceEpochMillis", snapshot.openSinceEpochMillis)
        }
        if (snapshot.error == null) {
            json.put("error", JSONObject.NULL)
        } else {
            json.put("error", snapshot.error)
        }
        if (snapshot.warning == null) {
            json.put("warning", JSONObject.NULL)
        } else {
            json.put("warning", snapshot.warning)
        }
        val swipes = JSONArray()
        snapshot.swipes.forEach { punch ->
            swipes.put(
                JSONObject()
                    .put("at", punch.at.toEpochMilli())
                    .put("direction", punch.direction?.name ?: JSONObject.NULL),
            )
        }
        json.put("swipes", swipes)
        state.edit().putString(KEY_SNAPSHOT, json.toString()).commit()
    }

    fun loadSnapshot(): OfficeSnapshot? {
        val raw = state.getString(KEY_SNAPSHOT, null) ?: return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val open = if (json.isNull("openSinceEpochMillis")) {
            null
        } else {
            json.optLong("openSinceEpochMillis")
        }
        val error = if (json.isNull("error")) null else json.optString("error").takeIf { it.isNotBlank() }
        val warning = if (json.isNull("warning")) null else json.optString("warning").takeIf { it.isNotBlank() }
        return OfficeSnapshot(
            settledMillis = json.optLong("settledMillis"),
            openSinceEpochMillis = open,
            requiredMillis = json.optLong("requiredMillis", AttendanceMath.REQUIRED_MILLIS),
            updatedAtEpochMillis = json.optLong("updatedAtEpochMillis"),
            detail = json.optString("detail"),
            error = error,
            blocked = json.optBoolean("blocked"),
            swipes = readSwipes(json.optJSONArray("swipes")),
            warning = warning,
        )
    }

    private fun readSwipes(array: JSONArray?): List<Punch> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val at = item.optLong("at")
                if (at <= 0L) continue
                val direction = when (item.optString("direction")) {
                    Direction.IN.name -> Direction.IN
                    Direction.OUT.name -> Direction.OUT
                    else -> null
                }
                add(Punch(Instant.ofEpochMilli(at), direction))
            }
        }
    }

    fun saveShift(shift: ShiftSettings) {
        state.edit()
            .putInt(KEY_SHIFT_START, shift.startMinutes)
            .putInt(KEY_SHIFT_END, shift.endMinutes)
            .putLong(KEY_REQUIRED, shift.requiredMillis)
            .apply()
    }

    fun loadShift(): ShiftSettings {
        return ShiftSettings(
            startMinutes = state.getInt(KEY_SHIFT_START, ShiftSettings.DEFAULT_START_MINUTES),
            endMinutes = state.getInt(KEY_SHIFT_END, ShiftSettings.DEFAULT_END_MINUTES),
            requiredMillis = state.getLong(KEY_REQUIRED, ShiftSettings.DEFAULT_REQUIRED_MILLIS),
        )
    }

    fun saveHistory(history: AttendanceHistory) {
        val days = JSONArray()
        history.days.forEach { day ->
            val json = JSONObject()
                .put("epochDay", day.epochDay)
                .put("inOfficeMillis", day.inOfficeMillis)
                .put("swipeCount", day.swipeCount)
                .put("firstInEpochMillis", day.firstInEpochMillis ?: JSONObject.NULL)
                .put("openSinceEpochMillis", day.openSinceEpochMillis ?: JSONObject.NULL)
                .put("marker", day.marker?.name ?: JSONObject.NULL)
                .put("markerLabel", day.markerLabel ?: JSONObject.NULL)
            days.put(json)
        }
        val json = JSONObject()
            .put("startEpochDay", history.startEpochDay)
            .put("endEpochDay", history.endEpochDay)
            .put("updatedAtEpochMillis", history.updatedAtEpochMillis)
            .put("days", days)
        state.edit().putString(KEY_HISTORY, json.toString()).apply()
    }

    fun loadHistory(): AttendanceHistory? {
        val raw = state.getString(KEY_HISTORY, null) ?: return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val array = json.optJSONArray("days") ?: return null
        val days = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    DaySummary(
                        epochDay = item.optLong("epochDay"),
                        inOfficeMillis = item.optLong("inOfficeMillis"),
                        firstInEpochMillis = if (item.isNull("firstInEpochMillis")) {
                            null
                        } else {
                            item.optLong("firstInEpochMillis")
                        },
                        swipeCount = item.optInt("swipeCount"),
                        openSinceEpochMillis = if (item.isNull("openSinceEpochMillis")) {
                            null
                        } else {
                            item.optLong("openSinceEpochMillis")
                        },
                        marker = if (item.isNull("marker")) {
                            null
                        } else {
                            runCatching { DayMarker.valueOf(item.optString("marker")) }.getOrNull()
                        },
                        markerLabel = if (item.isNull("markerLabel")) null else item.optString("markerLabel").takeIf { it.isNotBlank() },
                    ),
                )
            }
        }
        if (days.isEmpty()) return null
        return AttendanceHistory(
            startEpochDay = json.optLong("startEpochDay"),
            endEpochDay = json.optLong("endEpochDay"),
            days = days,
            updatedAtEpochMillis = json.optLong("updatedAtEpochMillis"),
        )
    }

    fun hoursDoneAlertDate(): String? = state.getString(KEY_ALERT_HOURS, null)?.takeIf { it.isNotBlank() }

    fun setHoursDoneAlertDate(date: String) {
        state.edit().putString(KEY_ALERT_HOURS, date).commit()
    }

    fun breakZeroAlertDate(): String? = state.getString(KEY_ALERT_BREAK, null)?.takeIf { it.isNotBlank() }

    fun setBreakZeroAlertDate(date: String) {
        state.edit().putString(KEY_ALERT_BREAK, date).commit()
    }

    fun endNearAlertDate(): String? = state.getString(KEY_ALERT_END, null)?.takeIf { it.isNotBlank() }

    fun setEndNearAlertDate(date: String) {
        state.edit().putString(KEY_ALERT_END, date).commit()
    }

    fun clearAlertDates() {
        state.edit().remove(KEY_ALERT_HOURS).remove(KEY_ALERT_BREAK).remove(KEY_ALERT_END).apply()
    }

    fun clearAll() {
        val shift = loadShift()
        secrets.edit().clear().apply()
        state.edit().clear().apply()
        saveShift(shift)
    }

    private fun createSecrets(): SharedPreferences {
        return try {
            openSecrets()
        } catch (first: Exception) {
            // A corrupt keyset (common after a restore or key invalidation) makes this throw and
            // would crash the app at launch. Drop the file and start over; the user signs in again.
            appContext.deleteSharedPreferences(SECRET_FILE)
            try {
                openSecrets()
            } catch (second: Exception) {
                appContext.getSharedPreferences("$SECRET_FILE.plain", Context.MODE_PRIVATE)
            }
        }
    }

    private fun openSecrets(): SharedPreferences {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        return EncryptedSharedPreferences.create(
            SECRET_FILE,
            masterKeyAlias,
            appContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    companion object {
        private const val SECRET_FILE = "office_hours_secrets"
        private const val STATE_FILE = "office_hours_state"
        private const val KEY_COMPANY = "company"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_BASE = "base"
        private const val KEY_HOST = "host"
        private const val KEY_TOKEN = "token"
        private const val KEY_BLOCKED = "blocked"
        private const val KEY_SNAPSHOT = "snapshot"
        private const val KEY_SHIFT_START = "shift_start_minutes"
        private const val KEY_SHIFT_END = "shift_end_minutes"
        private const val KEY_REQUIRED = "required_millis"
        private const val KEY_HISTORY = "history"
        private const val KEY_ALERT_HOURS = "alert_hours_date"
        private const val KEY_ALERT_BREAK = "alert_break_date"
        private const val KEY_ALERT_END = "alert_end_date"
    }
}
