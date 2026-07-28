package ai.rever.boss.plugin.dynamic.docker

import ai.rever.boss.plugin.api.ActiveTabData
import ai.rever.boss.plugin.api.TabSplitMode
import ai.rever.boss.plugin.api.TerminalTabPluginAPI
import ai.rever.boss.plugin.tab.terminal.TerminalTabInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.net.ServerSocket

/** Where a launched terminal tab should be placed. */
enum class OpenLocation { NEW_TAB, SPLIT_RIGHT, SPLIT_DOWN }

/**
 * A command awaiting delivery to the plugin's terminal tab.
 *
 * Carries [id] and [title] as well as the command so the consumer can still open a
 * BOSS terminal tab if the terminal it was queued for has gone away — the fallback
 * [DockerActions.openTerminal] documents is otherwise unreachable once that function
 * has returned true.
 */
private data class TerminalCommand(
    val id: String,
    val title: String,
    val command: String,
    val workingDir: String?,
)

/**
 * The build/run split.
 *
 * **Builds go to a BossTerm terminal tab.** `docker build` and `compose up
 * --build` are interactive, long, and already have excellent output — BuildKit's
 * live progress, full ANSI, and a Ctrl-C the user knows. Re-rendering that inside
 * a Compose pane would be strictly worse, so the plugin hands those to a terminal.
 *
 * **Long-lived containers are supervised by the plugin.** Once a container is up,
 * the events stream notices it, the sidebar lists it, and its service tab streams
 * `docker logs -f`.
 */
class DockerActions(private val services: DockerServices) {

    /** Containers we launched and want to auto-open, name → deadline (epoch ms). */
    private val pendingAutoOpen = LinkedHashMap<String, Long>()

    /**
     * The terminal tab this plugin owns, and whether anything has been typed into it.
     *
     * Written **only** by [runTerminalCommands], of which there is exactly one, so no
     * atomicity is needed. `ownedTerminal` is `@Volatile` because [runInExistingTerminal]
     * reads it from the caller's thread on the accept path; a stale `null` there only
     * costs one command a BOSS tab it could have shared, and the consumer re-validates
     * through [liveOwnedTerminal] regardless.
     *
     * Kept here rather than on [DockerServices] so that guarantee is structural — a
     * public field on the shared services object is one any future call site can write
     * without going through the queue.
     *
     * Session-scoped and never persisted; tab ids don't survive a restart.
     */
    @Volatile
    private var ownedTerminal: CommandTerminal? = null


    /**
     * One command per send, drained in order by [runTerminalCommands].
     *
     * Unbounded, which is what makes a dead consumer dangerous rather than merely
     * broken: `trySend` keeps succeeding, so [runInExistingTerminal] keeps returning
     * true and every later command is reported as launched while nothing is ever typed.
     * Hence the guard in the consumer loop and in [fallBackToBossTab], and [dispose]
     * closing the channel.
     */
    private val terminalCommands = Channel<TerminalCommand>(Channel.UNLIMITED)

    /**
     * Start draining [terminalCommands]. Called from [DockerServices.start].
     *
     * Not an `init` block: this class is constructed from a `DockerServices` property
     * initializer, so launching there would depend on `scope` happening to be declared
     * above `actions` — reorder those two lines and the plugin fails to activate with an
     * NPE and no obvious cause. An explicit call makes the ordering a fact rather than
     * an accident.
     */
    fun start() {
        services.scope.launch { runTerminalCommands() }
    }

    /**
     * Stop accepting commands.
     *
     * Closing matters as much as cancelling the scope: on a closed channel `trySend`
     * fails, so [runInExistingTerminal] returns false and a command issued during
     * teardown falls through to the BOSS-tab path instead of being accepted by a
     * consumer that will never run and reported as a success.
     */
    fun dispose() {
        terminalCommands.close()
        // Named rather than dropped in silence. Anything still queued was already
        // reported as launched, so a note is the difference between a debuggable
        // teardown and a mystery.
        val dropped = generateSequence { terminalCommands.tryReceive().getOrNull() }.toList()
        if (dropped.isNotEmpty()) {
            System.err.println(
                "[Docker] Dropped ${dropped.size} queued command(s) on dispose: " +
                    dropped.joinToString("; ") { it.command },
            )
        }
    }

    // -------------------------------------------------------------- run flow

