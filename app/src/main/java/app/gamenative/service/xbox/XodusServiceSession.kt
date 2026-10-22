package app.gamenative.service.xbox

import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.TimeUnit

/** A connected stream to a Unix domain socket (Android: `LocalSocket`). */
interface UnixStream : Closeable {
    val input: InputStream
    val output: OutputStream
}

fun interface UnixConnector {
    /** Connects to the filesystem socket at [path] or throws. */
    fun connect(path: String): UnixStream
}

/**
 * Loopback TCP bridge in front of `xodus-service`'s Unix socket.
 *
 * Games run under Wine, whose patched `xgameruntime.dll` reaches the account
 * service over TCP (`XODUS_BRIDGE_PORT`), authenticating every connection with
 * the 64-character `XODUS_BRIDGE_KEY` before any byte is forwarded. Port of
 * Xodus Nexus's `startXodusBridge`.
 */
class XodusBridge private constructor(
    private val server: ServerSocket,
    val key: String,
    private val socketPath: String,
    private val connector: UnixConnector,
) : Closeable {
    val port: Int get() = server.localPort
    private val open = Collections.synchronizedSet(HashSet<Closeable>())
    @Volatile private var closed = false

    private fun acceptLoop() {
        while (!closed) {
            val client = try {
                server.accept()
            } catch (_: Exception) {
                break
            }
            open += client
            Thread({ handle(client) }, "xodus-bridge-conn").apply { isDaemon = true; start() }
        }
    }

    private fun handle(client: Socket) {
        var upstream: UnixStream? = null
        try {
            client.soTimeout = 10_000
            val expected = key.toByteArray(Charsets.US_ASCII)
            val supplied = ByteArray(expected.size)
            var read = 0
            val input = client.getInputStream()
            while (read < supplied.size) {
                val n = input.read(supplied, read, supplied.size - read)
                if (n < 0) throw IllegalStateException("closed before key")
                read += n
            }
            if (!MessageDigest.isEqual(supplied, expected)) throw SecurityException("bad bridge key")
            client.soTimeout = 0
            val target = connector.connect(socketPath).also { upstream = it; open += it }
            val up = pipe(input, target.output) { shutdown(client, target) }
            pipe(target.input, client.getOutputStream()) { shutdown(client, target) }
            up.join()
        } catch (_: Exception) {
            upstream?.let { shutdown(client, it) } ?: closeQuietly(client)
        }
    }

    private fun shutdown(client: Socket, upstream: UnixStream) {
        closeQuietly(client)
        closeQuietly(upstream)
    }

    private fun closeQuietly(item: Closeable) {
        open -= item
        runCatching { item.close() }
    }

    override fun close() {
        closed = true
        runCatching { server.close() }
        synchronized(open) { open.toList() }.forEach { runCatching { it.close() } }
        open.clear()
    }

    companion object {
        fun start(socketPath: String, connector: UnixConnector, random: SecureRandom = SecureRandom()): XodusBridge {
            val bytes = ByteArray(32).also(random::nextBytes)
            val key = bytes.joinToString("") { "%02x".format(it) }
            // xgameruntime connects to 127.0.0.1 over IPv4; Android's getLoopbackAddress() is ::1,
            // which never accepts it.
            val server = ServerSocket(0, 16, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
            return XodusBridge(server, key, socketPath, connector).also { bridge ->
                Thread(bridge::acceptLoop, "xodus-bridge").apply { isDaemon = true; start() }
            }
        }

        internal fun pipe(from: InputStream, to: OutputStream, onEnd: () -> Unit): Thread =
            Thread({
                try {
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val n = from.read(buffer)
                        if (n < 0) break
                        to.write(buffer, 0, n)
                        to.flush()
                    }
                } catch (_: Exception) {
                } finally {
                    onEnd()
                }
            }, "xodus-bridge-pipe").apply { isDaemon = true; start() }
    }
}

