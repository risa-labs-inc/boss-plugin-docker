package ai.rever.boss.plugin.dynamic.docker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The name a Run reserves, and when it is given back (boss-plugin-docker#2).
 *
 * A reservation keeps `docker run --name` from colliding with a container that is on its way.
 * It is also what made an interrupted build cost a name: the shared terminal tab types over
 * whatever is running, so a second Run kills the first, and without a release the dead build's
 * name stayed taken for the full window - the next Run handed out `app-2`, then `app-3`, for
 * containers that never existed.
 */
class AutoOpenReservationsTest {
    private var clock = 1_000L
    private fun reservations(windowMs: Long = 10_000L) = AutoOpenReservations(windowMs) { clock }

    @Test
    fun `a reserved name is taken until its container appears`() {
        val reservations = reservations()
        reservations.reserve("app")

        assertEquals(setOf("app"), reservations.names())
        assertEquals("app-2", nextFreeName("app", reservations.names()))

        assertTrue(reservations.claim("app"), "the container we were waiting for")
        assertFalse(reservations.claim("app"), "a second sighting must not open a second tab")
        assertTrue(reservations.isEmpty)
        assertEquals("app", nextFreeName("app", reservations.names()))
    }

    @Test
    fun `a superseded build gives its name back instead of burning it`() {
        val reservations = reservations()
        reservations.reserve("app")

        // The second Run interrupts the first in the shared tab: that container is not coming.
        reservations.release("app")

        assertTrue(reservations.isEmpty)
        assertEquals("app", nextFreeName("app", reservations.names()), "the next Run must reuse the name")
    }

    @Test
    fun `releasing a name nobody reserved is harmless`() {
        val reservations = reservations()
        reservations.reserve("app")

        // Most commands - compose, a plain build, anything typed - reserve nothing.
        reservations.release("other")

        assertEquals(setOf("app"), reservations.names())
    }

    @Test
    fun `the window is the backstop for a build that simply failed`() {
        val reservations = reservations(windowMs = 10_000L)
        reservations.reserve("app")

        clock += 9_999
        reservations.pruneExpired()
        assertEquals(setOf("app"), reservations.names(), "still inside the window")

        clock += 2
        reservations.pruneExpired()
        assertTrue(reservations.isEmpty, "past the window, the name is free again")
    }

    @Test
    fun `a name is derived from the artifact and stepped on collision`() {
        assertEquals("my-app", nextFreeName("My App", emptySet()))
        assertEquals("app", nextFreeName("---", emptySet()), "a name that cleans away falls back")
        assertEquals("app-2", nextFreeName("app", setOf("app")))
        assertEquals("app-4", nextFreeName("app", setOf("app", "app-2", "app-3")))
        assertEquals("keep_dots.and-dashes", nextFreeName("keep_dots.and-dashes", emptySet()))
    }
}
