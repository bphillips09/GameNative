package app.gamenative.service.xbox

import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Where the Android build of the Xodus Nexus backend lives and what it needs
 * from the environment. The backend ships inside the APK as
 * `lib/arm64-v8a/libxodus_cli.so` and `libxodus_service.so` (executables named
 * like libraries so the package installer extracts them executable, the same
 * trick GameNative uses for Comet).
 */
data class XodusPaths(
    /** `applicationInfo.nativeLibraryDir`. */
    val nativeLibraryDir: File,
    /** Private persistent root, e.g. `filesDir/xodus`. */
    val dataDir: File,
    /** Private scratch root, e.g. `cacheDir/xodus`. */
    val cacheDir: File,
) {
    val cli: File get() = File(nativeLibraryDir, XboxConstants.CLI_BINARY)
    val service: File get() = File(nativeLibraryDir, XboxConstants.SERVICE_BINARY)
    val home: File get() = File(dataDir, "home")
    /** Microsoft account/device tokens (one file per entry, mode 0600). */
    val secrets: File get() = File(dataDir, "secrets")
    val caBundle: File get() = File(dataDir, "cacerts.pem")
    val tmp: File get() = File(cacheDir, "tmp")
    /** Per-game launch views (decrypted executables live here only while a game runs). */
    fun viewRoot(productId: String): File = File(dataDir, "views/$productId")
    val serviceSessions: File get() = File(cacheDir, "service")

    fun ensureDirectories() {
        for (dir in listOf(dataDir, home, secrets, tmp, cacheDir, serviceSessions)) {
            dir.mkdirs()
            // Owner-only; the Android sandbox already isolates app storage.
            dir.setReadable(false, false); dir.setReadable(true, true)
            dir.setWritable(false, false); dir.setWritable(true, true)
            dir.setExecutable(false, false); dir.setExecutable(true, true)
        }
    }

    /** Environment for every backend process. Inherits nothing but PATH/locale basics. */
    fun environment(deviceManufacturer: String, deviceModel: String, verbose: Boolean = false): Map<String, String> = linkedMapOf(
        "HOME" to home.absolutePath,
        "TMPDIR" to tmp.absolutePath,
        "XODUS_SECRETS_DIR" to secrets.absolutePath,
        // OpenSSL (sign-in) and rustls (everything else) both honour this bundle.
        "SSL_CERT_FILE" to caBundle.absolutePath,
        "XODUS_DEVICE_MANUFACTURER" to deviceManufacturer,
        "XODUS_DEVICE_MODEL" to deviceModel,
        "XODUS_LOG" to if (verbose) "info" else "error",
        "RUST_BACKTRACE" to "0",
    )
}

class XodusException(message: String, val credentialError: XodusProtocol.CredentialError? = null) : Exception(message)

data class ProcessResult(val code: Int, val stdout: String, val stderr: String)

/**
 * Runs `xodus-cli` subcommands. Long-lived interactive commands (sign-in,
 * downloads, launch handoff) get a [Session] with line-based stdio.
 */
