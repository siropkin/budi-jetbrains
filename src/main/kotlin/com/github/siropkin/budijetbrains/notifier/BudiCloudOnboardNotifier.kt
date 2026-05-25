package com.github.siropkin.budijetbrains.notifier

import com.github.siropkin.budijetbrains.daemon.DaemonHealth
import com.github.siropkin.budijetbrains.daemon.MIN_API_VERSION
import com.github.siropkin.budijetbrains.poller.BudiPoller
import com.github.siropkin.budijetbrains.settings.BudiSettings
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Outcome of an [evaluateCloudOnboardPrompt] decision. Pure data so the
 * logic can be tested without spinning up an IDE.
 */
internal data class CloudOnboardDecision(
    val showPrompt: Boolean,
)

/**
 * Decide whether to show the cloud-onboarding notification.
 *
 * Shows iff **all** of:
 *  - daemon is reachable (`health != null`)
 *  - daemon api_version is compatible (≥ floor)
 *  - daemon explicitly reports `cloud_linked = false`
 *  - user has not dismissed the notification
 *  - prompt has not already been shown this session
 *
 * When `cloud_linked` is `null` (older daemon that doesn't report the
 * field), the prompt is suppressed — we only surface it when the daemon
 * affirmatively says "not linked."
 */
internal fun evaluateCloudOnboardPrompt(
    health: DaemonHealth?,
    sessionShown: Boolean,
    persistentDismiss: Boolean,
    floor: Int = MIN_API_VERSION,
): CloudOnboardDecision {
    if (health == null) return CloudOnboardDecision(showPrompt = false)
    if (health.apiVersion < floor) return CloudOnboardDecision(showPrompt = false)
    if (health.cloudLinked != false) return CloudOnboardDecision(showPrompt = false)
    if (persistentDismiss) return CloudOnboardDecision(showPrompt = false)
    if (sessionShown) return CloudOnboardDecision(showPrompt = false)
    return CloudOnboardDecision(showPrompt = true)
}

/**
 * "Connect to budi cloud" prompt shown when the daemon is healthy but
 * not yet linked to cloud (#92).
 *
 * Two-layer throttle (same pattern as [BudiUpgradeNotifier]):
 *  1. **Per-session latch** — once shown, stays silent until the next
 *     IDE start.
 *  2. **Persistent dismiss** — clicking "Dismiss" sets
 *     `dismissedCloudOnboardNotification` in settings.
 *
 * The primary action shells out to `budi cloud onboard --timeout 120`
 * which opens the browser, handles the OAuth callback, writes config,
 * and restarts the daemon. The extension waits for the exit code.
 */
@Service(Service.Level.APP)
class BudiCloudOnboardNotifier {
    private val log = Logger.getInstance(BudiCloudOnboardNotifier::class.java)
    private val sessionShown = AtomicBoolean(false)

    internal fun onHealthObserved(health: DaemonHealth?) {
        if (health?.cloudLinked == true && sessionShown.get()) {
            sessionShown.set(false)
        }

        val settings = BudiSettings.getInstance()
        val decision =
            evaluateCloudOnboardPrompt(
                health = health,
                sessionShown = sessionShown.get(),
                persistentDismiss = settings.state.dismissedCloudOnboardNotification,
            )
        if (!decision.showPrompt) return
        if (!sessionShown.compareAndSet(false, true)) return

        val project = pickProjectForBalloon() ?: return
        showCloudOnboardPrompt(project)
    }

    private fun pickProjectForBalloon(): Project? = ProjectManager.getInstance().openProjects.firstOrNull { !it.isDisposed }

    private fun showCloudOnboardPrompt(project: Project) {
        val notification: Notification =
            NotificationGroupManager
                .getInstance()
                .getNotificationGroup(BUDI_NOTIFICATION_GROUP)
                .createNotification(
                    "Connect to budi cloud",
                    "Link this machine to your budi cloud account for dashboards, alerts, and team visibility.",
                    NotificationType.INFORMATION,
                )

        notification.addAction(
            object : NotificationAction("Connect") {
                override fun actionPerformed(
                    e: AnActionEvent,
                    n: Notification,
                ) {
                    n.expire()
                    runCloudOnboard(project)
                }
            },
        )

        notification.addAction(
            object : NotificationAction("Dismiss") {
                override fun actionPerformed(
                    e: AnActionEvent,
                    n: Notification,
                ) {
                    BudiSettings.getInstance().updateAndPersist { dismissedCloudOnboardNotification = true }
                    n.expire()
                }
            },
        )

        ApplicationManager.getApplication().invokeLater {
            notification.notify(project)
        }
    }

    private fun runCloudOnboard(project: Project) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val process =
                    ProcessBuilder(listOf("budi", "cloud", "onboard", "--timeout", "120"))
                        .redirectErrorStream(true)
                        .start()
                val finished = process.waitFor(130, TimeUnit.SECONDS)
                val success = finished && process.exitValue() == 0

                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    if (success) {
                        BudiPoller.getInstance().refreshNow()
                        NotificationGroupManager
                            .getInstance()
                            .getNotificationGroup(BUDI_NOTIFICATION_GROUP)
                            .createNotification(
                                "Connected to budi cloud",
                                "This machine is now linked. <a href=\"dashboard\">Open dashboard</a>",
                                NotificationType.INFORMATION,
                            ).setListener { _, _ ->
                                val settings = BudiSettings.getInstance()
                                com.intellij.ide.BrowserUtil.browse(
                                    "${settings.resolvedCloudEndpoint().trimEnd('/')}/dashboard",
                                )
                            }.notify(project)
                    } else {
                        NotificationGroupManager
                            .getInstance()
                            .getNotificationGroup(BUDI_NOTIFICATION_GROUP)
                            .createNotification(
                                "Cloud onboarding did not complete",
                                "Run <code>budi cloud onboard</code> in a terminal to try again.",
                                NotificationType.WARNING,
                            ).notify(project)
                        sessionShown.set(false)
                    }
                }
            } catch (ex: Exception) {
                log.warn("budi cloud onboard failed", ex)
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    NotificationGroupManager
                        .getInstance()
                        .getNotificationGroup(BUDI_NOTIFICATION_GROUP)
                        .createNotification(
                            "Cloud onboarding failed",
                            "Could not run <code>budi cloud onboard</code>. Is budi installed?",
                            NotificationType.WARNING,
                        ).notify(project)
                    sessionShown.set(false)
                }
            }
        }
    }

    companion object {
        fun getInstance(): BudiCloudOnboardNotifier = ApplicationManager.getApplication().getService(BudiCloudOnboardNotifier::class.java)
    }
}
