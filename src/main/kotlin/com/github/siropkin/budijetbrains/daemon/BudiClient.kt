/**
 * Daemon wire contract + status-bar rendering helpers.
 *
 * Everything in this file is a pure function or a thin HTTP client over
 * the loopback budi daemon (`http://127.0.0.1:7878` by default). Two
 * endpoints are consumed:
 *
 *   - `GET /health` → [DaemonHealth]; gates the FIRST_RUN/RED/YELLOW/GREEN
 *     state machine via `api_version` floor [MIN_API_VERSION].
 *   - `GET /analytics/statusline?surface=jetbrains[&project_dir=…]` →
 *     [StatuslineData]; drives the rolling 1d/7d/30d cost line.
 *   - `GET /health/sources?surface=jetbrains` → [DetectedSources]; only
 *     consumed by the settings panel.
 *
 * The status-bar text, tooltip, and click-through URL are **byte-for-byte
 * parity** with the Claude Code statusline (and the budi-cursor extension)
 * — `buildStatusText`, `buildTooltip`, `clickUrl`, `formatCost`,
 * `formatCostLine`, and `formatProviderName` here mirror the same-named
 * functions in budi-cursor 1:1. Edits to one side must be mirrored on the
 * other; see siropkin/budi-cursor#232 / #314 for the design.
 *
 * Defense-in-depth limits on the HTTP client mirror budi-cursor#42–#44:
 * loopback-only daemon URL, https-on-getbudi.dev cloud endpoint, 3 s
 * timeout, 64 KB body cap, 2xx + `application/json` only, `null` on any
 * failure. Callers fold a `null` into the RED health state — there is no
 * thrown-exception path out of [BudiClient].
 */
package com.github.siropkin.budijetbrains.daemon

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * The minimum daemon `/health.api_version` this plugin requires.
 *
 * Pinned at `1` to match the same wire shape budi-cursor v1.4 ships with
 * (see siropkin/budi-cursor#40 for the why — every released daemon today
 * still reports `api_version: 1` even after the host-scoped #650/#702
 * landings, so anything higher gates out 100% of users).
 *
 * Bump only when budi-core actually bumps `API_VERSION` for a breaking
 * wire change, and update both sides in the same release.
 */
internal const val MIN_API_VERSION = 1

/**
 * Surface name this plugin sends as `?surface=<name>` on every request.
 * Hardcoded — JetBrains plugins know they are JetBrains, no host enum
 * (siropkin/budi#702 + budi-jetbrains#6).
 */
internal const val SURFACE_JETBRAINS = "jetbrains"

/**
 * Default daemon URL — must stay in sync with the Configurable's default
 * and with budi's SOUL.md "loopback only" pin.
 */
internal const val DEFAULT_DAEMON_URL = "http://127.0.0.1:7878"

/**
 * Default cloud endpoint — must stay in sync with the Configurable's
 * default and with budi's SOUL.md "cloud lives at app.getbudi.dev" pin.
 */
internal const val DEFAULT_CLOUD_ENDPOINT = "https://app.getbudi.dev"

/** Loopback hosts the daemon is allowed to bind on (mirrors budi-cursor#42). */
private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "[::1]")

/** Apex domain the cloud dashboard is served from (mirrors budi-cursor#43). */
private const val CLOUD_HOST_ROOT = "getbudi.dev"

/**
 * Hard ceiling on bytes accepted from the daemon per request (mirrors
 * budi-cursor#44). Legitimate `/health` and `/analytics/statusline`
 * payloads are well under 1 KB; 64 KB leaves room for forward-compat
 * fields without letting a hostile or buggy server flood the heap inside
 * the 3 s request window.
 */
private const val MAX_RESPONSE_BYTES = 64L * 1024L

/** Daemon request timeout. */
private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(3)

/** `/health` response shape. */
internal data class DaemonHealth(
    val ok: Boolean = false,
    val version: String = "",
    @SerializedName("api_version") val apiVersion: Int = 0,
)

