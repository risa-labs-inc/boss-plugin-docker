package ai.rever.boss.plugin.dynamic.docker

import ai.rever.boss.plugin.ui.BossAlertDialog
import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelInfo
import ai.rever.boss.plugin.ui.BossSearchBar
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.ui.BossThemeColors
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.AlertDialog
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.ComponentContext

class DockerPanelComponent(
    ctx: ComponentContext,
    override val panelInfo: PanelInfo,
    private val services: DockerServices,
) : PanelComponentWithUI, ComponentContext by ctx {

    private val viewModel = DockerPanelViewModel(services)

    @Composable
    override fun Content() {
        BossTheme {
            DockerPanelScreen(viewModel)
        }
    }
}

@Composable
private fun DockerPanelScreen(viewModel: DockerPanelViewModel) {
    val daemon by viewModel.daemon.collectAsState()
    val query by viewModel.query.collectAsState()
    val confirm by viewModel.confirm.collectAsState()
    val runRequest by viewModel.runRequest.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BossThemeColors.SurfaceColor),
    ) {
        PanelHeader(viewModel)
        Divider(color = BossThemeColors.BorderColor)

        when (daemon) {
            is DaemonState.CliMissing -> CliMissingBody()
            is DaemonState.Stopped, is DaemonState.Starting -> DaemonStoppedBody(viewModel)
            is DaemonState.Error -> ErrorBody((daemon as DaemonState.Error).message, viewModel)
            is DaemonState.Unknown -> LoadingBody()
            is DaemonState.Running -> {
                Box(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                    Box(Modifier.height(28.dp).fillMaxWidth()) {
                        BossSearchBar(
                            query = query,
                            onQueryChange = viewModel::setQuery,
                            placeholder = "Filter…",
                        )
                    }
                }
                DockerLists(viewModel)
            }
        }
    }

    runRequest?.let { RunDialog(viewModel, it) }
    confirm?.let { ConfirmDialog(viewModel, it) }
}

