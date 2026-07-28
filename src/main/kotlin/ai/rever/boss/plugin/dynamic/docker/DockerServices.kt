package ai.rever.boss.plugin.dynamic.docker

import ai.rever.boss.plugin.api.NotificationType
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.PluginStorageProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Shared brain for the Docker plugin: one instance per activation, handed to the
 * sidebar panel, every service tab, and the MCP tools.
 *
 * Every host provider is pulled lazily and may be null — each call site degrades
 * to something usable rather than crashing.
 */
class DockerServices(val context: PluginContext) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val engine = DockerEngine(scope) { context.projectPath }
    val actions = DockerActions(this)

    private val storage: PluginStorageProvider? by lazy {
        runCatching { context.pluginStorageFactory?.createStorage(PLUGIN_ID) }.getOrNull()
    }

    /** Open a service tab automatically when a container we launched shows up. */
    private val _autoOpenServiceTab = MutableStateFlow(true)
    val autoOpenServiceTab: StateFlow<Boolean> = _autoOpenServiceTab.asStateFlow()

    private var autoOpenWatcher: Job? = null

    fun start() {
        engine.start()
        scope.launch {
            _autoOpenServiceTab.value = getPref(KEY_AUTO_OPEN, "true").toBoolean()
        }
        autoOpenWatcher = scope.launch {
            engine.containers.collect { list -> actions.onContainersChanged(list) }
        }
    }

    fun dispose() {
        autoOpenWatcher?.cancel()
        engine.dispose()
        scope.cancel()
    }

    fun setAutoOpenServiceTab(enabled: Boolean) {
        _autoOpenServiceTab.value = enabled
        scope.launch { setPref(KEY_AUTO_OPEN, enabled.toString()) }
    }

    // ------------------------------------------------------------------ tabs

    /**
     * Open (or focus) the service tab for [container].
     *
     * The tab id is stable per container, and an already-open tab is FOCUSED
     * rather than reopened: the host's `openTab` does not dedupe by id, and two
     * tabs sharing an id share one component, so stacking them makes closing one
     * blank the other.
     */
    fun openServiceTab(container: ContainerInfo): Boolean {
        val tabInfo = DockerServiceTabInfo(
            containerId = container.id,
            containerName = container.name,
        )
        val existing = context.activeTabsProvider?.activeTabs?.value?.firstOrNull { it.tabId == tabInfo.id }
        if (existing != null) {
            context.activeTabsProvider?.selectTab(existing.tabId, existing.panelId)
            return true
        }
        val ops = context.splitViewOperations ?: run {
            toastError("Cannot open a tab — this host does not expose split view operations")
            return false
        }
        ops.openTab(tabInfo)
        return true
    }

    /**
     * Like [openServiceTab], but waits for the tab to actually appear.
     *
     * `openTab` is fire-and-forget: the host marshals it onto the UI thread and
     * silently drops the tab if no factory is registered for its type. Callers
     * that report success to a user (the MCP tools) must confirm rather than
     * assume, otherwise "Opened the service tab" can be a lie.
     */
    suspend fun openServiceTabVerified(container: ContainerInfo): TabOpenOutcome {
        val tabInfo = DockerServiceTabInfo(
            containerId = container.id,
            containerName = container.name,
        )
        val tabs = context.activeTabsProvider
        tabs?.activeTabs?.value?.firstOrNull { it.tabId == tabInfo.id }?.let { existing ->
            tabs.selectTab(existing.tabId, existing.panelId)
            return TabOpenOutcome.Focused
        }
        val ops = context.splitViewOperations ?: return TabOpenOutcome.NoSplitViewOperations
        ops.openTab(tabInfo)

        if (tabs == null) return TabOpenOutcome.Unverifiable
        repeat(TAB_POLL_ATTEMPTS) {
            delay(TAB_POLL_INTERVAL_MS)
            if (tabs.activeTabs.value.any { it.tabId == tabInfo.id }) return TabOpenOutcome.Opened
        }
        return TabOpenOutcome.Dropped
    }

    /** Open [url] in a host browser tab (the fallback when preview is unavailable). */
    fun openUrl(url: String, title: String) {
        val ops = context.splitViewOperations ?: run {
            toastError("Cannot open $url — this host does not expose split view operations")
            return
        }
        ops.openUrlInActivePanel(url, title)
    }

    // ---------------------------------------------------------------- toasts

    fun toastSuccess(message: String) = toast(message, NotificationType.SUCCESS)
    fun toastError(message: String) = toast(message, NotificationType.ERROR)
    fun toastInfo(message: String) = toast(message, NotificationType.INFO)

    private fun toast(message: String, type: NotificationType) {
        context.notificationProvider?.showToast(message, type, title = "Docker")
    }

    // --------------------------------------------------------------- storage

    suspend fun getPref(key: String, default: String): String =
        runCatching { storage?.getString(key, default) }.getOrNull() ?: default

    suspend fun setPref(key: String, value: String) {
        runCatching { storage?.putString(key, value) }
    }

    companion object {
        const val PLUGIN_ID = "ai.rever.boss.plugin.dynamic.docker"
        const val KEY_AUTO_OPEN = "autoOpenServiceTab"
        const val KEY_PORT_PREFIX = "port."

        private const val TAB_POLL_ATTEMPTS = 25
        private const val TAB_POLL_INTERVAL_MS = 100L
    }
}

/**
 * Identifies the terminal tab the plugin runs its commands in.
 *
 * Held privately by [DockerActions], which is the only thing that may change it — a
 * public field here would let any call site retarget the tab without going through the
 * command queue that keeps deliveries in order.
 */
data class CommandTerminal(
    val windowId: String,
    val terminalId: String,
    val tabId: String,
)

/** What [DockerServices.openServiceTabVerified] observed. */
enum class TabOpenOutcome {
    /** A tab with this id already existed and was focused. */
    Focused,

    /** The tab was requested and confirmed present. */
    Opened,

    /** Requested, but never showed up — usually no factory for the tab type. */
    Dropped,

    /** Requested, but the host exposes no way to observe tabs. */
    Unverifiable,

    /** The host exposes no split-view operations at all. */
    NoSplitViewOperations,
}
