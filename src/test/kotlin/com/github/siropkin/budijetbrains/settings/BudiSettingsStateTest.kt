package com.github.siropkin.budijetbrains.settings

import com.github.siropkin.budijetbrains.daemon.DEFAULT_CLOUD_ENDPOINT
import com.github.siropkin.budijetbrains.daemon.DEFAULT_DAEMON_URL
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BudiSettingsStateTest {
    @Test
    fun `defaults match budi-cursor's package_json defaults`() {
        val s = BudiSettingsState()
        assertEquals(DEFAULT_DAEMON_URL, s.daemonUrl)
        assertEquals(DEFAULT_CLOUD_ENDPOINT, s.cloudEndpoint)
        assertEquals(DEFAULT_POLLING_INTERVAL_MS, s.pollingIntervalMs)
        assertFalse(s.includeOtherSurfaces)
        assertFalse(s.everSawDaemon)
    }

    @Test
    fun `MIN_POLLING_INTERVAL_MS is 3 seconds (matches budi-cursor's lower bound)`() {
        assertEquals(3_000, MIN_POLLING_INTERVAL_MS)
    }

    @Test
    fun `default polling interval is 15 seconds (matches budi-cursor's default)`() {
        assertEquals(15_000, DEFAULT_POLLING_INTERVAL_MS)
    }

    @Test
    fun `XmlSerializerUtil round-trip preserves all fields`() {
        val original =
            BudiSettingsState().apply {
                daemonUrl = "http://localhost:9999"
                cloudEndpoint = "https://staging.app.getbudi.dev"
                pollingIntervalMs = 30_000
                includeOtherSurfaces = true
                everSawDaemon = true
            }
        val element =
            com.intellij.util.xmlb.XmlSerializer
                .serialize(original)
        assertNotNull(element)
        val restored =
            com.intellij.util.xmlb.XmlSerializer
                .deserialize(element, BudiSettingsState::class.java)
        assertEquals(original.daemonUrl, restored.daemonUrl)
        assertEquals(original.cloudEndpoint, restored.cloudEndpoint)
        assertEquals(original.pollingIntervalMs, restored.pollingIntervalMs)
        assertEquals(original.includeOtherSurfaces, restored.includeOtherSurfaces)
        assertEquals(original.everSawDaemon, restored.everSawDaemon)
    }

    @Test
    fun `XmlSerializerUtil round-trip survives a missing field (forward compat)`() {
        // Simulate reading state written by an older plugin version that
        // didn't have `includeOtherSurfaces` yet — the field should fall
        // back to its default rather than throw.
        val element = org.jdom.Element("BudiSettings")
        element.addContent(
            org.jdom
                .Element("option")
                .setAttribute("name", "daemonUrl")
                .setAttribute("value", "http://127.0.0.1:7878"),
        )
        val restored =
            com.intellij.util.xmlb.XmlSerializer
                .deserialize(element, BudiSettingsState::class.java)
        assertEquals("http://127.0.0.1:7878", restored.daemonUrl)
        assertFalse(restored.includeOtherSurfaces)
        assertEquals(DEFAULT_POLLING_INTERVAL_MS, restored.pollingIntervalMs)
    }

    @Test
    fun `pollingIntervalMs floor enforced by Configurable, not by state class itself`() {
        // The state class is a dumb data holder — direct mutation does
        // not validate. Validation happens in BudiConfigurable.apply().
        // This test pins that contract so we don't accidentally start
        // throwing from the setter and break XML deserialization paths.
        val s = BudiSettingsState()
        s.pollingIntervalMs = 500 // below MIN_POLLING_INTERVAL_MS
        assertTrue(s.pollingIntervalMs == 500)
    }
}