/**
 * `/analytics/statusline` response shape. Mirrors budi-cursor's
 * `StatuslineData` 1:1 — including the deprecated 8.0 aliases so this
 * plugin still renders something useful against a pre-#224 daemon.
 */
internal data class StatuslineData(
    @SerializedName("cost_1d") val cost1d: Double? = null,
    @SerializedName("cost_7d") val cost7d: Double? = null,
    @SerializedName("cost_30d") val cost30d: Double? = null,
    @SerializedName("active_provider") val activeProvider: String? = null,
    @SerializedName("provider_scope") val providerScope: String? = null,
    @SerializedName("contributing_providers") val contributingProviders: List<String>? = null,
    @SerializedName("today_cost") val todayCost: Double? = null,
    @SerializedName("week_cost") val weekCost: Double? = null,
    @SerializedName("month_cost") val monthCost: Double? = null,
)

/** Resolved rolling-cost triple consumed by status-bar / tooltip rendering. */
internal data class ResolvedCosts(
    val cost1d: Double,
    val cost7d: Double,
    val cost30d: Double,
)

/**
 * `/health/sources` response shape. The daemon returns the on-disk paths
 * it is tailing for the requested surface so the settings panel can show
 * users which transcript-storage locations were discovered. Surface
 * filtering happens server-side (parity with the `?surface=jetbrains`
 * scoping of `/analytics/statusline`); the plugin renders what comes
 * back as-is.
 *
 * Endpoint tracked daemon-side; until it lands, this fetch returns null
 * and the settings panel renders a quiet "no sources detected" empty
 * state (see budi-jetbrains#33).
 */
internal data class DetectedSources(
    val surface: String? = null,
    val paths: List<String> = emptyList(),
)

/**
 * Status-bar health state. Drives the status-bar copy and the welcome
 * notification lifecycle; no glyph rides on top of it. Mirrors
 * budi-cursor's `HealthState` 1:1 — see #232 / #314 for the design.
 *
 * - GRAY      — plugin still starting up (no reading yet).
 * - FIRST_RUN — daemon unreachable AND this install has never seen a
 *               healthy daemon. Routes the user to the welcome
 *               notification instead of an "offline" error.
 * - RED       — daemon unreachable or reports an incompatible
 *               api_version, AND the install has seen a healthy daemon
 *               at some point.
 * - YELLOW    — daemon healthy but no JetBrains-surface usage in the
 *               rolling window.
 * - GREEN     — daemon healthy and traffic recorded.
 */
internal enum class HealthState { GRAY, FIRST_RUN, RED, YELLOW, GREEN }

/**
 * Resolve the rolling cost fields, preferring the canonical
 * `cost_1d` / `cost_7d` / `cost_30d` shape and falling back to the
 * deprecated 8.0 aliases when talking to an older daemon. Mirrors
 * `resolveCosts` in budi-cursor.
 */
internal fun resolveCosts(data: StatuslineData): ResolvedCosts {
    fun pick(
        primary: Double?,
        legacy: Double?,
    ): Double {
        if (primary != null && primary.isFinite()) return primary
        if (legacy != null && legacy.isFinite()) return legacy
        return 0.0
    }
    return ResolvedCosts(
        cost1d = pick(data.cost1d, data.todayCost),
        cost7d = pick(data.cost7d, data.weekCost),
        cost30d = pick(data.cost30d, data.monthCost),
    )
}

/**
 * Format a single cost value the same way budi-cli and budi-cursor do.
 * Bands:
 *   < 0 / NaN / Infinity → "$0.00"
 *   ≥ 1000               → "$1.5K"
 *   ≥ 100                → "$123"
 *   > 0                  → "$1.23"
 *   = 0                  → "$0.00"
 */
