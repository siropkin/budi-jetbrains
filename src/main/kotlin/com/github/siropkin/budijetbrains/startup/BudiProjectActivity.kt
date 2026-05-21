package com.github.siropkin.budijetbrains.startup

import com.github.siropkin.budijetbrains.daemon.BudiDaemonDetector
import com.github.siropkin.budijetbrains.notifier.showFirstRunNotification
import com.github.siropkin.budijetbrains.poller.BudiPoller
import com.github.siropkin.budijetbrains.settings.BudiSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Boot the poller on first project open and surface the one-time
 * install notification when the daemon binary is missing (#69).
 *
 * Mirrors `extension.ts:activate` from budi-cursor, with the
 * differences a JetBrains IDE imposes:
 *
 *   - The poller is application-scoped (one for the IDE lifetime), so
 *     `start()` is a no-op after the first project.
 *   - The welcome surface is a sticky balloon, not a webview — see
 *     `BudiFirstRunNotifier.kt` for the rationale.
 *   - We give the poller a single tick to pull state before checking
 *     for the binary, so a just-installed daemon has a chance to
 *     respond before we declare it missing.
 */
class BudiProjectActivity : ProjectActivity {
    /**
     * Boot path: start the (idempotent) application-scoped poller, wait
     * one initial tick, then decide whether to surface the install
     * notification.
     *
     * Install notification fires iff **all** of:
     *  - we are not in unit-test mode (would clutter integration runs);
     *  - the project is still alive after the 1.5 s wait;
     *  - the budi binary is not found on `$PATH` (binary installed but
     *    daemon not running is a separate scenario — #69);
     *  - the user has not previously dismissed the notification
     *    (persistent `dismissedInstallNotification` flag).
     */
    override suspend fun execute(project: Project) {
        if (ApplicationManager.getApplication().isUnitTestMode) return

        BudiPoller.getInstance().start()

        delay(1_500)

        if (project.isDisposed) return

        val settings = BudiSettings.getInstance()
        if (settings.state.everSawDaemon) return
        if (settings.state.dismissedInstallNotification) return

        val binaryFound = withContext(Dispatchers.IO) { BudiDaemonDetector.isBinaryInstalled() }
        if (!binaryFound) {
            showFirstRunNotification(project)
        }
    }
}