    /**
     * Build [artifact] and run it, publishing [hostPort] → [containerPort].
     *
     * @return the container name that will be created, or null if it couldn't launch.
     */
    fun buildAndRun(
        artifact: ProjectArtifact,
        hostPort: Int,
        containerPort: Int,
        location: OpenLocation = OpenLocation.NEW_TAB,
    ): String? {
        val dockerfile = artifact.file
        val contextDir = dockerfile.parentFile ?: return null
        val tag = "${artifact.suggestedName}:boss"
        val name = uniqueContainerName(artifact.suggestedName)

        val command = listOf(
            "docker build -t ${q(tag)} -f ${q(dockerfile.absolutePath)} ${q(contextDir.absolutePath)}",
            // Loopback-only publish: a dev container should not be reachable from
            // the LAN just because someone clicked Run.
            "docker run -d --name ${q(name)} -p 127.0.0.1:$hostPort:$containerPort ${q(tag)}",
            "echo \"Serving on http://localhost:$hostPort\"",
        ).joinToString(" && ")

        val launched = openTerminal(
            id = "docker-build-$name-${System.currentTimeMillis()}",
            title = "Build: ${artifact.suggestedName}",
            command = command,
            workingDir = contextDir.absolutePath,
            location = location,
        )
        if (!launched) return null

        rememberPort(artifact, hostPort)
        if (services.autoOpenServiceTab.value) {
            pendingAutoOpen[name] = System.currentTimeMillis() + AUTO_OPEN_WINDOW_MS
        }
        return name
    }

    /** Run an image that is already built. */
    fun runImage(
        image: ImageInfo,
        hostPort: Int?,
        containerPort: Int?,
        location: OpenLocation = OpenLocation.NEW_TAB,
    ): String? {
        val base = image.repository.substringAfterLast('/').ifBlank { "image" }
        val name = uniqueContainerName(base)
        val publish = if (hostPort != null && containerPort != null) {
            " -p 127.0.0.1:$hostPort:$containerPort"
        } else {
            ""
        }
        val command = buildString {
            append("docker run -d --name ${q(name)}$publish ${q(image.reference)}")
            if (hostPort != null) append(" && echo \"Serving on http://localhost:$hostPort\"")
        }
        val launched = openTerminal(
            id = "docker-run-$name-${System.currentTimeMillis()}",
            title = "Run: $base",
            command = command,
            workingDir = services.context.projectPath,
            location = location,
        )
        if (!launched) return null
        if (services.autoOpenServiceTab.value) {
            pendingAutoOpen[name] = System.currentTimeMillis() + AUTO_OPEN_WINDOW_MS
        }
        return name
    }

    fun composeUp(artifact: ProjectArtifact, location: OpenLocation = OpenLocation.NEW_TAB): Boolean {
        val file = artifact.file
        val dir = file.parentFile ?: return false
        return openTerminal(
            id = "docker-compose-up-${artifact.suggestedName}-${System.currentTimeMillis()}",
            title = "Compose: ${artifact.suggestedName}",
            // Detached: the plugin, not this terminal, owns the containers' lifetime.
            command = "docker compose -f ${q(file.absolutePath)} up --build -d",
            workingDir = dir.absolutePath,
            location = location,
        )
    }

    fun composeDown(project: ComposeProject, location: OpenLocation = OpenLocation.NEW_TAB): Boolean {
        val configFile = project.firstConfigFile
        val command = if (configFile.isNullOrBlank()) {
            "docker compose -p ${q(project.name)} down"
        } else {
            "docker compose -f ${q(configFile)} down"
        }
        return openTerminal(
            id = "docker-compose-down-${project.name}-${System.currentTimeMillis()}",
            title = "Compose down: ${project.name}",
            command = command,
            workingDir = configFile?.let { File(it).parent },
            location = location,
        )
    }

    /**
     * Run [command] in a terminal. Also used by the MCP tools.
     *
     * **Reuses one plugin-owned terminal tab** rather than opening a tab per
     * command. Every build, compose-up and run spawning its own top-level BOSS tab
     * fills the tab bar within minutes of ordinary use, and BossTerm already has a
     * tab strip built for this.
     *
     * Falls back to a new BOSS terminal tab when there is no live terminal to join at
     * all, or when the terminal-tab plugin isn't loaded. A terminal in this window is
     * preferred but not required — see [findTerminalHost].
     */
    fun openTerminal(
        id: String,
        title: String,
        command: String,
        workingDir: String?,
        location: OpenLocation = OpenLocation.NEW_TAB,
    ): Boolean {
        if (location == OpenLocation.NEW_TAB && runInExistingTerminal(id, title, command, workingDir)) {
            return true
        }

        val ops = services.context.splitViewOperations ?: run {
            services.toastError("No terminal available — run manually: $command")
            return false
        }
        val tabInfo = TerminalTabInfo(
            id = id,
            title = title,
            initialCommand = command,
            workingDirectory = workingDir,
        )
        when (location) {
            OpenLocation.NEW_TAB -> ops.openTab(tabInfo)
            OpenLocation.SPLIT_RIGHT -> ops.openTabInSplit(tabInfo, TabSplitMode.VERTICAL_SPLIT)
            OpenLocation.SPLIT_DOWN -> ops.openTabInSplit(tabInfo, TabSplitMode.HORIZONTAL_SPLIT)
        }
        return true
    }

