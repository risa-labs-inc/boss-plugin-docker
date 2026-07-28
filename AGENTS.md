# AGENTS.md

## Project Overview

**Docker** (`ai.rever.boss.plugin.dynamic.docker`) is a dynamic plugin for the BOSS desktop application.

Manage the local Docker daemon from BOSS: a sidebar panel listing the open project's
Dockerfiles and compose files alongside every container, image, volume and network on the
machine, plus a main-panel service tab per container with live logs, a preview of what it
serves, and inspect output.

- **Plugin ID**: `ai.rever.boss.plugin.dynamic.docker`
- **Main Class**: `ai.rever.boss.plugin.dynamic.docker.DockerDynamicPlugin`
- **API Version**: 1.0.48 (`minApiVersion` 1.0.48 — needs the MCP tool-provider API)
- **Type**: `mixed` (registers both a panel and a tab type)

## Essential Commands

```bash
./gradlew buildPluginJar    # Build plugin JAR (output: build/libs/)
./gradlew build             # Full build
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

**Published ports bind `127.0.0.1` only.** A dev container should not become reachable from
the LAN because someone clicked Run.

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

Destructive tools (`docker_stop`, `docker_rm`, `docker_compose_down`) are gated on the
`docker.manage` permission via `McpToolDefinition.withRbac(...)`. Never `.copy()` a gated
definition — that silently drops the gate.

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