internal fun formatCost(dollars: Double): String {
    if (!dollars.isFinite() || dollars < 0.0) return "$0.00"
    if (dollars >= 1000.0) return "$%.1fK".format(dollars / 1000.0)
    if (dollars >= 100.0) return "$%d".format(Math.round(dollars))
    if (dollars > 0.0) return "$%.2f".format(dollars)
    return "$0.00"
}

/**
 * Render the numeric portion of the statusline, byte-for-byte matching
 * the default Claude Code cost line (`$X 1d · $Y 7d · $Z 30d`).
 */
internal fun formatCostLine(costs: ResolvedCosts): String =
    listOf(
        "${formatCost(costs.cost1d)} 1d",
        "${formatCost(costs.cost7d)} 7d",
        "${formatCost(costs.cost30d)} 30d",
    ).joinToString(" · ")

/**
 * Decide which health state the status bar is in. Mirrors
 * `deriveHealthState` in budi-cursor 1:1.
 */
internal fun deriveHealthState(
    health: DaemonHealth?,
    statusline: StatuslineData?,
    everSawDaemon: Boolean = true,
): HealthState {
    if (health == null) return if (everSawDaemon) HealthState.RED else HealthState.FIRST_RUN
    if (health.apiVersion < MIN_API_VERSION) return HealthState.RED
    if (statusline == null) return HealthState.YELLOW
    val costs = resolveCosts(statusline)
    val hasTraffic = costs.cost1d > 0.0 || costs.cost7d > 0.0 || costs.cost30d > 0.0
    return if (hasTraffic) HealthState.GREEN else HealthState.YELLOW
}

/**
 * Build the status bar text. Mirrors `buildStatusText` in budi-cursor.
 * Health state drives the copy variants (`budi`, `budi · setup`,
 * `budi · offline`, `budi · $X 1d · …`); no leading glyph.
 */
internal fun buildStatusText(
    state: HealthState,
    statusline: StatuslineData?,
): String =
    when (state) {
        HealthState.FIRST_RUN -> "budi · setup"
        HealthState.RED -> "budi · offline"
        HealthState.GRAY -> "budi"
        HealthState.GREEN, HealthState.YELLOW -> "budi · ${formatCostLine(resolveCosts(statusline ?: StatuslineData()))}"
    }

/**
 * Build a status-bar tooltip. Mirrors `buildTooltip` in budi-cursor with
 * the host-dependent branches collapsed to the JetBrains-only path.
 */
internal fun buildTooltip(
    state: HealthState,
    statusline: StatuslineData?,
    cloudEndpoint: String,
): String {
    val lines = mutableListOf("budi — JetBrains usage", "")
    when (state) {
        HealthState.FIRST_RUN -> {
            lines += "budi is not installed on this machine yet."
            lines += "Click to set it up in one step."
            return lines.joinToString("\n")
        }
        HealthState.RED -> {
            lines += "Daemon not reachable."
            lines += "Run `budi doctor` to verify."
            lines += ""
            lines += "Click to open the dashboard."
            return lines.joinToString("\n")
        }
        else -> Unit
    }
    val costs = resolveCosts(statusline ?: StatuslineData())
    lines += "1d  ${formatCost(costs.cost1d)}"
    lines += "7d  ${formatCost(costs.cost7d)}"
    lines += "30d ${formatCost(costs.cost30d)}"
    lines += ""
    val contributing = statusline?.contributingProviders.orEmpty()
    if (contributing.size > 1) {
        lines += "Tracking: ${contributing.joinToString(", ", transform = ::formatProviderName)}"
    } else {
        val single = statusline?.providerScope ?: statusline?.activeProvider ?: "copilot_chat"
        lines += "Provider: ${formatProviderName(single)}"
    }
    if (state == HealthState.YELLOW) {
        val scope = if (contributing.size == 1) formatProviderName(contributing[0]) else "JetBrains AI"
        lines += "No recent $scope traffic in the last 24h."
    }
    lines += ""
    lines += "Click to open ${cloudEndpoint.trimEnd('/')}"
    return lines.joinToString("\n")
}

