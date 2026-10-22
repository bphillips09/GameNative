package app.gamenative.service.xbox

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.ProtocolFamily
import java.net.SocketAddress
import java.net.StandardProtocolFamily
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Drives the real process plumbing against small test doubles that speak the
 * backend's line protocols (src/test/resources/xbox).
 */
class XodusProcessTest {
    private lateinit var root: File
    private lateinit var paths: XodusPaths
    private lateinit var backend: XodusBackend

    private fun resource(name: String): File =
        File(javaClass.classLoader!!.getResource("xbox/$name")!!.toURI())

    @Before
    fun setUp() {
        assumeTrue(File("/bin/sh").canExecute())
        // The service's Unix socket lives under this root, and socket paths are limited to
        // 104 bytes (macOS) or 108 (Linux); Gradle's per-task temp dir is too deep for that.
        val shortTmp = File("/tmp").takeIf { it.isDirectory && it.canWrite() }
        root = (shortTmp?.let { createTempDirectory(it.toPath(), "xp") } ?: createTempDirectory("xodus-process")).toFile()
        val lib = File(root, "lib").apply { mkdirs() }
        resource("fake-xodus-cli.sh").copyTo(File(lib, XboxConstants.CLI_BINARY)).setExecutable(true)
        resource("fake-xodus-service.py").copyTo(File(lib, XboxConstants.SERVICE_BINARY)).setExecutable(true)
        paths = XodusPaths(lib, File(root, "data"), File(root, "cache"))
        backend = XodusBackend(paths, paths.environment("Ayn", "Odin 3"))
    }

    @After
    fun tearDown() {
        if (::root.isInitialized) root.deleteRecursively()
    }

    @Test
    fun environment_pointsEverythingAtPrivateStorage() {
        val env = paths.environment("Ayn", "Odin 3")
        assertEquals(File(root, "data/secrets").absolutePath, env["XODUS_SECRETS_DIR"])
        assertEquals(File(root, "data/cacerts.pem").absolutePath, env["SSL_CERT_FILE"])
        assertEquals(File(root, "cache/tmp").absolutePath, env["TMPDIR"])
        assertEquals("Odin 3", env["XODUS_DEVICE_MODEL"])
        assertEquals("error", env["XODUS_LOG"])
    }

    @Test
    fun account_andCredentialErrors() {
        assertEquals(XodusProtocol.Account(true, "player@example.com"), backend.account())
        val error = assertThrows(XodusException::class.java) { backend.library("player@example.com", "US") }
        assertEquals(XodusProtocol.CredentialError.KEYRING_MISSING_DATA, error.credentialError)
    }

    @Test
    fun signIn_relaysFramesBetweenBackendAndPage() {
        val signIn = XboxSignIn(backend)
        val done = CountDownLatch(1)
        val opened = mutableListOf<String>()
        var evaluated: String? = null
        var result: Pair<String?, Boolean>? = null
        signIn.start(object : XboxSignIn.View {
            override fun open(session: Long, url: String, headers: Map<String, String>) {
                opened += url
                assertEquals("CloudExperienceHost", headers["hostapp"])
                // The page asks the host for its context first.
                signIn.onPageNotify(session, """{"type":"invoke","value":{"name":"CloudExperienceHost.getContext","context":"x"}}""")
            }
            override fun close(session: Long) {}
            override fun evaluate(session: Long, script: String) {
                evaluated = script
                // Then the token arrives once the user signed in.
                signIn.onPostSrfLoaded(session, """{"sDAToken":"t","sDASessionKey":"k"}""")
            }
            override fun finished(error: String?, cancelled: Boolean) {
                result = error to cancelled
                done.countDown()
            }
        })
        assertTrue(done.await(20, TimeUnit.SECONDS))
        assertEquals(null to false, result)
        assertEquals(listOf("https://login.live.com/ppsecure/InlineLogin.srf?id=80604"), opened)
        assertTrue(evaluated!!.contains("CloudExperienceHost.Bridge.dispatchMessage"))
    }

    @Test
    fun signIn_staleSessionsAreIgnored() {
        val signIn = XboxSignIn(backend)
        // Nothing open yet: page messages must not reach the backend.
        signIn.onPageNotify(1, "{}")
        signIn.onWindowClosed(1)
        assertTrue(XboxSignIn.isPostSrf("https://login.live.com/ppsecure/post.srf?x=1"))
        assertFalse(XboxSignIn.isPostSrf("https://login.live.com.evil/ppsecure/post.srf"))
        assertTrue(XboxSignIn.documentStartScript("__xodusAuth").contains("window['__xodusAuth']"))
    }