    /**
     * Hand [command] to the terminal tab this plugin owns.
     *
     * Two things this must not do. Create a tab per command — that is the bug being
     * fixed. And type into a tab the plugin doesn't own: `sendCommand` writes to the
     * terminal's *active* tab, so reusing "whatever is focused" would inject a
     * `docker build` into whatever the user happens to be running there.
     *
     * Only the *decision* happens here: is there a terminal to join at all? That is a
     * `getPluginAPI` plus one `hasTerminalState` registry lookup per open tab — cheap,
     * but not free, and on a panel click it is the UI thread. Everything that touches the terminal is queued to [terminalCommands] and
     * performed by one consumer, because the delivery is a multi-step sequence
     * (interrupt, wait, type) that must not interleave with another command's — see
     * [runTerminalCommands]. A `true` return therefore means "accepted for delivery",
     * which is why the consumer, not this function, is what reports a failure.
     *
     * Wrapped whole rather than per call. A host whose terminal-tab plugin is absent or
     * older than the interface fails at *linkage* — `NoClassDefFoundError` on the
     * `TerminalTabPluginAPI::class.java` literal, `NoSuchMethodError` on a call — and
     * those are `Error`s thrown on entry, which a `runCatching` inside the method never
     * runs to catch. `runCatching` here catches `Throwable`, so a missing terminal
     * degrades to the BOSS-tab path instead of taking down build/run.
     */
    private fun runInExistingTerminal(
        id: String,
        title: String,
        command: String,
        workingDir: String?,
    ): Boolean =
        runCatching {
            // Reads only; the consumer owns every write. `ownedTerminal` is @Volatile
            // because this read is genuinely off the consumer's thread — a stale null
            // just means one command opens a BOSS tab that could have joined ours, and
            // the consumer re-validates anyway.
            val api = services.context.getPluginAPI(TerminalTabPluginAPI::class.java) ?: return false
            val tabs = services.context.activeTabsProvider?.activeTabs?.value ?: return false
            if (ownedTerminal == null && findTerminalHost(api, tabs) == null) return false
            // Never leave the directory implicit. On reuse the tab sits wherever the
            // last command left it, so a null workingDir would run this command in
            // another project's directory — composeDown passes null on its
            // `-p <project>` fallback, and runImage's projectPath is itself nullable.
            val dir = workingDir?.takeIf { it.isNotBlank() }
                ?: services.context.projectPath?.takeIf { it.isNotBlank() }
            terminalCommands.trySend(TerminalCommand(id, title, command, dir)).isSuccess
        }.getOrDefault(false)

    /**
     * The single consumer of [terminalCommands].
     *
     * One consumer is the whole design. Delivering a command is
     * switch → interrupt → wait → type, and the wait is unavoidable: `sendCommand`
     * writes to the tab's pty, so while a foreground process is running the text goes
     * to *that process's stdin* — never queued, never run — and Ctrl-C is what makes
     * the shell the reader again. Nothing may interleave with that sequence, and a lock
     * cannot supply that when part of the sequence is a suspend.
     *
     * Running here also keeps all of it off the UI thread: panel clicks call
     * [openTerminal] directly, and this body makes cross-plugin calls whose threading
     * contract the plugin does not control.
     */
    private suspend fun runTerminalCommands() {
        for (queued in terminalCommands) {
            // One bad command must not cost the feature: a consumer that dies takes
            // every later command with it, silently (see [terminalCommands]). Both
            // `getPluginAPI` (linkage) and the toast calls (cross-plugin) can throw
            // here, so the guard catches Throwable.
            try {
                deliver(queued)
            } catch (cancel: CancellationException) {
                // Named for the same reason dispose() names what it drops: this command was
                // already reported as launched, and it is the one most likely to matter,
                // because it was mid-delivery rather than still queued.
                System.err.println("[Docker] Cancelled mid-delivery: ${queued.command}")
                throw cancel // plugin is being disposed; not a delivery failure
            } catch (t: Throwable) {
                System.err.println("[Docker] Terminal delivery failed: $t")
                fallBackToBossTab(queued)
            }
        }
    }

