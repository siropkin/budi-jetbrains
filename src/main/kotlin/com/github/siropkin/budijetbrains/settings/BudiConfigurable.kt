package com.github.siropkin.budijetbrains.settings

import com.github.siropkin.budijetbrains.daemon.BudiClient
import com.github.siropkin.budijetbrains.daemon.DEFAULT_CLOUD_ENDPOINT
import com.github.siropkin.budijetbrains.daemon.DEFAULT_DAEMON_URL
import com.github.siropkin.budijetbrains.daemon.isAllowedCloudEndpoint
import com.github.siropkin.budijetbrains.daemon.isLoopbackDaemonUrl
import com.github.siropkin.budijetbrains.daemon.renderDetectedSourcesHtml
import com.github.siropkin.budijetbrains.poller.BudiPoller
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/**
 * Settings page rendered under Settings → Tools → budi. Mutates the
 * [BudiSettings] application-scope service.
 *
 * Shape mirrors the budi-cursor extension's three knobs:
 *
 *   - daemonUrl (loopback-only, validated)
 *   - cloudEndpoint (https on getbudi.dev, validated)
 *   - pollingIntervalMs (lower-bounded at 3 s)
 *
 * Plus the JetBrains-specific `includeOtherSurfaces` toggle so power
 * users can switch the status bar to "everything on this machine"
 * mode (drops the `?surface=jetbrains` filter).
 */
class BudiConfigurable : Configurable {

    private val settings = BudiSettings.getInstance()
    private lateinit var panel: DialogPanel

    private var daemonUrlField = settings.state.daemonUrl
    private var cloudEndpointField = settings.state.cloudEndpoint
    private var pollingIntervalField = settings.state.pollingIntervalMs
    private var includeOtherSurfacesField = settings.state.includeOtherSurfaces
    private var suppressUpdateNotificationField = settings.state.suppressUpdateNotification

    private val detectedSourcesLabel = JBLabel(LOADING_SOURCES_HTML)

    override fun getDisplayName(): String = "budi"

    override fun createComponent(): JComponent {
        panel = panel {
            row {
                comment(
                    "Status bar renders the daemon's <code>surface=jetbrains</code> rollup. " +
                        "v0.1 tracks GitHub Copilot for JetBrains; " +
                        "JetBrains AI Assistant (Anthropic-backed, separate JetBrains subscription) " +
                        "is planned for v0.2 — see <a href=\"https://github.com/siropkin/budi-jetbrains/issues/32\">#32</a>.",
                )
            }
            row("Daemon URL:") {
                textField()
                    .bindText({ daemonUrlField }, { daemonUrlField = it })
                    .columns(40)
                    .comment(
                        "Loopback only — 127.0.0.1, localhost, or [::1]. " +
                            "Off-loopback values are rejected at runtime and fall back to $DEFAULT_DAEMON_URL.",
                    )
            }
            row("Cloud endpoint:") {
                textField()
                    .bindText({ cloudEndpointField }, { cloudEndpointField = it })
                    .columns(40)
                    .comment(
                        "https on getbudi.dev (or a subdomain). Off-domain values fall back to $DEFAULT_CLOUD_ENDPOINT.",
                    )
            }
            row("Polling interval (ms):") {
                intTextField(IntRange(MIN_POLLING_INTERVAL_MS, 600_000))
                    .bindIntText({ pollingIntervalField }, { pollingIntervalField = it })
                    .columns(8)
                    .comment("How often to refresh the status bar. Minimum ${MIN_POLLING_INTERVAL_MS / 1000}s.")
            }
            row {
                cell(JBCheckBox("Include other surfaces (drop the ?surface=jetbrains filter)"))
                    .bindSelected({ includeOtherSurfacesField }, { includeOtherSurfacesField = it })
                    .comment(
                        "When checked, the status bar shows your aggregate spend across every editor host. " +
                            "Off by default — the per-host scope is what makes the cloud dashboard's surface breakdown useful.",
                    )
            }
            row {
                cell(JBCheckBox("Suppress \"daemon needs an update\" notification"))
                    .bindSelected({ suppressUpdateNotificationField }, { suppressUpdateNotificationField = it })
                    .comment(
                        "When checked, the upgrade prompt is silenced for the current stale-version episode. " +
                            "Auto-resets the next time the daemon's api_version catches up and then drifts stale again.",
                    )
            }
            row("Detected sources:") {
                cell(detectedSourcesLabel)
                    .comment(
                        "Filesystem paths the daemon is tailing for <code>surface=jetbrains</code>. " +
                            "Read-only — discovery lives in budi-core. " +
                            "Refreshed each time this settings page opens.",
                    )
            }
        }
        refreshDetectedSources()
        return panel
    }

    /**
     * Kick off an off-EDT fetch of `/health/sources` and update the
     * label on the EDT when it returns. Failures (offline daemon,
     * endpoint missing on older daemons, malformed payload) all fold
     * into the quiet "no sources detected" empty state — see
     * `renderDetectedSourcesHtml` in BudiClient.kt.
     */
    private fun refreshDetectedSources() {
        detectedSourcesLabel.text = LOADING_SOURCES_HTML
        val daemonUrl = settings.resolvedDaemonUrl()
        val includeOtherSurfaces = settings.state.includeOtherSurfaces
        ApplicationManager.getApplication().executeOnPooledThread {
            val sources = BudiClient().fetchSources(daemonUrl, includeOtherSurfaces)
            val html = renderDetectedSourcesHtml(sources)
            ApplicationManager.getApplication().invokeLater {
                detectedSourcesLabel.text = html
            }
        }
    }

    override fun isModified(): Boolean {
        panel.apply() // copy UI → fields
        return daemonUrlField != settings.state.daemonUrl ||
            cloudEndpointField != settings.state.cloudEndpoint ||
            pollingIntervalField != settings.state.pollingIntervalMs ||
            includeOtherSurfacesField != settings.state.includeOtherSurfaces ||
            suppressUpdateNotificationField != settings.state.suppressUpdateNotification
    }

    override fun apply() {
        panel.apply()
        if (!isLoopbackDaemonUrl(daemonUrlField)) {
            throw ConfigurationException(
                "Daemon URL must be a loopback address (http(s)://127.0.0.1, localhost, or [::1]).",
            )
        }
        if (!isAllowedCloudEndpoint(cloudEndpointField)) {
            throw ConfigurationException(
                "Cloud endpoint must be an https URL on getbudi.dev (or a subdomain).",
            )
        }
        if (pollingIntervalField < MIN_POLLING_INTERVAL_MS) {
            throw ConfigurationException(
                "Polling interval must be at least ${MIN_POLLING_INTERVAL_MS / 1000}s.",
            )
        }
        settings.updateAndPersist {
            daemonUrl = daemonUrlField
            cloudEndpoint = cloudEndpointField
            pollingIntervalMs = pollingIntervalField
            includeOtherSurfaces = includeOtherSurfacesField
            suppressUpdateNotification = suppressUpdateNotificationField
        }
        BudiPoller.getInstance().refreshNow()
    }

    override fun reset() {
        daemonUrlField = settings.state.daemonUrl
        cloudEndpointField = settings.state.cloudEndpoint
        pollingIntervalField = settings.state.pollingIntervalMs
        includeOtherSurfacesField = settings.state.includeOtherSurfaces
        suppressUpdateNotificationField = settings.state.suppressUpdateNotification
        panel.reset()
    }

    private companion object {
        const val LOADING_SOURCES_HTML = "<html><i>Loading detected sources…</i></html>"
    }
}

