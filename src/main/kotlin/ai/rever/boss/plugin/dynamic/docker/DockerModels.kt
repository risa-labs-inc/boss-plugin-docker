package ai.rever.boss.plugin.dynamic.docker

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Lenient decoder for `docker ... --format '{{json .}}'` output. Docker adds
 * fields between releases, so unknown keys must never fail a whole list.
 */
internal val DockerJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

/** Where the daemon stands. Drives which sidebar body is shown. */
sealed interface DaemonState {
    /** Not probed yet. */
    data object Unknown : DaemonState

    /** No `docker` binary anywhere on PATH or in the usual install dirs. */
    data object CliMissing : DaemonState

    /** CLI present, daemon not answering. */
    data object Stopped : DaemonState

    /** We asked the OS to start Docker Desktop and are waiting for it. */
    data object Starting : DaemonState

    /** Daemon answering; [serverVersion] is whatever `docker version` reported. */
    data class Running(val serverVersion: String) : DaemonState

    /** CLI present, daemon answering, but the command failed for another reason. */
    data class Error(val message: String) : DaemonState
}

/** One published port mapping of a running container. */
data class PortBinding(
    val hostIp: String,
    val hostPort: Int,
    val containerPort: Int,
    val protocol: String,
) {
    /** A URL that actually resolves from this machine. */
    val localUrl: String get() = "http://localhost:$hostPort"

    val display: String get() = "$hostPort→$containerPort/$protocol"
}

/** A container as reported by `docker ps -a`. */
data class ContainerInfo(
    val id: String,
    val name: String,
    val image: String,
    val state: String,
    val status: String,
    val ports: List<PortBinding>,
    val labels: Map<String, String>,
    val createdAt: String,
) {
    val isRunning: Boolean get() = state.equals("running", ignoreCase = true)

    val shortId: String get() = id.take(12)

    /** Compose project this container belongs to, if any. */
    val composeProject: String? get() = labels["com.docker.compose.project"]

    /**
     * The port a preview should point at: the lowest published TCP port, which
     * for a typical web service is the one serving HTTP.
     */
    val primaryPort: PortBinding?
        get() = ports.filter { it.protocol.equals("tcp", ignoreCase = true) }.minByOrNull { it.hostPort }
}

@Serializable
internal data class RawContainer(
    @SerialName("ID") val id: String = "",
    @SerialName("Names") val names: String = "",
    @SerialName("Image") val image: String = "",
    @SerialName("State") val state: String = "",
    @SerialName("Status") val status: String = "",
    @SerialName("Ports") val ports: String = "",
    @SerialName("Labels") val labels: String = "",
    @SerialName("CreatedAt") val createdAt: String = "",
) {
    fun toInfo(): ContainerInfo = ContainerInfo(
        id = id,
        // `docker ps` joins multiple names with commas; the first is the real one.
        name = names.split(",").firstOrNull()?.trim().orEmpty().ifBlank { id.take(12) },
        image = image,
        state = state,
        status = status,
        ports = parsePorts(ports),
        labels = parseLabels(labels),
        createdAt = createdAt,
    )
}

data class ImageInfo(
    val id: String,
    val repository: String,
    val tag: String,
    val size: String,
    val createdSince: String,
) {
    /** `repo:tag`, or the short id for a dangling image. */
    val reference: String
        get() = when {
            repository.isBlank() || repository == "<none>" -> id.removePrefix("sha256:").take(12)
            tag.isBlank() || tag == "<none>" -> repository
            else -> "$repository:$tag"
        }

    val isDangling: Boolean get() = repository == "<none>" || tag == "<none>"
}

@Serializable
internal data class RawImage(
    @SerialName("ID") val id: String = "",
    @SerialName("Repository") val repository: String = "",
    @SerialName("Tag") val tag: String = "",
    @SerialName("Size") val size: String = "",
    @SerialName("CreatedSince") val createdSince: String = "",
) {
    fun toInfo() = ImageInfo(id, repository, tag, size, createdSince)
}

data class VolumeInfo(val name: String, val driver: String, val mountpoint: String)

@Serializable
internal data class RawVolume(
    @SerialName("Name") val name: String = "",
    @SerialName("Driver") val driver: String = "",
    @SerialName("Mountpoint") val mountpoint: String = "",
) {
    fun toInfo() = VolumeInfo(name, driver, mountpoint)
}

