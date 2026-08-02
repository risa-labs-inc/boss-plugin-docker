package ai.rever.boss.plugin.dynamic.docker

import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.PluginContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The RBAC gate on this plugin's MCP surface, enforced against the real tool
 * objects.
 *
 * Why this test exists: BossConsole's README restates which `docker_*` tools are
 * permission-gated, and this plugin releases independently of BossConsole — so
 * nothing over there can notice when the gating changes here. The invariant is
 * asserted at the source of truth instead (boss-plugin-docker#3).
 *
 * Why it builds the provider rather than reading the source file: definitions are
 * created through **two** factories — `McpToolDefinition(...)` and
 * `McpToolDefinition.withRbac(...)`. Any text-based audit that splits on
 * `McpToolDefinition(` silently drops every `withRbac` call, i.e. exactly the
 * gated set, and concludes that nothing is gated. Enumerating the objects
 * [DockerMcpToolProvider.tools] actually returns cannot miss a spelling.
 *
 * It also catches the `.copy()` hazard `McpToolDefinition` warns about:
 * `requiredPermissions` is a body property excluded from `equals`/`copy`, so
 * copying a gated definition returns an ungated one. That is invisible in review
 * and fails [gatedToolsRequireExactlyDockerManage] here.
 */
class DockerMcpToolRbacTest {

    /**
     * Every tool the plugin contributes, exactly as the host receives it.
     *
     * `tools()` only *constructs* definitions — every reference to the services
     * object is inside an [ai.rever.boss.plugin.api.McpToolHandler] lambda that is
     * never invoked here — so a stub [PluginContext] that answers null to
     * everything is enough, and nothing shells out to `docker`.
     */
    private fun tools(): List<McpToolDefinition> {
        val context = Proxy.newProxyInstance(
            PluginContext::class.java.classLoader,
            arrayOf(PluginContext::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                // Object's methods reach the handler too, and null is not a legal
                // answer for hashCode's int return.
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "PluginContext(stub)"
                // Every provider on PluginContext is nullable and the plugin is
                // required to degrade rather than crash when one is absent.
                else -> null
            }
        } as PluginContext

        val services = DockerServices(context)
        return try {
            DockerMcpToolProvider(providerId = "test", services = services).tools()
        } finally {
            // start() was never called; dispose() is safe either way and releases
            // the coroutine scope the constructor opened.
            services.dispose()
        }
    }

    // -------------------------------------------------------------- the invariant

    /**
     * A mutating tool must declare a permission, or be listed in
     * [UNGATED_BY_DESIGN] with a reason. Adding a `readOnly = false` tool with
     * neither fails here.
     */
    @Test
    fun mutatingToolsAreGatedOrExplicitlyAllowListed() {
        val unguarded = tools()
            .filter { !it.readOnly }
            .filter { it.requiredPermissions.isEmpty() && !it.requiresAdmin }
            .map { it.name }
            .filterNot { it in UNGATED_BY_DESIGN }

        assertTrue(
            unguarded.isEmpty(),
            "These tools mutate state (readOnly = false) but declare no " +
                "requiredPermissions and no requiresAdmin: $unguarded.\n" +
                "Either build them with McpToolDefinition.withRbac(" +
                "requiredPermissions = listOf(\"$PERMISSION_MANAGE\")), or — if the " +
                "tool genuinely changes nothing outside BOSS's own UI — add it to " +
                "UNGATED_BY_DESIGN in this test with the reason.",
        )
    }

    /**
     * The gated set, pinned by name and by permission.
     *
     * Removing a gate is as much a regression as forgetting to add one, and
     * [mutatingToolsAreGatedOrExplicitlyAllowListed] alone would accept a gate
     * moved to some other permission string nothing grants.
     */
    @Test
    fun gatedToolsRequireExactlyDockerManage() {
        val gated = tools().filter { it.requiredPermissions.isNotEmpty() }

        assertEquals(
            EXPECTED_GATED,
            gated.map { it.name }.toSet(),
            "The set of permission-gated docker_* tools changed. If that is " +
                "intended, update EXPECTED_GATED here and the tables in README.md " +
                "and AGENTS.md that name the gated tools.",
        )
        for (tool in gated) {
            assertEquals(
                listOf(PERMISSION_MANAGE),
                tool.requiredPermissions,
                "${tool.name} should require exactly [$PERMISSION_MANAGE]",
            )
        }
        // Gated tools are gated *because* they mutate; a read-only tool behind a
        // permission would be friction with no benefit.
        for (tool in gated) {
            assertTrue(!tool.readOnly, "${tool.name} is gated but marked readOnly")
        }
    }

    /**
     * Keeps the allow-list honest: a name that no longer exists, or one that is no
     * longer mutating, is an exemption nobody is reviewing any more.
     */
    @Test
    fun allowListHasNoStaleEntries() {
        val byName = tools().associateBy { it.name }
        for ((name, reason) in UNGATED_BY_DESIGN) {
            val tool = assertNotNull(
                byName[name],
                "UNGATED_BY_DESIGN names '$name', which no tool declares any more — " +
                    "drop the entry. (Recorded reason: $reason)",
            )
            assertTrue(
                !tool.readOnly,
                "'$name' is read-only now, so it needs no mutation exemption — " +
                    "drop it from UNGATED_BY_DESIGN.",
            )
            assertTrue(
                tool.requiredPermissions.isEmpty(),
                "'$name' is both gated and allow-listed — drop it from " +
                    "UNGATED_BY_DESIGN so the gate is the only story.",
            )
        }
    }

    /**
     * Every permission a tool requires must be one the manifest defines, or no
     * admin can grant it and the tool is invisible to everyone but admins.
     */
    @Test
    fun everyRequiredPermissionIsDeclaredInTheManifest() {
        val manifest = javaClass.getResourceAsStream("/META-INF/boss-plugin/plugin.json")
            ?.use { it.readBytes().decodeToString() }
            ?: error("plugin.json is not on the test classpath")
        val declared = Json.parseToJsonElement(manifest)
            .jsonObject["definedPermissions"]
            ?.jsonArray
            ?.map { it.jsonObject.getValue("name").jsonPrimitive.content }
            ?.toSet()
            ?: emptySet()

        val undeclared = tools().flatMap { it.requiredPermissions }.toSet() - declared
        assertTrue(
            undeclared.isEmpty(),
            "Tools require permissions the manifest never defines: $undeclared. " +
                "Add them to definedPermissions in plugin.json.",
        )
    }

    /** Duplicate names would silently shadow each other in the host registry. */
    @Test
    fun toolNamesAreUnique() {
        val names = tools().map { it.name }
        assertEquals(names.size, names.toSet().size, "Duplicate tool names in $names")
    }

    private companion object {
        const val PERMISSION_MANAGE = "docker.manage"

        /**
         * Mutating tools deliberately exposed without a permission, each with the
         * reason it is safe. Keep this list short and argued — it is the only
         * escape hatch from [mutatingToolsAreGatedOrExplicitlyAllowListed].
         */
        val UNGATED_BY_DESIGN = mapOf(
            "docker_open_service" to
                "Opens a BOSS tab for a container that already exists. Changes no " +
                "Docker state; `readOnly = false` records a side effect on the " +
                "window, not on the daemon. The logs and inspect output it shows " +
                "are already reachable through the ungated docker_logs/docker_ps.",
        )

        /**
         * Tools that require [PERMISSION_MANAGE] — everything that changes Docker
         * state. Read-only tools (`docker_ps`, `docker_images`,
         * `docker_project_files`, `docker_logs`, `docker_compose_ls`) and the
         * allow-listed `docker_open_service` are deliberately absent.
         */
        val EXPECTED_GATED = setOf(
            "docker_build",
            "docker_start",
            "docker_restart",
            "docker_stop",
            "docker_rm",
            "docker_compose_up",
            "docker_compose_down",
        )
    }
}
