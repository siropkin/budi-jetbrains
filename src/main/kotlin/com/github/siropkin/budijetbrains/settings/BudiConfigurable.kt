package com.github.siropkin.budijetbrains.settings

import com.github.siropkin.budijetbrains.daemon.DEFAULT_CLOUD_ENDPOINT
import com.github.siropkin.budijetbrains.daemon.DEFAULT_DAEMON_URL
import com.github.siropkin.budijetbrains.daemon.isAllowedCloudEndpoint
import com.github.siropkin.budijetbrains.daemon.isLoopbackDaemonUrl
import com.github.siropkin.budijetbrains.poller.BudiPoller
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBCheckBox
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

    override fun getDisplayName(): String = "budi"

    override fun createComponent(): JComponent {
        panel = panel {
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
        }
        return panel
    }

    override fun isModified(): Boolean {
        panel.apply() // copy UI → fields
        return daemonUrlField != settings.state.daemonUrl ||
            cloudEndpointField != settings.state.cloudEndpoint ||
            pollingIntervalField != settings.state.pollingIntervalMs ||
            includeOtherSurfacesField != settings.state.includeOtherSurfaces
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
        }
        BudiPoller.getInstance().refreshNow()
    }

    override fun reset() {
        daemonUrlField = settings.state.daemonUrl
        cloudEndpointField = settings.state.cloudEndpoint
        pollingIntervalField = settings.state.pollingIntervalMs
        includeOtherSurfacesField = settings.state.includeOtherSurfaces
        panel.reset()
    }
}

