package ai.rever.boss.plugin.dynamic.docker

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/**
 * How [DockerCli] finds and runs the docker binary.
 *
 * The process tests run a real child (`/bin/sh`), because both failures were in how a real
 * child is started and read, not in anything a fake would exercise.
 */
class DockerCliTest {
    private val dir: File = Files.createTempDirectory("docker-cli-test").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun executable(name: String): File =
        File(dir, name).apply {
            writeText("#!/bin/sh\n")
            setExecutable(true)
        }

    private val sh = File("/bin/sh")

    // region resolution (boss-plugin-docker#9)

    @Test
    fun `on Windows the exe wins over Docker Desktop's extensionless shell script`() {
        // Docker Desktop's resources\bin holds both; Windows has no execute bit, so the
        // extensionless one also reads as runnable - and CreateProcess cannot start it.
        executable("docker")
        val exe = executable("docker.exe")

        assertEquals(exe, DockerCli.findExecutable("docker", listOf(dir.path), windows = true, pathExt = ".COM;.EXE;.BAT;.CMD"))
    }

    @Test
    fun `on Windows an extensionless file alone is not a docker CLI`() {
        executable("docker")

        assertNull(DockerCli.findExecutable("docker", listOf(dir.path), windows = true, pathExt = null))
    }

    @Test
    fun `elsewhere the bare name is the binary`() {
        val bare = executable("docker")
        executable("docker.exe")

        assertEquals(bare, DockerCli.findExecutable("docker", listOf(dir.path), windows = false, pathExt = null))
    }

    @Test
    fun `Windows extensions follow PATHEXT, and a missing PATHEXT falls back to the system default`() {
        assertEquals(listOf("docker.exe", "docker.cmd"), DockerCli.executableNames("docker", windows = true, pathExt = ".EXE; .CMD;;"))
        assertEquals(
            listOf("docker.com", "docker.exe", "docker.bat", "docker.cmd"),
            DockerCli.executableNames("docker", windows = true, pathExt = null),
        )
        // An explicit extension is taken as given.
        assertEquals(listOf("docker.exe"), DockerCli.executableNames("docker.exe", windows = true, pathExt = null))
    }

    // endregion

    // region launch failure (boss-plugin-docker#9)

    @Test
    fun `a binary the OS refuses to start is reported, not thrown`() = runBlocking {
        // No execute permission: start() throws IOException, the Linux form of error=193.
        val unlaunchable = File(dir, "docker").apply { writeText("not a program") }

        val result = DockerCli.runProcess(unlaunchable, listOf("version"))

        assertEquals(DockerExec.EXIT_LAUNCH_FAILED, result.exitCode)
        assertTrue(result.message.startsWith("Could not start ${unlaunchable.absolutePath}"), result.message)
    }

    @Test
    fun `a stream whose binary will not start returns the launch code and says why`() = runBlocking {
        val unlaunchable = File(dir, "docker").apply { writeText("not a program") }
        val lines = mutableListOf<String>()

        val code = DockerCli.streamProcess(unlaunchable, listOf("events")) { lines += it }

        assertEquals(DockerExec.EXIT_LAUNCH_FAILED, code)
        assertTrue(lines.single().startsWith("Could not start"), lines.toString())
    }

    // endregion

    // region bounded timeouts

    @Test
    fun `a child that holds stdout open is timed out, not waited on forever`() = runBlocking {
        // The wedged-daemon shape: the CLI neither answers nor closes stdout. Before, the
        // blocking read ran before the timed wait, so this took the full 30 s.
        val elapsed = measureTime {
            val result = DockerCli.runProcess(sh, listOf("-c", "exec sleep 30"), timeoutMs = 500)
            assertEquals(DockerExec.EXIT_TIMEOUT, result.exitCode)
        }

        assertTrue(elapsed < 10.seconds, "took $elapsed; the timeout must bound the reads")
    }

    @Test
    fun `a grandchild holding stdout is killed with its parent`() = runBlocking {
        // `docker compose` / `docker buildx` run as CLI-plugin children of docker and inherit
        // its stdout. Killing docker alone leaves them holding the pipe, so the read still
        // blocks for as long as they live.
        val elapsed = measureTime {
            val result = DockerCli.runProcess(sh, listOf("-c", "sleep 30; echo done"), timeoutMs = 500)
            assertEquals(DockerExec.EXIT_TIMEOUT, result.exitCode)
        }

        assertTrue(elapsed < 10.seconds, "took $elapsed; the plugin child kept the pipe open")
    }

    @Test
    fun `a normal command still returns its exit code and both streams`() = runBlocking {
        val result = DockerCli.runProcess(sh, listOf("-c", "echo out; echo err >&2; exit 3"))

        assertEquals(3, result.exitCode)
        assertEquals("out\n", result.stdout)
        assertEquals("err\n", result.stderr)
    }

    // endregion
}
