package com.github.siropkin.budijetbrains.notifier

import com.github.siropkin.budijetbrains.daemon.DaemonHealth
import com.github.siropkin.budijetbrains.daemon.MIN_API_VERSION
import com.github.siropkin.budijetbrains.install.currentInstallPlatform
import com.github.siropkin.budijetbrains.install.upgradeCommandForPlatform
import com.github.siropkin.budijetbrains.settings.BudiSettings
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import java.awt.datatransfer.StringSelection
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Outcome of an [evaluateUpgradePrompt] decision. Pure data so the
 * throttle / suppress / reset logic can be tested without spinning up
 * an IDE.
 */
internal data class UpgradeDecision(
    /** True iff the caller should render the upgrade balloon now. */
    val showPrompt: Boolean,
    /** True iff the caller should reset its in-session "already shown" latch. */
    val resetSessionShown: Boolean,
    /** True iff the caller should clear `suppressUpdateNotification`. */
    val resetPersistentSuppress: Boolean,
)

/**
 * Decide what the upgrade-notifier should do for the latest poll.
 * Pure function, no IDE deps. Side effects (writing settings, showing
 * balloons) live in [BudiUpgradeNotifier.onHealthObserved].
 *
 *  - Healthy daemon (current ≥ floor): clear the per-session latch so a
 *    within-session regression *can* re-show. Reset the persistent
 *    suppress only when transitioning out of a previously-stale state.
 *  - Stale daemon (current < floor): show the prompt iff neither the
 *    persistent suppress nor the per-session latch is set.
 *  - No reading at all (daemon unreachable): do nothing — the offline
 *    state already tells the user something is wrong.
 */
internal fun evaluateUpgradePrompt(
    currentApiVersion: Int?,
    previousApiVersion: Int,
    sessionShown: Boolean,
    persistentSuppress: Boolean,
    floor: Int = MIN_API_VERSION,
): UpgradeDecision {
    if (currentApiVersion == null) {
        return UpgradeDecision(showPrompt = false, resetSessionShown = false, resetPersistentSuppress = false)
    }
    val resetPersistentSuppress = currentApiVersion >= floor &&
        previousApiVersion < floor &&
        persistentSuppress
    if (currentApiVersion >= floor) {
        return UpgradeDecision(
            showPrompt = false,
            resetSessionShown = true,
            resetPersistentSuppress = resetPersistentSuppress,
        )
    }
    val show = !persistentSuppress && !sessionShown
    return UpgradeDecision(
        showPrompt = show,
        resetSessionShown = false,
        resetPersistentSuppress = false,
    )
}

/**
 * "Daemon api_version is older than this plugin requires" prompt
 * (parity with siropkin/budi-cursor#51).
 *
 * Two-layer throttle so the user is not pestered every poll tick:
 *
 *  1. **Per-session latch** — once the prompt has shown in this IDE
 *     session, it stays silent until the next IDE start. Resets on
 *     `IDE restart` rather than on a daemon recovery within the same
 *     session, because a single in-session show is more than enough.
 *  2. **Persistent suppress** — clicking "Don't show again" flips
 *     `suppressUpdateNotification` in `BudiSettings`. It is auto-reset
 *     when the daemon eventually catches up (`api_version` rises above
 *     the recorded `lastObservedApiVersion`), so a future stale-episode
 *     produces a fresh prompt.
 *
 * Never auto-runs `budi update`. The balloon's primary action opens a
 * Messages dialog with two strings the user can copy: the universal
 * `budi update` (preferred when the daemon is reachable) and the
 * platform-specific upgrade command (fallback when the daemon is too
 * broken to run a self-update).
 */
@Service(Service.Level.APP)
class BudiUpgradeNotifier {

    private val sessionShown = AtomicBoolean(false)