/**
 * `xodus-service` running beside a game: it serves token, license, Store and
 * cloud-save requests from the in-prefix `xgameruntime.dll`. Started before
 * Wine, stopped after Wine exits (save upload happens at game exit).
 */
class XodusServiceSession private constructor(
    private val session: XodusBackend.Session,
    private val runtimeDir: File,
    val bridge: XodusBridge,
    private val signals: ProcessSignals,
) : Closeable {
    /** Variables the game's Wine environment needs to reach the service. */
    val gameEnvironment: Map<String, String>
        get() = mapOf("XODUS_BRIDGE_PORT" to bridge.port.toString(), "XODUS_BRIDGE_KEY" to bridge.key)

    val isAlive: Boolean get() = session.isAlive

    override fun close() {
        bridge.close()
        // xodus-service shuts down cleanly (and removes its socket) on SIGINT only.
        if (session.isAlive) {
            signals.interrupt(session.process)
            if (session.waitFor(4) == null) session.close()
        }
        runtimeDir.deleteRecursively()
    }

    companion object {
        const val SOCKET_NAME = "xodus.sock"

        fun start(
            backend: XodusBackend,
            paths: XodusPaths,
            connector: UnixConnector,
            serviceBinary: File = paths.service,
            timeoutMillis: Long = 90_000,
            signals: ProcessSignals = ProcessSignals,
        ): XodusServiceSession {
            paths.ensureDirectories()
            val runtimeDir = File(paths.serviceSessions, "game-${System.nanoTime()}").apply {
                mkdirs()
                setReadable(false, false); setReadable(true, true)
                setWritable(false, false); setWritable(true, true)
                setExecutable(false, false); setExecutable(true, true)
            }
            val socket = File(runtimeDir, SOCKET_NAME)
            val session = backend.startSession(emptyList(), serviceBinary, mapOf("XDG_RUNTIME_DIR" to runtimeDir.absolutePath))
            // stdout carries nothing we need; keep the pipe drained.
            Thread({ runCatching { session.readLines { true } } }, "xodus-service-out").apply { isDaemon = true; start() }
            val deadline = System.currentTimeMillis() + timeoutMillis
            try {
                while (System.currentTimeMillis() < deadline) {
                    if (!session.isAlive) {
                        val credential = XodusProtocol.CredentialError.find(session.stderrText())
                        throw XodusException(
                            credential?.message ?: "The Xbox account service stopped. Check the sign-in and network connection.",
                            credential,
                        )
                    }
                    if (socket.exists() && runCatching { connector.connect(socket.absolutePath).close() }.isSuccess) {
                        val bridge = XodusBridge.start(socket.absolutePath, connector)
                        return XodusServiceSession(session, runtimeDir, bridge, signals)
                    }
                    Thread.sleep(250)
                }
                throw XodusException("The Xbox account service did not become ready in time.")
            } catch (e: Exception) {
                session.close()
                runtimeDir.deleteRecursively()
                throw e
            }
        }
    }
}

/** Sends SIGINT, which the Rust backend handles as Ctrl+C (graceful shutdown). */
open class ProcessSignals {
    open fun interrupt(process: Process) {
        val pid = pidOf(process) ?: return run { process.destroy() }
        runCatching {
            ProcessBuilder("kill", "-INT", pid.toString()).redirectErrorStream(true).start().waitFor(2, TimeUnit.SECONDS)
        }.onFailure { process.destroy() }
    }

    companion object Default : ProcessSignals() {
        /** `Process.pid()` exists on the JVM; Android's `UNIXProcess` keeps it in a private field. */
        fun pidOf(process: Process): Int? =
            runCatching { (Process::class.java.getMethod("pid").invoke(process) as Long).toInt() }.getOrNull()
                ?: runCatching {
                    process.javaClass.getDeclaredField("pid").apply { isAccessible = true }.getInt(process)
                }.getOrNull()
    }
}