@Composable
private fun PanelHeader(viewModel: DockerPanelViewModel) {
    val busy by viewModel.busy.collectAsState()
    val autoOpen by viewModel.autoOpenServiceTab.collectAsState()
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "DOCKER",
            color = BossThemeColors.TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                color = BossThemeColors.TextMuted,
                strokeWidth = 1.5.dp,
            )
            Spacer(Modifier.width(6.dp))
        }
        IconTool(icon = Icons.Outlined.Refresh, description = "Refresh") { viewModel.refresh() }
        Box {
            IconTool(icon = Icons.Outlined.MoreVert, description = "More") { menuOpen = true }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    onClick = {
                        viewModel.setAutoOpen(!autoOpen)
                        menuOpen = false
                    },
                ) {
                    Text(
                        text = if (autoOpen) "✓ Auto-open service tab" else "Auto-open service tab",
                        fontSize = 12.sp,
                        color = BossThemeColors.TextPrimary,
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------ daemon states

@Composable
private fun LoadingBody() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun CliMissingBody() {
    EmptyBody(
        title = "Docker isn't installed",
        detail = "Install Docker Desktop (or the docker CLI) and reopen this panel.",
    )
}

@Composable
private fun DaemonStoppedBody(viewModel: DockerPanelViewModel) {
    val starting by viewModel.startingDaemon.collectAsState()
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = DockerIcon,
            contentDescription = null,
            tint = BossThemeColors.TextMuted.copy(alpha = 0.5f),
            modifier = Modifier.size(36.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Docker isn't running",
            color = BossThemeColors.TextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (starting) "Starting Docker…" else "Start the daemon to see containers.",
            color = BossThemeColors.TextMuted,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(12.dp))
        if (starting) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            TextButton(onClick = viewModel::startDaemon) {
                Text("Start Docker", color = BossThemeColors.AccentColor, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ErrorBody(message: String, viewModel: DockerPanelViewModel) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Docker error", color = BossThemeColors.ErrorColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Text(message, color = BossThemeColors.TextMuted, fontSize = 11.sp)
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = viewModel::refresh) {
            Text("Retry", color = BossThemeColors.AccentColor, fontSize = 12.sp)
        }
    }
}

@Composable
private fun EmptyBody(title: String, detail: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = DockerIcon,
            contentDescription = null,
            tint = BossThemeColors.TextMuted.copy(alpha = 0.5f),
            modifier = Modifier.size(36.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(title, color = BossThemeColors.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text(detail, color = BossThemeColors.TextMuted, fontSize = 11.sp)
    }
}

// -------------------------------------------------------------------- lists

@Composable
private fun DockerLists(viewModel: DockerPanelViewModel) {
    val expanded by viewModel.expanded.collectAsState()
    val artifacts by viewModel.projectArtifacts.collectAsState()
    val composeProjects by viewModel.composeProjects.collectAsState()
    val containers by viewModel.containers.collectAsState()
    val images by viewModel.images.collectAsState()
    val volumes by viewModel.volumes.collectAsState()
    val networks by viewModel.networks.collectAsState()

    val shownArtifacts = artifacts.filter { viewModel.matches(it.relativePath) }
    val shownContainers = containers.filter { viewModel.matches(it.name) || viewModel.matches(it.image) }
    val shownImages = images.filter { viewModel.matches(it.reference) }
    val shownVolumes = volumes.filter { viewModel.matches(it.name) }
    val shownNetworks = networks.filter { viewModel.matches(it.name) }

    LazyColumn(Modifier.fillMaxSize()) {
        section(PanelSection.PROJECT, shownArtifacts.size, expanded, viewModel) {
            if (shownArtifacts.isEmpty()) {
                item { HintRow("No Dockerfile or compose file in this project") }
            }
            items(shownArtifacts, key = { it.file.absolutePath }) { artifact ->
                ArtifactRow(artifact, composeProjects, viewModel)
            }
        }
        section(PanelSection.CONTAINERS, shownContainers.size, expanded, viewModel) {
            if (shownContainers.isEmpty()) item { HintRow("No containers") }
            items(shownContainers, key = { it.id }) { container ->
                ContainerRow(container, viewModel)
            }
        }
        section(PanelSection.IMAGES, shownImages.size, expanded, viewModel) {
            if (shownImages.isEmpty()) item { HintRow("No images") }
            items(shownImages, key = { it.id + it.tag }) { image ->
                ImageRow(image, viewModel)
            }
        }
        section(PanelSection.VOLUMES, shownVolumes.size, expanded, viewModel) {
            if (shownVolumes.isEmpty()) item { HintRow("No volumes") }
            items(shownVolumes, key = { it.name }) { volume ->
                RowShell(
                    title = volume.name,
                    subtitle = volume.driver,
                    actions = listOf(RowAction("Remove", destructive = true) { viewModel.removeVolume(volume) }),
                )
            }
        }
        section(PanelSection.NETWORKS, shownNetworks.size, expanded, viewModel) {
            if (shownNetworks.isEmpty()) item { HintRow("No networks") }
            items(shownNetworks, key = { it.id }) { network ->
                RowShell(
                    title = network.name,
                    subtitle = "${network.driver} · ${network.scope}",
                    actions = listOf(RowAction("Remove", destructive = true) { viewModel.removeNetwork(network) }),
                )
            }
        }
    }
}

/** Header + (when expanded) body, as one LazyListScope block. */
private fun LazyListScope.section(
    section: PanelSection,
    count: Int,
    expanded: Set<PanelSection>,
    viewModel: DockerPanelViewModel,
    body: LazyListScope.() -> Unit,
) {
    item(key = "header-${section.name}") {
        SectionHeader(section, count, section in expanded) { viewModel.toggleSection(section) }
    }
    if (section in expanded) body()
}

@Composable
private fun SectionHeader(section: PanelSection, count: Int, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (expanded) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = BossThemeColors.TextMuted,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = section.label.uppercase(),
            color = BossThemeColors.TextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(6.dp))
        Text(text = count.toString(), color = BossThemeColors.TextMuted, fontSize = 10.sp)
    }
}

@Composable
private fun HintRow(text: String) {
    Text(
        text = text,
        color = BossThemeColors.TextMuted,
        fontSize = 11.sp,
        modifier = Modifier.padding(start = 26.dp, end = 8.dp, top = 2.dp, bottom = 6.dp),
    )
}

@Composable
private fun ArtifactRow(
    artifact: ProjectArtifact,
    composeProjects: List<ComposeProject>,
    viewModel: DockerPanelViewModel,
) {
    val isCompose = artifact.kind == ProjectArtifact.Kind.COMPOSE
    // A compose file is "up" when a project with a matching config file is running.
    val runningProject = composeProjects.firstOrNull { project ->
        project.isRunning && project.configFiles.contains(artifact.file.absolutePath)
    }

    RowShell(
        title = artifact.relativePath,
        subtitle = if (isCompose) {
            runningProject?.status ?: "compose"
        } else {
            "Dockerfile"
        },
        leading = {
            Box(
                Modifier
                    .size(6.dp)
                    .background(
                        if (runningProject != null) BossThemeColors.SuccessColor else Color.Transparent,
                        RoundedCornerShape(3.dp),
                    ),
            )
        },
        trailing = {
            IconTool(
                icon = Icons.Outlined.PlayArrow,
                description = if (isCompose) "Compose up" else "Build & run",
                tint = BossThemeColors.AccentColor,
            ) {
                if (isCompose) viewModel.composeUp(artifact) else viewModel.beginRun(artifact)
            }
        },
        actions = buildList {
            if (isCompose) {
                add(RowAction("Up (build)") { viewModel.composeUp(artifact) })
                if (runningProject != null) {
                    add(RowAction("Down", destructive = true) { viewModel.composeDown(runningProject) })
                }
            } else {
                add(RowAction("Build & run…") { viewModel.beginRun(artifact) })
            }
        },
    )
}

@Composable
private fun ContainerRow(container: ContainerInfo, viewModel: DockerPanelViewModel) {
    val port = container.primaryPort
    RowShell(
        title = container.name,
        subtitle = listOfNotNull(
            container.image.takeIf { it.isNotBlank() },
            port?.display,
            container.status.takeIf { it.isNotBlank() },
        ).joinToString(" · "),
        onClick = { viewModel.openServiceTab(container) },
        leading = {
            Box(
                Modifier
                    .size(6.dp)
                    .background(
                        when {
                            container.isRunning -> BossThemeColors.SuccessColor
                            container.state.equals("exited", true) -> BossThemeColors.ErrorColor
                            else -> BossThemeColors.WarningColor
                        },
                        RoundedCornerShape(3.dp),
                    ),
            )
        },
        trailing = {
            if (port != null && container.isRunning) {
                IconTool(icon = Icons.Outlined.OpenInBrowser, description = "Open ${port.localUrl}") {
                    viewModel.openInBrowser(container)
                }
            }
        },
        actions = buildList {
            add(RowAction("Open service tab") { viewModel.openServiceTab(container) })
            if (container.isRunning) {
                add(RowAction("Restart") { viewModel.restartContainer(container) })
                add(RowAction("Stop") { viewModel.stopContainer(container) })
            } else {
                add(RowAction("Start") { viewModel.startContainer(container) })
            }
            add(RowAction("Remove", destructive = true) { viewModel.removeContainer(container) })
        },
    )
}

@Composable
private fun ImageRow(image: ImageInfo, viewModel: DockerPanelViewModel) {
    RowShell(
        title = image.reference,
        subtitle = listOfNotNull(
            image.size.takeIf { it.isNotBlank() },
            image.createdSince.takeIf { it.isNotBlank() },
            "dangling".takeIf { image.isDangling },
        ).joinToString(" · "),
        actions = listOf(RowAction("Remove", destructive = true) { viewModel.removeImage(image) }),
    )
}

/** One entry in a row's ⋮ menu. */
private data class RowAction(
    val label: String,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/** Shared row chrome: dot, two lines of text, optional trailing action, ⋮ menu. */
@Composable
private fun RowShell(
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    actions: List<RowAction> = emptyList(),
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(start = 22.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(6.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = BossThemeColors.TextPrimary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    color = BossThemeColors.TextMuted,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
        if (actions.isNotEmpty()) {
            Box {
                IconTool(icon = Icons.Outlined.MoreVert, description = "Actions") { menuOpen = true }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    actions.forEach { action ->
                        DropdownMenuItem(
                            onClick = {
                                menuOpen = false
                                action.onClick()
                            },
                        ) {
                            Text(
                                text = action.label,
                                fontSize = 12.sp,
                                color = if (action.destructive) {
                                    BossThemeColors.ErrorColor
                                } else {
                                    BossThemeColors.TextPrimary
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ dialogs

@Composable
private fun RunDialog(viewModel: DockerPanelViewModel, request: RunRequest) {
    BossAlertDialog(
        onDismissRequest = viewModel::cancelRun,
        backgroundColor = BossThemeColors.SurfaceColor,
        title = {
            Text(
                "Build & run ${request.artifact.suggestedName}",
                color = BossThemeColors.TextPrimary,
                fontSize = 14.sp,
            )
        },
        text = {
            Column {
                Text(
                    text = request.artifact.relativePath,
                    color = BossThemeColors.TextMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(12.dp))
                PortField("Host port", request.hostPort) { viewModel.updateRunRequest(hostPort = it) }
                Spacer(Modifier.height(8.dp))
                PortField("Container port", request.containerPort) {
                    viewModel.updateRunRequest(containerPort = it)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Published on 127.0.0.1 only — not reachable from your network.",
                    color = BossThemeColors.TextMuted,
                    fontSize = 10.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::confirmRun, enabled = request.isValid) {
                Text(
                    "Build & run",
                    color = if (request.isValid) BossThemeColors.AccentColor else BossThemeColors.TextMuted,
                    fontSize = 12.sp,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::cancelRun) {
                Text("Cancel", color = BossThemeColors.TextSecondary, fontSize = 12.sp)
            }
        },
    )
}

@Composable
private fun PortField(label: String, value: String, onChange: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            color = BossThemeColors.TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.width(110.dp),
        )
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = { input -> onChange(input.filter { it.isDigit() }.take(5)) },
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(
                color = BossThemeColors.TextPrimary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(BossThemeColors.AccentColor),
            modifier = Modifier
                .width(90.dp)
                .background(BossThemeColors.BackgroundColor, RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun ConfirmDialog(viewModel: DockerPanelViewModel, request: ConfirmRequest) {
    BossAlertDialog(
        onDismissRequest = viewModel::dismissConfirm,
        backgroundColor = BossThemeColors.SurfaceColor,
        title = { Text(request.title, color = BossThemeColors.TextPrimary, fontSize = 14.sp) },
        text = { Text(request.message, color = BossThemeColors.TextSecondary, fontSize = 12.sp) },
        confirmButton = {
            TextButton(onClick = viewModel::acceptConfirm) {
                Text(request.confirmLabel, color = BossThemeColors.ErrorColor, fontSize = 12.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismissConfirm) {
                Text("Cancel", color = BossThemeColors.TextSecondary, fontSize = 12.sp)
            }
        },
    )
}