    /**
     * Apply the upgrade-prompt logic to the latest daemon health. Called
     * from `BudiPoller` after every successful `/health` round-trip on
     * the poller's pooled thread.
     *
     * The ordering of side effects matters and is deliberate:
     *  1. Snapshot inputs into a pure [UpgradeDecision] *before* writing
     *     anything — [evaluateUpgradePrompt] reads `previousApiVersion`,
     *     so the persisted snapshot must reflect the pre-tick state.
     *  2. Reset the persistent suppress first (if applicable). This is
     *     the upward-edge transition — we want a future stale episode
     *     to fire fresh, so clearing the latch now is correct.
     *  3. Update `lastObservedApiVersion` only when `health != null`;
     *     an unreachable daemon must not overwrite the cursor.
     *  4. Reset the in-session latch if the decision says so (daemon
     *     caught up within the same session); a regression later in the
     *     same session will then be allowed to re-show.
     *  5. Bail out unless the decision says "show". The
     *     `compareAndSet(false, true)` is the second safety on top of
     *     the in-session latch — guards against two near-simultaneous
     *     poll completions both deciding to show.
     */
    internal fun onHealthObserved(health: DaemonHealth?) {
        val settings = BudiSettings.getInstance()
        val previousApi = settings.state.lastObservedApiVersion
        val decision = evaluateUpgradePrompt(
            currentApiVersion = health?.apiVersion,
            previousApiVersion = previousApi,
            sessionShown = sessionShown.get(),
            persistentSuppress = settings.state.suppressUpdateNotification,
        )
        if (decision.resetPersistentSuppress) {
            settings.updateAndPersist { suppressUpdateNotification = false }
        }
        if (health != null) {
            settings.updateAndPersist { lastObservedApiVersion = health.apiVersion }
        }
        if (decision.resetSessionShown) {
            sessionShown.set(false)
        }
        if (!decision.showPrompt) return
        if (!sessionShown.compareAndSet(false, true)) return

        val project = pickProjectForBalloon() ?: return
        showUpgradePrompt(project, health!!)
    }

    private fun pickProjectForBalloon(): Project? =
        ProjectManager.getInstance().openProjects.firstOrNull { !it.isDisposed }

    private fun showUpgradePrompt(project: Project, health: DaemonHealth) {
        val notification: Notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(BUDI_NOTIFICATION_GROUP)
            .createNotification(
                "budi daemon needs an update",
                "Daemon api_version <b>${health.apiVersion}</b> is older than this plugin requires (<b>$MIN_API_VERSION</b>). " +
                    "Update budi to keep the status bar working.",
                NotificationType.WARNING,
            )

        notification.addAction(object : NotificationAction("Show update command") {
            override fun actionPerformed(e: AnActionEvent, n: Notification) {
                showUpgradeDialog(project)
            }
        })
        notification.addAction(object : NotificationAction("Don't show again") {
            override fun actionPerformed(e: AnActionEvent, n: Notification) {
                BudiSettings.getInstance().updateAndPersist { suppressUpdateNotification = true }
                n.expire()
            }
        })

        ApplicationManager.getApplication().invokeLater {
            notification.notify(project)
        }
    }

    private fun showUpgradeDialog(project: Project) {
        val platform = currentInstallPlatform()
        val platformCommand = upgradeCommandForPlatform(platform)
        val message = buildString {
            append("Run either of the following in a terminal:\n\n")
            append("Universal:\n  budi update\n\n")
            append("Platform fallback (${platform.name.lowercase().replaceFirstChar { it.uppercase() }}):\n  $platformCommand\n")
        }
        val choice = Messages.showDialog(
            project,
            message,
            "Update budi daemon",
            arrayOf("Copy `budi update`", "Copy platform command", "Close"),
            0,
            Messages.getInformationIcon(),
        )
        when (choice) {
            0 -> CopyPasteManager.getInstance().setContents(StringSelection("budi update"))
            1 -> CopyPasteManager.getInstance().setContents(StringSelection(platformCommand))
            else -> Unit
        }
    }

    companion object {
        fun getInstance(): BudiUpgradeNotifier =
            ApplicationManager.getApplication().getService(BudiUpgradeNotifier::class.java)
    }
}
