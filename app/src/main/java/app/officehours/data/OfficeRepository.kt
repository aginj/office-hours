package app.officehours.data

import android.content.Context
import app.officehours.widget.RefreshScheduler
import app.officehours.widget.WidgetUpdater

enum class RefreshOutcome {
    UPDATED,
    BLOCKED,
    FAILED,
    NETWORK,
}

class OfficeRepository(context: Context) {
    private val appContext = context.applicationContext
    private val store = CredentialStore(appContext)
    private val jar = AppCookieJar()
    private val client = GreythrClient(jar)

    fun savedLogin(): SavedLogin? = store.loadLogin()

    fun currentSnapshot(): OfficeSnapshot? = store.loadSnapshot()

    fun currentHistory(): AttendanceHistory? = store.loadHistory()

    fun currentShift(): ShiftSettings = store.loadShift()

    fun signIn(company: String, username: String, password: String): OfficeSnapshot {
        store.clearBlocked()
        store.clearSession()
        jar.clear()
        val login = SavedLogin(company.trim(), username.trim(), password)
        try {
            val snapshot = loadSnapshot(login, forceLogin = true)
            store.saveLogin(login.company, login.username, login.password)
            publish(snapshot)
            return snapshot
        } catch (error: GreythrException) {
            recordFailure(error)
            throw error
        }
    }

    fun prepareForToday(): Boolean {
        val existing = store.loadSnapshot() ?: return false
        val rolled = existing.rolledForward(System.currentTimeMillis(), store.loadShift()) ?: return false
        store.saveSnapshot(rolled)
        WidgetUpdater.updateAll(appContext)
        return true
    }

    fun refresh(forceLogin: Boolean = false): RefreshOutcome {
        val login = store.loadLogin()
        val blocked = store.blockedMessage()
        if (login == null) {
            if (blocked != null) {
                publish(blockedSnapshot(blocked))
                return RefreshOutcome.BLOCKED
            }
            return fail("Sign in with your greytHR employee number and password.")
        }
        if (blocked != null && !forceLogin) {
            publish(
                OfficeSnapshot(
                    settledMillis = 0,
                    openSinceEpochMillis = null,
                    requiredMillis = store.loadShift().requiredMillis,
                    updatedAtEpochMillis = System.currentTimeMillis(),
                    detail = blocked,
                    error = blocked,
                    blocked = true,
                ),
            )
            return RefreshOutcome.BLOCKED
        }
        return try {
            val snapshot = loadSnapshot(login, forceLogin)
            store.clearBlocked()
            publish(snapshot)
            RefreshOutcome.UPDATED
        } catch (error: GreythrException) {
            recordFailure(error)
        } catch (error: Exception) {
            publish(messageSnapshot(error.message ?: "Something went wrong."))
            RefreshOutcome.FAILED
        }
    }

    fun saveShift(shift: ShiftSettings) {
        store.saveShift(shift)
        store.clearAlertDates()
        val snapshot = store.loadSnapshot()
        if (snapshot != null) {
            store.saveSnapshot(snapshot.copy(requiredMillis = shift.requiredMillis))
        }
        WidgetUpdater.updateAll(appContext)
        RefreshScheduler.ensure(appContext)
    }

    private fun recordFailure(error: GreythrException): RefreshOutcome {
        return when (error.kind) {
            GreythrException.Kind.PASSWORD_RESET -> {
                store.clearSession()
                val message = error.message ?: GreythrClient.PASSWORD_RESET_MESSAGE
                store.setBlocked(message)
                publish(blockedSnapshot(message))
                RefreshOutcome.BLOCKED
            }
            GreythrException.Kind.MFA -> {
                store.clearSession()
                val message = error.message ?: GreythrClient.MFA_MESSAGE
                store.setBlocked(message)
                publish(blockedSnapshot(message))
                RefreshOutcome.BLOCKED
            }
            GreythrException.Kind.NETWORK -> {
                val previous = store.loadSnapshot()
                if (previous != null && !previous.blocked && previous.error == null) {
                    publish(previous.copy(detail = error.message ?: previous.detail))
                } else {
                    publish(messageSnapshot(error.message ?: "Couldn't reach greytHR."))
                }
                RefreshOutcome.NETWORK
            }
            GreythrException.Kind.AUTH -> {
                store.clearSession()
                val message = error.message ?: "Sign-in failed."
                store.setBlocked(message)
                publish(blockedSnapshot(message))
                RefreshOutcome.BLOCKED
            }
        }
    }

    fun signOut() {
        jar.clear()
        store.clearAll()
        WidgetUpdater.updateAll(appContext)
        RefreshScheduler.cancel(appContext)
    }

    private fun loadSnapshot(login: SavedLogin, forceLogin: Boolean): OfficeSnapshot {
        val shift = store.loadShift()
        if (!forceLogin) {
            val saved = store.loadSession()
            if (saved != null) {
                try {
                    return loadAttendance(saved, shift)
                } catch (error: GreythrException) {
                    if (error.kind != GreythrException.Kind.AUTH) throw error
                    store.clearSession()
                    jar.clear()
                }
            }
        }
        val session = client.signIn(login.company, login.username, login.password)
        store.saveSession(session)
        return try {
            loadAttendance(session, shift)
        } catch (error: GreythrException) {
            // The credentials just worked, so a 401/403 from the data endpoints is a greytHR hiccup,
            // not a sign-in problem. Keep the account signed in and let the next refresh retry.
            if (error.kind == GreythrException.Kind.AUTH) {
                throw GreythrException(
                    error.message ?: "greytHR refused the attendance request. Will retry.",
                    GreythrException.Kind.NETWORK,
                )
            }
            throw error
        }
    }

    private fun loadAttendance(session: SavedSession, shift: ShiftSettings): OfficeSnapshot {
        val load = client.loadAttendance(session, shift)
        store.saveHistory(load.history)
        return load.today
    }

    private fun publish(snapshot: OfficeSnapshot) {
        store.saveSnapshot(snapshot)
        WidgetUpdater.updateAll(appContext)
        RefreshScheduler.ensure(appContext)
    }

    private fun fail(message: String): RefreshOutcome {
        publish(messageSnapshot(message))
        return RefreshOutcome.FAILED
    }

    private fun messageSnapshot(message: String) = OfficeSnapshot(
        settledMillis = 0,
        openSinceEpochMillis = null,
        requiredMillis = store.loadShift().requiredMillis,
        updatedAtEpochMillis = System.currentTimeMillis(),
        detail = message,
        error = message,
    )

    private fun blockedSnapshot(message: String) = OfficeSnapshot(
        settledMillis = 0,
        openSinceEpochMillis = null,
        requiredMillis = store.loadShift().requiredMillis,
        updatedAtEpochMillis = System.currentTimeMillis(),
        detail = message,
        error = message,
        blocked = true,
    )
}