class XodusBackend(
    private val paths: XodusPaths,
    private val environment: Map<String, String>,
) {
    val isAvailable: Boolean get() = paths.cli.isFile

    private fun builder(binary: File, args: List<String>, extraEnv: Map<String, String> = emptyMap()): ProcessBuilder {
        if (!binary.isFile) throw XodusException("The Xbox backend is not included in this build.")
        paths.ensureDirectories()
        return ProcessBuilder(listOf(binary.absolutePath) + args).apply {
            directory(paths.home)
            val env = environment()
            // Keep the parent's PATH/locale, drop anything that could leak in (e.g. LD_PRELOAD).
            val keep = env.filterKeys { it == "PATH" || it == "LANG" || it == "ANDROID_ROOT" || it == "ANDROID_DATA" }
            env.clear()
            env.putAll(keep)
            env.putAll(environment)
            env.putAll(extraEnv)
        }
    }

    /** Runs a short command to completion. */
    fun run(args: List<String>, timeoutSeconds: Long = 120, maxOutput: Int = 8_000_000): ProcessResult {
        val process = builder(paths.cli, args).start()
        process.outputStream.close()
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val outThread = drain(process.inputStream.bufferedReader(), stdout, maxOutput)
        val errThread = drain(process.errorStream.bufferedReader(), stderr, 64_000)
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            outThread.join(1000); errThread.join(1000)
            throw XodusException("The Xbox backend did not respond in time.")
        }
        outThread.join(); errThread.join()
        return ProcessResult(process.exitValue(), stdout.toString(), stderr.toString())
    }

    /** Runs a command and throws a user-presentable error when it fails. */
    fun runChecked(args: List<String>, failure: String, timeoutSeconds: Long = 120): String {
        val result = run(args, timeoutSeconds)
        if (result.code != 0) {
            val credential = XodusProtocol.CredentialError.find(result.stderr)
            throw XodusException(credential?.message ?: failure, credential)
        }
        return result.stdout
    }

    fun account(): XodusProtocol.Account =
        XodusProtocol.parseAccount(runChecked(listOf("account", "--json"), "The Xbox account could not be read.", 30))

    fun library(account: String, market: String): XodusProtocol.Library =
        XodusProtocol.parseLibrary(
            runChecked(listOf("library", "--json", "--market", market), "Your Xbox library could not be loaded. Try again later.", 60),
            account,
            market,
        )

    fun resolvePackage(productId: String, market: String): XodusProtocol.PackagePlan =
        XodusProtocol.parsePackagePlan(
            runChecked(listOf("resolve-package", productId, "--json", "--market", market), "Store package information is unavailable.", 60),
            productId,
            market,
        )

    fun logout(removeDevice: Boolean = false) {
        runChecked(if (removeDevice) listOf("logout", "--device") else listOf("logout"), "Sign-out failed.", 30)
    }

    /** Starts a long-running subcommand with line-oriented stdin/stdout. */
    fun startSession(args: List<String>, binary: File = paths.cli, extraEnv: Map<String, String> = emptyMap()): Session =
        Session(builder(binary, args, extraEnv).start())

    /**
     * A running backend process. stdout is delivered line by line to [onLine];
     * stderr is kept (bounded) only to look for fixed credential error codes.
     */
    class Session internal constructor(val process: Process) : Closeable {
        private val writer = OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8)
        private val stderr = StringBuilder()
        private val closed = AtomicBoolean(false)
        private val errThread = drain(process.errorStream.bufferedReader(), stderr, 64_000)

        /** Reads stdout until [onLine] returns false or the stream ends. Call from one thread. */
        fun readLines(onLine: (String) -> Boolean) {
            val reader = BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8))
            while (true) {
                val line = reader.readLine() ?: break
                if (!onLine(line)) break
            }
        }

        @Synchronized
        fun send(line: String) {
            if (closed.get()) return
            writer.write(line)
            writer.write("\n")
            writer.flush()
        }

        /** Closes stdin: the backend treats EOF as "stop"/"disconnected". */
        @Synchronized
        fun closeInput() {
            runCatching { writer.close() }
        }

        fun waitFor(timeoutSeconds: Long): Int? =
            if (process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) process.exitValue() else null

        fun stderrText(): String {
            errThread.join(500)
            synchronized(stderr) { return stderr.toString() }
        }

        val isAlive: Boolean get() = process.isAlive

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            closeInput()
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroy()
                if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
            }
        }
    }

    companion object {
        internal fun drain(reader: BufferedReader, sink: StringBuilder, limit: Int): Thread =
            Thread({
                runCatching {
                    val buffer = CharArray(8192)
                    while (true) {
                        val n = reader.read(buffer)
                        if (n < 0) break
                        synchronized(sink) {
                            if (sink.length < limit) sink.append(buffer, 0, minOf(n, limit - sink.length))
                        }
                    }
                }
            }, "xodus-drain").apply { isDaemon = true; start() }
    }
}
