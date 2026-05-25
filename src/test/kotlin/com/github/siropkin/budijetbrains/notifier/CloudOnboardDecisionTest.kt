package com.github.siropkin.budijetbrains.notifier

import com.github.siropkin.budijetbrains.daemon.DaemonHealth
import com.github.siropkin.budijetbrains.daemon.MIN_API_VERSION
import org.junit.Test
import kotlin.test.assertEquals

class CloudOnboardDecisionTest {
    private val floor = MIN_API_VERSION

    private fun health(
        apiVersion: Int = floor,
        cloudLinked: Boolean? = null,
    ) = DaemonHealth(ok = true, version = "1.0.0", apiVersion = apiVersion, cloudLinked = cloudLinked)

    @Test
    fun `null health is a no-op`() {
        val d = evaluateCloudOnboardPrompt(health = null, sessionShown = false, persistentDismiss = false)
        assertEquals(CloudOnboardDecision(showPrompt = false), d)
    }

    @Test
    fun `stale daemon suppresses prompt`() {
        val d =
            evaluateCloudOnboardPrompt(
                health = health(apiVersion = floor - 1, cloudLinked = false),
                sessionShown = false,
                persistentDismiss = false,
            )
        assertEquals(CloudOnboardDecision(showPrompt = false), d)
    }

    @Test
    fun `cloud_linked null suppresses prompt`() {
        val d =
            evaluateCloudOnboardPrompt(
                health = health(cloudLinked = null),
                sessionShown = false,
                persistentDismiss = false,
            )
        assertEquals(CloudOnboardDecision(showPrompt = false), d)
    }

    @Test
    fun `cloud_linked true suppresses prompt`() {
        val d =
            evaluateCloudOnboardPrompt(
                health = health(cloudLinked = true),
                sessionShown = false,
                persistentDismiss = false,
            )
        assertEquals(CloudOnboardDecision(showPrompt = false), d)
    }

    @Test
    fun `cloud_linked false fires prompt`() {
        val d =
            evaluateCloudOnboardPrompt(
                health = health(cloudLinked = false),
                sessionShown = false,
                persistentDismiss = false,
            )
        assertEquals(CloudOnboardDecision(showPrompt = true), d)
    }

    @Test
    fun `persistent dismiss silences prompt`() {
        val d =
            evaluateCloudOnboardPrompt(
                health = health(cloudLinked = false),
                sessionShown = false,
                persistentDismiss = true,
            )
        assertEquals(CloudOnboardDecision(showPrompt = false), d)
    }

    @Test
    fun `session already shown silences prompt`() {
        val d =
            evaluateCloudOnboardPrompt(
                health = health(cloudLinked = false),
                sessionShown = true,
                persistentDismiss = false,
            )
        assertEquals(CloudOnboardDecision(showPrompt = false), d)
    }

    @Test
    fun `both dismiss and session shown silence prompt`() {
        val d =
            evaluateCloudOnboardPrompt(
                health = health(cloudLinked = false),
                sessionShown = true,
                persistentDismiss = true,
            )
        assertEquals(CloudOnboardDecision(showPrompt = false), d)
    }
}
