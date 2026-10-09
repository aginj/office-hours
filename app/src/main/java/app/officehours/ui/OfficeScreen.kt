package app.officehours.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import app.officehours.alerts.OfficeAlerts
import app.officehours.data.AttendanceHistory
import app.officehours.data.AttendanceMath
import app.officehours.data.Direction
import app.officehours.data.OfficeSnapshot
import app.officehours.data.ShiftSettings
import app.officehours.data.formatHoursMinutes
import app.officehours.data.isFreshFor
import app.officehours.data.rolledForward
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfficeApp(model: OfficeViewModel = viewModel()) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) model.onResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    OfficeTheme {
        RequestNotificationPermission(enabled = model.showHome)
        Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
            if (model.showHome && model.snapshot != null) {
                HomeScreen(
                    snapshot = model.snapshot!!,
                    history = model.history,
                    shift = model.shift,
                    tab = model.tab,
                    busy = model.busy,
                    onTab = model::selectTab,
                    onRefresh = model::refresh,
                    onSignOut = model::signOut,
                    onOpenShift = model::openSettings,
                    modifier = Modifier.padding(padding),
                )
            } else {
                LoginScreen(
                    company = model.company,
                    username = model.username,
                    password = model.password,
                    busy = model.busy,
                    error = model.error,
                    onCompanyChange = model::onCompanyChange,
                    onUsernameChange = model::onUsernameChange,
                    onPasswordChange = model::onPasswordChange,
                    onSignIn = model::signIn,
                    modifier = Modifier.padding(padding),
                )
            }
        }
        if (model.showSettings && model.snapshot != null) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(onDismissRequest = model::closeSettings, sheetState = sheetState) {
                ShiftSettingsContent(
                    shift = model.shift,
                    busy = model.busy,
                    onSave = model::saveShift,
                    onReset = { model.saveShift(ShiftSettings.DEFAULT) },
                )
            }
        }
    }
}

