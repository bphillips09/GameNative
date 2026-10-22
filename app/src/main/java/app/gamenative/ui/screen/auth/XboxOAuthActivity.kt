package app.gamenative.ui.screen.auth

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.gamenative.service.xbox.XboxCaBundle
import app.gamenative.service.xbox.XboxConstants
import app.gamenative.service.xbox.XboxService
import app.gamenative.service.xbox.XboxSignIn
import app.gamenative.service.xbox.XodusProtocol
import app.gamenative.ui.component.dialog.AuthWebViewDialog
import app.gamenative.ui.theme.PluviaTheme
import org.json.JSONArray

/**
 * Microsoft account sign-in for the Xbox store.
 *
 * Unlike the other stores there is no redirect to capture: the Xodus backend drives the
 * sign-in pages (NEXUS_AUTH_V1), telling this screen what to load and which scripts to run,
 * and performs the token exchange itself. This screen only relays what the pages report,
 * so nothing here sees or stores tokens.
 */
class XboxOAuthActivity : ComponentActivity(), XboxSignIn.View {
    companion object {
        const val EXTRA_ERROR = "error"
        private const val BRIDGE = "__xodusAuth"
    }

    private var webView: WebView? = null
    private var pendingOpen: Triple<Long, String, Map<String, String>>? = null
    private lateinit var signIn: XboxSignIn
    @Volatile private var session: Long = 0
    @Volatile private var trustedPage = false
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        XboxService.init(this)
        signIn = XboxSignIn(XboxService.backend)

        setContent {
            PluviaTheme {
                AuthWebViewDialog(
                    isVisible = true,
                    // The backend sends the first sign-in page once it has started.
                    url = "about:blank",
                    onDismissRequest = {
                        if (session != 0L) signIn.onWindowClosed(session) else signIn.cancel()
                        finished(null, cancelled = true)
                    },
                    customWebViewClient = webViewClient,
                    onWebViewCreated = ::attach,
                )
            }
        }

        try {
            XboxCaBundle.ensure(XboxService.paths.caBundle)
            signIn.start(this)
        } catch (e: Exception) {
            fail(e.message ?: "Sign-in could not start.")
        }
    }

    @SuppressLint("JavascriptInterface")
    private fun attach(view: WebView) {
        view.settings.userAgentString = XboxConstants.SIGN_IN_USER_AGENT
        view.settings.setSupportMultipleWindows(false)
        view.addJavascriptInterface(Bridge(), BRIDGE)
        webView = view
        // The dialog loads its placeholder right after this hook, so a page the backend sent
        // early is loaded afterwards.
        pendingOpen?.let { (session, url, headers) -> view.post { open(session, url, headers) } }
        pendingOpen = null
    }

    private val webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            if (XodusProtocol.trustedAuthUrl(url)) return false
            fail("Sign-in tried to leave Microsoft's sign-in pages.")
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            trustedPage = XodusProtocol.trustedAuthUrl(url)
            if (trustedPage) view.evaluateJavascript(XboxSignIn.documentStartScript(BRIDGE), null)
        }

        override fun onPageFinished(view: WebView, url: String) {
            trustedPage = XodusProtocol.trustedAuthUrl(url)
            if (!trustedPage) return
            // Back from the first sign-in page closes the dialog instead of returning to the blank placeholder.
            val history = view.copyBackForwardList()
            if (history.currentIndex > 0 && history.getItemAtIndex(history.currentIndex - 1)?.url == "about:blank") {
                view.clearHistory()
            }
            view.evaluateJavascript(XboxSignIn.documentStartScript(BRIDGE), null)
            if (XboxSignIn.isPostSrf(url)) {
                val current = session
                view.evaluateJavascript("JSON.stringify(globalThis.ServerData ?? null)") { result ->
                    // evaluateJavascript returns the JSON encoding of the string result.
                    val data = runCatching { JSONArray("[$result]").getString(0) }.getOrNull() ?: return@evaluateJavascript
                    if (data.length <= XodusProtocol.IPC_PAYLOAD_LIMIT) signIn.onPostSrfLoaded(current, data)
                }
            }
        }
    }

    private inner class Bridge {
        @JavascriptInterface
        fun notify(payload: String?) {
            if (payload == null || !trustedPage) return
            signIn.onPageNotify(session, payload)
        }
    }

    // ---- XboxSignIn.View (called from the backend thread)

    override fun open(session: Long, url: String, headers: Map<String, String>) = runOnUiThread {
        val view = webView
        if (view == null) {
            pendingOpen = Triple(session, url, headers)
            return@runOnUiThread
        }
        this.session = session
        trustedPage = false
        view.loadUrl(url, headers)
    }

    override fun close(session: Long) = runOnUiThread {
        if (this.session == session) {
            this.session = 0
            webView?.loadUrl("about:blank")
        }
    }

    override fun evaluate(session: Long, script: String) = runOnUiThread {
        if (this.session == session && trustedPage) webView?.evaluateJavascript(script, null)
    }

    override fun finished(error: String?, cancelled: Boolean) = runOnUiThread {
        if (finished) return@runOnUiThread
        finished = true
        when {
            cancelled -> setResult(Activity.RESULT_CANCELED)
            error != null -> setResult(Activity.RESULT_CANCELED, Intent().putExtra(EXTRA_ERROR, error))
            else -> setResult(Activity.RESULT_OK)
        }
        finish()
    }

    private fun fail(message: String) {
        signIn.cancel()
        finished(message, cancelled = false)
    }

    override fun onDestroy() {
        if (!finished) signIn.cancel()
        // The dialog destroys the WebView itself.
        webView = null
        super.onDestroy()
    }
}