data class NetworkInfo(val id: String, val name: String, val driver: String, val scope: String)

@Serializable
internal data class RawNetwork(
    @SerialName("ID") val id: String = "",
    @SerialName("Name") val name: String = "",
    @SerialName("Driver") val driver: String = "",
    @SerialName("Scope") val scope: String = "",
) {
    fun toInfo() = NetworkInfo(id, name, driver, scope)
}

/** A compose project as reported by `docker compose ls --all`. */
@Serializable
data class ComposeProject(
    @SerialName("Name") val name: String = "",
    @SerialName("Status") val status: String = "",
    @SerialName("ConfigFiles") val configFiles: String = "",
) {
    val isRunning: Boolean get() = status.startsWith("running", ignoreCase = true)
    val firstConfigFile: String? get() = configFiles.split(",").firstOrNull()?.trim()?.ifBlank { null }
}

/** One line of `docker events --format '{{json .}}'`. */
@Serializable
internal data class RawEvent(
    @SerialName("Type") val type: String = "",
    @SerialName("Action") val action: String = "",
    @SerialName("status") val status: String = "",
    @SerialName("id") val id: String = "",
)

/** A Dockerfile or compose file found in the open project. */
data class ProjectArtifact(
    val file: File,
    val kind: Kind,
    /** Path relative to the project root, for display. */
    val relativePath: String,
) {
    enum class Kind { DOCKERFILE, COMPOSE }

    /** Default image tag / compose project name derived from the containing dir. */
    val suggestedName: String
        get() = (file.parentFile?.name ?: "app").lowercase()
            .replace(Regex("[^a-z0-9_.-]+"), "-")
            .trim('-')
            .ifBlank { "app" }
}

// ---------------------------------------------------------------- parsing

/**
 * Parse the `Ports` column, e.g.
 * `0.0.0.0:8080->80/tcp, :::8080->80/tcp` or `80/tcp` (published nothing).
 *
 * Docker lists the IPv4 and IPv6 bindings of the same publish separately; they
 * are deduped here so the UI shows one row per actual mapping.
 */
internal fun parsePorts(raw: String): List<PortBinding> {
    if (raw.isBlank()) return emptyList()
    val seen = LinkedHashMap<Pair<Int, Int>, PortBinding>()
    for (piece in raw.split(",")) {
        val part = piece.trim()
        if (!part.contains("->")) continue // not published to the host
        val (hostSide, containerSide) = part.split("->", limit = 2).let { it[0].trim() to it[1].trim() }

        val protocol = containerSide.substringAfter('/', "tcp").ifBlank { "tcp" }
        val containerPort = containerSide.substringBefore('/').toIntOrNull() ?: continue

        // hostSide is `ip:port`; IPv6 forms look like `:::8080` or `[::1]:8080`.
        val hostPort = hostSide.substringAfterLast(':').toIntOrNull() ?: continue
        val hostIp = hostSide.substringBeforeLast(':').ifBlank { "::" }

        val key = hostPort to containerPort
        val existing = seen[key]
        // Prefer the IPv4 record when both are present — it is the friendlier display.
        if (existing == null || (existing.hostIp.contains(':') && !hostIp.contains(':'))) {
            seen[key] = PortBinding(hostIp, hostPort, containerPort, protocol)
        }
    }
    return seen.values.sortedBy { it.hostPort }
}

/** Parse the `Labels` column: `k=v,k2=v2`. */
internal fun parseLabels(raw: String): Map<String, String> {
    if (raw.isBlank()) return emptyMap()
    return raw.split(",").mapNotNull { entry ->
        val trimmed = entry.trim()
        if (!trimmed.contains('=')) return@mapNotNull null
        trimmed.substringBefore('=') to trimmed.substringAfter('=')
    }.toMap()
}

/** Decode NDJSON (one object per line), skipping lines docker didn't mean as data. */
internal inline fun <reified T> parseNdJson(stdout: String): List<T> =
    stdout.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("{") }
        .mapNotNull { line -> runCatching { DockerJson.decodeFromString<T>(line) }.getOrNull() }
        .toList()
