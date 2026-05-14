package com.github.siropkin.budijetbrains.settings

import com.github.siropkin.budijetbrains.daemon.DEFAULT_CLOUD_ENDPOINT
import com.github.siropkin.budijetbrains.daemon.DEFAULT_DAEMON_URL
import com.github.siropkin.budijetbrains.daemon.isAllowedCloudEndpoint
import com.github.siropkin.budijetbrains.daemon.isLoopbackDaemonUrl
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Default polling interval for the status bar (mirrors budi-cursor's
 * `package.json` default). 15 s balances "feels live" against daemon
 * load — analytics is a slow-moving signal.
 */
internal const val DEFAULT_POLLING_INTERVAL_MS = 15_000

/** Lower bound enforced by the Configurable UI to avoid hammering the daemon. */
internal const val MIN_POLLING_INTERVAL_MS = 3_000

/**
 * Persistent settings for the budi plugin. Application-scoped because
 * the daemon URL and cloud endpoint are user-machine concerns, not
 * project-specific.
 *
 * Mutating fields directly bypasses validation; use [updateAndPersist]
 * or the [BudiConfigurable] UI for changes that should round-trip
 * through the URL allowlists.
 */
class BudiSettingsState {
    /**
     * Daemon URL. Default: [DEFAULT_DAEMON_URL]. Valid range: any
     * `http(s)://` URL whose host is `127.0.0.1`, `localhost`, or `[::1]`
     * — anything else is rejected by the Configurable and silently
     * fenced by [BudiSettings.resolvedDaemonUrl] at read time. No
     * poller restart needed — [BudiConfigurable.apply] calls
     * `refreshNow()` and every subsequent tick re-reads the resolved
     * value.
     */
    var daemonUrl: String = DEFAULT_DAEMON_URL

    /**
     * Cloud dashboard endpoint for the status-bar click-through.
     * Default: [DEFAULT_CLOUD_ENDPOINT]. Valid range: `https://` on
     * `getbudi.dev` (or a subdomain), no userinfo. Same fencing as
     * [daemonUrl]. Picked up the next time the user clicks the
     * widget — no poller restart involved.
     */
    var cloudEndpoint: String = DEFAULT_CLOUD_ENDPOINT

    /**
     * Polling interval in milliseconds. Default:
     * [DEFAULT_POLLING_INTERVAL_MS]. Valid range:
     * [[MIN_POLLING_INTERVAL_MS], 600_000] (3 s … 10 min); enforced by
     * the Configurable and re-coerced to ≥ 1 s by the poller as
     * belt-and-suspenders. No poller restart needed — every alarm tick
     * re-reads this field, so a change takes effect on the next
     * scheduled refresh.
     */
    var pollingIntervalMs: Int = DEFAULT_POLLING_INTERVAL_MS

    /**
     * When true, the plugin omits the `?surface=jetbrains` filter so the
     * status bar shows aggregate spend across every editor host on the
     * machine. Default: `false` — the per-host scope is what makes the
     * cloud dashboard's surface breakdown useful. No poller restart
     * needed; takes effect on the next poll tick.
     */
    var includeOtherSurfaces: Boolean = false

    /**
     * One-time latch flipped to `true` the first time the plugin sees a
     * healthy daemon. Drives the FIRST_RUN → RED transition in
     * `deriveHealthState`: before the latch flips, an unreachable daemon
     * shows the welcome notification; after, it shows "offline".
     * Mirrors budi-cursor's `EVER_SAW_DAEMON_KEY` globalState entry.
     */
    var everSawDaemon: Boolean = false

    /**
     * When true, suppress the "Daemon api_version is older than this
     * plugin requires" notification (#7). Set by clicking "Don't show
     * again" on the upgrade balloon. Persists across IDE restarts so a
     * user who has chosen to ignore the prompt isn't pestered every
     * session.
     *
     * The notification will reappear automatically once the daemon's
     * api_version catches up with `MIN_API_VERSION` and then drifts
     * stale again — i.e. this latch only suppresses the *current*
     * stale-version episode. (See `BudiUpgradeNotifier` for the reset
     * mechanism.)
     */
    var suppressUpdateNotification: Boolean = false

    /**
     * The api_version this plugin most recently saw the daemon report.
     * Used as the cursor for the suppress-reset described above: if the
     * daemon catches up (current > snapshotted), `suppressUpdateNotification`
     * resets to `false`. Always written together with the snapshot — the
     * suppress is reset *only* on a transition out of stale state, never
     * during one.
     */
    var lastObservedApiVersion: Int = 0
}

@Service(Service.Level.APP)
@State(
    name = "BudiSettings",
    storages = [Storage("budi.xml")],
)
class BudiSettings : PersistentStateComponent<BudiSettingsState> {

    private var state = BudiSettingsState()

    override fun getState(): BudiSettingsState = state

    override fun loadState(state: BudiSettingsState) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    /**
     * Resolve the daemon URL, falling back to the loopback default if
     * the persisted value is somehow off-loopback (parity with
     * budi-cursor#42's defense). Workspace-level overrides do not exist
     * in the JetBrains plugin today — settings are application-scoped —
     * so this is belt-and-suspenders for migrated values from a future
     * project-scoped layer.
     */
    fun resolvedDaemonUrl(): String {
        val raw = state.daemonUrl
        return if (isLoopbackDaemonUrl(raw)) raw else DEFAULT_DAEMON_URL
    }

    /** Resolve the cloud endpoint with the same allowlist as #43. */
    fun resolvedCloudEndpoint(): String {
        val raw = state.cloudEndpoint
        return if (isAllowedCloudEndpoint(raw)) raw else DEFAULT_CLOUD_ENDPOINT
    }

    /** Persist a state mutation. Use this from the Configurable / poller. */
    fun updateAndPersist(block: BudiSettingsState.() -> Unit) {
        block(state)
    }

    companion object {
        fun getInstance(): BudiSettings =
            ApplicationManager.getApplication().getService(BudiSettings::class.java)
    }
}
