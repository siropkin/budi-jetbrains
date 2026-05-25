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
 * Pinned at `2` since this release dropped the legacy 8.0 cost-field
 * aliases — pre-8.1 daemons that emitted only the aliases would silently
 * render `$0` without an upgrade prompt. 8.4.2 is the first daemon to
 * bump `api_version` to 2 (see siropkin/budi#692), so the floor doubles
 * as the upgrade-prompt gate for anyone still on a daemon old enough to
 * lack the canonical `cost_1d` / `cost_7d` / `cost_30d` fields.
 *
 * Bump only when budi-core actually bumps `API_VERSION` for a breaking
 * wire change, and update both sides in the same release.
 */
internal const val MIN_API_VERSION = 2

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
    @SerializedName("cloud_linked") val cloudLinked: Boolean? = null,
)

/**
 * `/analytics/statusline` response shape. Mirrors budi-cursor's
 * `StatuslineData` 1:1.
 */
internal data class StatuslineData(
    @SerializedName("cost_1d") val cost1d: Double? = null,
    @SerializedName("cost_7d") val cost7d: Double? = null,
    @SerializedName("cost_30d") val cost30d: Double? = null,
    @SerializedName("active_provider") val activeProvider: String? = null,
    @SerializedName("provider_scope") val providerScope: String? = null,
    @SerializedName("contributing_providers") val contributingProviders: List<String>? = null,
    @SerializedName("quota_used_percent") val quotaUsedPercent: Double? = null,
    @SerializedName("quota_resets_at") val quotaResetsAt: String? = null,
    @SerializedName("cost_active_block_cents") val costActiveBlockCents: Double? = null,
    @SerializedName("active_block_started_at") val activeBlockStartedAt: String? = null,
    @SerializedName("burn_rate_cents_per_hour") val burnRateCentsPerHour: Double? = null,
    @SerializedName("breakdown_by_provider") val breakdownByProvider: Map<String, Double>? = null,
    @SerializedName("cycle_elapsed_percent") val cycleElapsedPercent: Double? = null,
    @SerializedName("spend_pace_percent") val spendPacePercent: Double? = null,
)

/**
 * Status bar display mode — which signal dominates the widget.
 * Mirrors budi-cursor's `statusBarMode` setting 1:1.
 */
enum class StatusBarMode {
    COST,
    QUOTA,
    BOTH,
}

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
 * Resolve the rolling cost fields from the canonical
 * `cost_1d` / `cost_7d` / `cost_30d` shape, defaulting missing or
 * non-finite values to `0.0`. Mirrors `resolveCosts` in budi-cursor.
 */
