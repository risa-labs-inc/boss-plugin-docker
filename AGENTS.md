# AGENTS.md

## Project Overview

**Docker** (`ai.rever.boss.plugin.dynamic.docker`) is a dynamic plugin for the BOSS desktop application.

Manage the local Docker daemon from BOSS: a sidebar panel listing the open project's
Dockerfiles and compose files alongside every container, image, volume and network on the
machine, plus a main-panel service tab per container with live logs, a preview of what it
serves, and inspect output.

- **Plugin ID**: `ai.rever.boss.plugin.dynamic.docker`
- **Main Class**: `ai.rever.boss.plugin.dynamic.docker.DockerDynamicPlugin`
- **API Version**: 1.0.52 (`minApiVersion` 1.0.52 — the MCP tool-provider API lands in 1.0.48,
  but `McpToolDefinition.withRbac`, which every gated tool uses, needs the companion object
  first shipped in **1.0.52**. `McpToolDefinition$Companion` is absent from the 1.0.48–1.0.51
  jars; on such a host `tools()` throws `NoSuchFieldError`, which the host swallows by
  registering the provider with an empty tool set plus a warn log — every `docker_*` tool
  disappears while the panel keeps working. `DockerMcpToolRbacTest` pins this floor.)
- **Type**: `mixed` (registers both a panel and a tab type)

## Essential Commands

```bash
./gradlew buildPluginJar    # Build plugin JAR (output: build/libs/)
./gradlew build             # Full build (runs the tests too)
./gradlew test              # RBAC guard over the MCP tool surface
```

## Workflow Rules

- Do NOT run the BOSS application to test. The user runs it.
- After building, copy the JAR to `~/.boss/plugins/` (or `~/.boss_debug/plugins/` for a
  dev-mode host) and reload from Toolbox.
- While iterating locally, keep one version and overwrite the same JAR. Bump only at release.

## Architecture

```
DockerCli.kt              → the ONLY place that shells out to `docker`
DockerModels.kt           → data types + `--format {{json .}}` parsing
DockerEngine.kt           → StateFlows + daemon supervision + project scan
DockerServices.kt         → shared brain: engine, actions, toasts, storage, tab opening
DockerActions.kt          → the build/run split (terminal tabs vs supervised containers)
DockerPanelInfo.kt        → sidebar PanelInfo (left.bottom, order 55)
DockerPanelViewModel.kt   → sidebar state
DockerPanelComponent.kt   → sidebar UI
DockerServiceTab.kt       → tab type + TabInfo + section enum
DockerServiceTabViewModel.kt → per-container state: log stream, inspect
DockerServiceTabComponent.kt → tab UI: Logs | Preview | Inspect
DockerMcpTools.kt         → `docker_*` MCP tools

src/test/…/DockerMcpToolRbacTest.kt → guards the RBAC gate on the MCP surface
```

### Load-bearing decisions

**Resolve `docker` to an absolute path, always.** `ProcessBuilder` resolves bare command
names against the *parent* process's PATH, which is nearly empty when the packaged host is
launched from Finder. Docker Desktop installs the CLI in `/usr/local/bin`, so a bare
`"docker"` works in dev and fails in the shipped app. `DockerCli.resolve()` searches PATH
plus the usual install dirs, and every child also gets a widened PATH.

**argv lists, never a shell string.** Container names, image tags and paths come from daemon
output and user input. `DockerCli` takes `List<String>` so there is no shell to inject into.
The one exception is `DockerActions`, which builds command *strings* for terminal tabs —
everything interpolated there goes through `DockerActions.q()` (single-quote escaping).

**Push, not poll.** After an initial snapshot the engine attaches to `docker events` and
refreshes on daemon events, so the sidebar is correct even when containers are started from a
terminal. A 30 s reconcile tick is a safety net only, for a silently dropped stream.

**Daemon-down is a first-class state**, not an error path — `DaemonState` is
`Unknown | CliMissing | Stopped | Starting | Running | Error`, each with its own UI.