/**
 * Pretty-print a provider wire name for tooltip rendering. Canonical
 * names map to the strings used in budi-cursor; unknown names round-trip
 * through underscore-to-space title-case so deferred providers from
 * siropkin/budi#295 still render readably.
 */
internal fun formatProviderName(provider: String): String =
    when (provider) {
        "cursor" -> "Cursor"
        "copilot_chat" -> "Copilot Chat"
        "copilot_cli" -> "Copilot CLI"
        "claude_code" -> "Claude Code"
        "codex" -> "Codex"
        "continue" -> "Continue"
        "cline" -> "Cline"
        "roo_code" -> "Roo Code"
        else ->
            provider.split('_').joinToString(" ") {
                if (it.isEmpty()) it else it[0].uppercaseChar() + it.substring(1)
            }
    }

/**
 * Build the click-through URL for the status-bar item. Mirrors
 * `clickUrl` in budi-cursor: when there is JetBrains-surface traffic in
 * the rolling 1d window, open the cloud session list; otherwise open
 * the dashboard root.
 */
internal fun clickUrl(
    cloudEndpoint: String,
    statusline: StatuslineData?,
): String {
    val base = cloudEndpoint.trimEnd('/')
    val active = statusline?.activeProvider
    if (statusline != null && active != null && active.isNotEmpty()) {
        return "$base/dashboard/sessions"
    }
    return "$base/dashboard"
}

/**
 * Compose `/analytics/statusline?surface=jetbrains[&project_dir=...]`.
 * Surface filter is omitted when `includeOtherSurfaces=true` so the
 * holistic-view crowd can see cross-editor totals. Mirrors
 * `buildStatuslineUrl` in budi-cursor with the JetBrains surface
 * hardcoded.
 */
internal fun buildStatuslineUrl(
    daemonUrl: String,
    projectDir: String? = null,
    includeOtherSurfaces: Boolean = false,
): String {
    val base = daemonUrl.trimEnd('/')
    val params =
        buildList {
            if (!includeOtherSurfaces) add("surface" to SURFACE_JETBRAINS)
            if (!projectDir.isNullOrEmpty()) add("project_dir" to projectDir)
        }
    if (params.isEmpty()) return "$base/analytics/statusline"
    val query =
        params.joinToString("&") { (k, v) ->
            "$k=${URLEncoder.encode(v, StandardCharsets.UTF_8)}"
        }
    return "$base/analytics/statusline?$query"
}

/**
 * Compose `/health/sources?surface=jetbrains`. Surface filter is omitted
 * when `includeOtherSurfaces=true` so a holistic-view user sees every
 * surface the daemon has discovered. Mirrors `buildStatuslineUrl` so the
 * two endpoints stay shape-consistent.
 */
internal fun buildSourcesUrl(
    daemonUrl: String,
    includeOtherSurfaces: Boolean = false,
): String {
    val base = daemonUrl.trimEnd('/')
    if (includeOtherSurfaces) return "$base/health/sources"
    val encoded = URLEncoder.encode(SURFACE_JETBRAINS, StandardCharsets.UTF_8)
    return "$base/health/sources?surface=$encoded"
}

/**
 * Render the "Detected sources" body for the settings panel. Pure
 * function so the empty-state and path-listing branches are easy to
 * unit-test without touching Swing.
 *
 * Null or empty input → quiet "No sources detected." HTML. Non-empty
 * input → an unordered list of paths, HTML-escaped so a path containing
 * `<` or `&` does not break the label rendering.
 */
internal fun renderDetectedSourcesHtml(sources: DetectedSources?): String {
    val paths = sources?.paths.orEmpty().filter { it.isNotBlank() }
    if (paths.isEmpty()) {
        return "<html><i>No sources detected.</i></html>"
    }
    return paths.joinToString(
        prefix = "<html>",
        separator = "<br/>",
        postfix = "</html>",
        transform = ::escapeForHtml,
    )
}

