package app.gamenative.service.xbox

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One package download/extraction (`xodus-cli streaming … --progress-json --control-stdin`).
 *
 * The backend downloads, verifies and extracts in one pass and keeps an
 * append-only checkpoint journal in the destination, so a cancelled or killed
 * download resumes from its last verified boundary when started again with
 * the same product, content ID and destination.
 */
class XboxDownload(
    private val backend: XodusBackend,
    val productId: String,
    val contentId: String,
    val destination: File,
    val market: String,
) {
    private val cancelled = AtomicBoolean(false)
    @Volatile private var session: XodusBackend.Session? = null
    @Volatile var progress: XodusProtocol.Progress? = null
        private set

    sealed class Outcome {
        object Complete : Outcome()
        object Cancelled : Outcome()
        data class Failed(val message: String) : Outcome()
    }

    fun arguments(): List<String> = listOf(
        "streaming", productId, destination.absolutePath,
        "--selected-content-id", contentId,
        "--market", market,
        "--progress-json", "--control-stdin",
    )

    /** Blocks until the download ends; [onProgress] runs on the calling thread. */
    fun run(onProgress: (XodusProtocol.Progress) -> Unit): Outcome {
        require(XodusProtocol.isProductId(productId)) { "Invalid product ID" }
        require(XodusProtocol.isContentId(contentId)) { "Invalid content ID" }
        destination.mkdirs()
        val process = backend.startSession(arguments())
        session = process
        if (cancelled.get()) process.close()
        val tracker = XodusProtocol.ProgressTracker()
        process.readLines { line ->
            tracker.update(line)?.let {
                progress = it
                onProgress(it)
            }
            true
        }
        val code = process.waitFor(30) ?: -1
        if (code == -1) process.close()
        val stderr = process.stderrText()
        session = null
        val last = tracker.last
        return when {
            cancelled.get() -> Outcome.Cancelled
            // Both a clean exit and a terminal `complete` record are required.
            code == 0 && last?.phase == XodusProtocol.Phase.COMPLETE -> Outcome.Complete
            else -> Outcome.Failed(
                XodusProtocol.CredentialError.find(stderr)?.message
                    ?: if (code == 0) "The download ended without completing." else "The download failed. It will resume from the last checkpoint.",
            )
        }
    }

    fun pause() {
        if (progress?.canPause == true) session?.send(XodusProtocol.pauseCommand())
    }

    fun resume() {
        session?.send(XodusProtocol.resumeCommand())
    }

    /** Stops the process; files and the checkpoint journal stay for a later resume. */
    fun cancel() {
        cancelled.set(true)
        session?.close()
    }

    companion object {
        /** Present once the package container was fully written. */
        fun isInstalled(installDir: File): Boolean = File(installDir, ".xodus-streaming.msixvc").isFile
        fun hasPartialDownload(installDir: File): Boolean =
            File(installDir, ".xodus-streaming-tmp.msixvc").isFile || File(installDir, ".nexus-streaming-v1.jsonl").isFile
    }
}
