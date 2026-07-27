package ai.rever.boss.plugin.dynamic.docker

import ai.rever.boss.plugin.api.Panel.Companion.bottom
import ai.rever.boss.plugin.api.Panel.Companion.left
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelInfo
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inventory2

/**
 * The Docker sidebar. Sits in the lower-left slot next to the other
 * project-scoped tools (git status, run configurations).
 */
object DockerPanelInfo : PanelInfo {
    override val id = PanelId("docker", 55, DockerServices.PLUGIN_ID)
    override val displayName = "Docker"
    override val icon = Icons.Outlined.Inventory2
    override val defaultSlotPosition = left.bottom
}
