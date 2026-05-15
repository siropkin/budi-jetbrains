package com.github.siropkin.budijetbrains.state

import com.github.siropkin.budijetbrains.daemon.DaemonHealth
import com.github.siropkin.budijetbrains.daemon.HealthState
import com.github.siropkin.budijetbrains.daemon.MIN_API_VERSION
import com.github.siropkin.budijetbrains.daemon.StatuslineData
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Direct unit tests for [BudiAppState]. The class is application-scoped
 * via `@Service`, but the constructor is parameterless and no method
 * touches `ApplicationManager`, so each test gets a fresh instance —
 * keeps the suite IDE-free and parallelizable.
 */
class BudiAppStateTest {
    private val healthy = DaemonHealth(ok = true, version = "8.4.2", apiVersion = MIN_API_VERSION)
    private val trafficked = StatuslineData(cost1d = 1.0, cost7d = 2.0, cost30d = 3.0)
    private val empty = StatuslineData()

    @Test
    fun `defaults to GRAY with no cached statusline`() {
        val s = BudiAppState()
        assertEquals(HealthState.GRAY, s.lastState)
        assertNull(s.lastStatusline)
    }

    @Test
    fun `update caches the statusline reference and derived state`() {
        val s = BudiAppState()
        s.update(healthy, trafficked, everSawDaemon = true)
        assertSame(trafficked, s.lastStatusline)
        assertEquals(HealthState.GREEN, s.lastState)
    }

    @Test
    fun `update routes null health + first-run latch through FIRST_RUN`() {
        val s = BudiAppState()
        s.update(health = null, statusline = null, everSawDaemon = false)
        assertEquals(HealthState.FIRST_RUN, s.lastState)
    }

    @Test
    fun `update routes null health + ever-saw-daemon through RED`() {
        val s = BudiAppState()
        s.update(health = null, statusline = null, everSawDaemon = true)
        assertEquals(HealthState.RED, s.lastState)
    }

    @Test
    fun `update routes healthy daemon + empty statusline through YELLOW`() {
        val s = BudiAppState()
        s.update(healthy, empty, everSawDaemon = true)
        assertEquals(HealthState.YELLOW, s.lastState)
    }

    @Test
    fun `successive updates overwrite, not accumulate`() {
        val s = BudiAppState()
        s.update(healthy, trafficked, everSawDaemon = true)
        s.update(healthy, empty, everSawDaemon = true)
        assertSame(empty, s.lastStatusline)
        assertEquals(HealthState.YELLOW, s.lastState)
    }

    @Test
    fun `addListener fires on every update`() {
        val s = BudiAppState()
        val fired = AtomicInteger(0)
        s.addListener { fired.incrementAndGet() }
        s.update(healthy, trafficked, everSawDaemon = true)
        s.update(healthy, empty, everSawDaemon = true)
        s.update(null, null, everSawDaemon = true)
        assertEquals(3, fired.get())
    }

    @Test
    fun `multiple listeners all fire`() {
        val s = BudiAppState()
        val a = AtomicInteger(0)
        val b = AtomicInteger(0)
        s.addListener { a.incrementAndGet() }
        s.addListener { b.incrementAndGet() }
        s.update(healthy, trafficked, everSawDaemon = true)
        assertEquals(1, a.get())
        assertEquals(1, b.get())
    }

    @Test
    fun `removeListener stops further notifications`() {
        val s = BudiAppState()
        val fired = AtomicInteger(0)
        val listener: () -> Unit = { fired.incrementAndGet() }
        s.addListener(listener)
        s.update(healthy, trafficked, everSawDaemon = true)
        s.removeListener(listener)
        s.update(healthy, empty, everSawDaemon = true)
        assertEquals(1, fired.get())
    }

    @Test
    fun `removeListener for an unregistered callback is a no-op`() {
        val s = BudiAppState()
        // Should not throw.
        s.removeListener { /* never registered */ }
        s.update(healthy, trafficked, everSawDaemon = true)
    }

    @Test
    fun `listener that mutates the listener list during fanout does not throw`() {
        // Pins the CopyOnWriteArrayList safety contract called out in the
        // class kdoc: add/remove during a notify must not throw
        // ConcurrentModificationException.
        val s = BudiAppState()
        val fired = AtomicInteger(0)
        val late: () -> Unit = { fired.incrementAndGet() }
        s.addListener {
            // Adding during fanout is allowed; the new listener doesn't
            // need to fire for the in-progress notification per the kdoc
            // ("no de-duplication and no fan-out ordering guarantee").
            s.addListener(late)
            s.removeListener(late)
        }
        s.update(healthy, trafficked, everSawDaemon = true)
        // The first listener ran; the late one was added then removed
        // before the next update, so the counter stays at 0.
        assertEquals(0, fired.get())
    }

    @Test
    fun `listeners observe the post-update snapshot, not the previous one`() {
        // Listener fires after lastStatusline / lastState are written,
        // so subscribers see the new reading — important for the widget
        // repaint contract.
        val s = BudiAppState()
        var observedState: HealthState? = null
        var observedStatusline: StatuslineData? = null
        s.addListener {
            observedState = s.lastState
            observedStatusline = s.lastStatusline
        }
        s.update(healthy, trafficked, everSawDaemon = true)
        assertEquals(HealthState.GREEN, observedState)
        assertSame(trafficked, observedStatusline)
    }
}
