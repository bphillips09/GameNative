package app.gamenative.service.xbox

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Base64
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.cert.X509Certificate

/** `xodus-service` socket access through Android's `LocalSocket` (filesystem namespace). */
object AndroidUnixConnector : UnixConnector {
    override fun connect(path: String): UnixStream {
        val socket = LocalSocket()
        try {
            socket.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
        return object : UnixStream {
            override val input: InputStream = socket.inputStream
            override val output: OutputStream = socket.outputStream
            override fun close() {
                runCatching { socket.shutdownInput() }
                runCatching { socket.shutdownOutput() }
                socket.close()
            }
        }
    }
}

/**
 * Exports the device's trusted CA certificates (system and user-added, as the
 * `AndroidCAStore` key store reports them) to a PEM bundle for the backend's
 * TLS stacks, which cannot reach the Java trust store themselves.
 */
object XboxCaBundle {
    /** Rewrites [target] when missing or older than [maxAgeMillis]. Returns true if the bundle is usable. */
    fun ensure(target: File, maxAgeMillis: Long = 24L * 60 * 60 * 1000): Boolean {
        if (target.isFile && target.length() > 0 && System.currentTimeMillis() - target.lastModified() < maxAgeMillis) return true
        val pem = runCatching { export() }.getOrNull() ?: return target.isFile
        if (pem.isEmpty()) return target.isFile
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.tmp")
        tmp.writeText(pem)
        return tmp.renameTo(target) || run { target.writeText(pem); tmp.delete(); true }
    }

    fun export(): String {
        val store = KeyStore.getInstance("AndroidCAStore")
        store.load(null, null)
        val out = StringBuilder()
        val aliases = store.aliases()
        while (aliases.hasMoreElements()) {
            val cert = store.getCertificate(aliases.nextElement()) as? X509Certificate ?: continue
            out.append("-----BEGIN CERTIFICATE-----\n")
            out.append(Base64.encodeToString(cert.encoded, Base64.NO_WRAP).chunked(64).joinToString("\n"))
            out.append("\n-----END CERTIFICATE-----\n")
        }
        return out.toString()
    }
}

/** Android's `Process` has no public pid accessor below API 33; send SIGINT via `android.os.Process`. */
object AndroidProcessSignals : ProcessSignals() {
    override fun interrupt(process: Process) {
        val pid = ProcessSignals.pidOf(process) ?: return process.destroy()
        runCatching { android.os.Process.sendSignal(pid, 2) }.onFailure { process.destroy() }
    }
}
