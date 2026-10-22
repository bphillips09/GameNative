package app.gamenative.service.xbox

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Microsoft account sign-in through the backend's external sign-in UI protocol
 * (`xodus-cli --external-webview-stdio login`, NEXUS_AUTH_V1).
 *
 * The backend keeps doing the real work: it chooses the URLs, performs the
 * Microsoft token exchange and stores the result. This class only moves frames
 * between the backend and a WebView, the same way Xodus Nexus's Electron window does:
 *
 * - `open`    → load the URL (with the given headers) in the WebView;
 * - page JS `window.external.notify(payload)` → [onPageNotify];
 * - finished `https://login.live.com/ppsecure/post.srf` → [onPostSrfLoaded] with `ServerData`;
 * - `context` → evaluate [View.evaluate] with the callback script;
 * - user closes the window → [onWindowClosed].
 *
 * Frames are never logged: they carry Microsoft tokens.
 */
class XboxSignIn(private val backend: XodusBackend) {
    interface View {
        fun open(session: Long, url: String, headers: Map<String, String>)
        fun close(session: Long)
        fun evaluate(session: Long, script: String)
        /** Called once; [error] is null after a successful sign-in, and for a user cancel [cancelled] is true. */
        fun finished(error: String?, cancelled: Boolean)
    }

    private val activeSession = AtomicLong(0)
    private val done = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    @Volatile private var session: XodusBackend.Session? = null

    /** Starts the backend and pumps frames on a background thread. */
    fun start(view: View) {
        val process = backend.startSession(listOf("--external-webview-stdio", "login"))
        session = process
        Thread({ pump(process, view) }, "xbox-sign-in").apply { isDaemon = true; start() }
    }

    private fun pump(process: XodusBackend.Session, view: View) {
        var protocolError: String? = null
        try {
            process.readLines { line ->
                val frame = try {
                    XodusProtocol.parseAuthFrame(line)
                } catch (e: XodusProtocolException) {
                    protocolError = e.message
                    return@readLines false
                }
                when (frame) {
                    null -> Unit
                    is XodusProtocol.AuthFrame.Ready -> process.send(XodusProtocol.authHello())
                    is XodusProtocol.AuthFrame.Open -> {
                        activeSession.set(frame.session)
                        view.open(frame.session, frame.url, frame.headers)
                    }
                    is XodusProtocol.AuthFrame.Close -> {
                        activeSession.compareAndSet(frame.session, 0)
                        view.close(frame.session)
                    }
                    is XodusProtocol.AuthFrame.Context ->
                        if (activeSession.get() == frame.session) {
                            view.evaluate(frame.session, XodusProtocol.contextCallbackScript(frame.context))
                        }
                }
                true
            }
        } catch (e: Exception) {
            protocolError = protocolError ?: "Sign-in was interrupted."
        }
        if (protocolError != null) process.close()
        val code = process.waitFor(30) ?: -1
        if (code == -1) process.close()
        val stderr = process.stderrText()
        activeSession.set(0)
        if (!done.compareAndSet(false, true)) return
        when {
            cancelled.get() -> view.finished(null, cancelled = true)
            protocolError != null -> view.finished(protocolError, cancelled = false)
            code == 0 -> view.finished(null, cancelled = false)
            else -> view.finished(
                XodusProtocol.CredentialError.find(stderr)?.message ?: "Microsoft sign-in failed. Try again.",
                cancelled = false,
            )
        }
    }

    /** `window.external.notify(payload)` from the sign-in page. */
    fun onPageNotify(session: Long, payload: String) {
        if (session == 0L || session != activeSession.get() || payload.length > XodusProtocol.IPC_PAYLOAD_LIMIT) return
        this.session?.send(XodusProtocol.authIpc(session, payload))
    }

    /** `JSON.stringify(globalThis.ServerData)` once `login.live.com/ppsecure/post.srf` has loaded. */
    fun onPostSrfLoaded(session: Long, serverDataJson: String) {
        if (serverDataJson == "null") return
        onPageNotify(session, serverDataJson)
    }

    /** The user dismissed the sign-in window. */
    fun onWindowClosed(session: Long) {
        if (session == 0L || session != activeSession.get()) return
        cancelled.set(true)
        this.session?.send(XodusProtocol.authClosed(session))
    }

    /** Aborts sign-in (e.g. the activity is destroyed). */
    fun cancel() {
        cancelled.set(true)
        session?.close()
    }

    companion object {
        /** True for the page whose `ServerData` completes a sign-in step. */
        fun isPostSrf(url: String): Boolean = try {
            val uri = java.net.URI(url)
            uri.scheme == "https" && uri.host == "login.live.com" && uri.path == "/ppsecure/post.srf"
        } catch (_: Exception) {
            false
        }

        /**
         * Installed before the page's own scripts run: the sign-in page reports
         * tokens through `window.external.notify`, which Android's WebView lacks.
         * [bridgeName] is the `addJavascriptInterface` object name.
         */
        fun documentStartScript(bridgeName: String): String = """
            (function () {
              try {
                var bridge = window['$bridgeName'];
                if (!bridge) return;
                var notify = function (payload) {
                  if (typeof payload === 'string' && payload.length <= ${XodusProtocol.IPC_PAYLOAD_LIMIT}) bridge.notify(payload);
                };
                if (!window.external) window.external = {};
                Object.defineProperty(window.external, 'notify', { value: notify, configurable: false, writable: false });
              } catch (e) {}
            })();
        """.trimIndent()
    }
}
