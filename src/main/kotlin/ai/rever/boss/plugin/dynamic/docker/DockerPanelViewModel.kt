package ai.rever.boss.plugin.dynamic.docker

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Collapsible groups in the sidebar, in display order. */
enum class PanelSection(val label: String) {
    PROJECT("Project"),
    CONTAINERS("Containers"),
    IMAGES("Images"),
    VOLUMES("Volumes"),
    NETWORKS("Networks"),
}

/** A pending "Build & Run" the user is filling in ports for. */
data class RunRequest(
    val artifact: ProjectArtifact,
    val hostPort: String,
    val containerPort: String,
) {
    val hostPortValue: Int? get() = hostPort.toIntOrNull()?.takeIf { it in 1..65535 }
    val containerPortValue: Int? get() = containerPort.toIntOrNull()?.takeIf { it in 1..65535 }
    val isValid: Boolean get() = hostPortValue != null && containerPortValue != null
}

/** A destructive action awaiting confirmation. */
data class ConfirmRequest(
    val title: String,
    val message: String,
    val confirmLabel: String,
    val onConfirm: () -> Unit,
)

class DockerPanelViewModel(private val services: DockerServices) {

    private val engine = services.engine
    private val scope = services.scope

    val daemon: StateFlow<DaemonState> = engine.daemon
    val containers: StateFlow<List<ContainerInfo>> = engine.containers
    val images: StateFlow<List<ImageInfo>> = engine.images
    val volumes: StateFlow<List<VolumeInfo>> = engine.volumes
    val networks: StateFlow<List<NetworkInfo>> = engine.networks
    val composeProjects: StateFlow<List<ComposeProject>> = engine.composeProjects
    val projectArtifacts: StateFlow<List<ProjectArtifact>> = engine.projectArtifacts
    val busy: StateFlow<Boolean> = engine.busy
    val autoOpenServiceTab: StateFlow<Boolean> = services.autoOpenServiceTab

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _expanded = MutableStateFlow(setOf(PanelSection.PROJECT, PanelSection.CONTAINERS))
    val expanded: StateFlow<Set<PanelSection>> = _expanded.asStateFlow()

    private val _runRequest = MutableStateFlow<RunRequest?>(null)
    val runRequest: StateFlow<RunRequest?> = _runRequest.asStateFlow()

    private val _confirm = MutableStateFlow<ConfirmRequest?>(null)
    val confirm: StateFlow<ConfirmRequest?> = _confirm.asStateFlow()

    private val _startingDaemon = MutableStateFlow(false)
    val startingDaemon: StateFlow<Boolean> = _startingDaemon.asStateFlow()

    // ------------------------------------------------------------------ view

    fun setQuery(value: String) {
        _query.value = value
    }

    fun toggleSection(section: PanelSection) {
        _expanded.update { if (section in it) it - section else it + section }
    }

    fun refresh() {
        engine.rescanProject()
        engine.requestRefresh()
    }

    fun setAutoOpen(enabled: Boolean) = services.setAutoOpenServiceTab(enabled)

    fun matches(text: String): Boolean {
        val q = _query.value.trim()
        return q.isEmpty() || text.contains(q, ignoreCase = true)
    }

    // ---------------------------------------------------------------- daemon

    fun startDaemon() {
        if (_startingDaemon.value) return
        scope.launch {
            _startingDaemon.value = true
            val ok = engine.startDockerDesktop()
            _startingDaemon.value = false
            if (!ok) {
                services.toastError("Couldn't start Docker. Launch Docker Desktop manually and try again.")
            }
        }
    }

    // ------------------------------------------------------------- run flow

    /** Open the port form for [artifact], pre-filled from EXPOSE and last use. */
    fun beginRun(artifact: ProjectArtifact) {
        scope.launch {
            val containerPort = DockerActions.firstExposedPort(artifact.file) ?: DEFAULT_CONTAINER_PORT
            val hostPort = services.actions.suggestedHostPort(artifact)
            _runRequest.value = RunRequest(artifact, hostPort.toString(), containerPort.toString())
        }
    }

