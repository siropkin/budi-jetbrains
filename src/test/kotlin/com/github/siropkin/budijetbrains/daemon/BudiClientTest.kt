package com.github.siropkin.budijetbrains.daemon

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BudiClientPureLogicTest {
    @Test
    fun `resolveCosts reads canonical rolling-window fields`() {
        val data =
            StatuslineData(
                cost1d = 1.23,
                cost7d = 4.56,
                cost30d = 7.89,
            )
        val resolved = resolveCosts(data)
        assertEquals(1.23, resolved.cost1d, 1e-9)
        assertEquals(4.56, resolved.cost7d, 1e-9)
        assertEquals(7.89, resolved.cost30d, 1e-9)
    }

    @Test
    fun `resolveCosts defaults missing or non-finite values to zero`() {
        val empty = resolveCosts(StatuslineData())
        assertEquals(0.0, empty.cost1d, 1e-9)
        assertEquals(0.0, empty.cost7d, 1e-9)
        assertEquals(0.0, empty.cost30d, 1e-9)

        val nonFinite =
            resolveCosts(
                StatuslineData(
                    cost1d = Double.NaN,
                    cost7d = Double.POSITIVE_INFINITY,
                    cost30d = Double.NEGATIVE_INFINITY,
                ),
            )
        assertEquals(0.0, nonFinite.cost1d, 1e-9)
        assertEquals(0.0, nonFinite.cost7d, 1e-9)
        assertEquals(0.0, nonFinite.cost30d, 1e-9)
    }

    @Test
    fun `formatCostLine matches Claude Code statusline shape`() {
        val line = formatCostLine(ResolvedCosts(2.34, 12.5, 48.1))
        assertEquals("$2.34 1d · $12.50 7d · $48.10 30d", line)
    }

    @Test
    fun `formatCostLine renders zero as $0_00`() {
        val line = formatCostLine(ResolvedCosts(0.0, 0.0, 0.0))
        assertEquals("$0.00 1d · $0.00 7d · $0.00 30d", line)
    }

    @Test
    fun `formatCost compacts kilobucks and rounds hundreds`() {
        assertEquals("$0.00", formatCost(0.0))
        assertEquals("$1.23", formatCost(1.23))
        assertEquals("$100", formatCost(100.0))
        assertEquals("$999", formatCost(999.0))
        assertEquals("$1.5K", formatCost(1500.0))
        assertEquals("$0.00", formatCost(-1.0))
        assertEquals("$0.00", formatCost(Double.NaN))
        assertEquals("$0.00", formatCost(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `deriveHealthState routes null health to FIRST_RUN before first sighting`() {
        assertEquals(HealthState.FIRST_RUN, deriveHealthState(null, null, everSawDaemon = false))
    }

    @Test
    fun `deriveHealthState routes null health to RED after first sighting`() {
        assertEquals(HealthState.RED, deriveHealthState(null, null, everSawDaemon = true))
    }

    @Test
    fun `deriveHealthState gates RED on stale api_version`() {
        val stale = DaemonHealth(ok = true, version = "8.0.0", apiVersion = MIN_API_VERSION - 1)
        assertEquals(HealthState.RED, deriveHealthState(stale, StatuslineData(cost1d = 1.0)))
    }

    @Test
    fun `deriveHealthState yellow when daemon healthy but no traffic`() {
        val ok = DaemonHealth(ok = true, version = "8.4.2", apiVersion = MIN_API_VERSION)
        assertEquals(HealthState.YELLOW, deriveHealthState(ok, StatuslineData()))
        assertEquals(HealthState.YELLOW, deriveHealthState(ok, null))
    }

    @Test
    fun `deriveHealthState green when traffic present`() {
        val ok = DaemonHealth(ok = true, version = "8.4.2", apiVersion = MIN_API_VERSION)
        val data = StatuslineData(cost1d = 0.0, cost7d = 0.5, cost30d = 0.0)
        assertEquals(HealthState.GREEN, deriveHealthState(ok, data))
    }

    @Test
    fun `buildStatusText routes by state`() {
        val ok = DaemonHealth(ok = true, version = "8.4.2", apiVersion = MIN_API_VERSION)
        val zero = StatuslineData()
        val withTraffic = StatuslineData(cost1d = 1.0, cost7d = 2.0, cost30d = 3.0)
        assertEquals("budi", buildStatusText(HealthState.GRAY, null))
        assertEquals("budi · setup", buildStatusText(HealthState.FIRST_RUN, null))
        assertEquals("budi · offline", buildStatusText(HealthState.RED, null))
        assertEquals(
            "budi · ${formatCostLine(resolveCosts(withTraffic))}",
            buildStatusText(deriveHealthState(ok, withTraffic), withTraffic),
        )
        assertEquals(
            "budi · ${formatCostLine(resolveCosts(zero))}",
            buildStatusText(HealthState.YELLOW, zero),
        )
    }

    @Test
    fun `buildTooltip first run hints at install`() {
        val tip = buildTooltip(HealthState.FIRST_RUN, null, DEFAULT_CLOUD_ENDPOINT)
        assertTrue(tip.contains("not installed on this machine yet"))
        assertTrue(tip.contains("Click to set it up"))
    }

    @Test
    fun `buildTooltip red routes to budi doctor and dashboard click`() {
        val tip = buildTooltip(HealthState.RED, null, DEFAULT_CLOUD_ENDPOINT)
        assertTrue(tip.contains("Daemon not reachable."))
        assertTrue(tip.contains("Run `budi doctor`"))
        assertTrue(tip.contains("Click to open the dashboard."))
    }

    @Test
    fun `buildTooltip green renders cost lines and provider`() {
        val data = StatuslineData(cost1d = 1.0, cost7d = 2.0, cost30d = 3.0, activeProvider = "copilot_chat")
        val tip = buildTooltip(HealthState.GREEN, data, DEFAULT_CLOUD_ENDPOINT)
        assertTrue(tip.contains("1d  $1.00"))
        assertTrue(tip.contains("7d  $2.00"))
        assertTrue(tip.contains("30d $3.00"))
        assertTrue(tip.contains("Provider: Copilot Chat"))
        assertTrue(tip.contains("Click to open https://app.getbudi.dev"))
    }

    @Test
    fun `buildTooltip yellow flags no recent traffic`() {
        val tip = buildTooltip(HealthState.YELLOW, StatuslineData(), DEFAULT_CLOUD_ENDPOINT)
        assertTrue(tip.contains("No recent JetBrains AI traffic in the last 24h."))
    }

    @Test
    fun `buildTooltip yellow narrows scope to single contributing provider`() {
        // When the surface rollup names a single contributor, the "No
        // recent X traffic" copy must use that contributor's pretty name
        // rather than the generic "JetBrains AI" fallback.
        val data = StatuslineData(contributingProviders = listOf("copilot_chat"))
        val tip = buildTooltip(HealthState.YELLOW, data, DEFAULT_CLOUD_ENDPOINT)
        assertTrue(tip.contains("No recent Copilot Chat traffic in the last 24h."))
        // Single contributor → render as Provider line, not Tracking line.
        assertTrue(tip.contains("Provider: Copilot Chat"))
        assertFalse(tip.contains("Tracking:"))
    }

    @Test
    fun `buildTooltip green renders Tracking line for multi-provider contributors`() {
        val data =
            StatuslineData(
                cost1d = 1.0,
                cost7d = 2.0,
                cost30d = 3.0,
                contributingProviders = listOf("copilot_chat", "claude_code"),
            )
        val tip = buildTooltip(HealthState.GREEN, data, DEFAULT_CLOUD_ENDPOINT)
        assertTrue(tip.contains("Tracking: Copilot Chat, Claude Code"))
        // Multi-provider rollup must not also render a single-Provider line.
        assertFalse(tip.contains("Provider: Copilot Chat\n"))
    }

    @Test
    fun `buildTooltip green falls back to copilot_chat when no provider hints`() {
        // When the daemon doesn't name a provider at all, the tooltip
        // labels the row "Copilot Chat" — the v0.1 default for the
        // JetBrains surface.
        val data = StatuslineData(cost1d = 1.0, cost7d = 2.0, cost30d = 3.0)
        val tip = buildTooltip(HealthState.GREEN, data, DEFAULT_CLOUD_ENDPOINT)
        assertTrue(tip.contains("Provider: Copilot Chat"))
    }

    @Test
    fun `buildTooltip prefers provider_scope over active_provider for the single-row label`() {
        // When both hints are present, provider_scope (the rollup scope)
        // wins — active_provider is the most-recent-session hint, not
        // the rollup label.
        val data =
            StatuslineData(
                cost1d = 1.0,
                cost7d = 2.0,
                cost30d = 3.0,
                activeProvider = "claude_code",
                providerScope = "copilot_chat",
            )
        val tip = buildTooltip(HealthState.GREEN, data, DEFAULT_CLOUD_ENDPOINT)
        assertTrue(tip.contains("Provider: Copilot Chat"))
    }

    @Test
    fun `buildTooltip strips a trailing slash on the cloud endpoint click line`() {
        val tip = buildTooltip(HealthState.GREEN, StatuslineData(cost1d = 1.0), "https://app.getbudi.dev/")
        assertTrue(tip.contains("Click to open https://app.getbudi.dev"))
        assertFalse(tip.contains("Click to open https://app.getbudi.dev/\n"))
    }

    @Test
    fun `formatProviderName covers canonical wire names`() {
        assertEquals("Copilot Chat", formatProviderName("copilot_chat"))
        assertEquals("Claude Code", formatProviderName("claude_code"))
        assertEquals("Codex", formatProviderName("codex"))
        // Unknown wire names round-trip via underscore-to-space title-case.
        assertEquals("Junie Chat", formatProviderName("junie_chat"))
    }

    @Test
    fun `formatProviderName handles empty segments without crashing`() {
        // Defensive: a trailing underscore would leave an empty segment.
        // The title-case mapper must not index into an empty string.
        assertEquals("", formatProviderName(""))
        assertEquals("Foo ", formatProviderName("foo_"))
        assertEquals(" Bar", formatProviderName("_bar"))
    }

    @Test
    fun `buildStatuslineUrl treats empty projectDir as omitted`() {
        val url = buildStatuslineUrl("http://127.0.0.1:7878", projectDir = "")
        assertEquals("http://127.0.0.1:7878/analytics/statusline?surface=jetbrains", url)
    }

    @Test
    fun `buildStatuslineUrl trims a trailing slash on the daemon URL`() {
        val url = buildStatuslineUrl("http://127.0.0.1:7878/")
        assertEquals("http://127.0.0.1:7878/analytics/statusline?surface=jetbrains", url)
    }

    @Test
    fun `clickUrl routes to sessions when active_provider present, dashboard otherwise`() {
        assertEquals(
            "https://app.getbudi.dev/dashboard",
            clickUrl(DEFAULT_CLOUD_ENDPOINT, null),
        )
        assertEquals(
            "https://app.getbudi.dev/dashboard",
            clickUrl(DEFAULT_CLOUD_ENDPOINT, StatuslineData()),
        )
        assertEquals(
            "https://app.getbudi.dev/dashboard/sessions",
            clickUrl(DEFAULT_CLOUD_ENDPOINT, StatuslineData(activeProvider = "copilot_chat")),
        )
    }

    @Test
    fun `clickUrl treats an empty active_provider string as no provider`() {
        // budi-cursor parity: `null` and `""` both route to /dashboard,
        // only a non-empty value flips to /dashboard/sessions.
        assertEquals(
            "https://app.getbudi.dev/dashboard",
            clickUrl(DEFAULT_CLOUD_ENDPOINT, StatuslineData(activeProvider = "")),
        )
    }

    @Test
    fun `clickUrl trims a trailing slash on the cloud endpoint`() {
        assertEquals(
            "https://app.getbudi.dev/dashboard",
            clickUrl("https://app.getbudi.dev/", null),
        )
    }

    @Test
    fun `buildStatuslineUrl pins surface=jetbrains by default`() {
        val url = buildStatuslineUrl("http://127.0.0.1:7878")
        assertEquals("http://127.0.0.1:7878/analytics/statusline?surface=jetbrains", url)
    }

    @Test
    fun `buildStatuslineUrl omits surface when includeOtherSurfaces is true`() {
        val url = buildStatuslineUrl("http://127.0.0.1:7878", includeOtherSurfaces = true)
        assertEquals("http://127.0.0.1:7878/analytics/statusline", url)
    }

    @Test
    fun `buildStatuslineUrl appends project_dir`() {
        val url =
            buildStatuslineUrl(
                "http://127.0.0.1:7878",
                projectDir = "/tmp/some project",
                includeOtherSurfaces = false,
            )
        // "/tmp/some project" → URL-encoded.
        assertEquals(
            "http://127.0.0.1:7878/analytics/statusline?surface=jetbrains&project_dir=%2Ftmp%2Fsome+project",
            url,
        )
    }

    @Test
    fun `isLoopbackDaemonUrl accepts only loopback http(s)`() {
        assertTrue(isLoopbackDaemonUrl("http://127.0.0.1:7878"))
        assertTrue(isLoopbackDaemonUrl("http://localhost:7878"))
        assertTrue(isLoopbackDaemonUrl("http://[::1]:7878"))
        assertTrue(isLoopbackDaemonUrl("https://localhost"))
        assertFalse(isLoopbackDaemonUrl("http://example.com"))
        assertFalse(isLoopbackDaemonUrl("file:///etc/passwd"))
        assertFalse(isLoopbackDaemonUrl("http://127.0.0.1.attacker.example"))
        assertFalse(isLoopbackDaemonUrl("not a url"))
    }

    @Test
    fun `isLoopbackDaemonUrl rejects empty and host-less inputs`() {
        assertFalse(isLoopbackDaemonUrl(""))
        // Scheme-only URI has a null host.
        assertFalse(isLoopbackDaemonUrl("http://"))
        // Non-http(s) schemes are rejected even if host is loopback.
        assertFalse(isLoopbackDaemonUrl("ws://127.0.0.1:7878"))
    }

    @Test
    fun `buildSourcesUrl pins surface=jetbrains by default`() {
        val url = buildSourcesUrl("http://127.0.0.1:7878")
        assertEquals("http://127.0.0.1:7878/health/sources?surface=jetbrains", url)
    }

    @Test
    fun `buildSourcesUrl omits surface when includeOtherSurfaces is true`() {
        val url = buildSourcesUrl("http://127.0.0.1:7878", includeOtherSurfaces = true)
        assertEquals("http://127.0.0.1:7878/health/sources", url)
    }

    @Test
    fun `buildSourcesUrl trims trailing slash on daemon URL`() {
        val url = buildSourcesUrl("http://127.0.0.1:7878/")
        assertEquals("http://127.0.0.1:7878/health/sources?surface=jetbrains", url)
    }

    @Test
    fun `renderDetectedSourcesHtml empty state on null`() {
        assertEquals("<html><i>No sources detected.</i></html>", renderDetectedSourcesHtml(null))
    }

    @Test
    fun `renderDetectedSourcesHtml empty state on empty paths`() {
        assertEquals(
            "<html><i>No sources detected.</i></html>",
            renderDetectedSourcesHtml(DetectedSources(surface = "jetbrains", paths = emptyList())),
        )
    }

    @Test
    fun `renderDetectedSourcesHtml drops blank entries`() {
        assertEquals(
            "<html><i>No sources detected.</i></html>",
            renderDetectedSourcesHtml(DetectedSources(paths = listOf("", "   "))),
        )
    }

    @Test
    fun `renderDetectedSourcesHtml lists paths on separate lines`() {
        val rendered =
            renderDetectedSourcesHtml(
                DetectedSources(
                    surface = "jetbrains",
                    paths = listOf("/Users/me/Library/Caches/JetBrains", "/Users/me/.config/JetBrains"),
                ),
            )
        assertEquals(
            "<html>/Users/me/Library/Caches/JetBrains<br/>/Users/me/.config/JetBrains</html>",
            rendered,
        )
    }

    @Test
    fun `renderDetectedSourcesHtml escapes HTML metacharacters in paths`() {
        val rendered =
            renderDetectedSourcesHtml(
                DetectedSources(paths = listOf("/tmp/<weird>&dir")),
            )
        assertEquals("<html>/tmp/&lt;weird&gt;&amp;dir</html>", rendered)
    }

    @Test
    fun `isAllowedCloudEndpoint accepts getbudi_dev https subdomains only`() {
        assertTrue(isAllowedCloudEndpoint("https://app.getbudi.dev"))
        assertTrue(isAllowedCloudEndpoint("https://staging.app.getbudi.dev"))
        assertTrue(isAllowedCloudEndpoint("https://getbudi.dev"))
        assertFalse(isAllowedCloudEndpoint("http://app.getbudi.dev"))
        assertFalse(isAllowedCloudEndpoint("https://app.getbudi.dev.attacker.example"))
        assertFalse(isAllowedCloudEndpoint("https://attacker@app.getbudi.dev"))
        assertFalse(isAllowedCloudEndpoint("not a url"))
    }

    // ── Quota helpers ───────────────────────────────────────────────

    @Test
    fun `hasQuotaData returns false when statusline is null or lacks quota fields`() {
        assertFalse(hasQuotaData(null))
        assertFalse(hasQuotaData(StatuslineData()))
        assertFalse(hasQuotaData(StatuslineData(quotaUsedPercent = Double.NaN)))
        assertFalse(hasQuotaData(StatuslineData(quotaUsedPercent = Double.POSITIVE_INFINITY)))
    }

    @Test
    fun `hasQuotaData returns true when quota percent is finite`() {
        assertTrue(hasQuotaData(StatuslineData(quotaUsedPercent = 0.0)))
        assertTrue(hasQuotaData(StatuslineData(quotaUsedPercent = 67.0)))
        assertTrue(hasQuotaData(StatuslineData(quotaUsedPercent = 100.0)))
    }

    @Test
    fun `formatQuotaPercent rounds and handles edge cases`() {
        assertEquals("0%", formatQuotaPercent(0.0))
        assertEquals("67%", formatQuotaPercent(67.0))
        assertEquals("67%", formatQuotaPercent(67.4))
        assertEquals("68%", formatQuotaPercent(67.5))
        assertEquals("100%", formatQuotaPercent(100.0))
        assertEquals("0%", formatQuotaPercent(-1.0))
        assertEquals("0%", formatQuotaPercent(Double.NaN))
    }

    @Test
    fun `formatQuotaLine includes reset date when present`() {
        val data = StatuslineData(quotaUsedPercent = 67.0, quotaResetsAt = "Jun 1")
        assertEquals("67% · resets Jun 1", formatQuotaLine(data))
    }

    @Test
    fun `formatQuotaLine omits reset when absent`() {
        assertEquals("67%", formatQuotaLine(StatuslineData(quotaUsedPercent = 67.0)))
        assertEquals("67%", formatQuotaLine(StatuslineData(quotaUsedPercent = 67.0, quotaResetsAt = "")))
        assertEquals("67%", formatQuotaLine(StatuslineData(quotaUsedPercent = 67.0, quotaResetsAt = "  ")))
    }

    @Test
    fun `formatBothLine renders 1d cost and quota percent`() {
        val data = StatuslineData(cost1d = 2.34, quotaUsedPercent = 67.0)
        assertEquals("$2.34 1d · 67% quota", formatBothLine(resolveCosts(data), data))
    }

    @Test
    fun `quotaPacingLabel returns null below 70 percent`() {
        assertNull(quotaPacingLabel(0.0))
        assertNull(quotaPacingLabel(69.9))
        assertNull(quotaPacingLabel(Double.NaN))
    }

    @Test
    fun `quotaPacingLabel escalates through thresholds`() {
        assertEquals("Quota elevated", quotaPacingLabel(70.0))
        assertEquals("Quota elevated", quotaPacingLabel(89.9))
        assertEquals("⚠ Quota very high", quotaPacingLabel(90.0))
        assertEquals("⚠ Quota very high", quotaPacingLabel(95.0))
        assertEquals("⚠ Quota critically high", quotaPacingLabel(95.1))
        assertEquals("⚠ Quota critically high", quotaPacingLabel(100.0))
    }

    // ── buildStatusText mode variants ───────────────────────────────

    @Test
    fun `buildStatusText COST mode renders cost line (backward compat)`() {
        val data = StatuslineData(cost1d = 1.0, cost7d = 2.0, cost30d = 3.0, quotaUsedPercent = 67.0)
        val text = buildStatusText(HealthState.GREEN, data, StatusBarMode.COST)
        assertEquals("budi · ${formatCostLine(resolveCosts(data))}", text)
    }

    @Test
    fun `buildStatusText QUOTA mode renders quota when available`() {
        val data = StatuslineData(cost1d = 1.0, quotaUsedPercent = 67.0, quotaResetsAt = "Jun 1")
        assertEquals("budi · 67% · resets Jun 1", buildStatusText(HealthState.GREEN, data, StatusBarMode.QUOTA))
    }

    @Test
    fun `buildStatusText QUOTA mode falls back to cost when no quota data`() {
        val data = StatuslineData(cost1d = 1.0, cost7d = 2.0, cost30d = 3.0)
        val text = buildStatusText(HealthState.GREEN, data, StatusBarMode.QUOTA)
        assertEquals("budi · ${formatCostLine(resolveCosts(data))}", text)
    }

    @Test
    fun `buildStatusText BOTH mode renders combined line`() {
        val data = StatuslineData(cost1d = 2.34, quotaUsedPercent = 67.0)
        assertEquals("budi · $2.34 1d · 67% quota", buildStatusText(HealthState.GREEN, data, StatusBarMode.BOTH))
    }

    @Test
    fun `buildStatusText BOTH mode falls back to cost when no quota data`() {
        val data = StatuslineData(cost1d = 1.0, cost7d = 2.0, cost30d = 3.0)
        val text = buildStatusText(HealthState.GREEN, data, StatusBarMode.BOTH)
        assertEquals("budi · ${formatCostLine(resolveCosts(data))}", text)
    }

    @Test
    fun `buildStatusText mode is ignored for non-healthy states`() {
        assertEquals("budi · setup", buildStatusText(HealthState.FIRST_RUN, null, StatusBarMode.QUOTA))
        assertEquals("budi · offline", buildStatusText(HealthState.RED, null, StatusBarMode.BOTH))
        assertEquals("budi", buildStatusText(HealthState.GRAY, null, StatusBarMode.QUOTA))
    }

    // ── buildTooltip quota section ──────────────────────────────────

    @Test
    fun `buildTooltip includes quota section when data present`() {
        val data = StatuslineData(cost1d = 1.0, quotaUsedPercent = 67.0, quotaResetsAt = "Jun 1")
        val tip = buildTooltip(HealthState.GREEN, data, DEFAULT_CLOUD_ENDPOINT)
        assertTrue(tip.contains("Quota: 67% used · resets Jun 1"))
    }

    @Test
    fun `buildTooltip omits quota section when data absent`() {
        val data = StatuslineData(cost1d = 1.0)
        val tip = buildTooltip(HealthState.GREEN, data, DEFAULT_CLOUD_ENDPOINT)
        assertFalse(tip.contains("Quota:"))
    }

    @Test
    fun `buildTooltip shows pacing warning at high quota usage`() {
        val data = StatuslineData(cost1d = 1.0, quotaUsedPercent = 96.0)
        val tip = buildTooltip(HealthState.GREEN, data, DEFAULT_CLOUD_ENDPOINT)
        assertTrue(tip.contains("⚠ Quota critically high"))
    }

    @Test
    fun `buildTooltip omits pacing warning when quota usage is comfortable`() {
        val data = StatuslineData(cost1d = 1.0, quotaUsedPercent = 50.0)
        val tip = buildTooltip(HealthState.GREEN, data, DEFAULT_CLOUD_ENDPOINT)
        assertFalse(tip.contains("Quota elevated"))
        assertFalse(tip.contains("⚠"))
    }

    // ── Active-session burn rate ────────────────────────────────────

    @Test
    fun `hasActiveSession returns false when fields are missing`() {
        assertFalse(hasActiveSession(null))
        assertFalse(hasActiveSession(StatuslineData()))
        assertFalse(hasActiveSession(StatuslineData(costActiveBlockCents = 42.0)))
        assertFalse(hasActiveSession(StatuslineData(burnRateCentsPerHour = 210.0)))
        assertFalse(
            hasActiveSession(
                StatuslineData(costActiveBlockCents = 42.0, burnRateCentsPerHour = 210.0),
            ),
        )
    }

    @Test
    fun `hasActiveSession returns false when values are non-finite`() {
        assertFalse(
            hasActiveSession(
                StatuslineData(
                    costActiveBlockCents = Double.NaN,
                    activeBlockStartedAt = "2025-01-01T00:00:00Z",
                    burnRateCentsPerHour = 210.0,
                ),
            ),
        )
        assertFalse(
            hasActiveSession(
                StatuslineData(
                    costActiveBlockCents = 42.0,
                    activeBlockStartedAt = "2025-01-01T00:00:00Z",
                    burnRateCentsPerHour = Double.POSITIVE_INFINITY,
                ),
            ),
        )
    }

    @Test
    fun `hasActiveSession returns true when all three fields are present and finite`() {
        assertTrue(
            hasActiveSession(
                StatuslineData(
                    costActiveBlockCents = 42.0,
                    activeBlockStartedAt = "2025-01-01T00:00:00Z",
                    burnRateCentsPerHour = 210.0,
                ),
            ),
        )
    }

    @Test
    fun `activeBlockElapsedMinutes returns null for missing or bad timestamps`() {
        assertNull(activeBlockElapsedMinutes(null))
        assertNull(activeBlockElapsedMinutes(""))
        assertNull(activeBlockElapsedMinutes("not-a-timestamp"))
    }

    @Test
    fun `activeBlockElapsedMinutes returns non-negative for past timestamps`() {
        val tenMinAgo =
            java.time.Instant
                .now()
                .minus(java.time.Duration.ofMinutes(10))
                .toString()
        val elapsed = activeBlockElapsedMinutes(tenMinAgo)
        assertNotNull(elapsed)
        assertTrue(elapsed >= 9)
        assertTrue(elapsed <= 11)
    }

    @Test
    fun `activeBlockElapsedMinutes clamps future timestamps to zero`() {
        val future =
            java.time.Instant
                .now()
                .plus(java.time.Duration.ofHours(1))
                .toString()
        assertEquals(0L, activeBlockElapsedMinutes(future))
    }

    @Test
    fun `formatBurnRateSuffix returns null when no rate`() {
        assertNull(formatBurnRateSuffix(null))
        assertNull(formatBurnRateSuffix(StatuslineData()))
        assertNull(formatBurnRateSuffix(StatuslineData(burnRateCentsPerHour = Double.NaN)))
    }

    @Test
    fun `formatBurnRateSuffix formats cents to dollars`() {
        assertEquals("$2.10/hr", formatBurnRateSuffix(StatuslineData(burnRateCentsPerHour = 210.0)))
        assertEquals("$0.50/hr", formatBurnRateSuffix(StatuslineData(burnRateCentsPerHour = 50.0)))
    }

    @Test
    fun `formatActiveSessionLine returns null when inactive`() {
        assertNull(formatActiveSessionLine(StatuslineData()))
    }

    @Test
    fun `formatActiveSessionLine renders cost and rate with elapsed time`() {
        val tenMinAgo =
            java.time.Instant
                .now()
                .minus(java.time.Duration.ofMinutes(10))
                .toString()
        val data =
            StatuslineData(
                costActiveBlockCents = 42.0,
                activeBlockStartedAt = tenMinAgo,
                burnRateCentsPerHour = 210.0,
            )
        val line = formatActiveSessionLine(data)
        assertNotNull(line)
        assertTrue(line.startsWith("Active session: \$0.42"))
        assertTrue(line.contains("min"))
        assertTrue(line.endsWith("\$2.10/hr"))
    }

    @Test
    fun `buildStatusText appends burn rate when showBurnRate is true`() {
        val data = StatuslineData(cost1d = 1.0, cost7d = 2.0, cost30d = 3.0, burnRateCentsPerHour = 210.0)
        val text = buildStatusText(HealthState.GREEN, data, StatusBarMode.COST, showBurnRate = true)
        assertTrue(text.endsWith(" · \$2.10/hr"))
    }

    @Test
    fun `buildStatusText omits burn rate when showBurnRate is false`() {
        val data = StatuslineData(cost1d = 1.0, cost7d = 2.0, cost30d = 3.0, burnRateCentsPerHour = 210.0)
        val text = buildStatusText(HealthState.GREEN, data, StatusBarMode.COST, showBurnRate = false)
        assertFalse(text.contains("/hr"))
    }

    @Test
    fun `buildStatusText showBurnRate gracefully handles null rate`() {
        val data = StatuslineData(cost1d = 1.0, cost7d = 2.0, cost30d = 3.0)
        val text = buildStatusText(HealthState.GREEN, data, StatusBarMode.COST, showBurnRate = true)
        assertFalse(text.contains("/hr"))
        assertEquals("budi · ${formatCostLine(resolveCosts(data))}", text)
    }

    @Test
    fun `buildStatusText showBurnRate ignored for non-healthy states`() {
        assertEquals("budi · setup", buildStatusText(HealthState.FIRST_RUN, null, showBurnRate = true))
        assertEquals("budi · offline", buildStatusText(HealthState.RED, null, showBurnRate = true))
        assertEquals("budi", buildStatusText(HealthState.GRAY, null, showBurnRate = true))
    }

    @Test
    fun `buildTooltip includes active session line when data present`() {
        val tenMinAgo =
            java.time.Instant
                .now()
                .minus(java.time.Duration.ofMinutes(10))
                .toString()
        val data =
            StatuslineData(
                cost1d = 1.0,
                cost7d = 2.0,
                cost30d = 3.0,
                activeProvider = "copilot_chat",
                costActiveBlockCents = 42.0,
                activeBlockStartedAt = tenMinAgo,
                burnRateCentsPerHour = 210.0,
            )
        val tip = buildTooltip(HealthState.GREEN, data, DEFAULT_CLOUD_ENDPOINT)
        assertTrue(tip.contains("Active session: \$0.42"))
        assertTrue(tip.contains("\$2.10/hr"))
    }

    @Test
    fun `buildTooltip omits active session line when fields are null`() {
        val data = StatuslineData(cost1d = 1.0, cost7d = 2.0, cost30d = 3.0)
        val tip = buildTooltip(HealthState.GREEN, data, DEFAULT_CLOUD_ENDPOINT)
        assertFalse(tip.contains("Active session"))
    }
}

class BudiClientBreakdownAndPacingTest {
    // ── Per-provider breakdown ─────────────────────────────────────

    @Test
    fun `hasProviderBreakdown returns false when data is null or missing`() {
        assertFalse(hasProviderBreakdown(null))
        assertFalse(hasProviderBreakdown(StatuslineData()))
    }

    @Test
    fun `hasProviderBreakdown returns false with fewer than 2 non-zero providers`() {
        assertFalse(hasProviderBreakdown(StatuslineData(breakdownByProvider = emptyMap())))
        assertFalse(
            hasProviderBreakdown(
                StatuslineData(breakdownByProvider = mapOf("claude_code" to 32.10)),
            ),
        )
        assertFalse(
            hasProviderBreakdown(
                StatuslineData(breakdownByProvider = mapOf("claude_code" to 32.10, "cursor" to 0.0)),
            ),
        )
    }

    @Test
    fun `hasProviderBreakdown returns true with 2 or more non-zero providers`() {
        assertTrue(
            hasProviderBreakdown(
                StatuslineData(
                    breakdownByProvider = mapOf("claude_code" to 32.10, "cursor" to 11.50),
                ),
            ),
        )
    }

    @Test
    fun `hasProviderBreakdown ignores non-finite values`() {
        assertFalse(
            hasProviderBreakdown(
                StatuslineData(
                    breakdownByProvider = mapOf("claude_code" to 32.10, "cursor" to Double.NaN),
                ),
            ),
        )
    }

    @Test
    fun `formatProviderBreakdown renders sorted descending by cost`() {
        val breakdown = mapOf("cursor" to 11.50, "claude_code" to 32.10, "copilot_chat" to 4.50)
        val lines = formatProviderBreakdown(breakdown)
        assertEquals("By provider (30d):", lines[0])
        assertTrue(lines[1].contains("Claude Code"))
        assertTrue(lines[1].contains("$32.10"))
        assertTrue(lines[2].contains("Cursor"))
        assertTrue(lines[2].contains("$11.50"))
        assertTrue(lines[3].contains("Copilot Chat"))
        assertTrue(lines[3].contains("$4.50"))
    }

    @Test
    fun `formatProviderBreakdown returns empty for fewer than 2 providers`() {
        assertTrue(formatProviderBreakdown(mapOf("claude_code" to 32.10)).isEmpty())
        assertTrue(formatProviderBreakdown(emptyMap()).isEmpty())
    }

    @Test
    fun `formatProviderBreakdown pads names to align cost column`() {
        val breakdown = mapOf("cursor" to 11.50, "copilot_chat" to 4.50)
        val lines = formatProviderBreakdown(breakdown)
        val cursorLine = lines.first { it.contains("Cursor") }
        val copilotLine = lines.first { it.contains("Copilot Chat") }
        val cursorCostIdx = cursorLine.indexOf('$')
        val copilotCostIdx = copilotLine.indexOf('$')
        assertEquals(cursorCostIdx, copilotCostIdx)
    }

    @Test
    fun `buildTooltip includes provider breakdown when 2+ providers present`() {
        val data =
            StatuslineData(
                cost1d = 1.0,
                cost7d = 2.0,
                cost30d = 3.0,
                breakdownByProvider = mapOf("claude_code" to 32.10, "cursor" to 11.50, "copilot_chat" to 4.50),
            )
        val tip = buildTooltip(HealthState.GREEN, data, DEFAULT_CLOUD_ENDPOINT)
        assertTrue(tip.contains("By provider (30d):"))
        assertTrue(tip.contains("Claude Code"))
        assertTrue(tip.contains("$32.10"))
        assertTrue(tip.contains("Cursor"))
        assertTrue(tip.contains("$11.50"))
    }

    @Test
    fun `buildTooltip omits provider breakdown when single provider`() {
        val data =
            StatuslineData(
                cost1d = 1.0,
                breakdownByProvider = mapOf("claude_code" to 32.10),
            )
        val tip = buildTooltip(HealthState.GREEN, data, DEFAULT_CLOUD_ENDPOINT)
        assertFalse(tip.contains("By provider"))
    }

    @Test
    fun `buildTooltip omits provider breakdown when absent`() {
        val data = StatuslineData(cost1d = 1.0, cost7d = 2.0, cost30d = 3.0)
        val tip = buildTooltip(HealthState.GREEN, data, DEFAULT_CLOUD_ENDPOINT)
        assertFalse(tip.contains("By provider"))
    }

    // ── Billing-cycle pacing ───────────────────────────────────────

    @Test
    fun `spendPacingHint returns null below 130`() {
        assertNull(spendPacingHint(0.0))
        assertNull(spendPacingHint(45.0))
        assertNull(spendPacingHint(130.0))
        assertNull(spendPacingHint(Double.NaN))
    }

    @Test
    fun `spendPacingHint returns yellow above 130`() {
        assertEquals("⚡ ", spendPacingHint(130.1))
        assertEquals("⚡ ", spendPacingHint(175.0))
    }

    @Test
    fun `spendPacingHint returns red above 175`() {
        assertEquals("⚠ ", spendPacingHint(175.1))
        assertEquals("⚠ ", spendPacingHint(200.0))
    }

    @Test
    fun `formatPacingLine returns null when fields are missing`() {
        assertNull(formatPacingLine(null))
        assertNull(formatPacingLine(StatuslineData()))
        assertNull(formatPacingLine(StatuslineData(spendPacePercent = 45.0)))
        assertNull(formatPacingLine(StatuslineData(cycleElapsedPercent = 60.0)))
    }

    @Test
    fun `formatPacingLine returns null when values are non-finite`() {
        assertNull(
            formatPacingLine(
                StatuslineData(cycleElapsedPercent = Double.NaN, spendPacePercent = 45.0),
            ),
        )
        assertNull(
            formatPacingLine(
                StatuslineData(cycleElapsedPercent = 60.0, spendPacePercent = Double.POSITIVE_INFINITY),
            ),
        )
    }

    @Test
    fun `formatPacingLine renders both values`() {
        val data = StatuslineData(cycleElapsedPercent = 60.0, spendPacePercent = 45.0)
        assertEquals("Pacing: 60% through month, 45% of typical spend", formatPacingLine(data))
    }

    @Test
    fun `formatPacingLine rounds fractional values`() {
        val data = StatuslineData(cycleElapsedPercent = 60.4, spendPacePercent = 45.6)
        assertEquals("Pacing: 60% through month, 46% of typical spend", formatPacingLine(data))
    }

    @Test
    fun `formatPacingLine includes yellow hint when pace is hot`() {
        val data = StatuslineData(cycleElapsedPercent = 60.0, spendPacePercent = 150.0)
        val line = formatPacingLine(data)!!
        assertTrue(line.startsWith("⚡ "))
        assertTrue(line.contains("150% of typical spend"))
    }

    @Test
    fun `formatPacingLine includes red hint when pace is very hot`() {
        val data = StatuslineData(cycleElapsedPercent = 60.0, spendPacePercent = 200.0)
        val line = formatPacingLine(data)!!
        assertTrue(line.startsWith("⚠ "))
        assertTrue(line.contains("200% of typical spend"))
    }

    @Test
    fun `buildTooltip includes pacing line when data present`() {
        val data =
            StatuslineData(
                cost1d = 1.0,
                cost7d = 2.0,
                cost30d = 3.0,
                cycleElapsedPercent = 60.0,
                spendPacePercent = 45.0,
            )
        val tip = buildTooltip(HealthState.GREEN, data, DEFAULT_CLOUD_ENDPOINT)
        assertTrue(tip.contains("Pacing: 60% through month, 45% of typical spend"))
    }

    @Test
    fun `buildTooltip omits pacing line when data absent`() {
        val data = StatuslineData(cost1d = 1.0, cost7d = 2.0, cost30d = 3.0)
        val tip = buildTooltip(HealthState.GREEN, data, DEFAULT_CLOUD_ENDPOINT)
        assertFalse(tip.contains("Pacing:"))
    }

    @Test
    fun `buildTooltip includes both breakdown and pacing together`() {
        val data =
            StatuslineData(
                cost1d = 1.0,
                cost7d = 2.0,
                cost30d = 3.0,
                breakdownByProvider = mapOf("claude_code" to 32.10, "cursor" to 11.50),
                cycleElapsedPercent = 60.0,
                spendPacePercent = 45.0,
            )
        val tip = buildTooltip(HealthState.GREEN, data, DEFAULT_CLOUD_ENDPOINT)
        assertTrue(tip.contains("By provider (30d):"))
        assertTrue(tip.contains("Pacing: 60% through month, 45% of typical spend"))
        val breakdownIdx = tip.indexOf("By provider")
        val pacingIdx = tip.indexOf("Pacing:")
        assertTrue(breakdownIdx < pacingIdx)
    }
}

class BudiClientHttpTest {
    private lateinit var server: HttpServer
    private lateinit var baseUrl: String
    private val client = BudiClient()

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = null
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun stop() {
        server.stop(0)
    }

    private fun handle(
        path: String,
        status: Int,
        contentType: String?,
        body: ByteArray,
    ) {
        server.createContext(path) { exchange: HttpExchange ->
            if (contentType != null) {
                exchange.responseHeaders.add("Content-Type", contentType)
            }
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
    }

    @Test
    fun `fetchHealth parses canonical health response`() {
        handle(
            "/health",
            200,
            "application/json",
            """{"ok":true,"version":"8.4.2","api_version":1}""".toByteArray(),
        )
        val health = client.fetchHealth(baseUrl)
        assertNotNull(health)
        assertTrue(health.ok)
        assertEquals("8.4.2", health.version)
        assertEquals(1, health.apiVersion)
    }

    @Test
    fun `fetchHealth returns null on non-2xx`() {
        handle("/health", 500, "application/json", "{}".toByteArray())
        assertNull(client.fetchHealth(baseUrl))
    }

    @Test
    fun `fetchHealth returns null on wrong content-type`() {
        handle("/health", 200, "text/html", "<html>nope</html>".toByteArray())
        assertNull(client.fetchHealth(baseUrl))
    }

    @Test
    fun `fetchHealth returns null on body too large`() {
        val large = ByteArray(80 * 1024) { 'x'.code.toByte() }
        handle("/health", 200, "application/json", large)
        assertNull(client.fetchHealth(baseUrl))
    }

    @Test
    fun `fetchHealth returns null when daemon unreachable`() {
        // No handler registered; connection refused / 404 path.
        assertNull(client.fetchHealth("http://127.0.0.1:1"))
    }

    @Test
    fun `fetchStatusline sends surface=jetbrains by default`() {
        var capturedQuery: String? = null
        server.createContext("/analytics/statusline") { exchange: HttpExchange ->
            capturedQuery = exchange.requestURI.query
            val body = """{"cost_1d":1.5,"cost_7d":4.0,"cost_30d":12.0,"active_provider":"copilot_chat"}"""
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.length.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        val data = client.fetchStatusline(baseUrl)
        assertNotNull(data)
        assertEquals(1.5, data.cost1d!!, 1e-9)
        assertEquals("copilot_chat", data.activeProvider)
        assertEquals("surface=jetbrains", capturedQuery)
    }

    @Test
    fun `fetchSources parses canonical response with surface filter`() {
        var capturedQuery: String? = null
        server.createContext("/health/sources") { exchange: HttpExchange ->
            capturedQuery = exchange.requestURI.query
            val body = """{"surface":"jetbrains","paths":["/p/one","/p/two"]}"""
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.length.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        val sources = client.fetchSources(baseUrl)
        assertNotNull(sources)
        assertEquals("jetbrains", sources.surface)
        assertEquals(listOf("/p/one", "/p/two"), sources.paths)
        assertEquals("surface=jetbrains", capturedQuery)
    }

    @Test
    fun `fetchSources omits surface when includeOtherSurfaces`() {
        var capturedQuery: String? = "<unset>"
        server.createContext("/health/sources") { exchange: HttpExchange ->
            capturedQuery = exchange.requestURI.query
            val body = """{"paths":[]}"""
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.length.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        client.fetchSources(baseUrl, includeOtherSurfaces = true)
        assertNull(capturedQuery)
    }

    @Test
    fun `fetchSources returns null when endpoint missing on older daemon`() {
        // No handler registered for /health/sources — daemon returns 404.
        // This is the v0.1 "endpoint not yet landed" path; renderer
        // collapses into the quiet empty state.
        assertNull(client.fetchSources(baseUrl))
    }

    @Test
    fun `fetchSources returns null when daemon offline`() {
        assertNull(client.fetchSources("http://127.0.0.1:1"))
    }

    @Test
    fun `fetchStatusline parses quota fields when present`() {
        server.createContext("/analytics/statusline") { exchange: HttpExchange ->
            val body =
                """{"cost_1d":1.5,"quota_used_percent":67.0,"quota_resets_at":"Jun 1"}"""
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.length.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        val data = client.fetchStatusline(baseUrl)
        assertNotNull(data)
        assertEquals(67.0, data.quotaUsedPercent!!, 1e-9)
        assertEquals("Jun 1", data.quotaResetsAt)
    }

    @Test
    fun `fetchStatusline leaves quota fields null when daemon omits them`() {
        server.createContext("/analytics/statusline") { exchange: HttpExchange ->
            val body = """{"cost_1d":1.5}"""
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.length.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        val data = client.fetchStatusline(baseUrl)
        assertNotNull(data)
        assertNull(data.quotaUsedPercent)
        assertNull(data.quotaResetsAt)
    }

    @Test
    fun `fetchStatusline parses active-session fields when present`() {
        server.createContext("/analytics/statusline") { exchange: HttpExchange ->
            val body =
                """{"cost_1d":1.5,""" +
                    """"cost_active_block_cents":42.0,""" +
                    """"active_block_started_at":"2025-01-01T00:00:00Z",""" +
                    """"burn_rate_cents_per_hour":210.0}"""
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.length.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        val data = client.fetchStatusline(baseUrl)
        assertNotNull(data)
        assertEquals(42.0, data.costActiveBlockCents!!, 1e-9)
        assertEquals("2025-01-01T00:00:00Z", data.activeBlockStartedAt)
        assertEquals(210.0, data.burnRateCentsPerHour!!, 1e-9)
    }

    @Test
    fun `fetchStatusline leaves active-session fields null when daemon omits them`() {
        server.createContext("/analytics/statusline") { exchange: HttpExchange ->
            val body = """{"cost_1d":1.5}"""
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.length.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        val data = client.fetchStatusline(baseUrl)
        assertNotNull(data)
        assertNull(data.costActiveBlockCents)
        assertNull(data.activeBlockStartedAt)
        assertNull(data.burnRateCentsPerHour)
    }

    @Test
    fun `fetchStatusline omits surface when includeOtherSurfaces`() {
        var capturedQuery: String? = "<unset>"
        server.createContext("/analytics/statusline") { exchange: HttpExchange ->
            capturedQuery = exchange.requestURI.query
            val body = "{}"
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.length.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        client.fetchStatusline(baseUrl, includeOtherSurfaces = true)
        assertNull(capturedQuery)
    }

    @Test
    fun `fetchStatusline parses breakdown and pacing fields when present`() {
        server.createContext("/analytics/statusline") { exchange: HttpExchange ->
            val body =
                """{"cost_1d":1.5,""" +
                    """"breakdown_by_provider":{"claude_code":32.10,"cursor":11.50},""" +
                    """"cycle_elapsed_percent":60.0,""" +
                    """"spend_pace_percent":45.0}"""
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.length.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        val data = client.fetchStatusline(baseUrl)
        assertNotNull(data)
        assertEquals(mapOf("claude_code" to 32.10, "cursor" to 11.50), data.breakdownByProvider)
        assertEquals(60.0, data.cycleElapsedPercent!!, 1e-9)
        assertEquals(45.0, data.spendPacePercent!!, 1e-9)
    }

    @Test
    fun `fetchStatusline leaves breakdown and pacing fields null when daemon omits them`() {
        server.createContext("/analytics/statusline") { exchange: HttpExchange ->
            val body = """{"cost_1d":1.5}"""
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.length.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        val data = client.fetchStatusline(baseUrl)
        assertNotNull(data)
        assertNull(data.breakdownByProvider)
        assertNull(data.cycleElapsedPercent)
        assertNull(data.spendPacePercent)
    }
}
