package com.github.siropkin.budijetbrains.notifier

import com.github.siropkin.budijetbrains.install.currentInstallPlatform
import com.github.siropkin.budijetbrains.install.installCommandForPlatform
import com.github.siropkin.budijetbrains.settings.BudiSettings
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import java.awt.datatransfer.StringSelection

/** Notification group id — matches the registration in plugin.xml. */
internal const val BUDI_NOTIFICATION_GROUP = "Budi"

/**
 * One-time install notification shown when the budi daemon binary is
 * not found on `$PATH` (#69). The IDE-native equivalent of a webview
 * is a sticky balloon notification with two actions:
 *
 * - **Show install command** — opens an information dialog with the
 *   platform-specific install one-liner; "Copy" puts it on the
 *   clipboard. The command is never executed automatically — the user
 *   must paste and run it themselves so they see what they are running
 *   first.
 * - **Dismiss** — expires the balloon and persists the
 *   `dismissedInstallNotification` flag so it never reappears, even
 *   on subsequent IDE launches.
 *
 * Notification copy is a security-sensitive surface — the install
 * command embedded in the dialog must not drift from
 * `BudiInstallCommands.kt`. Tests assert the dialog text contains the
 * exact command verbatim.
 */
internal fun showFirstRunNotification(project: Project) {
    val cmd = installCommandForPlatform(currentInstallPlatform())
    val notification: Notification =
        NotificationGroupManager
            .getInstance()
            .getNotificationGroup(BUDI_NOTIFICATION_GROUP)
            .createNotification(
                "budi is not installed on this machine yet",
                "Click <b>Show install command</b> to see the one-liner you need to run in a terminal.",
                NotificationType.INFORMATION,
            )

    notification.addAction(
        object : NotificationAction("Show install command") {
            override fun actionPerformed(
                e: AnActionEvent,
                n: Notification,
            ) {
                val title = "Install budi for ${cmd.label}"
                val message = "Run this in your $${cmd.shell} shell:\n\n${cmd.command}"
                val choice =
                    Messages.showDialog(
                        project,
                        message,
                        title,
                        arrayOf("Copy command", "Close"),
                        0,
                        Messages.getInformationIcon(),
                    )
                if (choice == 0) {
                    CopyPasteManager.getInstance().setContents(StringSelection(cmd.command))
                }
            }
        },
    )

    notification.addAction(
        object : NotificationAction("Dismiss") {
            override fun actionPerformed(
                e: AnActionEvent,
                n: Notification,
            ) {
                BudiSettings.getInstance().updateAndPersist { dismissedInstallNotification = true }
                n.expire()
            }
        },
    )

    notification.notify(project)
}
