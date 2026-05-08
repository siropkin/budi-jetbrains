package com.github.siropkin.budijetbrains.daemon

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class BudiClientPureLogicTest {

    @Test
    fun `resolveCosts prefers canonical fields over 8_0 aliases`() {
        val data = StatuslineData(
            cost1d = 1.23, cost7d = 4.56, cost30d = 7.89,
            todayCost = 99.0, weekCost = 99.0, monthCost = 99.0,
        )
        val resolved = resolveCosts(data)
        assertEquals(1.23, resolved.cost1d, 1e-9)
        assertEquals(4.56, resolved.cost7d, 1e-9)
        assertEquals(7.89, resolved.cost30d, 1e-9)
    }

    @Test
    fun `resolveCosts falls back to legacy aliases when canonical absent`() {
        val data = StatuslineData(todayCost = 2.0, weekCost = 10.0, monthCost = 40.0)
        val resolved = resolveCosts(data)
        assertEquals(2.0, resolved.cost1d, 1e-9)
        assertEquals(10.0, resolved.cost7d, 1e-9)
        assertEquals(40.0, resolved.cost30d, 1e-9)
    }

    @Test
    fun `resolveCosts defaults missing to zero`() {
        val r = resolveCosts(StatuslineData())
        assertEquals(0.0, r.cost1d, 1e-9)
        assertEquals(0.0, r.cost7d, 1e-9)
        assertEquals(0.0, r.cost30d, 1e-9)
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
    fun `formatProviderName covers canonical wire names`() {
        assertEquals("Copilot Chat", formatProviderName("copilot_chat"))
        assertEquals("Claude Code", formatProviderName("claude_code"))
        assertEquals("Codex", formatProviderName("codex"))
        // Unknown wire names round-trip via underscore-to-space title-case.
        assertEquals("Junie Chat", formatProviderName("junie_chat"))
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
        val url = buildStatuslineUrl(
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
    fun `isAllowedCloudEndpoint accepts getbudi_dev https subdomains only`() {
        assertTrue(isAllowedCloudEndpoint("https://app.getbudi.dev"))
        assertTrue(isAllowedCloudEndpoint("https://staging.app.getbudi.dev"))
        assertTrue(isAllowedCloudEndpoint("https://getbudi.dev"))
        assertFalse(isAllowedCloudEndpoint("http://app.getbudi.dev"))
        assertFalse(isAllowedCloudEndpoint("https://app.getbudi.dev.attacker.example"))
        assertFalse(isAllowedCloudEndpoint("https://attacker@app.getbudi.dev"))
        assertFalse(isAllowedCloudEndpoint("not a url"))
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

    private fun handle(path: String, status: Int, contentType: String?, body: ByteArray) {
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
            "/health", 200, "application/json",
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
}
