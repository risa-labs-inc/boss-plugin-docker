package ai.rever.boss.plugin.dynamic.docker

import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import java.io.File

/**
 * `docker_*` tools for in-terminal agents. They surface as `mcp__boss__docker_*`
 * and appear/disappear with the plugin.
 *
 * Destructive tools are gated on the `docker.manage` permission the manifest
 * declares. Admins implicitly hold every permission, so a signed-in admin sees
 * them immediately; everyone else needs an explicit grant.
 */
class DockerMcpToolProvider(
    override val providerId: String,
    private val services: DockerServices,
) : McpToolProvider {

    private val engine get() = services.engine

    override fun tools(): List<McpToolDefinition> = listOf(
        McpToolDefinition(
            name = "docker_ps",
            description = "List Docker containers with state, image and published ports. " +
                "Set all=false to show only running containers.",
            inputSchema = """
                {"type":"object","properties":{
                  "all":{"type":"boolean","description":"Include stopped containers (default true)"}
                }}
            """.trimIndent(),
            handler = McpToolHandler { args ->
                requireDaemon() ?: return@McpToolHandler daemonError()
                engine.refreshAll()
                val all = args.boolean("all") ?: true
                val list = engine.containers.value.filter { all || it.isRunning }
                if (list.isEmpty()) return@McpToolHandler McpToolResult("No containers.")
                McpToolResult(
                    list.joinToString("\n") { c ->
                        val ports = c.ports.joinToString(",") { it.display }.ifBlank { "-" }
                        "${c.shortId}  ${c.name}  ${c.image}  ${c.state}  ports=$ports"
                    },
                )
            },
        ),

        McpToolDefinition(
            name = "docker_images",
            description = "List Docker images on this machine.",
            handler = McpToolHandler {
                requireDaemon() ?: return@McpToolHandler daemonError()
                engine.refreshAll()
                val list = engine.images.value
                if (list.isEmpty()) return@McpToolHandler McpToolResult("No images.")
                McpToolResult(list.joinToString("\n") { "${it.reference}  ${it.size}  ${it.createdSince}" })
            },
        ),

        McpToolDefinition(
            name = "docker_project_files",
            description = "List Dockerfiles and compose files found in the currently open BOSS project.",
            handler = McpToolHandler {
                engine.rescanProject()
                val list = engine.projectArtifacts.value
                if (list.isEmpty()) return@McpToolHandler McpToolResult("No Dockerfile or compose file in this project.")
                McpToolResult(list.joinToString("\n") { "${it.kind.name.lowercase()}  ${it.relativePath}" })
            },
        ),

        McpToolDefinition(
            name = "docker_logs",
            description = "Recent log output of a container (by name, short id or full id).",
            inputSchema = """
                {"type":"object","properties":{
                  "container":{"type":"string","description":"Container name or id"},
                  "tail":{"type":"integer","description":"Number of trailing lines (default 200, max 2000)"}
                },"required":["container"]}
            """.trimIndent(),
            handler = McpToolHandler { args ->
                requireDaemon() ?: return@McpToolHandler daemonError()
                val ref = args.string("container")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: container", isError = true)
                val tail = (args.int("tail") ?: 200).coerceIn(1, 2000)
                val id = engine.findContainer(ref)?.id ?: ref
                val result = DockerCli.exec(listOf("logs", "--tail", tail.toString(), id), timeoutMs = 20_000)
                if (!result.ok) {
                    McpToolResult(result.message.ifBlank { "docker logs failed" }, isError = true)
                } else {
                    // docker sends container stdout on stdout and stderr on stderr;
                    // both matter in a log dump.
                    McpToolResult(
                        listOf(result.stdout, result.stderr)
                            .filter { it.isNotBlank() }
                            .joinToString("\n")
                            .ifBlank { "(no output)" },
                    )
                }
            },
        ),

        McpToolDefinition(
            name = "docker_build",
            description = "Build a Dockerfile and run it, publishing a port on 127.0.0.1. " +
                "Opens a BOSS terminal tab showing the build, then starts the container detached.",
            inputSchema = """
                {"type":"object","properties":{
                  "dockerfile":{"type":"string","description":"Path to the Dockerfile (absolute, or relative to the project)"},
                  "host_port":{"type":"integer","description":"Host port to publish on (default: a free port)"},
                  "container_port":{"type":"integer","description":"Port the container listens on (default: first EXPOSE, else 8080)"}
                },"required":["dockerfile"]}
            """.trimIndent(),
            readOnly = false,
            handler = McpToolHandler { args ->
                val path = args.string("dockerfile")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: dockerfile", isError = true)
                val file = resolveProjectFile(path)
                    ?: return@McpToolHandler McpToolResult("Dockerfile not found: $path", isError = true)

                val artifact = ProjectArtifact(
                    file = file,
                    kind = ProjectArtifact.Kind.DOCKERFILE,
                    relativePath = file.name,
                )
                val containerPort = args.int("container_port")
                    ?: DockerActions.firstExposedPort(file)
                    ?: 8080
                val hostPort = args.int("host_port") ?: DockerActions.freePort()
                val name = services.actions.buildAndRun(artifact, hostPort, containerPort)
                    ?: return@McpToolHandler McpToolResult("Couldn't open a terminal tab to run the build.", isError = true)
                // Deliberately hedged. The build runs in the plugin's shared terminal
                // tab, so a docker command issued behind it interrupts it — reporting
                // "it will start" would be a promise this cannot keep. Ask docker_ps.
                McpToolResult(
                    "Building ${file.absolutePath} in the docker terminal tab. " +
                        "If the build completes it starts as container '$name' on " +
                        "http://localhost:$hostPort — check docker_ps. Issuing another " +
                        "docker command first interrupts this one.",
                )
            },
        ),

        McpToolDefinition(
            name = "docker_start",
            description = "Start an existing stopped container.",
            inputSchema = containerArgSchema,
            readOnly = false,
            handler = McpToolHandler { args -> mutate(args) { engine.startContainer(it) } },
        ),

        McpToolDefinition(
            name = "docker_restart",
            description = "Restart a container.",
            inputSchema = containerArgSchema,
            readOnly = false,
            handler = McpToolHandler { args -> mutate(args) { engine.restartContainer(it) } },
        ),

        McpToolDefinition.withRbac(
            name = "docker_stop",
            description = "Stop a running container.",
            inputSchema = containerArgSchema,
            readOnly = false,
            requiredPermissions = listOf(PERMISSION_MANAGE),
            handler = McpToolHandler { args -> mutate(args) { engine.stopContainer(it) } },
        ),

        McpToolDefinition.withRbac(
            name = "docker_rm",
            description = "Remove a container. Destructive: anything written inside the container " +
                "outside a volume is lost.",
            inputSchema = """
                {"type":"object","properties":{
                  "container":{"type":"string","description":"Container name or id"},
                  "force":{"type":"boolean","description":"Remove even if running (default false)"}
                },"required":["container"]}
            """.trimIndent(),
            readOnly = false,
            requiredPermissions = listOf(PERMISSION_MANAGE),
            handler = McpToolHandler { args ->
                val force = args.boolean("force") ?: false
                mutate(args) { engine.removeContainer(it, force) }
            },
        ),

        McpToolDefinition(
            name = "docker_compose_up",
            description = "Bring a compose project up (detached, with --build) in a BOSS terminal tab.",
            inputSchema = """
                {"type":"object","properties":{
                  "file":{"type":"string","description":"Path to the compose file (absolute, or relative to the project)"}
                },"required":["file"]}
            """.trimIndent(),
            readOnly = false,
            handler = McpToolHandler { args ->
                val path = args.string("file")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: file", isError = true)
                val file = resolveProjectFile(path)
                    ?: return@McpToolHandler McpToolResult("Compose file not found: $path", isError = true)
                val artifact = ProjectArtifact(file, ProjectArtifact.Kind.COMPOSE, file.name)
                if (services.actions.composeUp(artifact)) {
                    McpToolResult("Running `docker compose up --build -d` for ${file.absolutePath} in a terminal tab.")
                } else {
                    McpToolResult("Couldn't open a terminal tab.", isError = true)
                }
            },
        ),

        McpToolDefinition.withRbac(
            name = "docker_compose_down",
            description = "Stop and remove a compose project's containers.",
            inputSchema = """
                {"type":"object","properties":{
                  "project":{"type":"string","description":"Compose project name (see docker_compose_ls)"}
                },"required":["project"]}
            """.trimIndent(),
            readOnly = false,
            requiredPermissions = listOf(PERMISSION_MANAGE),
            handler = McpToolHandler { args ->
                requireDaemon() ?: return@McpToolHandler daemonError()
                val name = args.string("project")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: project", isError = true)
                val project = engine.composeProjects.value.firstOrNull { it.name == name }
                    ?: return@McpToolHandler McpToolResult("No compose project named '$name'.", isError = true)
                if (services.actions.composeDown(project)) {
                    McpToolResult("Running `docker compose down` for $name in a terminal tab.")
                } else {
                    McpToolResult("Couldn't open a terminal tab.", isError = true)
                }
            },
        ),

        McpToolDefinition(
            name = "docker_compose_ls",
            description = "List compose projects known to Docker and whether they are running.",
            handler = McpToolHandler {
                requireDaemon() ?: return@McpToolHandler daemonError()
                engine.refreshAll()
                val list = engine.composeProjects.value
                if (list.isEmpty()) return@McpToolHandler McpToolResult("No compose projects.")
                McpToolResult(list.joinToString("\n") { "${it.name}  ${it.status}  ${it.configFiles}" })
            },
        ),

        McpToolDefinition(
            name = "docker_open_service",
            description = "Open the BOSS service tab for a container — live logs, a preview of what it " +
                "serves, and inspect output.",
            inputSchema = containerArgSchema,
            readOnly = false,
            handler = McpToolHandler { args ->
                requireDaemon() ?: return@McpToolHandler daemonError()
                val ref = args.string("container")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: container", isError = true)
                engine.refreshAll()
                val container = engine.findContainer(ref)
                    ?: return@McpToolHandler McpToolResult("No container matching '$ref'.", isError = true)
                when (services.openServiceTabVerified(container)) {
                    TabOpenOutcome.Opened -> McpToolResult("Opened the service tab for ${container.name}.")
                    TabOpenOutcome.Focused -> McpToolResult("Focused the existing service tab for ${container.name}.")
                    TabOpenOutcome.Unverifiable ->
                        McpToolResult("Requested the service tab for ${container.name} (couldn't confirm it opened).")
                    TabOpenOutcome.Dropped -> McpToolResult(
                        "The host dropped the service tab — no factory is registered for tab type " +
                            "'${DockerServiceTabType.typeId.typeId}'. Reload the Docker plugin from Toolbox.",
                        isError = true,
                    )
                    TabOpenOutcome.NoSplitViewOperations ->
                        McpToolResult("This host exposes no split-view operations, so tabs can't be opened.", isError = true)
                }
            },
        ),
    )

    // --------------------------------------------------------------- helpers

    /** Null when the daemon isn't usable; callers return [daemonError]. */
    private suspend fun requireDaemon(): DaemonState.Running? =
        engine.probeDaemon() as? DaemonState.Running

    private fun daemonError(): McpToolResult = when (engine.daemon.value) {
        is DaemonState.CliMissing -> McpToolResult("The docker CLI is not installed on this machine.", isError = true)
        is DaemonState.Error -> McpToolResult(
            "Docker error: ${(engine.daemon.value as DaemonState.Error).message}",
            isError = true,
        )
        else -> McpToolResult("The Docker daemon isn't running. Start Docker Desktop and retry.", isError = true)
    }

    private suspend fun mutate(
        args: ai.rever.boss.plugin.api.McpToolArgs,
        block: suspend (String) -> DockerExec,
    ): McpToolResult {
        requireDaemon() ?: return daemonError()
        val ref = args.string("container") ?: return McpToolResult(
            "Missing required argument: container",
            isError = true,
        )
        val container = engine.findContainer(ref)
        val id = container?.id ?: ref
        val result = block(id)
        return if (result.ok) {
            McpToolResult("OK — ${container?.name ?: ref}")
        } else {
            McpToolResult(result.message.ifBlank { "docker command failed" }, isError = true)
        }
    }

    /**
     * Resolve a user-supplied path against the open project. Relative paths are
     * common in agent calls; absolute ones are used as-is.
     */
    private fun resolveProjectFile(path: String): File? {
        val direct = File(path)
        if (direct.isAbsolute) return direct.takeIf { it.isFile }
        val root = services.context.projectPath ?: return direct.takeIf { it.isFile }
        return File(root, path).takeIf { it.isFile } ?: direct.takeIf { it.isFile }
    }

    private companion object {
        const val PERMISSION_MANAGE = "docker.manage"

        val containerArgSchema = """
            {"type":"object","properties":{
              "container":{"type":"string","description":"Container name or id"}
            },"required":["container"]}
        """.trimIndent()
    }
}
