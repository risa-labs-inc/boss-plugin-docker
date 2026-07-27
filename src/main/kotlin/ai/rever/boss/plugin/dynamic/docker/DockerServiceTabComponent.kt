package ai.rever.boss.plugin.dynamic.docker

import ai.rever.boss.plugin.api.TabComponentWithUI
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabTypeInfo
import ai.rever.boss.plugin.browser.BrowserConfig
import ai.rever.boss.plugin.browser.BrowserHandle
import ai.rever.boss.plugin.browser.BrowserService
import ai.rever.boss.plugin.ui.BossPrimaryButton
import ai.rever.boss.plugin.ui.BossSecondaryButton
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.ui.BossThemeColors
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.VerticalAlignBottom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.arkivanov.essenty.lifecycle.doOnDestroy

class DockerServiceTabComponent(
    ctx: ComponentContext,
    override val config: TabInfo,
    private val services: DockerServices,
) : TabComponentWithUI, ComponentContext by ctx {

    override val tabTypeInfo: TabTypeInfo = DockerServiceTabType

    private val viewModel = DockerServiceTabViewModel(
        services = services,
        tabInfo = config as? DockerServiceTabInfo
            ?: DockerServiceTabInfo(
                containerId = config.id.removePrefix("docker-service-"),
                containerName = config.title,
            ),
    )

    init {
        lifecycle.doOnDestroy { viewModel.dispose() }
    }

    @Composable
    override fun Content() {
        BossTheme {
            ServiceTabScreen(viewModel, services)
        }
    }
}

@Composable
private fun ServiceTabScreen(viewModel: DockerServiceTabViewModel, services: DockerServices) {
    val container by viewModel.container.collectAsState()
    val section by viewModel.section.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BossThemeColors.BackgroundColor),
    ) {
        ServiceHeader(viewModel, container)
        Divider(color = BossThemeColors.BorderColor)
        SectionTabs(section, viewModel::selectSection)
        Divider(color = BossThemeColors.BorderColor)

        Box(Modifier.fillMaxSize()) {
            when (section) {
                ServiceSection.LOGS -> LogsPane(viewModel, services)
                ServiceSection.PREVIEW -> PreviewPane(viewModel, services, container)
                ServiceSection.INSPECT -> InspectPane(viewModel)
            }
        }
    }
}

@Composable
private fun ServiceHeader(viewModel: DockerServiceTabViewModel, container: ContainerInfo?) {
    val status by viewModel.status.collectAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BossThemeColors.SurfaceColor)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StateDot(container)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = container?.name ?: "Container not found",
                color = BossThemeColors.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = buildList {
                add(container?.image ?: "—")
                container?.ports.orEmpty().takeIf { it.isNotEmpty() }
                    ?.let { ports -> add(ports.joinToString(" ") { it.display }) }
                container?.status?.takeIf { it.isNotBlank() }?.let(::add)
            }.joinToString("  ·  ")
            Text(
                text = subtitle,
                color = BossThemeColors.TextMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (status != null) {
            Text(status.orEmpty(), color = BossThemeColors.WarningColor, fontSize = 11.sp)
            Spacer(Modifier.width(8.dp))
        }

        if (container?.primaryPort != null) {
            BossSecondaryButton(
                text = "Open",
                onClick = viewModel::openInBrowser,
                icon = Icons.Outlined.OpenInBrowser,
            )
            Spacer(Modifier.width(6.dp))
        }
        if (container?.isRunning == true) {
            BossSecondaryButton(text = "Restart", onClick = viewModel::restart)
            Spacer(Modifier.width(6.dp))
            BossSecondaryButton(text = "Stop", onClick = viewModel::stop, isDestructive = true)
        } else if (container != null) {
            BossPrimaryButton(text = "Start", onClick = viewModel::start)
        }
    }
}

@Composable
private fun StateDot(container: ContainerInfo?) {
    val color = when {
        container == null -> BossThemeColors.TextMuted
        container.isRunning -> BossThemeColors.SuccessColor
        container.state.equals("exited", true) -> BossThemeColors.ErrorColor
        else -> BossThemeColors.WarningColor
    }
    Box(
        Modifier
            .size(8.dp)
            .background(color, RoundedCornerShape(4.dp)),
    )
}

