package ai.rever.boss.plugin.dynamic.docker

import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.plugin.api.TabTypeInfo
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.ui.graphics.vector.ImageVector

object DockerServiceTabType : TabTypeInfo {
    override val typeId = TabTypeId("docker-service", DockerServices.PLUGIN_ID)
    override val displayName = "Docker Service"
    override val icon: ImageVector = Icons.Outlined.Inventory2
}

/**
 * One service tab, bound to the container it watches. The id is stable per
 * container so opening the same service twice focuses the existing tab instead
 * of stacking a duplicate (see [DockerServices.openServiceTab]).
 */
data class DockerServiceTabInfo(
    val containerId: String,
    val containerName: String,
    override val id: String = "docker-service-${containerId.take(12)}",
    override val typeId: TabTypeId = DockerServiceTabType.typeId,
    override val title: String = containerName,
    override val icon: ImageVector = Icons.Outlined.Inventory2,
) : TabInfo

/** Which body the service tab is showing. */
enum class ServiceSection(val label: String) {
    LOGS("Logs"),
    PREVIEW("Preview"),
    INSPECT("Inspect"),
}
