package ai.rever.boss.plugin.dynamic.docker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
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

        /**
         * A binary was found but the OS refused to start it (boss-plugin-docker#9: on Windows
         * the extensionless `docker` shell script next to `docker.exe`, CreateProcess
         * error=193). Reported through [message], so the engine shows it as an error state
         * instead of the exception escaping as a crash.
         */
        const val EXIT_LAUNCH_FAILED = -3

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
        val found = findExecutable(binary, pathDirs + extraDirs)
        if (binary == "docker") cached = found
        return found
    }

    /**
     * The first runnable [binary] in [dirs], or null.
     *
     * On Windows the name is tried with each `PATHEXT` extension and **never bare**, which is
     * how Windows itself resolves a command. Docker Desktop ships an extensionless `docker`
     * (a shell script for WSL and Git Bash) in the same `resources\bin` directory as
     * `docker.exe`, and Windows has no execute bit, so `canExecute()` is true for it:
     * picking it made every call fail with CreateProcess error=193 (boss-plugin-docker#9).
     */
    internal fun findExecutable(
        binary: String,
        dirs: List<String>,
        windows: Boolean = isWindows(),
        pathExt: String? = System.getenv("PATHEXT"),
    ): File? {
        val names = executableNames(binary, windows, pathExt)
        return dirs.asSequence()
            .filter { it.isNotBlank() }
            .flatMap { dir -> names.asSequence().map { File(dir, it) } }
            .firstOrNull { it.isFile && it.canExecute() }
    }

    internal fun executableNames(binary: String, windows: Boolean, pathExt: String?): List<String> {
        if (!windows || binary.contains('.')) return listOf(binary)
        val extensions = pathExt
            ?.split(';')
            ?.map { it.trim().lowercase() }
            ?.filter { it.startsWith(".") && it.length > 1 }
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_PATHEXT
        return extensions.map { binary + it }
    }

    private val DEFAULT_PATHEXT = listOf(".com", ".exe", ".bat", ".cmd")

    private fun isWindows(): Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

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
    ): DockerExec {
        val exe = resolve() ?: return DockerExec(
            DockerExec.EXIT_CLI_MISSING,
            "",
            "The docker CLI was not found on this machine.",
        )
        return runProcess(exe, args, workingDir, timeoutMs)
    }

    /**
     * Run [exe] with [args] to completion, bounded by [timeoutMs]. Split from [exec] so the
     * process handling can be exercised with any executable, not only an installed docker.
     */
    internal suspend fun runProcess(
        exe: File,
        args: List<String>,
        workingDir: File? = null,
        timeoutMs: Long = 30_000,
    ): DockerExec = withContext(Dispatchers.IO) {
        val process = try {
            ProcessBuilder(listOf(exe.absolutePath) + args)
                .directory(workingDir)
                .withResolvedPath()
                .start()
        } catch (e: IOException) {
            launchFailed(exe)
            return@withContext DockerExec(DockerExec.EXIT_LAUNCH_FAILED, "", launchFailureMessage(exe, e))
        }

        // Kill the child the moment this coroutine is cancelled; closing its
        // streams is what unblocks the reads below.
        val killer = currentCoroutineContext().job.invokeOnCompletion { destroyTree(process) }
        try {
            process.outputStream.close() // never let docker block waiting on stdin
            // The timeout has to bound the READS, not just the exit reap. readText() blocks
            // until docker closes stdout, so a `waitFor(timeoutMs)` placed after it bounds only
            // the gap between EOF and exit. The docker CLI has no client-side request timeout,
            // so a wedged daemon socket hangs `docker version` with stdout open, and the probe
            // never returned.
            //
            // Raced, not wrapped - the same fix boss-microkernel-runtime's ProcessRunner carries.
            // readText() has no suspension point, so enclosing it in withTimeoutOrNull would give
            // the cancellation nowhere to land. Awaiting a separate job does suspend, and
            // destroying the child on expiry closes its streams, which unblocks the readers.
            coroutineScope {
                val body = async {
                    val errText = async { runCatching { process.errorStream.bufferedReader().readText() }.getOrDefault("") }
                    val outText = runCatching { process.inputStream.bufferedReader().readText() }.getOrDefault("")
                    val err = errText.await()
                    process.waitFor()
                    DockerExec(process.exitValue(), outText, err)
                }
                withTimeoutOrNull(timeoutMs) { body.await() } ?: run {
                    // The whole tree, not just docker: `docker compose` and `docker buildx` run
                    // as CLI-plugin child processes that inherit stdout, and the readers only
                    // reach EOF once every writer is gone.
                    destroyTree(process)
                    body.cancel()
                    DockerExec(DockerExec.EXIT_TIMEOUT, "", "docker ${args.firstOrNull().orEmpty()} timed out")
                }
            }
        } finally {
            killer.dispose()
            if (process.isAlive) destroyTree(process)
        }
    }

    /**
     * Kill [process] and everything it started. Descendants are collected first: once the
     * parent is gone they are re-parented and no longer reachable from it.
     */
    private fun destroyTree(process: Process) {
        val descendants = runCatching { process.descendants().toList() }.getOrDefault(emptyList())
        process.destroyForcibly()
        descendants.forEach { runCatching { it.destroyForcibly() } }
    }

    /** Forget a cached binary the OS refused to start, so a repaired install is found again. */
    private fun launchFailed(exe: File) {
        if (cached == exe) cached = null
    }

    private fun launchFailureMessage(exe: File, e: IOException): String =
        "Could not start ${exe.absolutePath}: ${e.message ?: e::class.java.simpleName}"

    /**
     * Run a long-lived streaming command (`docker logs -f`, `docker events`) and
     * deliver stdout+stderr line by line until the process ends or the caller is
     * cancelled. Suspends for the lifetime of the stream; launch it yourself.
     *
     * @return the process exit code, [DockerExec.EXIT_CLI_MISSING], or
     *   [DockerExec.EXIT_LAUNCH_FAILED] (after passing the reason to [onLine]).
     */
    suspend fun stream(
        args: List<String>,
        workingDir: File? = null,
        onLine: suspend (String) -> Unit,
    ): Int {
        val exe = resolve() ?: return DockerExec.EXIT_CLI_MISSING
        return streamProcess(exe, args, workingDir, onLine)
    }

    internal suspend fun streamProcess(
        exe: File,
        args: List<String>,
        workingDir: File? = null,
        onLine: suspend (String) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        val process = try {
            ProcessBuilder(listOf(exe.absolutePath) + args)
                .directory(workingDir)
                .redirectErrorStream(true) // interleaved is what a log view wants
                .withResolvedPath()
                .start()
        } catch (e: IOException) {
            launchFailed(exe)
            onLine(launchFailureMessage(exe, e))
            return@withContext DockerExec.EXIT_LAUNCH_FAILED
        }

        val killer = currentCoroutineContext().job.invokeOnCompletion { destroyTree(process) }
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
            if (process.isAlive) destroyTree(process)
        }
    }

    /** Convenience: run and return stdout, or null when the command failed. */
    suspend fun execOrNull(args: List<String>, timeoutMs: Long = 30_000): String? =
        exec(args, timeoutMs = timeoutMs).takeIf { it.ok }?.stdout
}
