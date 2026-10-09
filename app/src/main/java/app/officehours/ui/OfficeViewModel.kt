package app.officehours.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.officehours.data.AttendanceHistory
import app.officehours.data.OfficeRepository
import app.officehours.data.OfficeSnapshot
import app.officehours.data.RefreshOutcome
import app.officehours.data.ShiftSettings
import app.officehours.data.isFreshFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class HomeTab { TODAY, WEEK }

class OfficeViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = OfficeRepository(app)

    var company by mutableStateOf("")
        private set
    var username by mutableStateOf("")
        private set
    var password by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var snapshot by mutableStateOf<OfficeSnapshot?>(null)
        private set
    var history by mutableStateOf<AttendanceHistory?>(null)
        private set
    var shift by mutableStateOf(ShiftSettings.DEFAULT)
        private set
    var showHome by mutableStateOf(false)
        private set
    var showSettings by mutableStateOf(false)
        private set
    var tab by mutableStateOf(HomeTab.TODAY)
        private set

    init {
        val saved = repository.savedLogin()
        company = saved?.company.orEmpty()
        username = saved?.username.orEmpty()
        shift = repository.currentShift()
        history = repository.currentHistory()
        val existing = repository.currentSnapshot()
        snapshot = existing
        if (saved != null && existing?.blocked != true) {
            showHome = existing != null && existing.error == null
            refresh()
        } else {
            error = existing?.error
            showHome = false
        }
    }

    fun onCompanyChange(value: String) {
        company = value
    }

    fun onUsernameChange(value: String) {
        username = value
    }

    fun onPasswordChange(value: String) {
        password = value
    }

    fun selectTab(value: HomeTab) {
        tab = value
    }

    fun openSettings() {
        showSettings = true
    }

    fun closeSettings() {
        showSettings = false
    }

    fun saveShift(value: ShiftSettings) {
        if (busy) return
        busy = true
        showSettings = false
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repository.saveShift(value)
                repository.refresh()
            }
            pullState()
            busy = false
            error = snapshot?.error
            showHome = snapshot?.error == null && snapshot != null
        }
    }

    fun onResume() {
        val stored = repository.currentSnapshot()
        if (stored != null && stored.updatedAtEpochMillis != snapshot?.updatedAtEpochMillis) {
            pullState()
        }
        val current = snapshot ?: return
        if (!current.hasTimes) return
        val now = System.currentTimeMillis()
        // Refresh when the day has changed, or when what we have is older than a minute, so
        // coming back to the app always shows the latest swipes without a manual pull.
        if (current.isFreshFor(now) && now - current.updatedAtEpochMillis < RESUME_STALE_MILLIS) return
        refresh()
    }

    fun signIn() {
        if (busy) return
        error = null
        busy = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    repository.signIn(company, username, password)
                }
            }
            busy = false
            result.onSuccess { day ->
                pullState()
                snapshot = day
                error = null
                password = ""
                showHome = true
            }.onFailure { failure ->
                pullState()
                error = failure.message ?: "Sign-in failed."
                showHome = false
            }
        }
    }

    fun refresh() {
        if (busy) return
        busy = true
        viewModelScope.launch {
            val rolled = withContext(Dispatchers.IO) { repository.prepareForToday() }
            if (rolled) pullState()
            val outcome = withContext(Dispatchers.IO) { repository.refresh() }
            pullState()
            busy = false
            when (outcome) {
                RefreshOutcome.UPDATED -> {
                    error = null
                    showHome = true
                }
                RefreshOutcome.NETWORK -> {
                    error = if (snapshot?.error == null) null else snapshot?.error
                    showHome = snapshot?.error == null && snapshot != null
                }
                RefreshOutcome.BLOCKED, RefreshOutcome.FAILED -> {
                    error = snapshot?.error
                    showHome = false
                }
            }
        }
    }

    fun signOut() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.signOut()
            withContext(Dispatchers.Main) {
                password = ""
                snapshot = null
                history = null
                shift = repository.currentShift()
                error = null
                showHome = false
                showSettings = false
                tab = HomeTab.TODAY
                busy = false
            }
        }
    }

    private fun pullState() {
        snapshot = repository.currentSnapshot()
        history = repository.currentHistory()
        shift = repository.currentShift()
    }

    private companion object {
        const val RESUME_STALE_MILLIS = 60_000L
    }
}
