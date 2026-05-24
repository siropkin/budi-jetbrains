package com.github.siropkin.budijetbrains.statusbar

import com.github.siropkin.budijetbrains.daemon.HealthState
import com.github.siropkin.budijetbrains.daemon.buildStatusText
import com.github.siropkin.budijetbrains.daemon.buildTooltip
import com.github.siropkin.budijetbrains.daemon.clickUrl
import com.github.siropkin.budijetbrains.notifier.showFirstRunNotification
import com.github.siropkin.budijetbrains.poller.BudiPoller
import com.github.siropkin.budijetbrains.settings.BudiSettings
import com.github.siropkin.budijetbrains.state.BudiAppState
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidget.WidgetPresentation
import com.intellij.openapi.wm.StatusBarWidgetFactory
import java.awt.event.MouseEvent

internal const val BUDI_WIDGET_ID = "BudiStatusBarWidget"

/**
 * Status bar widget factory. Each open project gets its own widget
 * instance that reads from the application-scoped [BudiAppState] —
 * widgets are presentation only, the poller does the work.
 */
class BudiStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = BUDI_WIDGET_ID

    override fun getDisplayName(): String = "budi"

    override fun isAvailable(project: Project): Boolean = true

    override fun isConfigurable(): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget = BudiStatusBarWidget(project)

    override fun disposeWidget(widget: StatusBarWidget) {
        Disposer.dispose(widget)
    }

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}

internal class BudiStatusBarWidget(
    private val project: Project,
) : StatusBarWidget {
    private var statusBar: StatusBar? = null
    private val listener: () -> Unit = ::scheduleRepaint

    // Separate TextPresentation instance — keeps StatusBarWidget and
    // its nested TextPresentation interfaces from colliding in the
    // same class. The diamond inheritance would force the Kotlin
    // compiler to synthesize bridges for the deprecated
    // getPresentation(PlatformType) overload, which the JetBrains
    // Marketplace plugin verifier flags as deprecated API usage.
    private val presentation =
        object : StatusBarWidget.TextPresentation {
            override fun getText(): String =
                buildStatusText(
                    BudiAppState.getInstance().lastState,
                    BudiAppState.getInstance().lastStatusline,
                    BudiSettings.getInstance().state.statusBarMode,
                )

            override fun getAlignment(): Float = 0f

            override fun getTooltipText(): String =
                buildTooltip(
                    BudiAppState.getInstance().lastState,
                    BudiAppState.getInstance().lastStatusline,
                    BudiSettings.getInstance().resolvedCloudEndpoint(),
                )

            /**
             * Click handler. Mirrors budi-cursor's `budi.statusBarClick`:
             *
             * - FIRST_RUN: open the welcome notification (drops the user into
             *   the install flow — same surface they see at IDE start).
             * - everything else: open the cloud endpoint via the system browser.
             */
            override fun getClickConsumer(): com.intellij.util.Consumer<MouseEvent> =
                com.intellij.util.Consumer {
                    val state = BudiAppState.getInstance().lastState
                    if (state == HealthState.FIRST_RUN) {
                        showFirstRunNotification(project)
                        return@Consumer
                    }
                    val settings = BudiSettings.getInstance()
                    val url = clickUrl(settings.resolvedCloudEndpoint(), BudiAppState.getInstance().lastStatusline)
                    BrowserUtil.browse(url)
                    // A click is also a hint that the user wants fresh data;
                    // poke the poller so the widget updates ASAP after they
                    // come back from the browser.
                    BudiPoller.getInstance().refreshNow()
                }
        }

    override fun ID(): String = BUDI_WIDGET_ID

    override fun getPresentation(): WidgetPresentation = presentation

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        BudiAppState.getInstance().addListener(listener)
    }

    override fun dispose() {
        BudiAppState.getInstance().removeListener(listener)
        statusBar = null
    }

    private fun scheduleRepaint() {
        ApplicationManager.getApplication().invokeLater {
            statusBar?.updateWidget(BUDI_WIDGET_ID)
        }
    }
}