**Published ports bind `127.0.0.1` only — on the `docker run` path.** A dev container should
not become reachable from the LAN because someone clicked Run, so `DockerActions` pins
`-p 127.0.0.1:$hostPort:$containerPort`. This guarantee does **not** extend to compose:
`composeUp` runs `docker compose -f <file> up --build -d` on the project's own file
unmodified, so ports declared there bind as declared. Rewriting a user's compose file would
be worse; the mitigation is that the `docker_compose_up` **MCP tool** is permission-gated (the
panel's button is not — see "MCP Tools" below) and that the README states the loopback claim
for the `docker run` path only instead of over-promising it.

**Destructive actions always confirm.** The confirmation dialog is rendered by the panel
itself rather than the host's `genericDialogProvider`, because that provider is nullable and
"the host didn't give us a dialog" must never degrade into "we deleted it without asking".

**Terminal commands go through one consumer, not a lock.** `DockerActions` decides only
*whether* a terminal exists and enqueues to a `Channel`; a single coroutine drains it and performs
every switch → interrupt → wait → send. The interrupt is required because `sendCommand` writes to
the tab's pty, so while a foreground process runs the text goes to *that process's stdin* and never
executes — and the sequence must be indivisible, or a second command's interrupt lands before the
first has been typed and the first swallows it. A `synchronized` block that launches the send does
**not** achieve that. One consumer also keeps this off the UI thread (panel clicks call
`openTerminal` directly, and these are cross-plugin calls whose threading contract the plugin does
not control). `ownedTerminal` is a `private var` in `DockerActions` rather than a field on the shared services
object, so no call site can retarget the tab without going through the queue. It is confined to
the consumer — the only reader and writer — and the `@Volatile` on it is belt-and-braces for a
caller-side read that no longer exists, not a live requirement. Reusing one tab makes docker commands
mutually exclusive — a second build or compose command stops the first — and that is said
**prospectively**, by the MCP tool descriptions and results and by the panel's own toast, not by a
notification afterwards. There is no liveness signal to gate an after-the-fact toast on
(boss-plugins#11), so every guard written for one was either vacuous or fired on every command.

**`openTerminal` returning true means "accepted for delivery", not "running".** The command is
queued; the consumer types it later. So `buildAndRun` registers `pendingAutoOpen` and the panel
toasts before anything has been typed, and a command can still fall back to a BOSS tab — or fail
— after its caller was told it launched. The MCP tools hedge their wording for this; the panel
toasts do not, because a click has visible consequences the operator is already watching.

**No API-version floor was raised for the terminal reuse.** Everything it uses predates the
declared `minApiVersion` 1.0.48: `getPluginAPI`, `PluginContext.windowId`, `ActiveTabData.windowId`,
`hasTerminalState`, `sendCommand` and `sendInterrupt` land in `boss-plugin-api` **1.0.16**, and
`TerminalTabPluginAPI` / `createTab` / `switchToTab` / `listTabs` in **1.0.23**. There is therefore
no host that satisfies the floor but lacks the terminal API. The `runCatching { Throwable }` wrap
is not the compatibility contract — it is there for the case that terminal-tab simply is not
loaded, which now logs rather than degrading in silence.

**`openTab` is fire-and-forget.** The host drops a tab silently if no factory is registered
for its type, so `openServiceTabVerified` polls `activeTabs` and reports what actually
happened instead of assuming success.

### Key Patterns
- Entry point: `DynamicPlugin` with `register(context)` / `dispose()`
- UI: `PanelComponentWithUI` / `TabComponentWithUI` with `@Composable Content()`
- State: ViewModel + `StateFlow`
- Every `PluginContext` provider may be null — degrade, never crash

### Dependencies
- **boss-plugin-api**: compileOnly (provided by the host at runtime)
- **Compose Desktop**, **Decompose**, **Coroutines**, **kotlinx-serialization**

## MCP Tools

`docker_ps`, `docker_images`, `docker_project_files`, `docker_logs`, `docker_build`,
`docker_start`, `docker_restart`, `docker_stop`, `docker_rm`, `docker_compose_up`,
`docker_compose_down`, `docker_compose_ls`, `docker_open_service`.

Every tool that changes Docker state is gated on the `docker.manage` permission via
`McpToolDefinition.withRbac(...)`: `docker_build`, `docker_start`, `docker_restart`,
`docker_stop`, `docker_rm`, `docker_compose_up`, `docker_compose_down`. Never `.copy()` a
gated definition — `requiredPermissions` is a body property excluded from `equals`/`copy`, so
that silently drops the gate.

`docker_open_service` is `readOnly = false` but deliberately **not** gated: it opens a BOSS
tab for a container that already exists and returns only "Opened the service tab" to the
caller. Logs, preview and inspect render in the window for the human — inspect in particular
(container `Env`, so possibly DB passwords) is fetched only when a person selects that
section, so this is not an agent-readable path to secret material.

**Gating is MCP-only. The panel is not gated at all.** `DockerPanelViewModel` calls the same
`DockerActions`/`DockerEngine` methods with no permission check, and the panel is registered
unconditionally, so a user without `docker.manage` can still build, start, stop, restart,
remove, and bring a compose stack up or down by clicking. That is deliberate for now: the
threat model being addressed is an *agent* acting unattended, and there is no api affordance
for action-level RBAC in a panel — `PluginManifest.requiredPermissions` is all-or-nothing and
would hide the read-only sidebar too. Do not describe this plugin as permission-gated without
saying "MCP tools"; a separate issue should cover the panel.

**`requiredPermissions` is unsatisfiable before sign-in.** `McpToolRegistryImpl.permitted()`
is `isAdmin || permissions.containsAll(required)`, and a signed-out session has neither, so
the gated seven are absent until sign-in and vanish again on sign-out. Admins bypass the check
entirely. Gating a tool therefore trades "works anonymously" for "requires a session" — worth
weighing before adding a gate to a tool people use casually.

### The guard test

`DockerMcpToolRbacTest` pins the surface rather than spot-checking it:

- providers are **discovered** by scanning compiled classes, so a second `McpToolProvider`
  cannot contribute tools the test never sees;
- `MUTATING` + `READ_ONLY` must equal the contributed tool set, so a new tool, a deleted one,
  or one hidden behind a host-provider null-check fails — the surface is enumerated twice,
  under an all-null and an all-non-null `PluginContext`, and the union is pinned;
- each tool's declared `readOnly` flag must match its classification. This matters because
  `readOnly` **defaults to `true`**: a destructive tool whose author forgot the line looks
  read-only to the host and to any audit that trusts the flag.

What it does not do: verify that a tool filed under `READ_ONLY` really is read-only, or stop a
deliberate `UNGATED_BY_DESIGN` addition. Both are small diffs whose only gate is review; the
lists exist so the diff has to say it out loud. A tool built conditionally on something that
is not a `PluginContext` provider (env var, StateFlow value) is also still invisible.

Auditing `DockerMcpTools.kt` **by text** instead needs care: definitions come from two
factories, `McpToolDefinition(` and `McpToolDefinition.withRbac(`, and splitting on the former
alone drops exactly the gated set and makes it look like nothing is gated.

### `docker.manage` in the permission catalog

`definedPermissions[].description` in `plugin.json` is only written **once**, the first time
the permission is registered: the host's `register_plugin_permission` returns early when the
name already exists and never updates the text. Editing it therefore changes what a *fresh*
install records and nothing about an existing one — do not expect an edit here to reach
anybody's Roles screen. Keep it accurate anyway, and scope it to what the permission actually
gates (the MCP tools; the panel's image/volume/network removals are ungated, so claiming them
would overstate it).

## Version Management

**`build.gradle.kts` is the single source of truth for version.** `processResources` syncs it
into `plugin.json` at build time (with the `inputs.property("pluginVersion", version)` guard,
without which a version-only bump ships a stale manifest). Never hand-edit the version in
`plugin.json`.

The default `jar` task is **disabled**: it writes a classes-only archive into the same
`build/libs` the release workflow uploads wholesale, and a `boss-plugin-docker-<ver>-thin.jar`
would match both the store's asset picker and the host's GitHub-asset regex.

## Code Quality

- Compose Multiplatform APIs (not Android-specific)
- All Kotlin files must end with a newline
- Handle null providers gracefully

## CI/CD

Pushes to `main` trigger the release workflow which builds the JAR, creates a GitHub release,
and publishes to the BOSS Plugin Store. Defined in `.github/workflows/build.yml`, delegating
to the shared workflow in `risa-labs-inc/BossConsole-Releases`. Requires the
`BOSS_STORE_PLUGIN_PUBLISH_KEY` repo secret.

`.github/workflows/test.yml` runs `./gradlew test` on every pull request. The release
workflow's `./gradlew build` runs them too, but only after it has already bump-pushed the
version — too late to stop a bad merge, hence the separate PR job. It fetches the
boss-plugin-api jar because the api is `compileOnly` (host-provided at runtime) and the tests
run outside the host.

Two honest limits on that job:

- **It is advisory.** `main` has no branch protection and no rulesets, so a red *Tests* run
  blocks nothing — it is a signal for the reviewer, not a gate. Making it binding means adding
  it as a required status check on `main`.
- **It resolves the api as `latest`**, matching what `build.yml` asks the release workflow for,
  which is deliberately *not* the local pin (`1.0.66`) or the manifest floor (`1.0.52`). So the
  guard is exercised against two api versions — the newest, in CI, and the pin, locally — and
  neither run proves the floor itself compiles. Raising the floor is a manifest edit the test
  checks; verifying it by building against that exact jar is not automated.

The job also asserts the guard's own results file exists afterwards, because a `test` task with
no test sources is `NO-SOURCE` and passes: deleting the test file would otherwise turn the
guard off with a green build.
