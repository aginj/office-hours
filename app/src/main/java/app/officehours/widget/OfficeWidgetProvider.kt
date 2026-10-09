package app.officehours.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle

class OfficeWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        WidgetUpdater.updateAll(context)
        RefreshScheduler.ensure(context, immediate = true)
    }

    override fun onEnabled(context: Context) {
        WidgetUpdater.updateAll(context)
        RefreshScheduler.ensure(context, immediate = true)
    }

    override fun onDisabled(context: Context) {
        RefreshScheduler.cancel(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        WidgetUpdater.updateAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TICK -> {
                WidgetUpdater.updateAll(context)
                RefreshScheduler.scheduleTick(context)
                return
            }
            ACTION_REFRESH -> {
                WidgetUpdater.markRefreshing(context)
                RefreshScheduler.refreshNow(context)
                return
            }
        }
        super.onReceive(context, intent)
    }

    companion object {
        const val ACTION_TICK = "app.officehours.action.TICK"
        const val ACTION_REFRESH = "app.officehours.action.REFRESH"
    }
}
