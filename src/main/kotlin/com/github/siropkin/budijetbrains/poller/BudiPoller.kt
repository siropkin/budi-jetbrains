package com.github.siropkin.budijetbrains.poller

import com.github.siropkin.budijetbrains.daemon.BudiClient
import com.github.siropkin.budijetbrains.notifier.BudiUpgradeNotifier
import com.github.siropkin.budijetbrains.settings.BudiSettings
import com.github.siropkin.budijetbrains.state.BudiAppState
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.ProjectManager
import com.intellij.util.Alarm
import com.intellij.util.application
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Background poller that keeps [BudiAppState] in sync with the daemon.
 *
 * Mirrors budi-cursor's `requestRefresh` loop: a single in-flight
 * request at a time; user-driven refreshes coalesce with the next
 * scheduled tick rather than overlapping. Disposed alongside the
 * application — there is one poller for the lifetime of the IDE.
 */
@Service(Service.Level.APP)
class BudiPoller {

    private val log = Logger.getInstance(BudiPoller::class.java)
    private val client = BudiClient()

    /**
     * Pooled-thread alarm so HTTP work happens off the EDT. Disposed
     * with the application via the service container — see plugin.xml.
     */
    private val alarm: Alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, application)

    private val refreshing = AtomicBoolean(false)
    private val pendingRefresh = AtomicBoolean(false)

    /**
     * Start polling. Idempotent — calling twice is safe; the alarm just
     * re-arms with the latest interval.
     */
    fun start() {
        scheduleNext(initial = true)
    }

    /** Force an out-of-band refresh (e.g. from a settings change or manual action). */
    fun refreshNow() {
        triggerRefresh()
    }

    private fun scheduleNext(initial: Boolean) {
        val interval = BudiSettings.getInstance().state.pollingIntervalMs.coerceAtLeast(1_000)
        alarm.cancelAllRequests()
        alarm.addRequest({
            triggerRefresh()
            scheduleNext(initial = false)
        }, if (initial) 0 else interval)
    }

    private fun triggerRefresh() {
        if (!refreshing.compareAndSet(false, true)) {
            // A refresh is in flight; let it know another is wanted.
            pendingRefresh.set(true)
            return
        }
        try {
            doRefresh()
        } finally {
            refreshing.set(false)
            if (pendingRefresh.compareAndSet(true, false)) {
                triggerRefresh()
            }
        }
    }

    private fun doRefresh() {
        val settings = BudiSettings.getInstance()
        val daemonUrl = settings.resolvedDaemonUrl()
        val includeOtherSurfaces = settings.state.includeOtherSurfaces
        val projectDir = ProjectManager.getInstance().openProjects
            .firstOrNull { !it.isDisposed }
            ?.basePath
        val health = client.fetchHealth(daemonUrl)
        val statusline = client.fetchStatusline(
            daemonUrl,
            projectDir,
            includeOtherSurfaces,
        )
        if (health != null && !settings.state.everSawDaemon) {
            settings.updateAndPersist { everSawDaemon = true }
            log.info("First daemon detection — leaving FIRST_RUN.")
        }
        BudiAppState.getInstance().update(health, statusline, settings.state.everSawDaemon)
        BudiUpgradeNotifier.getInstance().onHealthObserved(health)
    }

    companion object {
        fun getInstance(): BudiPoller =
            ApplicationManager.getApplication().getService(BudiPoller::class.java)
    }
}
