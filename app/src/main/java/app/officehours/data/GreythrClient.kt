package app.officehours.data

import android.net.Uri
import android.util.Base64
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

class GreythrException(
    message: String,
    val kind: Kind,
) : Exception(message) {
    enum class Kind {
        PASSWORD_RESET,
        MFA,
        AUTH,
        NETWORK,
    }
}

class GreythrClient(
    private val jar: AppCookieJar,
) {
    private val http = OkHttpClient.Builder()
        .cookieJar(jar)
        .followRedirects(true)
        .followSslRedirects(true)
        .callTimeout(45, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
                    )
                    .header("Accept", "application/json, text/plain, */*")
                    .build(),
            )
        }
        .build()

    private val manual = http.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    fun signIn(companyInput: String, username: String, password: String): SavedSession {
        val company = CompanyAddress.parse(companyInput)
        val config = try {
            loadSessionConfig(company)
        } catch (error: GreythrException) {
            throw error
        } catch (error: Exception) {
            throw network(error)
        }

        val verifier = randomToken(64)
        val challenge = pkceChallenge(verifier)
        val nonce = randomToken(32)
        val state = "$nonce;${base64("""{"host":"${company.host}"}""")}"
        val authorize = config.issuer.toHttpUrl().newBuilder()
            .addPathSegments("oauth2/auth")
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", config.clientId)
            .addQueryParameter("redirect_uri", config.redirectUri)
            .addQueryParameter("scope", "openid offline")
            .addQueryParameter("state", state)
            .addQueryParameter("nonce", nonce)
            .addQueryParameter("code_challenge", challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("access_id", config.accessId)
            .addQueryParameter("gt_user_token", "")
            .addQueryParameter("origin_user", "")
            .build()

        val loginPage = follow(Request.Builder().url(authorize).get().build())
        val oauthChallenge = findParam(loginPage.urls, loginPage.result.body, "login_challenge")
            ?: throw GreythrException(
                "greytHR did not start a sign-in session. Check the company address.",
                GreythrException.Kind.AUTH,
            )

        val started = execute(
            Request.Builder()
                .url(
                    company.base.newBuilder()
                        .addPathSegments("uas/v1/initiate-login")
                        .addPathSegment(oauthChallenge)
                        .build(),
                )
                .header("X-OAUTH-CHALLENGE", oauthChallenge)
                .get()
                .build(),
        )
        if (started.code !in 200..299) {
            throw loginFailure(started.body, "Could not start sign-in.")
        }
        val startedJson = parseObject(started.body)
        if (startedJson != null && startedJson.has("enabled") && !startedJson.optBoolean("enabled", true)) {
            throw GreythrException("This greytHR account is disabled.", GreythrException.Kind.AUTH)
        }

        val encrypted = PasswordCipher.encrypt(password)
        val payload = JSONObject()
            .put("userName", username)
            .put("password", encrypted)
            .toString()
        val login = execute(
            Request.Builder()
                .url(company.base.newBuilder().addPathSegments("uas/v1/login").build())
                .header("X-OAUTH-CHALLENGE", oauthChallenge)
                .header("Content-Type", "application/json")
                .post(payload.toRequestBody(JSON))
                .build(),
        )
        if (login.code !in 200..299) {
            throw loginFailure(login.body, "Employee number or password was not accepted.")
        }
        val loginJson = parseObject(login.body)
            ?: throw GreythrException("greytHR returned an unexpected sign-in response.", GreythrException.Kind.AUTH)
        throwIfPasswordReset(loginJson)
        val redirect = loginJson.optString("redirectUrl", "")
        if (redirect.contains("change-password", ignoreCase = true)) {
            throw passwordReset()
        }
        if (redirect.contains("/mfa", ignoreCase = true)) {
            throw mfa()
        }
        if (redirect.isBlank()) {
            val message = loginJson.optString("message", "").ifBlank {
                "Sign-in did not finish."
            }
            if (looksLikePasswordReset(message) || loginJson.optString("errorState") == "PASSWORD_EXPIRED") {
                throw passwordReset()
            }
            throw GreythrException(message, GreythrException.Kind.AUTH)
        }

        val redirectUrl = company.base.resolve(redirect)
            ?: throw GreythrException("greytHR returned a sign-in link that could not be opened.", GreythrException.Kind.AUTH)
        val afterLogin = follow(Request.Builder().url(redirectUrl).get().build())
        val error = afterLogin.urls.firstNotNullOfOrNull { it.queryParameter("error") }
        if (!error.isNullOrBlank()) {
            val description = afterLogin.urls.firstNotNullOfOrNull { it.queryParameter("error_description") } ?: error
            if (looksLikePasswordReset(description)) throw passwordReset()
            if (description.contains("change-password", ignoreCase = true)) throw passwordReset()
            throw GreythrException(description, GreythrException.Kind.AUTH)
        }
        if (afterLogin.urls.any { it.encodedPath.contains("change-password") }) throw passwordReset()
        if (afterLogin.urls.any { it.encodedPath.contains("/mfa") }) throw mfa()
        val code = findParam(afterLogin.urls, afterLogin.result.body, "code")
            ?: throw GreythrException(
                "greytHR did not finish sign-in. If it asked you to reset your password, do that on the website first.",
                GreythrException.Kind.AUTH,
            )

        val tokenCall = execute(
            Request.Builder()
                .url(company.base.newBuilder().addPathSegments("uas/v1/initiate/token-request").build())
                .header("CODE", code)
                .header("PKCE-verifier", verifier)
                .post("{}".toRequestBody(JSON))
                .build(),
        )
        val portalToken = tokenFrom(tokenCall.body)
        var cookieToken = jar.find("access_token", company.host)
        val hydraToken = if (portalToken.isNullOrBlank() && cookieToken.isNullOrBlank()) {
            exchangeWithHydra(config, code, verifier)
        } else {
            null
        }
        val headerToken = portalToken ?: cookieToken ?: hydraToken
        if (!headerToken.isNullOrBlank()) {
            execute(
                Request.Builder()
                    .url(company.base.newBuilder().addPathSegments("uas/v1/session-cookie").build())
                    .header("ACCESS-TOKEN", headerToken)
                    .header("x-greythr-domain", company.host)
                    .post("{}".toRequestBody(JSON))
                    .build(),
            )
            cookieToken = jar.find("access_token", company.host) ?: cookieToken
        }
        if (cookieToken.isNullOrBlank() && !headerToken.isNullOrBlank()) {
            jar.installAccessToken(company.host, headerToken)
            cookieToken = headerToken
        }
        val savedToken = cookieToken ?: headerToken
        if (savedToken.isNullOrBlank()) {
            throw GreythrException(
                "Signed in, but greytHR did not keep the session. Try again.",
                GreythrException.Kind.AUTH,
            )
        }
        return SavedSession(
            companyBase = company.base.toString().trimEnd('/'),
            host = company.host,
            accessToken = savedToken,
        )
    }

    fun loadAttendance(session: SavedSession, shift: ShiftSettings): AttendanceLoad {
        return loadAttendance(session, shift, allowRolloverRetry = true)
    }

    private fun loadAttendance(
        session: SavedSession,
        shift: ShiftSettings,
        allowRolloverRetry: Boolean,
    ): AttendanceLoad {
        restore(session)
        val base = session.companyBase.toHttpUrl()
        val zone = AttendanceMath.OFFICE_ZONE
        val now = Instant.now()
        val today = LocalDate.now(zone)
        val monday = today.with(DayOfWeek.MONDAY)
        val friday = monday.plusDays(4)
        val monthStart = today.withDayOfMonth(1)
        val rangeStart = if (monday.isBefore(monthStart)) monday else monthStart
        val rangeEnd = if (today.isAfter(friday)) today else friday
        val statuses = linkedMapOf<String, Int>()

        val calendar = loadCalendar(base, session, rangeStart, rangeEnd, statuses)
        val employeeId = calendar.employeeId
            ?: throw attendanceFailure(statuses, "Could not find your greytHR profile")

        // Today must come from the open-ended call (empty endDate). greytHR answers that one with
        // live swipes; a call with a real endDate is served from processed attendance and lags.
        val live = fetchPunches(
            base = base,
            session = session,
            employeeId = employeeId,
            start = today,
            end = today,
            zone = zone,
            statuses = statuses,
            label = "today",
            emptyEndDate = true,
        )
        val todayResult = if (live.loaded) {
            live
        } else {
            fetchPunches(base, session, employeeId, today, today, zone, statuses, "today-range")
        }

        // Past days for the week / month view. Best effort: a failure here must not hide today.
        val pastResult = if (rangeStart.isBefore(today)) {
            runCatching {
                fetchPunches(base, session, employeeId, rangeStart, today.minusDays(1), zone, statuses, "history")
            }.getOrElse { PunchFetch(emptyList(), false) }
        } else {
            PunchFetch(emptyList(), true)
        }

        if (!todayResult.loaded) {
            // Never show "no swipes yet" just because the swipe endpoints failed.
            throw attendanceFailure(statuses, "Could not read today's attendance")
        }
        if (allowRolloverRetry && LocalDate.now(zone) != today) {
            return loadAttendance(session, shift, allowRolloverRetry = false)
        }
        val todayPunches = todayResult.punches.filter { punch -> punch.at.atZone(zone).toLocalDate() == today }
        val pastPunches = pastResult.punches.filter { punch -> punch.at.atZone(zone).toLocalDate().isBefore(today) }
        val snapshot = AttendanceMath.snapshot(todayPunches, now, zone, shift.requiredMillis)
        val history = AttendanceMath.historyFromPunches(
            punches = pastPunches + todayPunches,
            todaySnapshot = snapshot,
            today = today,
            start = rangeStart,
            end = rangeEnd,
            now = now,
            zone = zone,
            shift = shift,
            markers = calendar.markers,
        )
        return AttendanceLoad(snapshot, history)
    }

    private data class PunchFetch(
        val punches: List<Punch>,
        val loaded: Boolean,
    )

    private fun fetchPunches(
        base: HttpUrl,
        session: SavedSession,
        employeeId: String,
        start: LocalDate,
        end: LocalDate,
        zone: ZoneId,
        statuses: MutableMap<String, Int>,
        label: String,
        emptyEndDate: Boolean = false,
    ): PunchFetch {
        var loaded = false
        for (root in ATTENDANCE_ROOTS) {
            val response = authorizedGet(swipesUrl(base, root, employeeId, start, end, emptyEndDate), session)
            statuses["swipes $label $root"] = response.code
            if (response.code == 401 || response.code == 403) continue
            if (response.code !in 200..299) continue
            loaded = true
            val parsed = inRange(parseAttendanceBody(response.body, zone), zone, start, end)
            if (parsed.isNotEmpty()) {
                return PunchFetch(parsed, true)
            }
        }
        return PunchFetch(emptyList(), loaded)
    }

    private fun restore(session: SavedSession) {
        val token = session.accessToken ?: return
        if (jar.find("access_token", session.host) == null) {
            jar.installAccessToken(session.host, token)
        }
    }

    private data class CalendarInfo(
        val employeeId: String?,
        val markers: Map<LocalDate, DayMark>,
    )

    /**
     * Reads the leave calendar for every month touching [start]..[end]. The response carries the
     * employee id the swipe call needs, and the holiday / leave / week-off entries for the grid.
     */
    private fun loadCalendar(
        base: HttpUrl,
        session: SavedSession,
        start: LocalDate,
        end: LocalDate,
        statuses: MutableMap<String, Int>,
    ): CalendarInfo {
        val months = linkedSetOf<Pair<Int, Int>>()
        var cursor = start.withDayOfMonth(1)
        while (!cursor.isAfter(end)) {
            months += cursor.monthValue to cursor.year
            cursor = cursor.plusMonths(1)
        }
        val markers = linkedMapOf<LocalDate, DayMark>()
        var employeeId: String? = null
        for (root in ATTENDANCE_ROOTS) {
            var anyLoaded = false
            for ((month, year) in months) {
                val url = base.newBuilder()
                    .addPathSegments("$root/leave/calendar/$month/$year/entries")
                    .addQueryParameter("type", "myLeaveCalendarShortlist")
                    .build()
                val response = authorizedGet(url, session)
                statuses["profile $root"] = response.code
                if (response.code !in 200..299) continue
                anyLoaded = true
                if (employeeId == null) employeeId = employeeIdFrom(response.body)
                runCatching { CalendarMarkers.parse(response.body, start, end) }
                    .getOrDefault(emptyMap())
                    .forEach { (date, mark) -> markers.putIfAbsent(date, mark) }
            }
            if (anyLoaded && employeeId != null) break
        }
        return CalendarInfo(employeeId, markers)
    }

    private fun swipesUrl(
        base: HttpUrl,
        root: String,
        employeeId: String,
        start: LocalDate,
        end: LocalDate,
        emptyEndDate: Boolean = false,
    ): HttpUrl {
        return base.newBuilder()
            .addPathSegments("$root/attendance/info/$employeeId/swipes")
            .addQueryParameter("startDate", start.toString())
            .addQueryParameter("endDate", if (emptyEndDate) "" else end.toString())
            .addQueryParameter("systemSwipes", "true")
            .addQueryParameter("swipePairs", "true")
            .build()
    }

    private fun employeeIdFrom(body: String): String? {
        val json = parseObject(body) ?: return null
        val value = json.opt("employee").takeUnless { it == null || it == JSONObject.NULL }
            ?: json.optJSONObject("data")?.opt("employee")?.takeUnless { it == JSONObject.NULL }
            ?: return null
        val text = when (value) {
            is Number -> value.toLong().toString()
            is String -> value.trim()
            is JSONObject -> value.optString("id").ifBlank { value.optString("employeeId") }
            else -> value.toString()
        }
        return text.takeIf { it.isNotBlank() && it != "null" && it.all { char -> char.isLetterOrDigit() || char == '-' || char == '_' } }
    }

    private fun attendanceFailure(statuses: Map<String, Int>, prefix: String): GreythrException {
        val denied = statuses.isNotEmpty() && statuses.values.all { it == 401 || it == 403 }
        val detail = statuses.entries.joinToString(", ") { "${it.key} HTTP ${it.value}" }
        return GreythrException(
            "$prefix ($detail).",
            if (denied) GreythrException.Kind.AUTH else GreythrException.Kind.NETWORK,
        )
    }

    private fun authorizedGet(url: HttpUrl, session: SavedSession): HttpResult {
        val token = session.accessToken
        val headerSets = buildList {
            if (!token.isNullOrBlank()) {
                add(mapOf("ACCESS-TOKEN" to token))
                add(mapOf("Authorization" to "Bearer $token"))
            }
            add(emptyMap())
        }
        var last: HttpResult? = null
        for (headers in headerSets) {
            val builder = Request.Builder()
                .url(url)
                .get()
                .header("x-greythr-domain", session.host)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Cache-Control", "no-cache, no-store")
                .header("Pragma", "no-cache")
            headers.forEach { (name, value) -> builder.header(name, value) }
            last = call(manual, builder.build())
            if (last.code != 401 && last.code != 403) return last
        }
        return last ?: throw GreythrException("Could not read today's attendance.", GreythrException.Kind.NETWORK)
    }

    private fun inRange(punches: List<Punch>, zone: ZoneId, start: LocalDate, end: LocalDate): List<Punch> {
        return punches.filter { punch ->
            val date = punch.at.atZone(zone).toLocalDate()
            !date.isBefore(start) && !date.isAfter(end)
        }
    }

    private fun parseAttendanceBody(body: String, zone: ZoneId): List<Punch> {
        val fromSwipes = parseSwipes(body, zone)
        if (fromSwipes.isNotEmpty()) return fromSwipes
        return parseDashlet(parseObject(body), zone)
    }

    private fun loadSessionConfig(company: Company): PortalConfig {
        val response = execute(
            Request.Builder()
                .url(company.base.newBuilder().addPathSegments("uas/v1/session-config").build())
                .get()
                .build(),
        )
        if (response.code !in 200..299) {
            throw GreythrException(
                "Could not reach that greytHR company. Check the address.",
                GreythrException.Kind.NETWORK,
            )
        }
        val json = parseObject(response.body)
            ?: throw GreythrException("greytHR did not share sign-in settings.", GreythrException.Kind.AUTH)
        val issuer = json.optString("hydraFrontendServer", "").trim().trimEnd('/')
        val clientId = json.optString("hydraClient", "")
        val redirect = json.optString("oAuthRedirectUrl", "")
        val accessId = json.optString("accessId", "")
        if (issuer.isBlank() || clientId.isBlank() || redirect.isBlank()) {
            throw GreythrException("greytHR did not share sign-in settings.", GreythrException.Kind.AUTH)
        }
        return PortalConfig(issuer, clientId, redirect, accessId)
    }

    private fun exchangeWithHydra(config: PortalConfig, code: String, verifier: String): String? {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", config.redirectUri)
            .add("client_id", config.clientId)
            .add("code_verifier", verifier)
            .build()
        val response = execute(
            Request.Builder()
                .url(config.issuer.toHttpUrl().newBuilder().addPathSegments("oauth2/token").build())
                .post(form)
                .build(),
        )
        if (response.code !in 200..299) return null
        return tokenFrom(response.body)
    }

    private fun execute(request: Request): HttpResult = follow(request, automatic = true).result

    private fun follow(request: Request, automatic: Boolean = false): Trail {
        if (automatic) {
            return Trail(listOf(request.url), call(http, request))
        }
        val urls = mutableListOf<HttpUrl>()
        var current = request
        repeat(15) {
            val response = call(manual, current)
            urls.add(response.finalUrl)
            if (response.code in 300..399) {
                val location = response.location
                if (location.isNullOrBlank()) return Trail(urls, response)
                val next = response.finalUrl.resolve(location) ?: return Trail(urls, response)
                urls.add(next)
                current = Request.Builder().url(next).get().build()
            } else {
                return Trail(urls, response)
            }
        }
        throw GreythrException("greytHR sent too many redirects.", GreythrException.Kind.NETWORK)
    }

    private fun call(client: OkHttpClient, request: Request): HttpResult {
        try {
            client.newCall(request).execute().use { response ->
                return HttpResult(
                    code = response.code,
                    body = response.body?.string().orEmpty(),
                    finalUrl = response.request.url,
                    location = response.header("Location"),
                )
            }
        } catch (error: GreythrException) {
            throw error
        } catch (error: Exception) {
            throw network(error)
        }
    }

    private fun parseSwipes(body: String, zone: ZoneId): List<Punch> {
        val trimmed = body.trim()
            val array = when {
            trimmed.startsWith("[") -> runCatching { JSONArray(trimmed) }.getOrNull()
            trimmed.startsWith("{") -> {
                val obj = parseObject(trimmed)
                obj?.optJSONArray("swipe")
                    ?: obj?.optJSONObject("data")?.optJSONArray("swipe")
                    ?: obj?.optJSONArray("data")
                    ?: obj?.optJSONObject("data")?.optJSONArray("swipes")
                    ?: obj?.optJSONObject("data")?.optJSONArray("content")
                    ?: obj?.optJSONArray("swipes")
                    ?: obj?.optJSONArray("content")
                    ?: obj?.optJSONArray("list")
                    ?: obj?.optJSONArray("items")
                    ?: obj?.optJSONArray("records")
            }
            else -> null
        } ?: return emptyList()
        val punches = mutableListOf<Punch>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val at = parseTime(item.opt("punchTime"), zone)
                ?: parseTime(item.opt("punchDateTime"), zone)
                ?: parseTime(item.opt("swipeDateTime"), zone)
                ?: parseTime(item.opt("swipeTime"), zone)
                ?: parseTime(item.opt("time"), zone)
                ?: parseTime(item.opt("inTime"), zone)
                ?: continue
            val direction = directionOf(
                if (item.has("inOutIndicator")) item.opt("inOutIndicator") else null,
            )
            punches.add(Punch(at, direction))
        }
        return punches
    }

    private fun parseDashlet(json: JSONObject?, zone: ZoneId): List<Punch> {
        if (json == null) return emptyList()
        val info = json.optJSONObject("attendanceInfo")
            ?: json.optJSONObject("data")
            ?: json
        val swipe = info.optJSONObject("swipeInfo") ?: info
        val first = parseTime(swipe.opt("firstInTime"), zone)
            ?: parseTime(info.opt("firstInTime"), zone)
            ?: return emptyList()
        val last = parseTime(swipe.opt("lastOutTime"), zone)
            ?: parseTime(info.opt("lastOutTime"), zone)
        val signedIn = signedInFlag(
            if (info.has("inOutIndicator")) info.opt("inOutIndicator") else swipe.opt("inOutIndicator"),
        )
        val punches = mutableListOf(Punch(first, Direction.IN))
        if (!signedIn && last != null && last.isAfter(first)) {
            punches.add(Punch(last, Direction.OUT))
        }
        return punches
    }

    private fun signedInFlag(value: Any?): Boolean = when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value.equals("true", true) || value == "1" || value.equals("IN", true)
        else -> false
    }

    private fun directionOf(value: Any?): Direction? = when (value) {
        null, JSONObject.NULL -> null
        is Boolean -> if (value) Direction.IN else Direction.OUT
        is Number -> if (value.toInt() == 0) Direction.OUT else Direction.IN
        is String -> when (value.trim().uppercase(Locale.US)) {
            "IN", "1", "TRUE" -> Direction.IN
            "OUT", "0", "FALSE" -> Direction.OUT
            else -> null
        }
        else -> null
    }

    private fun parseTime(value: Any?, zone: ZoneId): Instant? {
        if (value == null || value == JSONObject.NULL) return null
        if (value is Number) {
            val raw = value.toLong()
            if (raw <= 0L) return null
            return if (raw > 10_000_000_000L) Instant.ofEpochMilli(raw) else Instant.ofEpochSecond(raw)
        }
        val text = value.toString().trim()
        if (text.isEmpty() || text == "null") return null
        runCatching { Instant.parse(text) }.getOrNull()?.let { return it }
        runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()?.let { return it }
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss",
            "dd MMM yyyy HH:mm",
        )
        // greytHR punchDateTime has no zone and is UTC. 09:58 IST arrives as 04:28.
        for (pattern in patterns) {
            val parsed = runCatching {
                LocalDateTime.parse(text, DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH))
                    .atOffset(ZoneOffset.UTC)
                    .toInstant()
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return runCatching {
            val time = LocalTime.parse(text, DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ENGLISH))
            LocalDate.now(zone).atTime(time).atZone(zone).toInstant()
        }.getOrNull()
    }

    private fun loginFailure(body: String, fallback: String): GreythrException {
        val json = parseObject(body)
        val state = json?.optString("errorState").orEmpty()
        val message = json?.optString("message").orEmpty()
        if (state == "PASSWORD_EXPIRED" || looksLikePasswordReset(message)) return passwordReset()
        if (state == "ACCOUNT_DISABLED") {
            return GreythrException("This greytHR account is disabled.", GreythrException.Kind.AUTH)
        }
        if (state == "DISALLOWED_IPADDRESS") {
            return GreythrException(
                "greytHR blocked sign-in from this network.",
                GreythrException.Kind.AUTH,
            )
        }
        return GreythrException(message.ifBlank { fallback }, GreythrException.Kind.AUTH)
    }

    private fun throwIfPasswordReset(json: JSONObject) {
        val state = json.optString("errorState")
        val message = json.optString("message")
        if (state == "PASSWORD_EXPIRED" || looksLikePasswordReset(message)) throw passwordReset()
    }

    private fun looksLikePasswordReset(message: String): Boolean {
        val text = message.lowercase(Locale.US)
        return "reset your password" in text ||
            "reset the password" in text ||
            "password has expired" in text ||
            "password expired" in text ||
            "password is expired" in text
    }

    private fun passwordReset() = GreythrException(PASSWORD_RESET_MESSAGE, GreythrException.Kind.PASSWORD_RESET)

    private fun mfa() = GreythrException(MFA_MESSAGE, GreythrException.Kind.MFA)

    private fun network(error: Exception) = GreythrException(
        "Couldn't reach greytHR. Check the company address and your connection.",
        GreythrException.Kind.NETWORK,
    )

    private fun tokenFrom(body: String): String? {
        val json = parseObject(body) ?: return null
        return json.optString("access_token").takeIf { it.isNotBlank() }
            ?: json.optString("accessToken").takeIf { it.isNotBlank() }
    }

    private fun findParam(urls: List<HttpUrl>, body: String, name: String): String? {
        urls.forEach { url ->
            url.queryParameter(name)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        val match = Regex("$name=([^&#\"'\\s]+)").find(body) ?: return null
        return Uri.decode(match.groupValues[1])
    }

    private fun parseObject(body: String): JSONObject? {
        val trimmed = body.trim()
        if (!trimmed.startsWith("{")) return null
        return runCatching { JSONObject(trimmed) }.getOrNull()
    }

    private fun base64(text: String): String =
        Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun pkceChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun randomToken(length: Int): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        val random = SecureRandom()
        return buildString(length) {
            repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) }
        }
    }

    private data class HttpResult(
        val code: Int,
        val body: String,
        val finalUrl: HttpUrl,
        val location: String? = null,
    )

    private data class Trail(val urls: List<HttpUrl>, val result: HttpResult)
    private data class PortalConfig(
        val issuer: String,
        val clientId: String,
        val redirectUri: String,
        val accessId: String,
    )

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        const val PASSWORD_RESET_MESSAGE =
            "greytHR is asking you to reset your password before you can continue. " +
                "Reset it on the greytHR website, then sign in here with the new password."
        const val MFA_MESSAGE =
            "greytHR is asking for an extra verification step before login can finish. " +
                "Complete that on the greytHR website, then try again here."

        private val ATTENDANCE_ROOTS = listOf("v3/api", "latte/v3")
    }
}

class AppCookieJar : CookieJar {
    private val cookies = mutableListOf<Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { incoming ->
            this.cookies.removeAll { existing ->
                existing.name == incoming.name &&
                    existing.domain == incoming.domain &&
                    existing.path == incoming.path
            }
            if (incoming.expiresAt > System.currentTimeMillis()) {
                this.cookies.add(incoming)
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        cookies.removeAll { it.expiresAt <= now }
        return cookies.filter { it.matches(url) }
    }

    fun find(name: String, host: String): String? {
        return cookies.lastOrNull { cookie ->
            cookie.name == name && cookie.matches(https(host))
        }?.value
    }

    fun installAccessToken(host: String, token: String) {
        val cookie = Cookie.Builder()
            .name("access_token")
            .value(token)
            .hostOnlyDomain(host)
            .path("/")
            .secure()
            .httpOnly()
            .expiresAt(System.currentTimeMillis() + Duration.ofHours(10).toMillis())
            .build()
        saveFromResponse(https(host), listOf(cookie))
    }

    fun clear() {
        cookies.clear()
    }

    private fun https(host: String): HttpUrl = "https://$host/".toHttpUrl()
}

object CompanyAddress {
    fun parse(input: String): Company {
        var host = input.trim()
        if (host.isEmpty()) {
            throw GreythrException("Enter your company greytHR address.", GreythrException.Kind.AUTH)
        }
        host = host.removePrefix("https://").removePrefix("http://")
        host = host.substringBefore("/").substringBefore("?").trim().trimEnd('.')
        if (!host.contains('.')) host = "$host.greythr.com"
        if (host.any { it.isWhitespace() }) {
            throw GreythrException("That company address is not valid.", GreythrException.Kind.AUTH)
        }
        return Company(base = "https://$host".toHttpUrl(), host = host)
    }
}

data class Company(val base: HttpUrl, val host: String)
