package ai.rever.boss.plugin.dynamic.docker

import ai.rever.boss.plugin.api.ActiveTabData
import ai.rever.boss.plugin.api.TabSplitMode
import ai.rever.boss.plugin.api.TerminalTabPluginAPI
import ai.rever.boss.plugin.tab.terminal.TerminalTabInfo
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.net.ServerSocket

/** Where a launched terminal tab should be placed. */
enum class OpenLocation { NEW_TAB, SPLIT_RIGHT, SPLIT_DOWN }

/** A command awaiting delivery to the plugin's terminal tab, with its resolved cwd. */
private data class TerminalCommand(val command: String, val workingDir: String?)

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
     * Plain `var`s, not `@Volatile`: [runTerminalCommands] is the only reader or writer
     * and there is exactly one of it, so there is no cross-thread access to make
     * visible. Keeping them here rather than on [DockerServices] is what makes that
     * structural — a public field on the shared services object is one any future call
     * site can write without going through the queue.
     *
     * Session-scoped and never persisted; tab ids don't survive a restart.
     */
    private var ownedTerminal: CommandTerminal? = null
    private var hasSentCommand = false

    /** One command per send, drained in order by [runTerminalCommands]. */
    private val terminalCommands = Channel<TerminalCommand>(Channel.UNLIMITED)

    init {
        services.scope.launch { runTerminalCommands() }
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
     * Falls back to a new BOSS terminal tab when there is no live terminal in this
     * window to join, or when the terminal-tab plugin isn't loaded.
     */
    fun openTerminal(
        id: String,
        title: String,
        command: String,
        workingDir: String?,
        location: OpenLocation = OpenLocation.NEW_TAB,
    ): Boolean {
        if (location == OpenLocation.NEW_TAB && runInExistingTerminal(command, workingDir)) {
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
     * Only the *decision* happens here, and only cheaply: is there a terminal to join
     * at all? Everything that touches the terminal is queued to [terminalCommands] and
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
    private fun runInExistingTerminal(command: String, workingDir: String?): Boolean =
        runCatching {
            // No lock: these are reads, and the consumer owns every write.
            val api = services.context.getPluginAPI(TerminalTabPluginAPI::class.java) ?: return false
            val tabs = services.context.activeTabsProvider?.activeTabs?.value ?: return false
            if (ownedTerminal == null && findTerminalHost(api, tabs) == null) return false
            // Never leave the directory implicit. On reuse the tab sits wherever the
            // last command left it, so a null workingDir would run this command in
            // another project's directory — composeDown passes null on its
            // `-p <project>` fallback, and runImage's projectPath is itself nullable.
            val dir = workingDir?.takeIf { it.isNotBlank() }
                ?: services.context.projectPath?.takeIf { it.isNotBlank() }
            terminalCommands.trySend(TerminalCommand(command, dir)).isSuccess
        }.getOrDefault(false)

    /**
     * The single consumer of [terminalCommands].
     *
     * One consumer is the whole design. Delivering a command is
     * switch → interrupt → wait → type, and the wait is unavoidable: `sendCommand`
     * writes to the tab's pty, so while a foreground process is running the text goes
     * to *that process's stdin* — never queued, never run — and Ctrl-C is what makes
     * the shell the reader again. Nothing may interleave with that sequence. An earlier
     * attempt held a `synchronized` block across the interrupt but `launch`ed the send,
     * which let a second command's interrupt land *before* the first command had been
     * typed, so the first ran and the second was swallowed — the exact bug the
     * interrupt exists to prevent, reintroduced one step later. Sequencing it through
     * a channel makes the ordering structural rather than something the comments claim.
     *
     * Running here also keeps all of it off the UI thread: panel clicks call
     * [openTerminal] directly, and this body makes cross-plugin calls whose threading
     * contract the plugin does not control.
     */
    private suspend fun runTerminalCommands() {
        for ((command, dir) in terminalCommands) {
            val api = services.context.getPluginAPI(TerminalTabPluginAPI::class.java)
            val tabs = services.context.activeTabsProvider?.activeTabs?.value
            if (api == null || tabs == null) {
                services.toastError("No terminal available — run manually: $command")
                continue
            }
            val full = if (dir == null) command else "cd ${q(dir)} && $command"
            val owned = liveOwnedTerminal(api, tabs)
            if (owned != null) {
                deliverToOwnedTab(api, owned, full, tabs)
            } else if (!createOwnedTab(api, tabs, command, dir)) {
                // The terminal that existed when the command was accepted is gone.
                services.toastError("Couldn't reach the terminal — run manually: $command")
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
     */
    private suspend fun deliverToOwnedTab(
        api: TerminalTabPluginAPI,
        owned: CommandTerminal,
        full: String,
        tabs: List<ActiveTabData>,
    ) {
        // Switch first: sendCommand targets the active tab, so this is what guarantees
        // the command lands in ours.
        if (!runCatching { api.switchToTab(owned.windowId, owned.terminalId, owned.tabId) }.getOrDefault(false)) {
            services.toastError("Couldn't reach the terminal — run manually: $full")
            return
        }
        if (hasSentCommand) {
            services.toastInfo("Interrupting the previous docker command in the terminal")
        }
        runCatching { api.sendInterrupt(owned.windowId, owned.terminalId) }
        delay(SHELL_REGAIN_LINE_MS)
        val sent = runCatching { api.sendCommand(owned.windowId, owned.terminalId, full) }.getOrDefault(false)
        if (sent) {
            hasSentCommand = true
            focusHostTab(tabs, owned.terminalId, owned.windowId)
        } else {
            services.toastError("Couldn't reach the terminal — run manually: $full")
        }
    }

    /** Create the tab we will own from now on, running [command] as it starts. */
    private fun createOwnedTab(
        api: TerminalTabPluginAPI,
        tabs: List<ActiveTabData>,
        command: String,
        dir: String?,
    ): Boolean {
        val host = findTerminalHost(api, tabs) ?: return false
        val newTabId = runCatching {
            api.createTab(
                windowId = host.windowId,
                terminalId = host.tabId,
                workingDirectory = dir,
                initialCommand = command,
            )
        }.getOrNull() ?: return false

        ownedTerminal = CommandTerminal(host.windowId, host.tabId, newTabId)
        // A fresh tab starts idle, so the next command is the first that could
        // interrupt anything.
        hasSentCommand = false
        runCatching { api.switchToTab(host.windowId, host.tabId, newTabId) }
        focusHostTab(tabs, host.tabId, host.windowId)
        return true
    }

    /**
     * A tabbed terminal in *this* window to host our tab, or null.
     *
     * Probes every tab rather than filtering on a typeId string: `hasTerminalState` is a
     * registry lookup and the authoritative answer to "is this a tabbed terminal?", so
     * it can't drift if the type id is renamed.
     *
     * Confined to this window on purpose. Searching every window meant a click in
     * window A could create the tab in window B and pull focus there; falling back to a
     * new BOSS tab in the window the operator is actually looking at is the less
     * surprising outcome.
     */
    private fun findTerminalHost(api: TerminalTabPluginAPI, tabs: List<ActiveTabData>): ActiveTabData? {
        val myWindow = services.context.windowId
        return tabs.firstOrNull {
            it.windowId == myWindow && runCatching { api.hasTerminalState(it.windowId, it.tabId) }.getOrDefault(false)
        }
    }

    /**
     * Bring the BOSS tab hosting the terminal forward so the output is visible.
     *
     * Matched on window as well as tab: a tab id alone can name another window's tab.
     */
    private fun focusHostTab(tabs: List<ActiveTabData>, terminalId: String, windowId: String) {
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
         * How long to let the shell regain the line after Ctrl-C before typing.
         * Matches terminal-tab's own re-run delay, which defaults to the same 500 ms.
         */
        private const val SHELL_REGAIN_LINE_MS = 500L

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
