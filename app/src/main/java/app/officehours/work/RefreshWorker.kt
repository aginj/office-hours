package app.officehours.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.officehours.data.OfficeRepository
import app.officehours.data.RefreshOutcome

class RefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val outcome = OfficeRepository(applicationContext).refresh()
        return if (outcome == RefreshOutcome.NETWORK) Result.retry() else Result.success()
    }
}
