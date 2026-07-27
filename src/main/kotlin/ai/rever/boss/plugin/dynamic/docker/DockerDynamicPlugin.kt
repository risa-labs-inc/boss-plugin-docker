package ai.rever.boss.plugin.dynamic.docker

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext

/**
 * Docker — manage the local Docker daemon from BOSS.
 *
 * Registers:
 * - a sidebar panel listing the project's Dockerfiles/compose files alongside
 *   every container, image, volume and network on the machine,
 * - a main-panel service tab per container (live logs, preview, inspect),
 * - `docker_*` MCP tools so in-terminal agents can drive the same operations.
 */
class DockerDynamicPlugin : DynamicPlugin {

    override val pluginId = DockerServices.PLUGIN_ID
    override val displayName = "Docker"
    override val version = "1.0.0"
    override val description =
        "Manage local Docker from the sidebar — containers, images, volumes, networks, and the current " +
            "project's Dockerfiles and compose files, with live logs and a preview of what each service serves"
    override val author = "Risa Labs"
    override val url = "https://github.com/risa-labs-inc/boss-plugin-docker"

    private var services: DockerServices? = null

    override fun register(context: PluginContext) {
        val services = DockerServices(context).also { this.services = it }
        services.start()

        context.panelRegistry.registerPanel(DockerPanelInfo) { ctx, panelInfo ->
            DockerPanelComponent(ctx, panelInfo, services)
        }
        context.tabRegistry.registerTabType(DockerServiceTabType) { tabInfo, ctx ->
            DockerServiceTabComponent(ctx, tabInfo, services)
        }
        context.registerMcpToolProvider(DockerMcpToolProvider(pluginId, services))
    }

    override fun dispose() {
        services?.dispose()
        services = null
    }
}
