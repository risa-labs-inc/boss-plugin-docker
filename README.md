# BOSS Docker Plugin

Manage local Docker without leaving BOSS.

A sidebar panel lists the open project's Dockerfiles and compose files alongside every
container, image, volume and network on the machine. Running services get a main-panel tab
with live logs, a preview of whatever they're serving, and `docker inspect` output.

## What it does

**Sidebar** (lower-left slot)

- **Project** - Dockerfiles and compose files found in the open project. *Build & run* a
  Dockerfile (with a port form pre-filled from its first `EXPOSE`), or bring a compose
  project up and down.
- **Containers** - running first, with image, published ports and status. Start, stop,
  restart, remove, open the service tab, or jump straight to `localhost:<port>`.
- **Images / Volumes / Networks** - list and remove.

When Docker isn't installed, isn't running, or errors, the panel says so and offers the fix -
including a **Start Docker** button that launches Docker Desktop and waits for the daemon.

**Service tab** (main panel, one per container)

- **Logs** - streamed `docker logs -f`, ANSI stripped, with pause, auto-scroll, copy and clear.
- **Preview** - the page the container serves, rendered inline. Falls back to opening a
  browser tab when no embedded browser is available.
- **Inspect** - full `docker inspect` output.

**Builds go to a terminal tab.** `docker build` and `compose up --build` open a BossTerm tab
so you get BuildKit's live progress, real ANSI, and a Ctrl-C that works. Once the container is
up, the plugin takes over and its service tab opens automatically.

## MCP tools

Agents running in BOSS terminals get `mcp__boss__docker_*`:

| Tool | Purpose |
|---|---|
| `docker_ps` | List containers with state, image, ports |
| `docker_images` | List images |
| `docker_project_files` | Dockerfiles / compose files in the open project |
| `docker_logs` | Recent log output of a container |
| `docker_build` | Build a Dockerfile and run it on a published port |
| `docker_start` / `docker_restart` / `docker_stop` | Container lifecycle |
| `docker_rm` | Remove a container |
| `docker_compose_up` / `docker_compose_down` / `docker_compose_ls` | Compose projects |
| `docker_open_service` | Open a container's service tab |

**Every MCP tool that changes Docker state requires the `docker.manage` permission**:
`docker_build`, `docker_start`, `docker_restart`, `docker_stop`, `docker_rm`,
`docker_compose_up` and `docker_compose_down`. The read-only tools need nothing, and
`docker_open_service` is deliberately ungated - it opens a BOSS tab and touches no Docker
state.

Two consequences worth knowing. **Admins bypass every permission check**, so on a
single-user desktop the per-tool kill-switch in Toolbox is the control that actually bites.
And **before you sign in there are no permissions at all**, so a signed-out session sees only
the read-only tools plus `docker_open_service`; the gated seven appear on sign-in and
disappear again on sign-out. That is a change from ≤ 1.0.3, where build/start/restart/compose
up worked signed-out.

`DockerMcpToolRbacTest` holds the list above to the code: it pins the complete tool surface,
so a tool added, removed, hidden behind a host-provider check, or contributed by a second
provider fails the build, as does one that mutates without a permission. What it cannot check
is a tool a human deliberately files as read-only, or an exemption added on purpose - those
are visible in the diff and reviewed there, not machine-verified. The test covers the MCP
surface only; see below for the sidebar.

## Safety

- **The Dockerfile path publishes on `127.0.0.1` only.** `docker_build` and the panel's
  *Build & run* pin published ports to loopback, so a container BOSS starts that way is not
  reachable from your network.
- **Compose files run as written.** `docker_compose_up` is `docker compose up --build -d` on
  the project's own file - the plugin does not rewrite it, so a service declaring
  `"8080:8080"` or `network_mode: host` binds exactly as declared. That is why compose up is
  permission-gated alongside the destructive tools.
- **The sidebar is not permission-gated - only the MCP tools are.** Every button in the panel
  (build & run, start, stop, restart, remove, compose up/down, remove image/volume/network)
  runs without a permission check, so a signed-in user without `docker.manage` can still do
  all of it by hand, including bringing up a compose stack that publishes on `0.0.0.0`. The
  gate is about what an *agent* may do unattended. Action-level RBAC for panels needs host
  support that does not exist yet (the only manifest-level gate is all-or-nothing and would
  hide the read-only sidebar too).
- Every removal **from the panel** asks for confirmation first. `docker_rm` over MCP does
  not: it honours a `force` argument and never prompts - the permission is what stands in
  its way.
- No `docker system prune` or bulk destructive operations.
- The plugin observes the daemon; it never starts containers on its own.

## Requirements

- Docker Desktop, or the `docker` CLI with a reachable daemon
- BOSS ≥ 9.2.35, boss-plugin-api ≥ 1.0.52 (`McpToolDefinition.withRbac`, which every gated
  tool is built with, first exists in 1.0.52)

## Build

```bash
./gradlew buildPluginJar
cp build/libs/boss-plugin-docker-*.jar ~/.boss/plugins/
```

Then reload from Toolbox. See [AGENTS.md](AGENTS.md) for architecture and conventions.