    fun updateRunRequest(hostPort: String? = null, containerPort: String? = null) {
        _runRequest.update { current ->
            current?.copy(
                hostPort = hostPort ?: current.hostPort,
                containerPort = containerPort ?: current.containerPort,
            )
        }
    }

    fun cancelRun() {
        _runRequest.value = null
    }

    fun confirmRun() {
        val request = _runRequest.value ?: return
        val host = request.hostPortValue ?: return
        val container = request.containerPortValue ?: return
        _runRequest.value = null
        val name = services.actions.buildAndRun(request.artifact, host, container)
        if (name != null) {
            services.toastInfo("Building ${request.artifact.suggestedName} → localhost:$host")
        }
    }

    fun composeUp(artifact: ProjectArtifact) {
        if (services.actions.composeUp(artifact)) {
            services.toastInfo("Starting ${artifact.suggestedName}…")
        }
    }

    fun composeDown(project: ComposeProject) {
        askConfirm(
            title = "Stop ${project.name}?",
            message = "This runs `docker compose down`, stopping and removing the project's containers.",
            confirmLabel = "Down",
        ) {
            if (services.actions.composeDown(project)) {
                services.toastInfo("Stopping ${project.name}…")
            }
        }
    }

    // ------------------------------------------------------- container actions

    fun openServiceTab(container: ContainerInfo) = services.openServiceTab(container)

    fun openInBrowser(container: ContainerInfo) {
        val port = container.primaryPort ?: return
        services.openUrl(port.localUrl, container.name)
    }

    fun startContainer(container: ContainerInfo) = run("Started ${container.name}") {
        engine.startContainer(container.id)
    }

    fun stopContainer(container: ContainerInfo) = run("Stopped ${container.name}") {
        engine.stopContainer(container.id)
    }

    fun restartContainer(container: ContainerInfo) = run("Restarted ${container.name}") {
        engine.restartContainer(container.id)
    }

    fun removeContainer(container: ContainerInfo) {
        askConfirm(
            title = "Remove ${container.name}?",
            message = "The container and anything written inside it (outside a volume) is deleted. This can't be undone.",
            confirmLabel = "Remove",
        ) {
            run("Removed ${container.name}") { engine.removeContainer(container.id, force = container.isRunning) }
        }
    }

    fun removeImage(image: ImageInfo) {
        askConfirm(
            title = "Remove ${image.reference}?",
            message = "The image is deleted from this machine and would need to be pulled or rebuilt again.",
            confirmLabel = "Remove",
        ) {
            run("Removed ${image.reference}") { engine.removeImage(image.id) }
        }
    }

    fun removeVolume(volume: VolumeInfo) {
        askConfirm(
            title = "Remove volume ${volume.name}?",
            message = "All data stored in this volume is permanently deleted.",
            confirmLabel = "Remove",
        ) {
            run("Removed ${volume.name}") { engine.removeVolume(volume.name) }
        }
    }

    fun removeNetwork(network: NetworkInfo) {
        askConfirm(
            title = "Remove network ${network.name}?",
            message = "Containers still attached to it will lose this network.",
            confirmLabel = "Remove",
        ) {
            run("Removed ${network.name}") { engine.removeNetwork(network.id) }
        }
    }

    // --------------------------------------------------------------- confirm

    /**
     * Destructive actions always route through here. The dialog is rendered by
     * the panel itself rather than the host's `genericDialogProvider` — that
     * provider is nullable, and "the host didn't give us a dialog" must never
     * become "we deleted it without asking".
     */
    private fun askConfirm(title: String, message: String, confirmLabel: String, action: () -> Unit) {
        _confirm.value = ConfirmRequest(title, message, confirmLabel, action)
    }

    fun dismissConfirm() {
        _confirm.value = null
    }

    fun acceptConfirm() {
        val request = _confirm.value ?: return
        _confirm.value = null
        request.onConfirm()
    }

    private fun run(successMessage: String, block: suspend () -> DockerExec) {
        scope.launch {
            val result = block()
            if (result.ok) services.toastSuccess(successMessage) else services.toastError(result.message.take(200))
        }
    }

    private companion object {
        const val DEFAULT_CONTAINER_PORT = 8080
    }
}
