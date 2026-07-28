package ai.rever.boss.plugin.dynamic.docker

import ai.rever.boss.plugin.api.TabSplitMode
import ai.rever.boss.plugin.api.ActiveTabData
import ai.rever.boss.plugin.api.TerminalTabPluginAPI
import ai.rever.boss.plugin.tab.terminal.TerminalTabInfo
import kotlinx.coroutines.launch
import java.io.File
import java.net.ServerSocket

/** Where a launched terminal tab should be placed. */
enum class OpenLocation { NEW_TAB, SPLIT_RIGHT, SPLIT_DOWN }

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

    /** Guards the read-act-write of [DockerServices.commandTerminal]. */
    private val terminalLock = Any()

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
     * Falls back to a new BOSS terminal tab when there is no live terminal to join,
     * when [forceNewTab] is set, or when the terminal-tab plugin isn't loaded.
     */
    fun openTerminal(
        id: String,
        title: String,
        command: String,
        workingDir: String?,
        location: OpenLocation = OpenLocation.NEW_TAB,
        forceNewTab: Boolean = false,
    ): Boolean {
        if (!forceNewTab && location == OpenLocation.NEW_TAB && runInExistingTerminal(command, workingDir)) {
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
     * Run [command] in the single terminal tab this plugin owns, creating it only on
     * first use.
     *
     * Two things this must not do. Create a tab per command — that is the bug being
     * fixed. And type into a tab the plugin doesn't own: `sendCommand` writes to the
     * terminal's *active* tab, so reusing "whatever is focused" would inject a
     * `docker build` into whatever the user happens to be running there.
     *
     * Wrapped whole rather than per-call. Each individual API call already has its
     * own `runCatching`, but a host whose terminal-tab plugin is absent or older than
     * the interface fails at *linkage* — `NoClassDefFoundError` on the
     * `TerminalTabPluginAPI::class.java` literal, `NoSuchMethodError` on a call — and
     * those are `Error`s, which a `runCatching` inside the method never gets to run to
     * catch. `runCatching` here catches `Throwable`, so a missing terminal degrades to
     * the old open-a-BOSS-tab path instead of taking down build/run.
     *
     * @return true if the command was delivered to a terminal.
     */
    private fun runInExistingTerminal(command: String, workingDir: String?): Boolean =
        runCatching { reuseOwnedTerminal(command, workingDir) }.getOrDefault(false)

    /**
     * The reuse itself. Serialized: `commandTerminal` is read, acted on and written
     * here, and the callers are an MCP handler and a panel click on different
     * dispatchers. `@Volatile` gives visibility, not atomicity — two callers could both
     * see null, both create a tab, and both write, leaving an orphaned tab and two
     * commands racing into one pty. The lock also makes the interrupt-then-send
     * sequence below indivisible.
     */
    private fun reuseOwnedTerminal(command: String, workingDir: String?): Boolean =
        synchronized(terminalLock) {
            // Resolved per call: cross-plugin APIs can appear after our register().
            val api = services.context.getPluginAPI(TerminalTabPluginAPI::class.java) ?: return false
            val tabs = services.context.activeTabsProvider?.activeTabs?.value ?: return false
            // Never leave the directory implicit. On reuse the tab sits wherever the
            // last command left it, so a null workingDir would run this command in
            // another project's directory — composeDown passes null on its
            // `-p <project>` fallback, and runImage's projectPath is itself nullable.
            val dir = workingDir?.takeIf { it.isNotBlank() }
                ?: services.context.projectPath?.takeIf { it.isNotBlank() }

            // Reuse our own tab while it is still open.
            services.commandTerminal?.let { owned ->
                val stillHosted = tabs.any { it.tabId == owned.terminalId && it.windowId == owned.windowId }
                val stillOpen = stillHosted && runCatching {
                    api.listTabs(owned.windowId, owned.terminalId).any { it.id == owned.tabId }
                }.getOrDefault(false)
                if (stillOpen) {
                    // Switch first: sendCommand targets the active tab, so this is what
                    // guarantees the command lands in ours.
                    val switched = runCatching { api.switchToTab(owned.windowId, owned.terminalId, owned.tabId) }
                        .getOrDefault(false)
                    if (switched && sendToOwnedTab(api, owned, command, dir)) {
                        focusHostTab(tabs, owned.terminalId, owned.windowId)
                        return true
                    }
                }
                // Gone or unusable — forget it and create a fresh one below.
                services.commandTerminal = null
            }

            // Probe every tab rather than filtering on a typeId string: hasTerminalState
            // is a registry lookup and the authoritative answer to "is this a tabbed
            // terminal?", so it can't drift if the type id is renamed. Prefer this
            // window's terminals over another window's.
            val myWindow = services.context.windowId
            val candidate = tabs.asSequence()
                .sortedByDescending { it.windowId == myWindow }
                .firstOrNull { runCatching { api.hasTerminalState(it.windowId, it.tabId) }.getOrDefault(false) }
                ?: return false

            val newTabId = runCatching {
                api.createTab(
                    windowId = candidate.windowId,
                    terminalId = candidate.tabId,
                    workingDirectory = dir,
                    initialCommand = command,
                )
            }.getOrNull() ?: return false

            services.commandTerminal = CommandTerminal(candidate.windowId, candidate.tabId, newTabId)
            runCatching { api.switchToTab(candidate.windowId, candidate.tabId, newTabId) }
            focusHostTab(tabs, candidate.tabId, candidate.windowId)
            return true
        }

    /**
     * Deliver [command] to the tab we own, interrupting whatever is running there first.
     *
     * The interrupt is the load-bearing part. `sendCommand` writes to the tab's pty, so
     * with a foreground process still running — a two-minute `docker build`, a
     * `compose up` — the text goes to *that process's stdin* and is never queued and
     * never run. Returning true there would have the MCP tool answer "Building …" for
     * a command that vanished. Ctrl-C first means the shell is the one reading.
     *
     * This is the same switch → Ctrl-C → delay → send sequence terminal-tab itself uses
     * for a re-run, including the 500 ms it waits for the shell to regain the line, and
     * it is why the whole thing sits under [terminalLock]: two commands interleaving
     * here would interrupt each other's send.
     *
     * Interrupting is the direct consequence of "reuse one tab": this tab exists only
     * for the plugin's own commands, so the thing being interrupted is always an
     * earlier docker command that the new one supersedes.
     */
    private fun sendToOwnedTab(
        api: TerminalTabPluginAPI,
        owned: CommandTerminal,
        command: String,
        dir: String?,
    ): Boolean {
        val full = if (dir == null) command else "cd ${q(dir)} && $command"
        runCatching { api.sendInterrupt(owned.windowId, owned.terminalId) }
        // Off the caller's thread: the caller may be the UI thread, and blocking it for
        // half a second to type a command is a visible stall.
        services.scope.launch {
            kotlinx.coroutines.delay(SHELL_REGAIN_LINE_MS)
            runCatching { api.sendCommand(owned.windowId, owned.terminalId, full) }
        }
        return true
    }

    /**
     * Bring the BOSS tab hosting the terminal forward so the output is visible.
     *
     * Matched on window as well as tab: the candidate search deliberately spans windows,
     * so a tab id alone can name a different window's tab.
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