@Composable
private fun SectionTabs(selected: ServiceSection, onSelect: (ServiceSection) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BossThemeColors.SurfaceColor)
            .padding(horizontal = 8.dp),
    ) {
        ServiceSection.entries.forEach { entry ->
            val isSelected = entry == selected
            Box(
                modifier = Modifier
                    .padding(vertical = 4.dp, horizontal = 2.dp)
                    .background(
                        if (isSelected) BossThemeColors.AccentColor.copy(alpha = 0.18f) else Color.Transparent,
                        RoundedCornerShape(4.dp),
                    ),
            ) {
                Text(
                    text = entry.label,
                    color = if (isSelected) BossThemeColors.TextPrimary else BossThemeColors.TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                    modifier = Modifier
                        .clickable { onSelect(entry) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

// -------------------------------------------------------------------- logs

@Composable
private fun LogsPane(viewModel: DockerServiceTabViewModel, services: DockerServices) {
    val logs by viewModel.logs.collectAsState()
    val paused by viewModel.paused.collectAsState()
    val autoScroll by viewModel.autoScroll.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size, autoScroll) {
        if (autoScroll && logs.isNotEmpty()) listState.scrollToItem(logs.lastIndex)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            Text(
                text = "${logs.size} lines",
                color = BossThemeColors.TextMuted,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            IconTool(
                icon = if (paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                description = if (paused) "Resume" else "Pause",
            ) { viewModel.setPaused(!paused) }
            IconTool(
                icon = Icons.Outlined.VerticalAlignBottom,
                description = "Auto-scroll",
                tint = if (autoScroll) BossThemeColors.AccentColor else BossThemeColors.TextMuted,
            ) { viewModel.setAutoScroll(!autoScroll) }
            IconTool(icon = Icons.Outlined.ContentCopy, description = "Copy logs") {
                services.context.clipboardProvider?.setText(viewModel.logText())
                services.toastInfo("Logs copied")
            }
            IconTool(icon = Icons.Outlined.Delete, description = "Clear") { viewModel.clearLogs() }
        }
        Divider(color = BossThemeColors.BorderColor)

        if (logs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Waiting for output…",
                    color = BossThemeColors.TextMuted,
                    fontSize = 12.sp,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .background(BossThemeColors.BackgroundColor)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                items(logs) { line ->
                    Text(
                        text = line,
                        color = BossThemeColors.TextSecondary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        softWrap = false,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// ----------------------------------------------------------------- preview

/**
 * Live preview of whatever the container is serving.
 *
 * Degrades in two steps: no published port → explain; no embedded browser
 * (missing JxBrowser licence, unsupported platform) → offer to open a host
 * browser tab instead. A dead pane is never an acceptable outcome.
 */
@Composable
private fun PreviewPane(
    viewModel: DockerServiceTabViewModel,
    services: DockerServices,
    container: ContainerInfo?,
) {
    val port = container?.primaryPort
    if (port == null) {
        CenteredNotice(
            title = "No published port",
            detail = "This container doesn't publish a port to the host, so there is nothing to preview.",
        )
        return
    }

    val browserService: BrowserService? = services.context.browserService
    if (browserService == null || !browserService.isAvailable()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "Embedded preview unavailable",
                color = BossThemeColors.TextSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Open ${port.localUrl} in a browser tab instead.",
                color = BossThemeColors.TextMuted,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(14.dp))
            BossPrimaryButton(
                text = "Open in browser tab",
                onClick = viewModel::openInBrowser,
                icon = Icons.Outlined.OpenInBrowser,
            )
        }
        return
    }

    var handle by remember(port.hostPort) { mutableStateOf<BrowserHandle?>(null) }

    LaunchedEffect(port.hostPort) {
        handle?.dispose()
        handle = browserService.createBrowser(BrowserConfig(url = port.localUrl))
    }

    DisposableEffect(port.hostPort) {
        onDispose {
            handle?.dispose()
            handle = null
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = port.localUrl,
                color = BossThemeColors.TextMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            IconTool(icon = Icons.Outlined.Refresh, description = "Reload") { handle?.reload() }
            IconTool(icon = Icons.Outlined.OpenInBrowser, description = "Open in browser tab") {
                viewModel.openInBrowser()
            }
        }
        Divider(color = BossThemeColors.BorderColor)
        Box(Modifier.fillMaxSize()) {
            val current = handle
            if (current != null && current.isValid) {
                current.Content()
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

// ----------------------------------------------------------------- inspect

@Composable
private fun InspectPane(viewModel: DockerServiceTabViewModel) {
    val inspect by viewModel.inspect.collectAsState()

    LaunchedEffect(Unit) { if (inspect == null) viewModel.refreshInspect() }

    if (inspect == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            IconTool(icon = Icons.Outlined.Refresh, description = "Refresh") { viewModel.refreshInspect() }
        }
        Divider(color = BossThemeColors.BorderColor)
        Box(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            Text(
                text = inspect.orEmpty(),
                color = BossThemeColors.TextSecondary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

// ------------------------------------------------------------------ shared

@Composable
private fun CenteredNotice(title: String, detail: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Inventory2,
            contentDescription = null,
            tint = BossThemeColors.TextMuted.copy(alpha = 0.5f),
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(title, color = BossThemeColors.TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text(detail, color = BossThemeColors.TextMuted, fontSize = 12.sp)
    }
}

@Composable
internal fun IconTool(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tint: Color = BossThemeColors.TextSecondary,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(28.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(15.dp),
        )
    }
}

