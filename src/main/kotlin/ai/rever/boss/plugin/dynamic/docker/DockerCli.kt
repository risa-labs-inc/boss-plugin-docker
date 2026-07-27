package ai.rever.boss.plugin.dynamic.docker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Outcome of one `docker` invocation.
 *
 * [exitCode] uses two synthetic negatives for failures that never reached the
 * daemon, so callers can tell "docker isn't installed" apart from "docker said no".
 */
data class DockerExec(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val ok: Boolean get() = exitCode == 0

    /** stderr first — docker writes the useful failure text there. */
    val message: String get() = stderr.ifBlank { stdout }.trim()

    /** True when the CLI ran but could not reach a daemon. */
    val daemonUnreachable: Boolean
        get() = !ok && DAEMON_DOWN_MARKERS.any { stderr.contains(it, ignoreCase = true) }

    companion object {
        const val EXIT_CLI_MISSING = -1
        const val EXIT_TIMEOUT = -2

        private val DAEMON_DOWN_MARKERS = listOf(
            "Cannot connect to the Docker daemon",
            "Is the docker daemon running",
            "error during connect",
            "The system cannot find the file specified", // Windows named-pipe form
        )
    }
}

/**
 * The single place this plugin shells out to `docker`.
 *
 * Two rules hold everywhere in here:
 *
 * 1. **Absolute binary + widened child PATH.** `ProcessBuilder` resolves a bare
 *    command name against the *parent* process's PATH, which is nearly empty when
 *    the packaged host is launched from Finder. Docker Desktop installs the CLI in
 *    `/usr/local/bin`, so a bare `"docker"` works in dev and fails in the shipped
 *    app — the exact class of bug that has bitten MCP registration before.
 * 2. **argv lists, never a shell string.** Container names, image tags and paths
 *    come from daemon output and user input; passing a `List<String>` means there
 *    is no shell to inject into.
 */
object DockerCli {

    /** Install locations to search beyond PATH, and to prepend to the child's PATH. */
    private val extraDirs: List<String> by lazy {
        val home = System.getProperty("user.home").orEmpty()
        listOf(
            "/usr/local/bin",
            "/opt/homebrew/bin",
            "$home/.docker/bin",
            "/Applications/Docker.app/Contents/Resources/bin",
            "/usr/bin",
            "/bin",
        ).filter { it.isNotBlank() }
    }

    @Volatile
    private var cached: File? = null

    /**
     * Absolute path of the `docker` binary on PATH or in a common install dir,
     * or null when it is not installed. Cached, but re-resolved if the cached
     * file disappears (e.g. Docker Desktop uninstalled mid-session).
     */
    fun resolve(binary: String = "docker"): File? {
        if (binary == "docker") {
            cached?.let { if (it.isFile && it.canExecute()) return it }
        }
        val pathDirs = (System.getenv("PATH") ?: "").split(File.pathSeparator)
        val found = (pathDirs + extraDirs).asSequence()
            .filter { it.isNotBlank() }
            .map { File(it, binary) }
            .firstOrNull { it.isFile && it.canExecute() }
        if (binary == "docker") cached = found
        return found
    }

    /** True when the CLI is installed (says nothing about the daemon). */
    fun isInstalled(): Boolean = resolve() != null

    private fun ProcessBuilder.withResolvedPath(): ProcessBuilder = apply {
        val current = environment()["PATH"].orEmpty()
        environment()["PATH"] = (extraDirs + current)
            .filter { it.isNotBlank() }
            .joinToString(File.pathSeparator)
    }

    /**
     * Run `docker <args>` to completion and capture both streams.
     *
     * Cancelling the calling coroutine destroys the process immediately (via a
     * job completion handler) rather than waiting for the blocking reads to
     * return — without that, a cancelled call leaks a live `docker` child.
     */
    suspend fun exec(
        args: List<String>,
        workingDir: File? = null,
        timeoutMs: Long = 30_000,
    ): DockerExec = withContext(Dispatchers.IO) {
        val exe = resolve() ?: return@withContext DockerExec(
            DockerExec.EXIT_CLI_MISSING,
            "",
            "The docker CLI was not found on this machine.",
        )

        val process = ProcessBuilder(listOf(exe.absolutePath) + args)
            .directory(workingDir)
            .withResolvedPath()
            .start()

        // Kill the child the moment this coroutine is cancelled; closing its
        // streams is what unblocks the reads below.
        val killer = currentCoroutineContext().job.invokeOnCompletion { process.destroyForcibly() }
        try {
            process.outputStream.close() // never let docker block waiting on stdin
            val errText = async { runCatching { process.errorStream.bufferedReader().readText() }.getOrDefault("") }
            val outText = runCatching { process.inputStream.bufferedReader().readText() }.getOrDefault("")
            val err = errText.await()
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                DockerExec(DockerExec.EXIT_TIMEOUT, outText, "docker ${args.firstOrNull().orEmpty()} timed out")
            } else {
                DockerExec(process.exitValue(), outText, err)
            }
        } finally {
            killer.dispose()
            if (process.isAlive) process.destroyForcibly()
        }
    }

    /**
     * Run a long-lived streaming command (`docker logs -f`, `docker events`) and
     * deliver stdout+stderr line by line until the process ends or the caller is
     * cancelled. Suspends for the lifetime of the stream; launch it yourself.
     *
     * @return the process exit code, or [DockerExec.EXIT_CLI_MISSING].
     */
    suspend fun stream(
        args: List<String>,
        workingDir: File? = null,
        onLine: suspend (String) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        val exe = resolve() ?: return@withContext DockerExec.EXIT_CLI_MISSING

        val process = ProcessBuilder(listOf(exe.absolutePath) + args)
            .directory(workingDir)
            .redirectErrorStream(true) // interleaved is what a log view wants
            .withResolvedPath()
            .start()

        val killer = currentCoroutineContext().job.invokeOnCompletion { process.destroyForcibly() }
        try {
            process.outputStream.close()
            val reader = process.inputStream.bufferedReader()
            while (true) {
                val line = runCatching { reader.readLine() }.getOrNull() ?: break
                onLine(line)
            }
            runCatching { process.waitFor(5, TimeUnit.SECONDS) }
            if (process.isAlive) DockerExec.EXIT_TIMEOUT else process.exitValue()
        } finally {
            killer.dispose()
            if (process.isAlive) process.destroyForcibly()
        }
    }

    /** Convenience: run and return stdout, or null when the command failed. */
    suspend fun execOrNull(args: List<String>, timeoutMs: Long = 30_000): String? =
        exec(args, timeoutMs = timeoutMs).takeIf { it.ok }?.stdout
}