private fun escapeForHtml(s: String): String =
    s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

/**
 * True iff `url` is an `http(s)` URL whose host is a loopback alias.
 * Non-loopback daemon URLs are rejected at config-read time so a
 * malicious project-scoped override cannot redirect the polling traffic
 * (parity with siropkin/budi-cursor#42).
 */
internal fun isLoopbackDaemonUrl(url: String): Boolean {
    val parsed =
        try {
            URI(url)
        } catch (_: Exception) {
            return false
        }
    val scheme = parsed.scheme?.lowercase() ?: return false
    if (scheme != "http" && scheme != "https") return false
    val host = parsed.host?.lowercase() ?: return false
    val normalized = if (parsed.rawAuthority?.contains("[::1]") == true) "[::1]" else host
    return normalized in LOOPBACK_HOSTS
}

/**
 * True iff `url` is an `https` URL on `getbudi.dev` (or any subdomain)
 * with no userinfo. Off-domain cloud endpoints are rejected so a
 * malicious project-scoped override cannot redirect the click-through
 * to a phishing page (parity with siropkin/budi-cursor#43).
 */
internal fun isAllowedCloudEndpoint(url: String): Boolean {
    val parsed =
        try {
            URI(url)
        } catch (_: Exception) {
            return false
        }
    if (parsed.scheme?.lowercase() != "https") return false
    if (!parsed.userInfo.isNullOrEmpty()) return false
    val host = parsed.host?.lowercase() ?: return false
    return host == CLOUD_HOST_ROOT || host.endsWith(".$CLOUD_HOST_ROOT")
}

/**
 * Daemon HTTP client. Wraps the JDK 21 `java.net.http.HttpClient` with
 * the same defense-in-depth limits as budi-cursor#44: 3 s timeout,
 * 64 KB body cap, 2xx-only, JSON content-type only. Returns `null` on
 * any failure — callers fold that into the `RED` health state.
 */
internal class BudiClient(
    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(REQUEST_TIMEOUT)
            .build(),
    private val gson: Gson = Gson(),
) {
    fun fetchHealth(daemonUrl: String): DaemonHealth? = getJson("${daemonUrl.trimEnd('/')}/health", DaemonHealth::class.java)

    fun fetchStatusline(
        daemonUrl: String,
        projectDir: String? = null,
        includeOtherSurfaces: Boolean = false,
    ): StatuslineData? = getJson(buildStatuslineUrl(daemonUrl, projectDir, includeOtherSurfaces), StatuslineData::class.java)

    /**
     * Fetch the daemon's per-surface source discovery for the settings
     * panel "Detected sources" row (budi-jetbrains#33). Returns `null`
     * on any failure — including the endpoint not yet existing on older
     * daemons — so the caller can collapse missing data into the same
     * quiet empty state.
     */
    fun fetchSources(
        daemonUrl: String,
        includeOtherSurfaces: Boolean = false,
    ): DetectedSources? = getJson(buildSourcesUrl(daemonUrl, includeOtherSurfaces), DetectedSources::class.java)

    private fun <T> getJson(
        url: String,
        type: Class<T>,
    ): T? {
        val request =
            try {
                HttpRequest
                    .newBuilder()
                    .uri(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build()
            } catch (_: IllegalArgumentException) {
                return null
            }
        val response: HttpResponse<ByteArray> =
            try {
                httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
            } catch (_: IOException) {
                return null
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        if (response.statusCode() < 200 || response.statusCode() >= 300) return null
        val contentType =
            response
                .headers()
                .firstValue("content-type")
                .orElse("")
                .lowercase()
        if (!contentType.contains("application/json")) return null
        val body = response.body() ?: return null
        if (body.size > MAX_RESPONSE_BYTES) return null
        return try {
            gson.fromJson(String(body, StandardCharsets.UTF_8), type)
        } catch (_: JsonSyntaxException) {
            null
        }
    }
}