    @Test
    fun download_reportsProgressSupportsPauseAndRequiresComplete() {
        val destination = File(root, "games/9NOWNEDGAME1")
        val download = XboxDownload(backend, "9NOWNEDGAME1", "11111111-2222-3333-4444-555555555555", destination, "US")
        val seen = mutableListOf<XodusProtocol.Progress>()
        val outcome = download.run { progress ->
            seen += progress
            if (progress.sequence == 2L) download.pause()
            if (progress.paused) download.resume()
        }
        assertEquals(XboxDownload.Outcome.Complete, outcome)
        assertEquals(listOf(1L, 2L, 3L, 4L), seen.map { it.sequence })
        assertTrue(seen[2].paused)
        assertTrue(XboxDownload.isInstalled(destination))
        assertThrows(IllegalArgumentException::class.java) {
            XboxDownload(backend, "bad", "11111111-2222-3333-4444-555555555555", destination, "US").run { }
        }
    }

    @Test
    fun launch_preparesViewAndRemovesItOnClose() {
        val install = File(root, "games/9NOWNEDGAME1").apply { mkdirs() }
        val viewRoot = paths.viewRoot("9NOWNEDGAME1")
        val args = XboxLaunch.arguments(install, "Bin\\Game.exe", "US", viewRoot)
        assertEquals(listOf("prepare-launch", install.absolutePath, "--exe", "Bin\\Game.exe", "--market", "US", "--view-root", viewRoot.absolutePath, "--sweep-stale-views"), args)

        val launch = XboxLaunch.prepare(backend, install, "Bin\\Game.exe", "US", viewRoot)
        assertTrue(File(launch.ready.executable).isFile)
        assertEquals("A:\\xodus-view-test\\Bin\\Game.exe", launch.windowsExecutable())
        assertEquals(File(viewRoot, "xodus-view-test/Bin"), launch.workingDirectory)
        assertEquals(mapOf("XODUS_XSTS_PLAYFAB" to "XBL3.0 x=1;t"), launch.ready.environment)
        launch.close()
        assertFalse("decrypted view is gone after the game", File(viewRoot, "xodus-view-test").exists())
        assertFalse(launch.isAlive)
    }

    // Not Channels.newInputStream/newOutputStream: both lock the channel's blockingLock, so a read
    // blocked on one thread stalls the bridge's write on another. SocketChannel's own read and
    // write lock separately, like Android's LocalSocket streams.
    private class JdkUnixStream(private val channel: SocketChannel) : UnixStream {
        override val input: InputStream = object : InputStream() {
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int =
                if (len == 0) 0 else channel.read(ByteBuffer.wrap(b, off, len))
        }
        override val output: OutputStream = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(b: ByteArray, off: Int, len: Int) {
                val buffer = ByteBuffer.wrap(b, off, len)
                while (buffer.hasRemaining()) channel.write(buffer)
            }
        }
        override fun close() = channel.close()
    }

    private val jdkConnector = UnixConnector { path ->
        // Unix-domain channels are JDK 16+ APIs that android.jar does not declare,
        // so reach them reflectively; the JVM running unit tests has them.
        val address = Class.forName("java.net.UnixDomainSocketAddress")
            .getMethod("of", String::class.java).invoke(null, path) as SocketAddress
        val channel = SocketChannel::class.java.getMethod("open", ProtocolFamily::class.java)
            .invoke(null, StandardProtocolFamily.valueOf("UNIX")) as SocketChannel
        JdkUnixStream(channel.apply { connect(address) })
    }

    @Test
    fun service_bridgeRequiresTheKeyAndForwardsBytes() {
        assumeTrue(File("/usr/bin/python3").canExecute() || File("/bin/python3").canExecute())
        val service = XodusServiceSession.start(backend, paths, jdkConnector, timeoutMillis = 20_000)
        try {
            val env = service.gameEnvironment
            assertEquals(64, env.getValue("XODUS_BRIDGE_KEY").length)
            val port = env.getValue("XODUS_BRIDGE_PORT").toInt()

            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5000
                socket.getOutputStream().write((service.bridge.key + "ping").toByteArray())
                val reply = ByteArray(9)
                var read = 0
                while (read < reply.size) read += socket.getInputStream().read(reply, read, reply.size - read)
                assertEquals("echo:ping", String(reply))
            }

            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5000
                socket.getOutputStream().write(("0".repeat(64) + "ping").toByteArray())
                // Closing with "ping" still unread makes TCP send a reset rather than EOF.
                val read = runCatching { socket.getInputStream().read() }
                assertTrue("wrong key is disconnected", read.getOrNull() == -1 || read.exceptionOrNull() is java.net.SocketException)
            }
            assertTrue(service.isAlive)
        } finally {
            service.close()
        }
        assertFalse(service.isAlive)
        assertNull(paths.serviceSessions.listFiles()?.firstOrNull())
    }

    @Test
    fun service_failsFastWhenTheBinaryExits() {
        val error = assertThrows(XodusException::class.java) {
            // The CLI double exits immediately for unknown commands.
            XodusServiceSession.start(backend, paths, jdkConnector, serviceBinary = paths.cli, timeoutMillis = 10_000)
        }
        assertNotNull(error.message)
    }
}
