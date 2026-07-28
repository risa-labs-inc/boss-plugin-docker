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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Where a launched terminal tab should be placed. */
enum class OpenLocation { NEW_TAB, SPLIT_RIGHT, SPLIT_DOWN }

/**
 * Identifies the terminal tab the plugin runs its commands in.
 *
 * File-private and held only by [DockerActions], so the encapsulation the consumer
 * design depends on is structural: nothing outside this file can retarget the tab
 * without going through the queue.
 */
private data class CommandTerminal(
    val windowId: String,
    val terminalId: String,
    val tabId: String,
)

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

    /**
     * Containers we launched and want to auto-open, name → deadline (epoch ms).
     *
     * Concurrent because the two ends genuinely are: `buildAndRun` writes from a panel
     * click (UI thread) while `onContainersChanged` iterates and removes from the
     * engine's `Dispatchers.Default` collector. A plain LinkedHashMap here is a
     * ConcurrentModificationException waiting for the right timing — pre-existing, but
     * this change is explicitly about getting this file's threading right.
     */
    private val pendingAutoOpen = ConcurrentHashMap<String, Long>()

    /**
     * The terminal tab this plugin owns.
     *
     * Confined to the consumer: [runTerminalCommands] is the only reader and writer, and
     * there is exactly one of it, so no atomicity or visibility is needed today. (The
     * accept path used to read it too; that went away when it stopped inspecting
     * anything.) `@Volatile` is kept as belt-and-braces in case a caller-side read is
     * ever reintroduced — not because one exists.
     *
     * Kept here rather than on [DockerServices] so that guarantee is structural — a
     * public field on the shared services object is one any future call site can write
     * without going through the queue.
     *
     * Session-scoped and never persisted; tab ids don't survive a restart.
     */
    @Volatile
    private var ownedTerminal: CommandTerminal? = null

    /** Commands accepted but not yet taken by the consumer. */
    private val pending = AtomicInteger(0)

    /** The plugin has no host logger; this keeps the one prefix in one place. */
    private fun log(message: String) = System.err.println("[Docker] $message")

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
            log("Dropped ${dropped.size} queued command(s) on dispose: " + dropped.joinToString("; ") { it.command })
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
     *
     * **Every `NEW_TAB` caller shares one tab and interrupts the others**, and the MCP
     * tool text enumerates which commands those are ("another docker build or compose
     * command"). That enumeration is only true because of which callers pass `NEW_TAB`
     * — nothing checks it. Adding one (wiring up [runImage], say) falsifies three tool
     * descriptions and a panel toast, so update them together.
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
     * Queue [command] for the terminal tab this plugin owns.
     *
     * Two things this must not do. Create a tab per command — that is the bug being
     * fixed. And type into a tab the plugin doesn't own: `sendCommand` writes to the
     * terminal's *active* tab, so reusing "whatever is focused" would inject a
     * `docker build` into whatever the user happens to be running there.
     *
     * Nothing is inspected here, deliberately. Deciding whether a terminal exists meant
     * a `getPluginAPI` plus a `hasTerminalState` registry lookup **per open tab**, on
     * the caller's thread — which for a panel click is the UI thread, and scales with
     * tab count. The consumer already re-checks all of it and already handles "no
     * terminal to join" by opening a BOSS tab, so the work was duplicated as well as
     * misplaced. The only thing given up is a synchronous false, and on this path the
     * return was already advisory: it means "accepted for delivery", not "running".
     *
     * `trySend` fails only on a closed channel, i.e. after [dispose], which is the one
     * case where the caller should fall through to opening a tab itself.
     */
    private fun runInExistingTerminal(
        id: String,
        title: String,
        command: String,
        workingDir: String?,
    ): Boolean {
        // Never leave the directory implicit. On reuse the tab sits wherever the last
        // command left it, so a null workingDir would run this command in another
        // project's directory — composeDown passes null on its `-p <project>` fallback,
        // and runImage's projectPath is itself nullable.
        val dir = workingDir?.takeIf { it.isNotBlank() }
            ?: services.context.projectPath?.takeIf { it.isNotBlank() }
        val accepted = terminalCommands.trySend(TerminalCommand(id, title, command, dir)).isSuccess
        if (accepted) pending.incrementAndGet()
        return accepted
    }

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
            // every later command with it, silently (see [terminalCommands]).
            // `getPluginAPI` can fail at linkage and the fallback's toast is a
            // cross-plugin call, so the guard catches Throwable rather than Exception.
            pending.decrementAndGet()
            try {
                deliver(queued)
            } catch (cancel: CancellationException) {
                // Named for the same reason dispose() names what it drops: this command was
                // already reported as launched, and it is the one most likely to matter,
                // because it was mid-delivery rather than still queued.
                log("Cancelled mid-delivery: ${queued.command}")
                throw cancel // plugin is being disposed; not a delivery failure
            } catch (t: Throwable) {
                log("Terminal delivery failed: $t")
                fallBackToBossTab(queued)
            }
        }
    }

    /** Deliver one queued command, reusing our tab or creating it. */
    private suspend fun deliver(queued: TerminalCommand) {
        val api = services.context.getPluginAPI(TerminalTabPluginAPI::class.java)
        val tabs = services.context.activeTabsProvider?.activeTabs?.value
        if (api == null || tabs == null) {
            // Said out loud rather than swallowed: this degrades to a BOSS tab per
            // command — the clutter this path exists to remove — so the reason needs to
            // be findable. Both causes are real: terminal-tab not loaded, or a host that
            // supplies no activeTabsProvider. The message names which.
            val missing = if (api == null) "terminal-tab API" else "activeTabsProvider"
            log("No $missing; opening a BOSS tab for: ${queued.command}")
            fallBackToBossTab(queued)
            return
        }
        val full = if (queued.workingDir == null) queued.command else "cd ${q(queued.workingDir)} && ${queued.command}"
        val owned = liveOwnedTerminal(api, tabs)
        val hadOwnTab = owned != null
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
            // Two different failures, and conflating them is what you would be debugging
            // from: no tabbed terminal open anywhere, versus our own tab refusing a write.
            if (hadOwnTab) {
                // Forget it. `liveOwnedTerminal` only drops a tab that is *gone*, so one
                // that exists but refuses writes would stay owned forever: every later
                // command paying two Ctrl-Cs and 1.2 s, killing whatever is in it, and
                // opening a BOSS tab anyway — on a loop, in silence.
                log("Our terminal tab refused the command; dropping it")
                ownedTerminal = null
            } else {
                log("No tabbed terminal to join; opening a BOSS tab")
            }
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
            log("Terminal fallback failed: $failure")
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
     * Reusing one tab makes docker commands mutually exclusive, and that is a real cost:
     * building project A and then running `compose down` on project B stops A's build.
     * That is said **prospectively** — by the MCP tool descriptions and results, and by
     * the panel's own toast — never by a notification after the fact; see AGENTS.md for
     * why an after-the-fact one cannot be gated correctly.
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
        // Re-asserted before *every* write, not once up front.
        //
        // sendInterrupt and sendCommand take no tabId — they hit the terminal's *active*
        // tab — so the switch is what keeps them off the user's tab. Asserting it once
        // and then sleeping 1.2 s would mean acting on a state verified before the sleep,
        // and the active tab can change inside that window: the user clicking a sub-tab,
        // made *more* likely because the plugin just pulled focus to the terminal
        // mid-work. The consequence is the exact injection this design exists to prevent
        // — a `docker build` typed into whatever they were running.
        //
        // There is no atomic switch-and-write in the API, so the window cannot be closed;
        // this shrinks it from 1.2 s to the gap between two adjacent calls. It is free on
        // the happy path because switchToTabById returns true when the tab is already
        // active, and false only when the id is unknown — which is the correct bail-out.
        //
        // Two things verified against BossTerm rather than assumed (compose-ui
        // TabController, as of bossterm-compose 1.2.129):
        //
        // Safe from this background thread because BossTerm resolves the target by
        // *reading state*, not by waiting for recomposition: `activeTabIndex` is
        // `by mutableStateOf`, so a write from any thread lands in the global snapshot at
        // once, and `activeTab` is a plain getter over it.
        //
        // And `false` really does mean "no such tab": `switchToTabById` returns false only
        // when the id is not found (the already-active no-op early return lives in the
        // index-based overload it delegates to).
        suspend fun focusOurTab(): Boolean =
            runCatching { api.switchToTab(owned.windowId, owned.terminalId, owned.tabId) }
                .getOrDefault(false)

        if (!focusOurTab()) return false
        runCatching { api.sendInterrupt(owned.windowId, owned.terminalId) }
        delay(INTERRUPT_ESCALATE_MS)
        if (!focusOurTab()) return false
        runCatching { api.sendInterrupt(owned.windowId, owned.terminalId) }
        delay(SHELL_REGAIN_LINE_MS)
        if (!focusOurTab()) return false
        // A command already waiting means this one is superseded the moment it is typed:
        // the consumer returns as soon as sendCommand lands, so the next item's interrupt
        // fires ~0 ms later and this gets no runway at all.
        //
        // Logged rather than skipped, deliberately. Dropping looks like the obvious win
        // and is not, because it is only safe for some commands: a superseded build or
        // compose-up is genuinely redundant — the survivor supersedes it — but a
        // superseded `compose down` would be a *destructive action silently skipped*,
        // which is worse than a noisy one that at least starts. Acting on this needs that
        // per-command distinction, not a blanket skip.
        if (pending.get() > 0) {
            log("Another command is already queued; this one will be interrupted almost immediately")
        }
        val sent = runCatching { api.sendCommand(owned.windowId, owned.terminalId, full) }.getOrDefault(false)
        if (sent) {
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
                log("No terminal in window $myWindow; using one in ${it.windowId}")
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
