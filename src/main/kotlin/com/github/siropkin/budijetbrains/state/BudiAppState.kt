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
 */
@Service(Service.Level.APP)
class BudiAppState {

    @Volatile
    var lastHealth: DaemonHealth? = null
        private set

    @Volatile
    var lastStatusline: StatuslineData? = null
        private set

    @Volatile
    var lastState: HealthState = HealthState.GRAY
        private set

    private val listeners: MutableList<() -> Unit> = CopyOnWriteArrayList()

    /**
     * Replace the cached reading and recompute the derived health state.
     * `everSawDaemon` is threaded through from the persistent settings
     * so the FIRST_RUN → RED transition mirrors budi-cursor.
     */
    fun update(health: DaemonHealth?, statusline: StatuslineData?, everSawDaemon: Boolean) {
        lastHealth = health
        lastStatusline = statusline
        lastState = deriveHealthState(health, statusline, everSawDaemon)
        listeners.forEach { it.invoke() }
    }

    /**
     * Subscribe to state changes. The runnable is invoked on every
     * `update` call; subscribers are responsible for marshalling work
     * onto the EDT if they touch UI.
     */
    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    companion object {
        fun getInstance(): BudiAppState =
            ApplicationManager.getApplication().getService(BudiAppState::class.java)
    }
}
