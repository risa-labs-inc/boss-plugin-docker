package ai.rever.boss.plugin.dynamic.docker

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Owns all Docker state and keeps it fresh.
 *
 * Freshness is **push-first**: after an initial snapshot the engine attaches to
 * `docker events` and refreshes when the daemon says something changed. That is
 * what makes the sidebar correct when a container is started from a terminal tab
 * rather than from this plugin. A slow reconcile tick runs alongside purely as a
 * safety net (a dropped event stream must not leave the UI permanently stale),
 * and the supervisor reconnects with backoff when the daemon goes away.
 */
class DockerEngine(
    private val scope: CoroutineScope,
    private val getProjectPath: () -> String?,
) {
    private val _daemon = MutableStateFlow<DaemonState>(DaemonState.Unknown)
    val daemon: StateFlow<DaemonState> = _daemon.asStateFlow()

    private val _containers = MutableStateFlow<List<ContainerInfo>>(emptyList())
    val containers: StateFlow<List<ContainerInfo>> = _containers.asStateFlow()

    private val _images = MutableStateFlow<List<ImageInfo>>(emptyList())
    val images: StateFlow<List<ImageInfo>> = _images.asStateFlow()

    private val _volumes = MutableStateFlow<List<VolumeInfo>>(emptyList())
    val volumes: StateFlow<List<VolumeInfo>> = _volumes.asStateFlow()

    private val _networks = MutableStateFlow<List<NetworkInfo>>(emptyList())
    val networks: StateFlow<List<NetworkInfo>> = _networks.asStateFlow()

    private val _composeProjects = MutableStateFlow<List<ComposeProject>>(emptyList())
    val composeProjects: StateFlow<List<ComposeProject>> = _composeProjects.asStateFlow()

    private val _projectArtifacts = MutableStateFlow<List<ProjectArtifact>>(emptyList())
    val projectArtifacts: StateFlow<List<ProjectArtifact>> = _projectArtifacts.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Coalesces refresh requests so a compose-up event burst causes one refresh. */
    private val refreshRequests = Channel<Unit>(Channel.CONFLATED)

    private var supervisor: Job? = null
    private var refresher: Job? = null
    private var reconciler: Job? = null
    private var scanner: Job? = null

    fun start() {
        if (supervisor != null) return
        refresher = scope.launch {
            for (ignored in refreshRequests) {
                delay(DEBOUNCE_MS) // let a burst of events settle into one refresh
                refreshAll()
            }
        }
        // Safety net only. If the event stream silently drops (daemon restart,
        // broken pipe) the UI must not stay stale forever.
        reconciler = scope.launch {
            while (isActive) {
                delay(RECONCILE_MS)
                if (_daemon.value is DaemonState.Running) requestRefresh()
            }
        }
        supervisor = scope.launch { superviseDaemon() }
        rescanProject()
    }

    fun dispose() {
        supervisor?.cancel()
        refresher?.cancel()
        reconciler?.cancel()
        scanner?.cancel()
        supervisor = null
        refresher = null
        reconciler = null
        scanner = null
        refreshRequests.close()
    }

    /** Ask for a refresh; safe to call from the UI as often as you like. */
    fun requestRefresh() {
        refreshRequests.trySend(Unit)
    }

    // ------------------------------------------------------------ supervision

    private suspend fun superviseDaemon() {
        var backoffMs = MIN_BACKOFF_MS
        while (scope.isActive) {
            when (val state = probeDaemon()) {
                is DaemonState.Running -> {
                    backoffMs = MIN_BACKOFF_MS
                    refreshAll()
                    // Blocks for as long as the daemon stays up. Returning means
                    // the daemon (or the stream) died — loop round and re-probe.
                    streamEvents()
                }

                is DaemonState.CliMissing -> {
                    clearAll()
                    delay(CLI_RECHECK_MS) // nothing to stream; just re-check slowly
                }

                else -> {
                    // Stopped / Error: a stopped daemon has no containers, and
                    // showing stale rows with live-looking actions is worse than
                    // showing none.
                    clearAll()
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                }
            }
            // Breathe before re-probing, so a daemon that dies instantly on every
            // connect can't spin this loop.
            delay(MIN_BACKOFF_MS)
        }
    }

    /** Cheap liveness probe; also the source of the server version we display. */
    suspend fun probeDaemon(): DaemonState {
        if (!DockerCli.isInstalled()) {
            _daemon.value = DaemonState.CliMissing
            return DaemonState.CliMissing
        }
        val result = DockerCli.exec(
            listOf("version", "--format", "{{.Server.Version}}"),
            timeoutMs = PROBE_TIMEOUT_MS,
        )
        val next = when {
            result.ok -> DaemonState.Running(result.stdout.trim().ifBlank { "unknown" })
            result.daemonUnreachable -> DaemonState.Stopped
            result.exitCode == DockerExec.EXIT_CLI_MISSING -> DaemonState.CliMissing
            // A timeout while Docker Desktop boots looks like "stopped", not an error.
            result.exitCode == DockerExec.EXIT_TIMEOUT -> DaemonState.Stopped
            else -> DaemonState.Error(result.message.take(300))
        }
        // Don't clobber an in-flight "Starting" with "Stopped" — the start action
        // owns that transition until it times out.
        if (!(_daemon.value is DaemonState.Starting && next is DaemonState.Stopped)) {
            _daemon.value = next
        }
        return next
    }

    /**
     * Attach to `docker events`. Every container/image/volume/network event
     * requests a (debounced) refresh. Returns when the stream ends.
     */
    private suspend fun streamEvents() {
        DockerCli.stream(
            listOf(
                "events",
                "--format", "{{json .}}",
                "--filter", "type=container",
                "--filter", "type=image",
                "--filter", "type=volume",
                "--filter", "type=network",
            ),
        ) { line ->
            val event = runCatching { DockerJson.decodeFromString<RawEvent>(line) }.getOrNull()
            if (event != null) requestRefresh()
        }
    }

    // ------------------------------------------------------------ refreshing

    suspend fun refreshAll() {
        if (_daemon.value !is DaemonState.Running) {
            if (probeDaemon() !is DaemonState.Running) return
        }
        _busy.value = true
        try {
            DockerCli.exec(listOf("ps", "-a", "--no-trunc", "--format", "{{json .}}")).let { r ->
                if (r.ok) {
                    _containers.value = parseNdJson<RawContainer>(r.stdout)
                        .map { it.toInfo() }
                        .sortedWith(compareByDescending<ContainerInfo> { it.isRunning }.thenBy { it.name })
                } else if (r.daemonUnreachable) {
                    _daemon.value = DaemonState.Stopped
                    clearAll()
                    return
                }
            }
            DockerCli.exec(listOf("images", "--format", "{{json .}}")).let { r ->
                if (r.ok) {
                    _images.value = parseNdJson<RawImage>(r.stdout)
                        .map { it.toInfo() }
                        .sortedBy { it.reference }
                }
            }
            DockerCli.exec(listOf("volume", "ls", "--format", "{{json .}}")).let { r ->
                if (r.ok) _volumes.value = parseNdJson<RawVolume>(r.stdout).map { it.toInfo() }.sortedBy { it.name }
            }
            DockerCli.exec(listOf("network", "ls", "--format", "{{json .}}")).let { r ->
                if (r.ok) _networks.value = parseNdJson<RawNetwork>(r.stdout).map { it.toInfo() }.sortedBy { it.name }
            }
            refreshComposeProjects()
        } finally {
            _busy.value = false
        }
    }

    /** `docker compose ls` emits a JSON *array*, unlike the NDJSON of `ps`/`images`. */
    private suspend fun refreshComposeProjects() {
        val result = DockerCli.exec(listOf("compose", "ls", "--all", "--format", "json"))
        if (!result.ok) return
        val text = result.stdout.trim()
        if (!text.startsWith("[")) return
        _composeProjects.value = runCatching {
            DockerJson.decodeFromString<List<ComposeProject>>(text)
        }.getOrDefault(emptyList())
    }

    private fun clearAll() {
        _containers.value = emptyList()
        _images.value = emptyList()
        _volumes.value = emptyList()
        _networks.value = emptyList()
        _composeProjects.value = emptyList()
    }

    // ------------------------------------------------------- project scanning

    /** Re-scan the open project for Dockerfiles and compose files. */
    fun rescanProject() {
        scanner?.cancel()
        scanner = scope.launch {
            val root = getProjectPath()?.let(::File)
            if (root == null || !root.isDirectory) {
                _projectArtifacts.value = emptyList()
                return@launch
            }
            _projectArtifacts.value = scanArtifacts(root)
        }
    }

    private fun scanArtifacts(root: File): List<ProjectArtifact> = buildList {
        root.walkTopDown()
            .maxDepth(SCAN_DEPTH)
            .onEnter { dir -> dir.name !in SKIP_DIRS && !dir.name.startsWith(".") || dir == root }
            .filter { it.isFile }
            .forEach { file ->
                val kind = classify(file.name) ?: return@forEach
                add(
                    ProjectArtifact(
                        file = file,
                        kind = kind,
                        relativePath = file.relativeToOrNull(root)?.path ?: file.name,
                    ),
                )
                if (size >= MAX_ARTIFACTS) return@buildList
            }
    }.sortedWith(compareBy({ it.kind != ProjectArtifact.Kind.COMPOSE }, { it.relativePath }))

    private fun classify(fileName: String): ProjectArtifact.Kind? {
        val lower = fileName.lowercase()
        return when {
            lower == "dockerfile" || lower.startsWith("dockerfile.") -> ProjectArtifact.Kind.DOCKERFILE
            lower.endsWith(".dockerfile") -> ProjectArtifact.Kind.DOCKERFILE
            COMPOSE_NAMES.contains(lower) -> ProjectArtifact.Kind.COMPOSE
            else -> null
        }
    }

    // ------------------------------------------------------------- operations

    suspend fun startContainer(id: String) = op(listOf("start", id))
    suspend fun stopContainer(id: String) = op(listOf("stop", id), timeoutMs = 60_000)
    suspend fun restartContainer(id: String) = op(listOf("restart", id), timeoutMs = 60_000)
    suspend fun removeContainer(id: String, force: Boolean) =
        op(buildList { add("rm"); if (force) add("-f"); add(id) })

    suspend fun removeImage(id: String) = op(listOf("rmi", id))
    suspend fun removeVolume(name: String) = op(listOf("volume", "rm", name))
    suspend fun removeNetwork(id: String) = op(listOf("network", "rm", id))

    suspend fun inspect(id: String): DockerExec = DockerCli.exec(listOf("inspect", id))

    /** Find a container by id, short id, or exact name. */
    fun findContainer(ref: String): ContainerInfo? =
        containers.value.firstOrNull { it.id == ref || it.shortId == ref || it.name == ref }
            ?: containers.value.firstOrNull { it.id.startsWith(ref) }

    private suspend fun op(args: List<String>, timeoutMs: Long = 30_000): DockerExec =
        DockerCli.exec(args, timeoutMs = timeoutMs).also { requestRefresh() }

    /**
     * Ask the OS to launch Docker Desktop, then poll until the daemon answers.
     * macOS/Windows only — on Linux the daemon is a service the user manages.
     */
    suspend fun startDockerDesktop(): Boolean {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val launch = when {
            os.contains("mac") -> listOf("/usr/bin/open", "-a", "Docker")
            os.contains("win") -> listOf("cmd", "/c", "start", "", "Docker Desktop")
            else -> return false
        }
        _daemon.value = DaemonState.Starting
        val started = runCatching { ProcessBuilder(launch).start() }.isSuccess
        if (!started) {
            _daemon.value = DaemonState.Stopped
            return false
        }
        repeat(START_POLL_ATTEMPTS) {
            delay(START_POLL_INTERVAL_MS)
            if (!scope.isActive) return false
            if (DockerCli.exec(
                    listOf("version", "--format", "{{.Server.Version}}"),
                    timeoutMs = PROBE_TIMEOUT_MS,
                ).ok
            ) {
                probeDaemon()
                refreshAll()
                return true
            }
        }
        _daemon.value = DaemonState.Stopped
        return false
    }

    private companion object {
        const val DEBOUNCE_MS = 250L
        const val RECONCILE_MS = 30_000L
        const val MIN_BACKOFF_MS = 3_000L
        const val MAX_BACKOFF_MS = 30_000L
        const val CLI_RECHECK_MS = 60_000L
        const val PROBE_TIMEOUT_MS = 8_000L
        const val START_POLL_INTERVAL_MS = 2_000L
        const val START_POLL_ATTEMPTS = 45 // ~90s: Docker Desktop is slow to boot
        const val SCAN_DEPTH = 4
        const val MAX_ARTIFACTS = 60

        val SKIP_DIRS = setOf(
            "node_modules", "build", "dist", "out", "target", "vendor",
            "venv", "__pycache__", "Pods", "DerivedData", "tmp",
        )
        val COMPOSE_NAMES = setOf(
            "docker-compose.yml", "docker-compose.yaml",
            "compose.yml", "compose.yaml",
        )
    }
}
