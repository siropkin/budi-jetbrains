package com.github.siropkin.budijetbrains.startup

import com.github.siropkin.budijetbrains.daemon.HealthState
import com.github.siropkin.budijetbrains.notifier.showFirstRunNotification
import com.github.siropkin.budijetbrains.poller.BudiPoller
import com.github.siropkin.budijetbrains.settings.BudiSettings
import com.github.siropkin.budijetbrains.state.BudiAppState
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import kotlinx.coroutines.delay

/**
 * Boot the poller on first project open and surface the first-run
 * welcome notification when the daemon is missing.
 *
 * Mirrors `extension.ts:activate` from budi-cursor, with the
 * differences a JetBrains IDE imposes:
 *
 *   - The poller is application-scoped (one for the IDE lifetime), so
 *     `start()` is a no-op after the first project.
 *   - The welcome surface is a sticky balloon, not a webview — see
 *     `BudiFirstRunNotifier.kt` for the rationale.
 *   - We give the poller a single tick to pull state before deciding
 *     whether to show the welcome balloon; otherwise the balloon would
 *     fire on every IDE start regardless of whether the daemon comes
 *     up immediately.
 */
class BudiProjectActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        if (ApplicationManager.getApplication().isUnitTestMode) return

        BudiPoller.getInstance().start()

        // Wait for the initial poll to land (the alarm fires immediately
        // on start with `initial = true`). 1.5 s is generous — the
        // request itself caps at 3 s but a healthy local daemon answers
        // in milliseconds, so we'd rather race the welcome decision
        // than wait the full timeout on the first project open.
        delay(1_500)

        // The project may have been closed during the 1.5 s wait — skip
        // the welcome balloon in that case so we do not push a
        // notification at a disposed Project.
        if (project.isDisposed) return

        val state = BudiAppState.getInstance().lastState
        val everSawDaemon = BudiSettings.getInstance().state.everSawDaemon
        if (state == HealthState.FIRST_RUN && !everSawDaemon) {
            showFirstRunNotification(project)
        }
    }
}
