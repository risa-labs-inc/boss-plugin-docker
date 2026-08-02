package ai.rever.boss.plugin.dynamic.docker

import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.PluginContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The RBAC gate on this plugin's **MCP surface**, enforced against the real tool
 * objects.
 *
 * Scope: the `docker_*` MCP tools only. The sidebar panel's buttons run the same
 * `DockerActions` with **no** permission check and are deliberately out of scope —
 * see the "MCP Tools" section of AGENTS.md for why. Do not read a green run here as
 * a claim about the panel.
 *
 * Why this test exists: BossConsole's public README restates which `docker_*` tools
 * are permission-gated (its "Guardrails for the infrastructure plugins" section,
 * pinned to "boss-plugin-docker 1.0.2" and itself noting that "nothing in CI
 * relates this list to them"), and this plugin releases independently of
 * BossConsole. The invariant is asserted at the source of truth instead
 * (boss-plugin-docker#3).
 *
 * ## What this guarantees, and what it does not
 *
 * The whole tool surface is pinned: [MUTATING] and [READ_ONLY] together must equal
 * the set of tools the plugin really contributes, and each tool's declared
 * `readOnly` flag must agree with which list it is in. That makes three escape
 * hatches fail loudly:
 *
 * - a tool that **omits** `readOnly = false` — the flag defaults to `true`, so a
 *   destructive tool whose author forgot the line looks read-only to the host and
 *   to any audit that trusts the flag;
 * - a tool contributed by a **second provider** class;
 * - a tool **conditionally constructed** behind a null-check on some host provider,
 *   which would otherwise shrink this test's coverage in silence rather than fire an
 *   assertion. The surface is enumerated twice, under an all-null and an
 *   all-non-null [PluginContext], and the union is pinned — so neither polarity of
 *   such a guard hides a tool. See [toolSets] for the hole that remains.
 *
 * It cannot catch a tool a human deliberately misfiles in [READ_ONLY], or an
 * [UNGATED_BY_DESIGN] entry added on purpose. Those are small diffs and review is
 * the only gate on them; what the two lists buy is that the diff has to *say* "this
 * tool changes nothing" instead of silence saying it.
 *
 * Providers are discovered by scanning the compiled output rather than named, so
 * adding one cannot go unnoticed. Definitions are read off the objects
 * [McpToolProvider.tools] returns — never by parsing this plugin's source, which
 * would have to account for **two** factories (`McpToolDefinition(` and
 * `McpToolDefinition.withRbac(`) and drops exactly the gated set if it splits on
 * the first alone.
 *
 * It also catches the `.copy()` hazard `McpToolDefinition` warns about:
 * `requiredPermissions` is a body property excluded from `equals`/`copy`, so
 * copying a gated definition returns an ungated one.
 */
class DockerMcpToolRbacTest {

    // ------------------------------------------------------------------ discovery

    /**
     * Every concrete [McpToolProvider] compiled into this plugin.
     *
     * Found by walking the compiled classes, not by naming [DockerMcpToolProvider]:
     * a second provider registered in `DockerDynamicPlugin.register()` would
     * otherwise contribute ungated tools this test never sees.
     */
    private fun providerClasses(): List<Class<*>> {
        val loader = javaClass.classLoader
        val root = File(
            DockerMcpToolProvider::class.java.protectionDomain.codeSource.location.toURI(),
        )
        assertTrue(
            root.isDirectory,
            "Expected this plugin's compiled classes in a directory so they can be " +
                "walked; got $root. Teach this test to read a jar if the build layout " +
                "changed — do not let discovery come up empty.",
        )
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .map { it.relativeTo(root).path.removeSuffix(".class").replace(File.separatorChar, '.') }
            // initialize = false: loading must not run static initializers, or every
            // Compose-touching class in the plugin gets initialized here.
            .mapNotNull { runCatching { Class.forName(it, false, loader) }.getOrNull() }
            .filter { McpToolProvider::class.java.isAssignableFrom(it) }
            .filter { !it.isInterface && !Modifier.isAbstract(it.modifiers) }
            .sortedBy { it.name }
            .toList()
    }

    /**
     * Build a discovered provider. Only the constructor shapes this plugin actually
     * uses are supported — anything else fails with instructions rather than being
     * skipped, because skipping is how a provider's tools would go unaudited.
     */
    private fun instantiate(
        cls: Class<*>,
        services: DockerServices,
        context: PluginContext,
    ): McpToolProvider {
        val ctor = cls.declaredConstructors.minByOrNull { it.parameterCount }
            ?: fail("${cls.name} has no constructor this test can use")
        val args = ctor.parameterTypes.map { param ->
            when (param) {
                String::class.java -> "test"
                DockerServices::class.java -> services
                PluginContext::class.java -> context
                else -> fail(
                    "${cls.name} takes a ${param.name}, which this test doesn't know how " +
                        "to supply. Extend instantiate() — do not exclude the provider, or " +
                        "its tools ship unaudited.",
                )
            }
        }
        ctor.isAccessible = true
        return ctor.newInstance(*args.toTypedArray()) as McpToolProvider
    }

    /**
     * A stub [PluginContext].
     *
     * Built twice, with opposite polarity, because a tool can be **conditionally
     * constructed**: `if (context.workspaceDataProvider != null) add(tool)` hides it
     * from a null-answering stub, and `== null` hides it from a non-null one. The
     * surface is enumerated under both and the union is what gets pinned, so neither
     * polarity of such a guard can keep a tool out of this test.
     *
     * @param nonNull when true every nullable provider is answered with a further
     *   stub instead of null.
     */
    private fun stubContext(nonNull: Boolean): PluginContext =
        stubInterface(PluginContext::class.java, nonNull) as PluginContext

    private fun stubInterface(iface: Class<*>, nonNull: Boolean): Any =
        Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { proxy, method, args ->
            when {
                // Object's methods reach the handler too, and null is not a legal answer
                // for hashCode's int return.
                method.name == "hashCode" -> System.identityHashCode(proxy)
                method.name == "equals" -> proxy === args?.firstOrNull()
                method.name == "toString" -> "${iface.simpleName}(stub, nonNull=$nonNull)"
                // Every provider on PluginContext is nullable and the plugin is required
                // to degrade rather than crash when one is absent, so null is always a
                // legal answer.
                !nonNull -> null
                else -> when (val ret = method.returnType) {
                    String::class.java -> "stub"
                    Boolean::class.javaPrimitiveType, Boolean::class.java -> false
                    Int::class.javaPrimitiveType, Int::class.java -> 0
                    Long::class.javaPrimitiveType, Long::class.java -> 0L
                    Void.TYPE -> null
                    else -> if (ret.isInterface) stubInterface(ret, true) else null
                }
            }
        }

    /**
     * Every tool definition this plugin can contribute, as one list per context
     * polarity.
     *
     * `tools()` only *constructs* definitions — every reference to the services
     * object is inside an [ai.rever.boss.plugin.api.McpToolHandler] lambda that is
     * never invoked here — so stubs are enough and nothing shells out to `docker`.
     *
     * Residual hole, stated rather than papered over: a tool built conditionally on
     * something that is *not* a [PluginContext] provider — an env var, a StateFlow
     * value, the host OS — is still invisible to both passes. Nothing short of
     * running the real host closes that; what is closed is the null-check shape,
     * which is the one the plugin's own conventions encourage.
     */
    private fun toolSets(): List<List<McpToolDefinition>> {
        val classes = providerClasses()
        // Pinned here as well as in its own test, so no assertion below can run
        // against a partial surface.
        assertEquals(
            EXPECTED_PROVIDERS,
            classes.map { it.name }.toSet(),
            "The set of McpToolProvider classes changed. Add the new provider to " +
                "EXPECTED_PROVIDERS and classify its tools in MUTATING/READ_ONLY.",
        )
        return listOf(false, true).map { nonNull ->
            val context = stubContext(nonNull)
            val services = DockerServices(context)
            try {
                classes.flatMap { instantiate(it, services, context).tools() }
            } finally {
                // start() was never called; dispose() is safe either way and releases the
                // coroutine scope the constructor opened.
                services.dispose()
            }
        }
    }

    /** Every definition seen under either polarity. */
    private fun tools(): List<McpToolDefinition> = toolSets().flatten()

    // ------------------------------------------------------------- the invariants

    /**
     * The complete tool surface, pinned. A new tool, a removed tool, or a tool that
     * vanished because it is built conditionally all fail here — which is what makes
     * [mutatingToolsAreGatedOrExplicitlyAllowListed] a statement about the plugin
     * rather than about whatever happened to be in the list.
     */
    @Test
    fun toolSurfaceIsPinned() {
        val actual = tools().map { it.name }.toSet()
        assertEquals(
            MUTATING + READ_ONLY,
            actual,
            "The set of docker_* tools changed.\n" +
                "Missing (classified here but not contributed — a conditionally built " +
                "tool shrinks this test's coverage): ${(MUTATING + READ_ONLY) - actual}\n" +
                "Unexpected (contributed but not classified): " +
                "${actual - (MUTATING + READ_ONLY)}\n" +
                "Every tool must be classified in MUTATING or READ_ONLY, so a new one " +
                "cannot land in the safe bucket by omitting `readOnly = false`.",
        )
    }

    /**
     * The `readOnly` flag must agree with the classification.
     *
     * This is the half that survives a forgotten flag: `readOnly` defaults to `true`,
     * so a destructive tool missing the line is treated as read-only by the host.
     */
    @Test
    fun declaredReadOnlyFlagMatchesClassification() {
        for (tool in tools()) {
            val classifiedReadOnly = tool.name in READ_ONLY
            assertEquals(
                classifiedReadOnly,
                tool.readOnly,
                if (classifiedReadOnly) {
                    "${tool.name} is classified READ_ONLY but declares readOnly = false. " +
                        "Either drop the flag, or move it to MUTATING and gate it."
                } else {
                    "${tool.name} is classified MUTATING but declares readOnly = true — " +
                        "that is the default, so the `readOnly = false` line is probably " +
                        "missing. Add it, or the host treats a mutating tool as safe."
                },
            )
        }
    }

    /**
     * A mutating tool must declare a permission, or be listed in [UNGATED_BY_DESIGN]
     * with a reason.
     */
    @Test
    fun mutatingToolsAreGatedOrExplicitlyAllowListed() {
        val unguarded = tools()
            .filter { it.name in MUTATING }
            .filter { it.requiredPermissions.isEmpty() && !it.requiresAdmin }
            .map { it.name }
            .distinct()
            .filterNot { it in UNGATED_BY_DESIGN }

        assertTrue(
            unguarded.isEmpty(),
            "These tools change state but declare no requiredPermissions and no " +
                "requiresAdmin: $unguarded.\n" +
                "Either build them with McpToolDefinition.withRbac(" +
                "requiredPermissions = listOf(\"$PERMISSION_MANAGE\")), or — if the tool " +
                "genuinely changes nothing outside BOSS's own window — add it to " +
                "UNGATED_BY_DESIGN with the reason.",
        )
    }

    /**
     * The gated set, pinned by name and by permission string.
     *
     * Removing a gate is as much a regression as forgetting to add one, and
     * [mutatingToolsAreGatedOrExplicitlyAllowListed] alone would accept a gate moved
     * to some other permission that nothing grants.
     */
    @Test
    fun gatedToolsRequireExactlyDockerManage() {
        val gated = tools().filter { it.requiredPermissions.isNotEmpty() }

        assertEquals(
            MUTATING - UNGATED_BY_DESIGN.keys,
            gated.map { it.name }.toSet(),
            "The set of permission-gated docker_* tools changed. If that is intended, " +
                "update MUTATING/UNGATED_BY_DESIGN here and the tables in README.md and " +
                "AGENTS.md that name the gated tools.",
        )
        for (tool in gated) {
            assertEquals(
                listOf(PERMISSION_MANAGE),
                tool.requiredPermissions,
                "${tool.name} should require exactly [$PERMISSION_MANAGE]",
            )
        }
    }

    /**
     * Keeps the allow-list honest. It cannot stop a deliberate addition — that is a
     * small diff and review's job — but it does stop a stale exemption outliving the
     * tool it was written for, and it forces any addition to appear in two
     * differently-shaped places instead of one.
     */
    @Test
    fun allowListIsPinnedAndHasNoStaleEntries() {
        assertEquals(
            EXPECTED_UNGATED,
            UNGATED_BY_DESIGN.keys,
            "UNGATED_BY_DESIGN and EXPECTED_UNGATED disagree. Widening the " +
                "mutating-but-ungated set is a deliberate act: state it in both places, " +
                "with the reason.",
        )
        val byName = tools().associateBy { it.name }
        for ((name, reason) in UNGATED_BY_DESIGN) {
            val tool = assertNotNull(
                byName[name],
                "UNGATED_BY_DESIGN names '$name', which no tool declares any more — drop " +
                    "the entry. (Recorded reason: $reason)",
            )
            assertTrue(
                name in MUTATING,
                "'$name' is not classified MUTATING, so it needs no mutation exemption — " +
                    "drop it from UNGATED_BY_DESIGN.",
            )
            assertTrue(
                tool.requiredPermissions.isEmpty(),
                "'$name' is both gated and allow-listed — drop it from UNGATED_BY_DESIGN " +
                    "so the gate is the only story.",
            )
        }
    }

    /**
     * Every permission a tool requires must be one the manifest defines, or no admin
     * can grant it and the tool is invisible to everyone but admins.
     *
     * Also pins `minApiVersion`. `McpToolDefinition.withRbac` is a companion-object
     * function that first exists in **boss-plugin-api 1.0.52** — verified by jar
     * inspection: `McpToolDefinition${'$'}Companion` is absent from 1.0.48 through
     * 1.0.51. On an older api layer `tools()` throws `NoSuchFieldError`, which the
     * host handles by registering the provider with an empty tool set and a warn log,
     * so every `docker_*` tool would silently disappear while the panel kept working.
     */
    @Test
    fun manifestDeclaresRequiredPermissionsAndAnApiFloorSupportingWithRbac() {
        val manifest = Json.parseToJsonElement(readManifest()).jsonObject

        val declared = manifest["definedPermissions"]
            ?.jsonArray
            ?.map { it.jsonObject.getValue("name").jsonPrimitive.content }
            ?.toSet()
            ?: emptySet()
        val undeclared = tools().flatMap { it.requiredPermissions }.toSet() - declared
        assertTrue(
            undeclared.isEmpty(),
            "Tools require permissions the manifest never defines: $undeclared. Add them " +
                "to definedPermissions in plugin.json.",
        )

        val minApi = manifest["minApiVersion"]?.jsonPrimitive?.content.orEmpty()
        assertTrue(
            versionAtLeast(minApi, WITH_RBAC_MIN_API),
            "plugin.json declares minApiVersion '$minApi', but McpToolDefinition.withRbac " +
                "needs boss-plugin-api >= $WITH_RBAC_MIN_API. On an older api layer every " +
                "docker_* tool vanishes behind nothing but a warn log.",
        )
    }

    /**
     * Duplicate names would silently shadow each other in the host registry. Checked
     * per polarity, since the same tool legitimately appears in both passes.
     */
    @Test
    fun toolNamesAreUnique() {
        for (set in toolSets()) {
            val names = set.map { it.name }
            assertEquals(names.size, names.toSet().size, "Duplicate tool names in $names")
        }
    }

    // ------------------------------------------------------------------- helpers

    private fun readManifest(): String =
        javaClass.getResourceAsStream("/META-INF/boss-plugin/plugin.json")
            ?.use { it.readBytes().decodeToString() }
            ?: error("plugin.json is not on the test classpath")

    /** True when [version] >= [floor], comparing dotted numeric components. */
    private fun versionAtLeast(version: String, floor: String): Boolean {
        val actual = version.split('.').map { it.toIntOrNull() ?: return false }
        val required = floor.split('.').map { it.toInt() }
        if (actual.isEmpty()) return false
        for (i in 0 until maxOf(actual.size, required.size)) {
            val a = actual.getOrElse(i) { 0 }
            val b = required.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return true
    }

    private companion object {
        const val PERMISSION_MANAGE = "docker.manage"

        /** First boss-plugin-api release with `McpToolDefinition.Companion`. */
        const val WITH_RBAC_MIN_API = "1.0.52"

        /** Provider classes allowed to contribute tools. */
        val EXPECTED_PROVIDERS = setOf(
            "ai.rever.boss.plugin.dynamic.docker.DockerMcpToolProvider",
        )

        /**
         * Tools that change state — Docker's, or (for the allow-listed ones) BOSS's own
         * window. Every one must declare `readOnly = false`, and must be gated unless it
         * appears in [UNGATED_BY_DESIGN].
         */
        val MUTATING = setOf(
            "docker_build",
            "docker_start",
            "docker_restart",
            "docker_stop",
            "docker_rm",
            "docker_compose_up",
            "docker_compose_down",
            "docker_open_service",
        )

        /**
         * Tools that only read. Listing a tool here is a claim that it changes nothing;
         * nothing in this file can verify that claim, so it is one to review in the
         * diff, not to trust.
         */
        val READ_ONLY = setOf(
            "docker_ps",
            "docker_images",
            "docker_project_files",
            "docker_logs",
            "docker_compose_ls",
        )

        /**
         * Mutating tools deliberately exposed with no permission, each with the reason
         * it is safe. Cross-checked against [EXPECTED_UNGATED].
         */
        val UNGATED_BY_DESIGN = mapOf(
            "docker_open_service" to
                "Opens a BOSS tab for a container that already exists; it starts, stops " +
                "and configures nothing. What the tab shows is not returned to the " +
                "caller — the agent gets 'Opened the service tab', while logs, the " +
                "preview and `docker inspect` render in the window for the human. " +
                "Inspect matters most here, since it carries the container's Env and so " +
                "potentially DB passwords and API keys: it is fetched only when a person " +
                "selects that section, so this is not an agent-readable path to secret " +
                "material. The previewed URL is built from ports parsed out of the " +
                "daemon's own output, never from the caller's string.",
        )

        /** Second, differently-shaped statement of the allow-list. */
        val EXPECTED_UNGATED = setOf("docker_open_service")
    }
}