@Composable
private fun RequestNotificationPermission(enabled: Boolean) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(enabled) {
        if (!enabled || Build.VERSION.SDK_INT < 33) return@LaunchedEffect
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Composable
private fun LoginScreen(
    company: String,
    username: String,
    password: String,
    busy: Boolean,
    error: String?,
    onCompanyChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        ProgressRing(progress = 0.72f, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp), stroke = 8.dp) {}
        Text("Office Hours", style = MaterialTheme.typography.headlineMedium)
        Text(
            "How long you have been in office today, how much is left, and how much break still fits before the shift ends. Read from your greytHR swipes.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!error.isNullOrBlank()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(
                    error,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        OutlinedTextField(
            value = company,
            onValueChange = onCompanyChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Company address") },
            placeholder = { Text("yourcompany.greythr.com") },
            singleLine = true,
            enabled = !busy,
        )
        OutlinedTextField(
            value = username,
            onValueChange = onUsernameChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Employee number") },
            singleLine = true,
            enabled = !busy,
        )
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            enabled = !busy,
        )
        Button(
            onClick = onSignIn,
            enabled = !busy && company.isNotBlank() && username.isNotBlank() && password.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.height(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text("Sign in")
            }
        }
        Text(
            "Your employee number and password are saved on this phone, encrypted, so the app can sign in again after greytHR ends the session. If greytHR asks you to reset your password, that is shown as an error. Reset it on the website, then sign in here with the new password.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (busy) {
            Text("Signing in to greytHR…", color = MaterialTheme.colorScheme.primary)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    snapshot: OfficeSnapshot,
    history: AttendanceHistory?,
    shift: ShiftSettings,
    tab: HomeTab,
    busy: Boolean,
    onTab: (HomeTab) -> Unit,
    onRefresh: () -> Unit,
    onSignOut: () -> Unit,
    onOpenShift: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(snapshot.updatedAtEpochMillis, snapshot.openSinceEpochMillis) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val zone = AttendanceMath.OFFICE_ZONE
    val officeDay = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    LaunchedEffect(officeDay) {
        if (!snapshot.isFreshFor(System.currentTimeMillis())) onRefresh()
    }
    val shown = snapshot.rolledForward(now, shift) ?: snapshot
    val stale = shown !== snapshot
    val context = LocalContext.current
    val clock = remember { DateTimeFormatter.ofPattern("h:mm a").withZone(zone) }
    val dayLabel = DateTimeFormatter.ofPattern("EEEE, d MMMM").withZone(zone).format(Instant.ofEpochMilli(now))
    val updated = clock.format(Instant.ofEpochMilli(shown.updatedAtEpochMillis))
    val remaining = shown.remainingAt(now)
    val allowance = AttendanceMath.shiftBreak(shown, now, shift = shift)
    val status = todayStatus(shown, shift, now)
    val stays = remember(shown.swipes, shown.openSinceEpochMillis, shown.updatedAtEpochMillis) { staysOf(shown) }
    val breakMillis = remember(stays) { breakMillisOf(stays) }
    val onBreakSince = AttendanceMath.onBreakSinceEpochMillis(shown)
    LaunchedEffect(remaining == 0L, allowance <= 0L && remaining > 0L, stale) {
        if (!stale) withContext(Dispatchers.IO) { OfficeAlerts.sync(context) }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(dayLabel, style = MaterialTheme.typography.headlineSmall)
                Text(
                    when {
                        busy -> "Refreshing greytHR…"
                        stale -> "Loading today’s swipes…"
                        else -> "India time · updated $updated"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(onClick = onRefresh, enabled = !busy) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh")
            }
            IconButton(onClick = onOpenShift, enabled = !busy) {
                Icon(Icons.Default.Settings, contentDescription = "Shift settings")
            }
            IconButton(onClick = onSignOut, enabled = !busy) {
                Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "Sign out")
            }
        }
        TabRow(
            selectedTabIndex = tab.ordinal,
            containerColor = MaterialTheme.colorScheme.background,
            divider = {},
        ) {
            Tab(
                selected = tab == HomeTab.TODAY,
                onClick = { onTab(HomeTab.TODAY) },
                text = { Text("Today") },
            )
            Tab(
                selected = tab == HomeTab.WEEK,
                onClick = { onTab(HomeTab.WEEK) },
                text = { Text("Week & month") },
            )
        }
        PullToRefreshBox(
            isRefreshing = busy,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    val forward = targetState.ordinal > initialState.ordinal
                    val enter = slideInHorizontally { full -> if (forward) full / 6 else -full / 6 } + fadeIn()
                    val exit = slideOutHorizontally { full -> if (forward) -full / 6 else full / 6 } + fadeOut()
                    enter togetherWith exit
                },
                label = "tab",
            ) { current ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (current) {
                        HomeTab.TODAY -> TodayContent(
                            snapshot = shown,
                            shift = shift,
                            status = status,
                            now = now,
                            stays = stays,
                            breakMillis = breakMillis,
                            onBreakSince = onBreakSince,
                            clock = clock,
                        )
                        HomeTab.WEEK -> {
                            WeekCard(history = history, snapshot = shown, shift = shift, now = now)
                            MonthCard(history = history, snapshot = shown, shift = shift, now = now)
                        }
                    }
                    OutlinedButton(onClick = onRefresh, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(if (busy) "Refreshing…" else "Refresh from greytHR")
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun TodayContent(
    snapshot: OfficeSnapshot,
    shift: ShiftSettings,
    status: DayStatus,
    now: Long,
    stays: List<Stay>,
    breakMillis: Long,
    onBreakSince: Long?,
    clock: DateTimeFormatter,
) {
    snapshot.warning?.let { WarningBanner(it) }
    HeroCard(snapshot = snapshot, shift = shift, status = status, now = now, onBreakSince = onBreakSince)
    StatusCard(
        snapshot = snapshot,
        shift = shift,
        status = status,
        now = now,
        breakTakenMillis = breakMillis,
        onBreakSince = onBreakSince,
    )
    val longest = stays.maxOfOrNull { stay ->
        val end = stay.outAt?.toEpochMilli() ?: now
        (end - stay.inAt.toEpochMilli()).coerceAtLeast(0L)
    } ?: 0L
    val firstIn = snapshot.swipes.firstOrNull { it.direction == Direction.IN }?.at
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        StatTile(
            icon = Icons.Default.DateRange,
            label = "First in",
            value = firstIn?.let { clock.format(it) } ?: "—",
            modifier = Modifier.weight(1f),
        )
        StatTile(
            icon = Icons.Default.Info,
            label = "Breaks",
            value = if (breakMillis == 0L) "None" else formatHoursMinutes(breakMillis),
            modifier = Modifier.weight(1f),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        StatTile(
            icon = Icons.Default.Star,
            label = "Longest stay",
            value = if (longest == 0L) "—" else formatHoursMinutes(longest),
            modifier = Modifier.weight(1f),
        )
        StatTile(
            icon = Icons.Default.CheckCircle,
            label = "Stays",
            value = stays.size.toString(),
            modifier = Modifier.weight(1f),
        )
    }
    SwipeTimeline(stays = stays, now = now)
}
