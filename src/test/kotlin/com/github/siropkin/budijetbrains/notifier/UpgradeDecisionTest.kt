package com.github.siropkin.budijetbrains.notifier

import com.github.siropkin.budijetbrains.daemon.MIN_API_VERSION
import org.junit.Test
import kotlin.test.assertEquals

class UpgradeDecisionTest {

    private val floor = MIN_API_VERSION

    @Test
    fun `null health is a no-op`() {
        val d = evaluateUpgradePrompt(
            currentApiVersion = null,
            previousApiVersion = 0,
            sessionShown = false,
            persistentSuppress = false,
        )
        assertEquals(UpgradeDecision(false, false, false), d)
    }

    @Test
    fun `healthy daemon suppresses prompt and resets per-session latch`() {
        val d = evaluateUpgradePrompt(
            currentApiVersion = floor,
            previousApiVersion = floor,
            sessionShown = true,
            persistentSuppress = false,
        )
        assertEquals(UpgradeDecision(false, true, false), d)
    }

    @Test
    fun `recovery from stale clears the persistent suppress`() {
        val d = evaluateUpgradePrompt(
            currentApiVersion = floor,
            previousApiVersion = floor - 1,
            sessionShown = true,
            persistentSuppress = true,
        )
        assertEquals(UpgradeDecision(false, true, true), d)
    }

    @Test
    fun `staying healthy does not clear an unrelated persistent suppress`() {
        val d = evaluateUpgradePrompt(
            currentApiVersion = floor + 1,
            previousApiVersion = floor + 1,
            sessionShown = false,
            persistentSuppress = true,
        )
        assertEquals(UpgradeDecision(false, true, false), d)
    }

    @Test
    fun `first stale poll fires the prompt`() {
        val d = evaluateUpgradePrompt(
            currentApiVersion = floor - 1,
            previousApiVersion = floor,
            sessionShown = false,
            persistentSuppress = false,
        )
        assertEquals(UpgradeDecision(true, false, false), d)
    }

    @Test
    fun `repeat stale poll within the same session is silent`() {
        val d = evaluateUpgradePrompt(
            currentApiVersion = floor - 1,
            previousApiVersion = floor - 1,
            sessionShown = true,
            persistentSuppress = false,
        )
        assertEquals(UpgradeDecision(false, false, false), d)
    }

    @Test
    fun `persistent suppress silences even the first stale poll`() {
        val d = evaluateUpgradePrompt(
            currentApiVersion = floor - 1,
            previousApiVersion = floor,
            sessionShown = false,
            persistentSuppress = true,
        )
        assertEquals(UpgradeDecision(false, false, false), d)
    }

    @Test
    fun `recovery transition from stale clears suppress only on the upward edge`() {
        // First stale poll: prompt shows (suppress not yet set).
        val a = evaluateUpgradePrompt(floor - 1, floor, sessionShown = false, persistentSuppress = false)
        assertEquals(UpgradeDecision(true, false, false), a)

        // User clicks "Don't show again" → caller flips suppress.
        // Next stale poll: silent.
        val b = evaluateUpgradePrompt(floor - 1, floor - 1, sessionShown = true, persistentSuppress = true)
        assertEquals(UpgradeDecision(false, false, false), b)

        // Daemon recovers → suppress clears, session latch resets.
        val c = evaluateUpgradePrompt(floor, floor - 1, sessionShown = true, persistentSuppress = true)
        assertEquals(UpgradeDecision(false, true, true), c)

        // Daemon goes stale again → prompt fires fresh (caller has cleared suppress).
        val d = evaluateUpgradePrompt(floor - 1, floor, sessionShown = false, persistentSuppress = false)
        assertEquals(UpgradeDecision(true, false, false), d)
    }
}