    /** Deliver one queued command, reusing our tab or creating it. */
    private suspend fun deliver(queued: TerminalCommand) {
        val api = services.context.getPluginAPI(TerminalTabPluginAPI::class.java)
        val tabs = services.context.activeTabsProvider?.activeTabs?.value
        if (api == null || tabs == null) {
            // Said out loud rather than swallowed: on a host where the terminal-tab
            // plugin simply isn't loaded, this degrades to a BOSS tab per command — the
            // clutter this path exists to remove — with nothing to explain why.
            System.err.println("[Docker] No terminal-tab API; opening a BOSS tab for: ${queued.command}")
            fallBackToBossTab(queued)
            return
        }
        val full = if (queued.workingDir == null) queued.command else "cd ${q(queued.workingDir)} && ${queued.command}"
        val owned = liveOwnedTerminal(api, tabs)
        val delivered = if (owned != null) {
            deliverToOwnedTab(api, owned, full)
        } else {
            // `full`, not the bare command: both paths then derive the directory the same
            // way. Relying on createTab's workingDirectory here instead would mean that if
            // it is ever not honoured for the initial command, the *first* command runs in
            // the wrong place and every later one is right — a miserable thing to debug.
            // A redundant cd costs nothing.
            createOwnedTab(api, tabs, full)
        }
        if (!delivered) {
            // Forget the tab. `liveOwnedTerminal` only drops it when it is *gone*, so a tab
            // that exists but refuses writes would otherwise stay owned forever: every later
            // command would pay two Ctrl-Cs and 1.2 s, kill whatever is in it, and open a
            // BOSS tab anyway — the clutter this exists to prevent, on a loop, in silence.
            System.err.println("[Docker] Terminal refused the command; dropping the owned tab")
            ownedTerminal = null
            fallBackToBossTab(queued)
        }
    }

    /**
     * Open a BOSS terminal tab for a command we couldn't hand to the owned one.
     *
     * This is the fallback [openTerminal]'s KDoc promises, reached late. By the time a
     * command drains, the terminal that existed when it was accepted may be gone — and
     * without this the only remaining option was telling the operator to run it by hand,
     * which is a worse outcome than the pre-reuse behaviour.
     */
    private fun fallBackToBossTab(queued: TerminalCommand) {
        // Guarded as a whole, including the toasts: this runs inside the consumer's catch
        // block, so anything escaping here kills the consumer (see [terminalCommands]).
        // `toastError` reaches the host's notification provider, so it is not exempt.
        runCatching {
            val ops = services.context.splitViewOperations
            if (ops == null) {
                services.toastError("No terminal available — run manually: ${queued.command}")
                return@runCatching
            }
            ops.openTab(
                TerminalTabInfo(
                    id = queued.id,
                    title = queued.title,
                    initialCommand = queued.command,
                    workingDirectory = queued.workingDir,
                ),
            )
        }.onFailure { failure ->
            System.err.println("[Docker] Terminal fallback failed: $failure")
            runCatching {
                services.toastError("Couldn't reach the terminal — run manually: ${queued.command}")
            }
        }
    }

    /** Our tab, if it is still open; forgets it and returns null otherwise. */
    private fun liveOwnedTerminal(api: TerminalTabPluginAPI, tabs: List<ActiveTabData>): CommandTerminal? {
        val owned = ownedTerminal ?: return null
        val stillHosted = tabs.any { it.tabId == owned.terminalId && it.windowId == owned.windowId }
        val stillOpen = stillHosted && runCatching {
            api.listTabs(owned.windowId, owned.terminalId).any { it.id == owned.tabId }
        }.getOrDefault(false)
        if (stillOpen) return owned
        ownedTerminal = null
        return null
    }

