package ai.rever.boss.plugin.dynamic.docker

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * State for one Docker service tab: the container it watches, its log stream,
 * and its `docker inspect` output.
 */
class DockerServiceTabViewModel(
    private val services: DockerServices,
    private val tabInfo: DockerServiceTabInfo,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val containerId: String get() = tabInfo.containerId

    private val _container = MutableStateFlow<ContainerInfo?>(null)
    val container: StateFlow<ContainerInfo?> = _container.asStateFlow()

    private val _section = MutableStateFlow(ServiceSection.LOGS)
    val section: StateFlow<ServiceSection> = _section.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    private val _autoScroll = MutableStateFlow(true)
    val autoScroll: StateFlow<Boolean> = _autoScroll.asStateFlow()

    private val _inspect = MutableStateFlow<String?>(null)
    val inspect: StateFlow<String?> = _inspect.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    /**
     * Log lines live in a plain deque, not the StateFlow: a chatty container can
     * emit thousands of lines a second, and allocating a new immutable list per
     * line would melt the UI. The flow is refreshed on a timer instead.
     */
    private val buffer = ArrayDeque<String>(MAX_LINES)
    private val bufferLock = Mutex()
    private var dirty = false

    private var logJob: Job? = null

    init {
        // Track this container in the engine's list; it may not be there yet if
        // the tab was opened the instant the container was created.
        scope.launch {
            services.engine.containers.collect { list ->
                val found = list.firstOrNull { it.id == tabInfo.containerId }
                    ?: list.firstOrNull { it.id.startsWith(tabInfo.containerId) }
                    ?: list.firstOrNull { it.name == tabInfo.containerName }
                _container.value = found
                // (Re)attach the log stream whenever the container starts running.
                if (found?.isRunning == true && logJob?.isActive != true) startLogStream()
            }
        }
        // Publish buffered log lines at a steady rate rather than per line.
        scope.launch {
            while (isActive) {
                delay(FLUSH_MS)
                if (!dirty || _paused.value) continue
                bufferLock.withLock {
                    _logs.value = buffer.toList()
                    dirty = false
                }
            }
        }
    }

    fun dispose() {
        logJob?.cancel()
        scope.cancel()
    }

    fun selectSection(section: ServiceSection) {
        _section.value = section
        if (section == ServiceSection.INSPECT) refreshInspect()
    }

    fun setPaused(paused: Boolean) {
        _paused.value = paused
    }

    fun setAutoScroll(enabled: Boolean) {
        _autoScroll.value = enabled
    }

    fun clearLogs() {
        scope.launch {
            bufferLock.withLock {
                buffer.clear()
                _logs.value = emptyList()
                dirty = false
            }
        }
    }

    /** All log text, for copy-to-clipboard. */
    fun logText(): String = _logs.value.joinToString("\n")

    // ------------------------------------------------------------------ logs

    private fun startLogStream() {
        logJob?.cancel()
        logJob = scope.launch {
            val id = _container.value?.id ?: tabInfo.containerId
            DockerCli.stream(listOf("logs", "-f", "--tail", TAIL_LINES.toString(), id)) { line ->
                append(stripAnsi(line))
            }
            // Stream ended: the container stopped, or docker went away. The
            // container collector above re-attaches if it comes back.
        }
    }

    private suspend fun append(line: String) {
        bufferLock.withLock {
            buffer.addLast(line)
            while (buffer.size > MAX_LINES) buffer.removeFirst()
            dirty = true
        }
    }

    // --------------------------------------------------------------- actions

    fun stop() = act("Stopping…") { services.engine.stopContainer(it.id) }
    fun restart() = act("Restarting…") { services.engine.restartContainer(it.id) }
    fun start() = act("Starting…") { services.engine.startContainer(it.id) }

    private fun act(pending: String, block: suspend (ContainerInfo) -> DockerExec) {
        val target = _container.value ?: return
        scope.launch {
            _status.value = pending
            val result = block(target)
            _status.value = if (result.ok) null else result.message.take(200)
            if (!result.ok) services.toastError(result.message.take(200))
        }
    }

    fun openInBrowser() {
        val port = _container.value?.primaryPort ?: return
        services.openUrl(port.localUrl, _container.value?.name ?: "Service")
    }

    fun refreshInspect() {
        scope.launch {
            val id = _container.value?.id ?: tabInfo.containerId
            val result = services.engine.inspect(id)
            _inspect.value = if (result.ok) result.stdout else result.message
        }
    }

    private companion object {
        const val MAX_LINES = 5_000
        const val TAIL_LINES = 500
        const val FLUSH_MS = 120L
    }
}

/**
 * Strip ANSI escape sequences. Container logs are full of colour codes and the
 * log view renders plain text — leaving them in shows literal `[32m` garbage.
 */
internal fun stripAnsi(line: String): String {
    if (line.indexOf(ESC) < 0) return line
    return line
        .replace(OSC_REGEX, "")
        .replace(CSI_REGEX, "")
}

private const val ESC = '\u001B'

/** CSI: ESC `[` ... final byte — colour and cursor codes. */
private val CSI_REGEX = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

/** OSC: ESC `]` ... terminated by BEL or ESC `\` — title and hyperlink codes. */
private val OSC_REGEX = Regex("\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)")
