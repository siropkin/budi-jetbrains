package com.github.siropkin.budijetbrains.state

import com.github.siropkin.budijetbrains.daemon.DaemonHealth
import com.github.siropkin.budijetbrains.daemon.HealthState
import com.github.siropkin.budijetbrains.daemon.StatuslineData
import com.github.siropkin.budijetbrains.daemon.deriveHealthState
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Latest poll result, shared across every status-bar widget instance
 * (one widget per open project). Application-scoped so all widgets see
 * the same numbers and the daemon is hit once per polling tick rather
 * than once per project.
 *
 * Thread contract:
 *  - The cached `lastHealth` / `lastStatusline` / `lastState` fields are
 *    `@Volatile`; readers from any thread see the most recently written
 *    triple as a single consistent snapshot (the three writes in
 *    [update] are atomic-enough for the widget — torn reads would at
 *    worst paint one stale frame, which the next tick immediately
 *    corrects).
 *  - Listeners are invoked **synchronously on the caller's thread**.
 *    Today the only caller is [com.github.siropkin.budijetbrains.poller.BudiPoller]
 *    on its pooled-thread alarm, so subscribers run on a background
 *    thread and **must** marshal onto the EDT before touching Swing.
 *    Listener storage uses [CopyOnWriteArrayList] so concurrent
 *    add/remove during a notify is safe.
 */
@Service(Service.Level.APP)
class BudiAppState {
    @Volatile
    internal var lastHealth: DaemonHealth? = null
        private set

    @Volatile
    internal var lastStatusline: StatuslineData? = null
        private set

    @Volatile
    internal var lastState: HealthState = HealthState.GRAY
        private set

    private val listeners: MutableList<() -> Unit> = CopyOnWriteArrayList()

    /**
     * Replace the cached reading and recompute the derived health state.
     * `everSawDaemon` is threaded through from the persistent settings
     * so the FIRST_RUN → RED transition mirrors budi-cursor.
     */
    internal fun update(
        health: DaemonHealth?,
        statusline: StatuslineData?,
        everSawDaemon: Boolean,
    ) {
        lastHealth = health
        lastStatusline = statusline
        lastState = deriveHealthState(health, statusline, everSawDaemon)
        listeners.forEach { it.invoke() }
    }

    /**
     * Subscribe to state changes. The runnable fires synchronously from
     * the thread that called [update] — see the class-level thread
     * contract. Listeners that touch UI must marshal onto the EDT
     * themselves. There is no de-duplication and no fan-out ordering
     * guarantee across multiple subscribers.
     */
    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    companion object {
        fun getInstance(): BudiAppState = ApplicationManager.getApplication().getService(BudiAppState::class.java)
    }
}