    /**
     * Type [full] into the tab we own, interrupting whatever is running there first.
     *
     * Reusing one tab makes docker commands mutually exclusive, and that is a real cost
     * rather than a free win: building project A and then running `compose down` on
     * project B stops A's build. So it is announced. Silently killing a two-minute build
     * because the operator clicked something else is the kind of thing that reads as a
     * bug in docker.
     *
     * **The wait before typing is a heuristic, and the known weak point here.**
     * `sendCommand` writes to the pty, so the shell has to be the one reading by the
     * time it lands; the API exposes no prompt or liveness signal to wait *for*, only a
     * sleep. `docker build` under BuildKit traps SIGINT and cleans up, which can outlast
     * a single short delay, so the interrupt is sent twice with the wait split around
     * it: docker's own CLI escalates on the second SIGINT ("got 1 SIGTERM/SIGINTs,
     * forcing shutdown"), and a second Ctrl-C costs an idle shell nothing but a fresh
     * prompt line. Retrying the *command* instead would risk running it twice, which is
     * worse than losing it. A liveness query on the terminal API is the real fix.
     *
     * @return true if the command was typed.
     */
    private suspend fun deliverToOwnedTab(
        api: TerminalTabPluginAPI,
        owned: CommandTerminal,
        full: String,
    ): Boolean {
        // Switch first, and this is what makes the interrupt safe: sendInterrupt and
        // sendCommand take no tabId, so both act on the terminal's *active* tab, and
        // without the switch a Ctrl-C could land on the user's own tab.
        //
        // Safe to do from this (background) thread, and the reason is specific rather
        // than assumed: BossTerm resolves the target by *reading state*, not by waiting
        // for recomposition. TabController.activeTabIndex is `by mutableStateOf`, so a
        // write from any thread lands in the global snapshot immediately, and `activeTab`
        // is a plain getter over it (`tabs.getOrNull(activeTabIndex)`). The interrupt
        // therefore sees the switched tab in the same call chain, with nothing to settle.
        if (!runCatching { api.switchToTab(owned.windowId, owned.terminalId, owned.tabId) }.getOrDefault(false)) {
            return false
        }
        runCatching { api.sendInterrupt(owned.windowId, owned.terminalId) }
        delay(INTERRUPT_ESCALATE_MS)
        runCatching { api.sendInterrupt(owned.windowId, owned.terminalId) }
        delay(SHELL_REGAIN_LINE_MS)
        val sent = runCatching { api.sendCommand(owned.windowId, owned.terminalId, full) }.getOrDefault(false)
        if (sent) {
            // Deliberately not phrased as "interrupting the previous command": nothing
            // tells us whether one was still running, and a toast that cries wolf every
            // time is the one that gets tuned out on the occasion it is reporting a real
            // two-minute build being killed.
            // Re-read rather than reusing the snapshot taken before ~1.2 s of delay:
            // focusing a tab that has since moved is benign but wrong, and this is free.
            focusHostTab(owned.terminalId, owned.windowId)
        }
        return sent
    }

    /** Create the tab we will own from now on, running [command] as it starts. */
    private fun createOwnedTab(
        api: TerminalTabPluginAPI,
        tabs: List<ActiveTabData>,
        command: String,
    ): Boolean {
        val host = findTerminalHost(api, tabs) ?: return false
        val newTabId = runCatching {
            api.createTab(
                windowId = host.windowId,
                terminalId = host.tabId,
                // No workingDirectory: `command` carries its own cd, so this path and the
                // reuse path agree on where a command runs.
                initialCommand = command,
            )
        }.getOrNull() ?: return false

        ownedTerminal = CommandTerminal(host.windowId, host.tabId, newTabId)
        runCatching { api.switchToTab(host.windowId, host.tabId, newTabId) }
        focusHostTab(host.tabId, host.windowId)
        return true
    }

    /**
     * A tabbed terminal in *this* window to host our tab, or null.
     *
     * Probes every tab rather than filtering on a typeId string: `hasTerminalState` is a
     * registry lookup and the authoritative answer to "is this a tabbed terminal?", so
     * it can't drift if the type id is renamed.
     *
     * This window is *preferred*, not required. Searching every window meant a click in
     * window A could create the tab in window B and pull focus there — but filtering hard
     * would mean a blank or unmatched `context.windowId` silently disables reuse
     * altogether, bringing back the tab-per-command clutter this path exists to prevent,
     * with nothing logged. So: prefer, then fall back, and say so.
     */
    private fun findTerminalHost(api: TerminalTabPluginAPI, tabs: List<ActiveTabData>): ActiveTabData? {
        val myWindow = services.context.windowId
        val hosts = tabs.filter {
            runCatching { api.hasTerminalState(it.windowId, it.tabId) }.getOrDefault(false)
        }
        return hosts.firstOrNull { it.windowId == myWindow }
            ?: hosts.firstOrNull()?.also {
                System.err.println("[Docker] No terminal in window $myWindow; using one in ${it.windowId}")
            }
    }

