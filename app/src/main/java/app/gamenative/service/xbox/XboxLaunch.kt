package app.gamenative.service.xbox

import java.io.Closeable
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Launch handoff (`xodus-cli prepare-launch`, NEXUS_LAUNCH_V1).
 *
 * The backend fetches the game license, decrypts every encrypted image into a
 * private, temporary *launch view* (real directories down to the decrypted
 * files, symlinks to the install for everything else) and reports it. GameNative
 * then starts Wine itself on the view. The view, which contains decrypted
 * executables, exists only while the handoff process lives; [close] removes it.
 */
class XboxLaunch private constructor(
    private val session: XodusBackend.Session,
    val ready: XodusProtocol.LaunchReady,
    val viewRoot: File,
) : Closeable {

    /** The view directory (child of [viewRoot]), mapped to [XboxConstants.LAUNCH_DRIVE] in Wine. */
    val viewDir: File get() = File(ready.view ?: File(ready.executable).parent)

    /** Windows path of the launch executable when [viewRoot] is mapped to [drive]. */
    fun windowsExecutable(drive: Char = XboxConstants.LAUNCH_DRIVE): String {
        val relative = File(ready.executable).relativeTo(viewRoot).path.replace('/', '\\')
        return "$drive:\\$relative"
    }

    /** Unix working directory for Wine (the executable's own directory inside the view). */
    val workingDirectory: File get() = File(ready.workingDirectory ?: File(ready.executable).parent)

    val isAlive: Boolean get() = session.isAlive

    override fun close() {
        if (session.isAlive) {
            runCatching { session.send(XodusProtocol.launchStopCommand()) }
            session.closeInput()
            if (session.waitFor(10) == null) session.close()
        }
        session.close()
    }

    /** First result wins. */
    private class Outcome {
        @Volatile var value: Result<XodusProtocol.LaunchReady>? = null
            private set

        @Synchronized
        fun settle(result: Result<XodusProtocol.LaunchReady>) {
            if (value == null) value = result
        }
    }

    companion object {
        fun arguments(installDir: File, executable: String?, market: String, viewRoot: File): List<String> =
            buildList {
                add("prepare-launch")
                add(installDir.absolutePath)
                if (!executable.isNullOrBlank()) {
                    add("--exe"); add(executable)
                }
                add("--market"); add(market)
                add("--view-root"); add(viewRoot.absolutePath)
                add("--sweep-stale-views")
            }

        /**
         * Prepares the launch view and blocks until it is ready.
         * [executable] is the manifest path (e.g. `Game\Binaries\Game.exe`); null lets the backend pick.
         */
        fun prepare(
            backend: XodusBackend,
            installDir: File,
            executable: String?,
            market: String,
            viewRoot: File,
            timeoutSeconds: Long = 600,
        ): XboxLaunch {
            viewRoot.mkdirs()
            val session = backend.startSession(arguments(installDir, executable, market, viewRoot))
            val outcome = Outcome()
            val latch = CountDownLatch(1)
            Thread({
                try {
                    session.readLines { line ->
                        when (val frame = XodusProtocol.parseLaunchFrame(line)) {
                            is XodusProtocol.LaunchFrame.Ready -> {
                                outcome.settle(Result.success(frame.ready))
                                latch.countDown()
                            }
                            XodusProtocol.LaunchFrame.Stopped, null -> Unit
                        }
                        true
                    }
                } catch (e: Exception) {
                    outcome.settle(Result.failure(e))
                }
                outcome.settle(Result.failure(XodusException("ended")))
                latch.countDown()
            }, "xbox-launch").apply { isDaemon = true; start() }

            if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
                session.close()
                throw XodusException("Preparing the game took too long.")
            }
            val ready = outcome.value!!.getOrElse {
                val code = session.waitFor(5)
                val stderr = session.stderrText()
                session.close()
                val credential = XodusProtocol.CredentialError.find(stderr)
                throw XodusException(
                    credential?.message ?: when {
                        stderr.contains("license", ignoreCase = true) ->
                            "The game license could not be obtained. Check that this account owns the game and that the device is online."
                        code != null && code != 0 -> "The game could not be prepared for launch."
                        else -> "The game could not be prepared for launch."
                    },
                    credential,
                )
            }
            val executable = File(ready.executable)
            if (!executable.canonicalPath.startsWith(viewRoot.canonicalPath + File.separator)) {
                session.close()
                throw XodusException("The launch view is outside its private directory.")
            }
            return XboxLaunch(session, ready, viewRoot)
        }
    }
}
