package ai.rever.boss.plugin.dynamic.docker

import java.util.concurrent.ConcurrentHashMap

/**
 * The container names Run has promised but Docker has not produced yet.
 *
 * Two readers, one writer. [DockerActions.buildAndRun] reserves a name when it hands the build
 * command to a terminal; [DockerActions.onContainersChanged] claims it when the container turns
 * up, and opens its service tab. Until one of those happens the name is also **taken** for
 * [nextFreeName], because `docker run --name` fails outright on a collision.
 *
 * A reservation therefore has to be given back when the command that would have created it is
 * interrupted - the shared terminal tab types over whatever is running, so a second Run kills
 * the first (boss-plugin-docker#2). Without [release] the name stayed reserved for the whole
 * window and the next Run handed out `app-2`, then `app-3`, for containers that never existed.
 *
 * The window is the backstop for everything a release cannot see: a build that fails, a
 * `docker run` that is refused, a BOSS restart mid-build.
 */
internal class AutoOpenReservations(
    private val windowMs: Long,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val reservations = ConcurrentHashMap<String, Long>()

    val isEmpty: Boolean get() = reservations.isEmpty()

    /** The names currently spoken for, for [nextFreeName]. */
    fun names(): Set<String> = reservations.keys.toSet()

    fun reserve(name: String) {
        reservations[name] = now() + windowMs
    }

    /**
     * Give [name] back because the command that would have created it is not going to.
     *
     * A no-op for a name that was never reserved, which is the ordinary case: most commands
     * (compose, a plain `docker build`, anything the user typed) reserve nothing.
     */
    fun release(name: String) {
        reservations.remove(name)
    }

    /** True once, when the container this reservation was waiting for appears. */
    fun claim(name: String): Boolean = reservations.remove(name) != null

    /** Drop reservations whose window has passed. */
    fun pruneExpired() {
        val cutoff = now()
        reservations.entries.removeAll { it.value < cutoff }
    }
}

/**
 * A container name not in [taken], derived from [base].
 *
 * `docker run --name` fails outright on a collision, and silently `rm -f`ing the old container
 * would destroy state the user may still want, so a clash becomes `name-2`, `name-3`, and so on.
 */
internal fun nextFreeName(base: String, taken: Set<String>): String {
    val cleaned = base.lowercase().replace(Regex("[^a-z0-9_.-]+"), "-").trim('-').ifBlank { "app" }
    if (cleaned !in taken) return cleaned
    var n = 2
    while ("$cleaned-$n" in taken) n++
    return "$cleaned-$n"
}