    /**
     * Bring the BOSS tab hosting the terminal forward so the output is visible.
     *
     * Matched on window as well as tab: a tab id alone can name another window's tab.
     */
    private fun focusHostTab(terminalId: String, windowId: String) {
        val tabs = services.context.activeTabsProvider?.activeTabs?.value ?: return
        val host = tabs.firstOrNull { it.tabId == terminalId && it.windowId == windowId } ?: return
        runCatching { services.context.activeTabsProvider?.selectTab(host.tabId, host.panelId) }
    }

    /**
     * Called on every container-list change. Opens the service tab for anything
     * we launched, once it actually exists.
     */
    fun onContainersChanged(containers: List<ContainerInfo>) {
        if (pendingAutoOpen.isEmpty()) return
        val now = System.currentTimeMillis()
        pendingAutoOpen.entries.removeAll { it.value < now } // expired launches
        if (pendingAutoOpen.isEmpty()) return

        for (container in containers) {
            if (!container.isRunning) continue
            if (pendingAutoOpen.remove(container.name) == null) continue
            services.openServiceTab(container)
        }
    }

    // --------------------------------------------------------------- helpers

    /**
     * A container name not currently taken. `docker run --name` fails outright on
     * a collision, and silently `rm -f`ing the old one would destroy state the
     * user may still want.
     */
    fun uniqueContainerName(base: String): String {
        val cleaned = base.lowercase().replace(Regex("[^a-z0-9_.-]+"), "-").trim('-').ifBlank { "app" }
        val taken = services.engine.containers.value.map { it.name }.toSet() + pendingAutoOpen.keys
        if (cleaned !in taken) return cleaned
        var n = 2
        while ("$cleaned-$n" in taken) n++
        return "$cleaned-$n"
    }

    /** Last host port used for this artifact, else a free one. */
    suspend fun suggestedHostPort(artifact: ProjectArtifact): Int {
        val remembered = services
            .getPref(DockerServices.KEY_PORT_PREFIX + artifact.relativePath, "")
            .toIntOrNull()
        if (remembered != null && remembered in 1..65535 && isPortFree(remembered)) return remembered
        return freePort()
    }

    private fun rememberPort(artifact: ProjectArtifact, port: Int) {
        services.scope.launch {
            services.setPref(DockerServices.KEY_PORT_PREFIX + artifact.relativePath, port.toString())
        }
    }

    companion object {
        private const val AUTO_OPEN_WINDOW_MS = 10 * 60 * 1000L

        /**
         * How long to let the shell regain the line after the last Ctrl-C before typing.
         * terminal-tab's own re-run delay defaults to 500 ms, but that is for an *idle*
         * tab; here a live process may still be tearing down, so the total wait is
         * longer and split around a second interrupt.
         */
        private const val SHELL_REGAIN_LINE_MS = 800L

        /** Gap before the second Ctrl-C, which is what forces a stubborn client to quit. */
        private const val INTERRUPT_ESCALATE_MS = 400L


        /**
         * Single-quote a value for the shell. Terminal tabs take a command *string*
         * (a real shell runs it), so unlike the argv-only [DockerCli] path these
         * must be quoted — project paths routinely contain spaces.
         */
        fun q(value: String): String = "'" + value.replace("'", "'\\''") + "'"

        /**
         * First `EXPOSE`d port in a Dockerfile, which is the best guess at what
         * the image actually serves. Null when the Dockerfile declares none.
         */
        fun firstExposedPort(dockerfile: File): Int? = runCatching {
            dockerfile.useLines { lines ->
                lines.mapNotNull { line ->
                    val trimmed = line.trim()
                    if (!trimmed.startsWith("EXPOSE", ignoreCase = true)) return@mapNotNull null
                    trimmed.removePrefix("EXPOSE").removePrefix("expose").trim()
                        .split(Regex("\\s+")).firstOrNull()
                        ?.substringBefore('/')
                        ?.toIntOrNull()
                }.firstOrNull()
            }
        }.getOrNull()

        /**
         * An unused local port. Inherently racy — something else can take it
         * between here and `docker run` — but docker fails loudly if that happens.
         */
        fun freePort(): Int = runCatching {
            ServerSocket(0).use { it.localPort }
        }.getOrDefault(8080)

        fun isPortFree(port: Int): Boolean = runCatching {
            ServerSocket(port).use { true }
        }.getOrDefault(false)
    }
}