internal fun resolveCosts(data: StatuslineData): ResolvedCosts {
    fun pick(value: Double?): Double = if (value != null && value.isFinite()) value else 0.0
    return ResolvedCosts(
        cost1d = pick(data.cost1d),
        cost7d = pick(data.cost7d),
        cost30d = pick(data.cost30d),
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

/** True when the daemon returned usable quota fields. */
internal fun hasQuotaData(statusline: StatuslineData?): Boolean {
    val pct = statusline?.quotaUsedPercent ?: return false
    return pct.isFinite()
}

/**
 * Format the quota percentage for display. Rounds to the nearest
 * integer — fractional precision isn't meaningful for a progress
 * indicator.
 */
internal fun formatQuotaPercent(percent: Double): String {
    if (!percent.isFinite() || percent < 0.0) return "0%"
    return "${Math.round(percent)}%"
}

/**
 * Build the quota portion of the status line.
 * `budi · 67% · resets Jun 1` (with reset date) or `budi · 67%`
 * (without).
 */
internal fun formatQuotaLine(statusline: StatuslineData): String {
    val pct = formatQuotaPercent(statusline.quotaUsedPercent ?: 0.0)
    val reset = statusline.quotaResetsAt
    return if (!reset.isNullOrBlank()) "$pct · resets $reset" else pct
}

/**
 * Build the combined cost + quota status line for BOTH mode.
 * `$2.34 1d · 67% quota`
 */
internal fun formatBothLine(
    costs: ResolvedCosts,
    statusline: StatuslineData,
): String {
    val pct = formatQuotaPercent(statusline.quotaUsedPercent ?: 0.0)
    return "${formatCost(costs.cost1d)} 1d · $pct quota"
}

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
 *
 * [mode] selects the signal rendered in the GREEN/YELLOW branch:
 *   COST  — `budi · $X 1d · $Y 7d · $Z 30d` (default)
 *   QUOTA — `budi · 67% · resets Jun 1` (falls back to COST when
 *           the daemon doesn't return quota fields)
 *   BOTH  — `budi · $X 1d · 67% quota` (falls back to COST when
 *           quota fields are absent)
 *
 * [showBurnRate] appends ` · $X.XX/hr` when the daemon reports an
 * active-session burn rate. Default off — keeps the quiet aesthetic.
 */
internal fun buildStatusText(
    state: HealthState,
    statusline: StatuslineData?,
    mode: StatusBarMode = StatusBarMode.COST,
    showBurnRate: Boolean = false,
): String {
    val data = statusline ?: StatuslineData()
    return when (state) {
        HealthState.FIRST_RUN -> "budi · setup"
        HealthState.RED -> "budi · offline"
        HealthState.GRAY -> "budi"
        HealthState.GREEN, HealthState.YELLOW -> {
            val body =
                when {
                    mode == StatusBarMode.QUOTA && hasQuotaData(statusline) -> formatQuotaLine(data)
                    mode == StatusBarMode.BOTH && hasQuotaData(statusline) -> formatBothLine(resolveCosts(data), data)
                    else -> formatCostLine(resolveCosts(data))
                }
            val burnSuffix = if (showBurnRate) formatBurnRateSuffix(statusline)?.let { " · $it" }.orEmpty() else ""
            "budi · $body$burnSuffix"
        }
    }
}

/** True when the daemon returned usable active-session fields. */
internal fun hasActiveSession(statusline: StatuslineData?): Boolean {
    val cents = statusline?.costActiveBlockCents ?: return false
    val rate = statusline.burnRateCentsPerHour ?: return false
    return cents.isFinite() && rate.isFinite() && statusline.activeBlockStartedAt != null
}

/**
 * Compute elapsed minutes since the active block started. Returns
 * `null` when the timestamp is missing or unparseable. The daemon
 * sends ISO-8601 UTC; we compare against the local clock.
 */
internal fun activeBlockElapsedMinutes(startedAt: String?): Long? {
    if (startedAt.isNullOrBlank()) return null
    return try {
        val start = java.time.Instant.parse(startedAt)
        val elapsed = java.time.Duration.between(start, java.time.Instant.now())
        if (elapsed.isNegative) 0L else elapsed.toMinutes()
    } catch (_: Exception) {
        null
    }
}

/**
 * Format the active-session line for the tooltip.
 * `Active session: $0.42 · 12min · $2.10/hr`
 */
internal fun formatActiveSessionLine(statusline: StatuslineData): String? {
    if (!hasActiveSession(statusline)) return null
    val costDollars = statusline.costActiveBlockCents!! / 100.0
    val rateDollars = statusline.burnRateCentsPerHour!! / 100.0
    val minutes = activeBlockElapsedMinutes(statusline.activeBlockStartedAt)
    val elapsedPart = if (minutes != null) " · ${minutes}min" else ""
    return "Active session: ${formatCost(costDollars)}$elapsedPart · ${formatCost(rateDollars)}/hr"
}

/**
 * Format the burn-rate suffix for the status bar widget.
 * Returns `$2.10/hr` or `null` when the daemon doesn't report a
 * burn rate.
 */
internal fun formatBurnRateSuffix(statusline: StatuslineData?): String? {
    val rate = statusline?.burnRateCentsPerHour ?: return null
    if (!rate.isFinite()) return null
    return "${formatCost(rate / 100.0)}/hr"
}

/**
 * Pacing label for the tooltip when quota usage exceeds comfortable
 * thresholds. Returns null when usage is below 70% (no warning).
 * Mirrors budi-cursor's tooltip color hints.
 */
internal fun quotaPacingLabel(usedPercent: Double): String? =
    when {
        !usedPercent.isFinite() -> null
        usedPercent > 95.0 -> "⚠ Quota critically high"
        usedPercent >= 90.0 -> "⚠ Quota very high"
        usedPercent >= 70.0 -> "Quota elevated"
        else -> null
    }

/** Append the quota section to the tooltip lines (if quota data present). */
private fun appendQuotaSection(
    lines: MutableList<String>,
    statusline: StatuslineData?,
) {
    if (!hasQuotaData(statusline)) return
    lines += ""
    val pct = statusline!!.quotaUsedPercent!!
    val resetSuffix = if (!statusline.quotaResetsAt.isNullOrBlank()) " · resets ${statusline.quotaResetsAt}" else ""
    lines += "Quota: ${formatQuotaPercent(pct)} used$resetSuffix"
    quotaPacingLabel(pct)?.let { lines += it }
}

/**
 * True when the daemon returned a per-provider breakdown with at least two
 * providers carrying non-zero 30d spend. A single-provider breakdown adds
 * no information beyond the aggregate cost line, so the section is hidden.
 */
internal fun hasProviderBreakdown(statusline: StatuslineData?): Boolean {
    val breakdown = statusline?.breakdownByProvider ?: return false
    return breakdown.count { (_, cost) -> cost.isFinite() && cost > 0.0 } >= 2
}

/**
 * Format the per-provider breakdown section for the tooltip. Providers are
 * right-aligned to the widest cost string so the column reads cleanly.
 */
internal fun formatProviderBreakdown(breakdown: Map<String, Double>): List<String> {
    val nonZero =
        breakdown
            .filter { (_, cost) -> cost.isFinite() && cost > 0.0 }
            .entries
            .sortedByDescending { it.value }
    if (nonZero.size < 2) return emptyList()

    val formatted = nonZero.map { (provider, cost) -> formatProviderName(provider) to formatCost(cost) }
    val maxNameLen = formatted.maxOf { it.first.length }

    return buildList {
        add("By provider (30d):")
        for ((name, cost) in formatted) {
            add("  ${name.padEnd(maxNameLen)}  $cost")
        }
    }
}

/** Append the per-provider breakdown to the tooltip lines (if present). */
private fun appendProviderBreakdown(
    lines: MutableList<String>,
    statusline: StatuslineData?,
) {
    if (!hasProviderBreakdown(statusline)) return
    lines += ""
    lines += formatProviderBreakdown(statusline!!.breakdownByProvider!!)
}

/**
 * Pacing label for the billing-cycle spend pace. Returns a warning prefix
 * when spending is running hot relative to typical. Mirrors budi-cursor's
 * tooltip color hints:
 *   > 175% → red warning
 *   > 130% → yellow warning
 *   ≤ 130% → no prefix (plain text)
 */
internal fun spendPacingHint(pacePercent: Double): String? =
    when {
        !pacePercent.isFinite() -> null
        pacePercent > 175.0 -> "⚠ "
        pacePercent > 130.0 -> "⚡ "
        else -> null
    }

/**
 * Format the pacing line for the tooltip. Returns null when the daemon
 * does not supply pacing data.
 */
internal fun formatPacingLine(statusline: StatuslineData?): String? {
    val pace = statusline?.spendPacePercent ?: return null
    val elapsed = statusline.cycleElapsedPercent ?: return null
    if (!pace.isFinite() || !elapsed.isFinite()) return null
    val hint = spendPacingHint(pace) ?: ""
    return "${hint}Pacing: ${Math.round(elapsed)}% through month, ${Math.round(pace)}% of typical spend"
}

/** Append the pacing line to the tooltip lines (if present). */
private fun appendPacingSection(
    lines: MutableList<String>,
    statusline: StatuslineData?,
) {
    val pacingLine = formatPacingLine(statusline) ?: return
    lines += ""
    lines += pacingLine
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
    formatActiveSessionLine(statusline ?: StatuslineData())?.let {
        lines += it
        lines += ""
    }
    val costs = resolveCosts(statusline ?: StatuslineData())
    lines += "1d  ${formatCost(costs.cost1d)}"
    lines += "7d  ${formatCost(costs.cost7d)}"
    lines += "30d ${formatCost(costs.cost30d)}"
    appendProviderBreakdown(lines, statusline)
    appendQuotaSection(lines, statusline)
    appendPacingSection(lines, statusline)
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
