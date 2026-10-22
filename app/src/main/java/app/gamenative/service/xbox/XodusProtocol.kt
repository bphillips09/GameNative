package app.gamenative.service.xbox

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Line protocols spoken by the Xodus Nexus backend (`xodus-cli` / `xodus-service`).
 *
 * Every parser here is strict on purpose, mirroring the Xodus Nexus launcher:
 * frames that do not match their schema are ignored or rejected, never guessed at.
 * Nothing in this file touches Android APIs, so it is covered by plain JVM tests.
 */
object XodusProtocol {
    const val AUTH_PREFIX = "NEXUS_AUTH_V1 "
    const val PROGRESS_PREFIX = "NEXUS_PROGRESS "
    const val LAUNCH_PREFIX = "NEXUS_LAUNCH_V1 "
    const val CREDENTIAL_ERROR_PREFIX = "NEXUS_CREDENTIAL_ERROR_V1 "

    const val AUTH_FRAME_LIMIT = 128 * 1024
    const val IPC_PAYLOAD_LIMIT = 65_536

    /** Hosts the sign-in WebView may load. Anything else aborts sign-in. */
    val AUTH_HOSTS = setOf(
        "login.live.com", "account.live.com", "login.microsoftonline.com",
        "login.microsoft.com", "account.microsoft.com", "sisu.xboxlive.com",
    )

    private val AUTH_HEADERS = setOf(
        "cxh-capabilities", "cxh-correlationid", "cxh-msabinaryversion",
        "cxh-identityclientbinaryversion", "cxh-osversioninfo", "cxh-platform",
        "cxh-protocol", "cxh-source", "hostapp",
    )

    private val PRODUCT_ID = Regex("^[A-Z0-9]{12}$")
    private val CONTENT_ID = Regex("^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$")

    /** Game Pass / subscription parents, as used by Xodus Nexus. */
    val SUBSCRIPTION_PRODUCTS = setOf(
        "CFQ7TTC0KGQ8", "CFQ7TTC0KHS0", "CFQ7TTC0P85B", "CFQ7TTC0K5DJ", "CFQ7TTC0K6L8", "CFQ7TTC10QFD",
    )

    fun isProductId(value: String?): Boolean = value != null && PRODUCT_ID.matches(value)
    fun isContentId(value: String?): Boolean = value != null && CONTENT_ID.matches(value)

    /**
     * RFC 3339 timestamp → instant. The backend writes offsets like `+00:00`, which
     * `Instant.parse` only accepts from JDK 12; Android 13 and older reject them.
     */
    fun parseTimestamp(value: String?): Instant? =
        value?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }

    // ---------------------------------------------------------------- sign-in

    sealed class AuthFrame {
        data class Ready(val version: Int) : AuthFrame()
        data class Open(val session: Long, val url: String, val headers: Map<String, String>) : AuthFrame()
        data class Close(val session: Long) : AuthFrame()
        data class Context(val session: Long, val context: String) : AuthFrame()
    }

    fun trustedAuthUrl(value: String): Boolean = try {
        val uri = URI(value)
        uri.scheme == "https" && uri.userInfo == null && uri.port == -1 && uri.host in AUTH_HOSTS
    } catch (_: Exception) {
        false
    }

    /**
     * Parses one stdout line of `xodus-cli --external-webview-stdio login`.
     * Returns null for lines that are not auth frames (ordinary output);
     * throws for malformed or untrusted frames.
     */
    fun parseAuthFrame(line: String): AuthFrame? {
        if (!line.startsWith(AUTH_PREFIX)) return null
        if (line.toByteArray().size > AUTH_FRAME_LIMIT) throw XodusProtocolException("Sign-in frame exceeds the protocol limit.")
        val frame = try {
            JSONObject(line.substring(AUTH_PREFIX.length))
        } catch (e: JSONException) {
            throw XodusProtocolException("Invalid sign-in protocol frame.")
        }
        val type = frame.optString("type")
        if (type == "ready") {
            if (frame.optInt("version", -1) != 1) throw XodusProtocolException("Unsupported sign-in protocol version.")
            return AuthFrame.Ready(1)
        }
        val session = frame.opt("session")
        if (session !is Number || session.toLong() < 1 || session.toDouble() != session.toLong().toDouble()) {
            throw XodusProtocolException("Invalid sign-in session.")
        }
        val id = session.toLong()
        return when (type) {
            "close" -> AuthFrame.Close(id)
            "context" -> {
                val context = frame.opt("context") as? String
                    ?: throw XodusProtocolException("Invalid sign-in context.")
                if (context.length > 4096) throw XodusProtocolException("Invalid sign-in context.")
                AuthFrame.Context(id, context)
            }
            "open" -> {
                val url = frame.opt("url") as? String ?: throw XodusProtocolException("Invalid sign-in URL.")
                if (url.length > 32_768 || !trustedAuthUrl(url)) throw XodusProtocolException("Untrusted sign-in URL.")
                val headersJson = frame.optJSONObject("headers") ?: throw XodusProtocolException("Invalid sign-in request headers.")
                val headers = LinkedHashMap<String, String>()
                for (name in headersJson.keys()) {
                    val lower = name.lowercase()
                    val value = headersJson.opt(name) as? String
                    if (lower !in AUTH_HEADERS || value == null || value.length > 4096 ||
                        value.any { it.code < 0x20 || it.code > 0x7e } || headers.containsKey(lower)
                    ) {
                        throw XodusProtocolException("Invalid sign-in request headers.")
                    }
                    headers[lower] = value
                }
                AuthFrame.Open(id, url, headers)
            }
            else -> throw XodusProtocolException("Unsupported sign-in protocol frame.")
        }
    }

    fun authHello(): String = JSONObject().put("type", "hello").put("version", 1).toString()
    fun authClosed(session: Long): String = JSONObject().put("type", "closed").put("session", session).toString()
    fun authIpc(session: Long, payload: String): String =
        JSONObject().put("type", "ipc").put("session", session).put("payload", payload).toString()

    /** Script answering a `context` frame inside the sign-in page (CloudExperienceHost bridge). */
    fun contextCallbackScript(context: String): String {
        val message = JSONObject()
            .put("type", "callback")
            .put(
                "value",
                JSONObject()
                    .put("name", "CloudExperienceHost.getContext")
                    .put(
                        "args",
                        JSONArray()
                            .put("CloudExperienceHost").put("TokenBroker").put("TokenBroker")
                            .put("""{"PrivatePropertyBag":1,"PasswordlessConnect":1,"PreferAssociate":1,"ChromelessUI":0}"""),
                    )
                    .put("context", context),
            )
        // JSONObject.quote produces a valid JS string literal for the serialized message.
        return "globalThis[\"CloudExperienceHost.Bridge.dispatchMessage\"](${JSONObject.quote(message.toString())})"
    }

    // ---------------------------------------------------------------- downloads

    enum class Phase(val label: String) {
        INITIALIZING("Preparing download"),
        METADATA("Reading package metadata"),
        DOWNLOADING("Downloading and extracting game files"),
        FINALIZING("Finalizing installation"),
        COMPLETE("Installation complete"),
        FAILED("Installation failed"),
    }

    data class Progress(
        val sequence: Long,
        val phase: Phase,
        val completedBytes: Long,
        val totalBytes: Long?,
        val bytesPerSecond: Long,
        val paused: Boolean,
        val canPause: Boolean,
    ) {
        /** 0..1, capped below 1 until the process exits successfully with a `complete` record. */
        val fraction: Float?
            get() = totalBytes?.takeIf { it > 0 }?.let { (completedBytes.toDouble() / it).coerceAtMost(0.999).toFloat() }
        val terminal: Boolean get() = phase == Phase.COMPLETE || phase == Phase.FAILED
    }

    /** Tracks one download's progress stream; out-of-order and malformed records are dropped. */
    class ProgressTracker {
        var last: Progress? = null
            private set

        fun update(line: String): Progress? {
            if (!line.startsWith(PROGRESS_PREFIX) || line.length > 4096 || last?.terminal == true) return null
            val value = try {
                JSONObject(line.substring(PROGRESS_PREFIX.length))
            } catch (_: JSONException) {
                return null
            }
            if (value.optInt("schema", -1) != 1 || value.optString("event") != "progress") return null
            val sequence = counter(value, "sequence") ?: return null
            if (last != null && sequence <= last!!.sequence) return null
            val phase = Phase.entries.firstOrNull { it.name.lowercase() == value.optString("phase") } ?: return null
            val completed = counter(value, "completedBytes") ?: return null
            val total: Long? = if (value.isNull("totalBytes")) null else (counter(value, "totalBytes") ?: return null)
            if (total != null && completed > total) return null
            val rate = counter(value, "bytesPerSecond") ?: return null
            val paused = value.opt("paused") as? Boolean ?: return null
            val canPause = value.opt("canPause") as? Boolean ?: return null
            return Progress(sequence, phase, completed, total, rate, paused, canPause && phase == Phase.DOWNLOADING)
                .also { last = it }
        }

        private fun counter(value: JSONObject, key: String): Long? {
            val raw = value.opt(key) as? Number ?: return null
            val long = raw.toLong()
            return if (long >= 0 && raw.toDouble() == long.toDouble()) long else null
        }
    }

    fun pauseCommand(): String = JSONObject().put("schema", 1).put("command", "pause").toString()
    fun resumeCommand(): String = JSONObject().put("schema", 1).put("command", "resume").toString()

    // ---------------------------------------------------------------- launch handoff

    data class LaunchReady(
        val executable: String,
        val workingDirectory: String?,
        val view: String?,
        val environment: Map<String, String>,
    )

    sealed class LaunchFrame {
        data class Ready(val ready: LaunchReady) : LaunchFrame()
        object Stopped : LaunchFrame()
    }

    fun parseLaunchFrame(line: String): LaunchFrame? {
        if (!line.startsWith(LAUNCH_PREFIX)) return null
        val value = try {
            JSONObject(line.substring(LAUNCH_PREFIX.length))
        } catch (_: JSONException) {
            throw XodusProtocolException("Invalid launch handoff frame.")
        }
        if (value.optInt("schema", -1) != 1) throw XodusProtocolException("Unsupported launch handoff version.")
        return when (value.optString("event")) {
            "stopped" -> LaunchFrame.Stopped
            "ready" -> {
                val executable = value.opt("executable") as? String
                if (executable.isNullOrBlank() || !executable.startsWith("/")) throw XodusProtocolException("Invalid launch executable.")
                val env = LinkedHashMap<String, String>()
                value.optJSONObject("environment")?.let { json ->
                    for (key in json.keys()) {
                        // Only XODUS_* variables may be injected into the game environment.
                        val item = json.opt(key) as? String ?: continue
                        if (key.startsWith("XODUS_") && key.all { it.isUpperCase() || it.isDigit() || it == '_' }) env[key] = item
                    }
                }
                LaunchFrame.Ready(
                    LaunchReady(
                        executable = executable,
                        workingDirectory = value.opt("workingDirectory") as? String,
                        view = value.opt("view") as? String,
                        environment = env,
                    ),
                )
            }
            else -> null
        }
    }

    fun launchStopCommand(): String = JSONObject().put("command", "stop").toString()

    // ---------------------------------------------------------------- errors

    enum class CredentialError(val code: String, val message: String) {
        KEYRING_UNAVAILABLE("keyring-unavailable", "The Xbox credential store is unavailable."),
        KEYRING_INVALID_DATA("keyring-invalid-data", "Stored Xbox credentials are damaged. Sign out and sign in again."),
        KEYRING_WRITE_VERIFICATION("keyring-write-verification", "Xbox credentials could not be saved."),
        KEYRING_MISSING_DATA("keyring-missing-data", "Stored Xbox credentials are incomplete. Sign in again."),
        DEVICE_AUTHENTICATION("device-authentication", "This device could not be registered with Xbox. Check the connection and try again."),
        CREDENTIAL_IO("credential-io", "Xbox credentials could not be read or written."),
        ;

        companion object {
            /** Finds a fixed credential error code in backend stderr; arbitrary stderr is never shown. */
            fun find(stderr: String): CredentialError? {
                for (line in stderr.lineSequence()) {
                    val trimmed = line.trim()
                    if (!trimmed.startsWith(CREDENTIAL_ERROR_PREFIX)) continue
                    val code = trimmed.substring(CREDENTIAL_ERROR_PREFIX.length).trim()
                    return entries.firstOrNull { it.code == code }
                }
                return null
            }
        }
    }

    // ---------------------------------------------------------------- account

    data class Account(val stored: Boolean, val username: String?)

    fun parseAccount(output: String): Account {
        val line = lastJsonLine(output) ?: throw XodusProtocolException("Invalid account response.")
        val json = JSONObject(line)
        val stored = json.opt("stored") == true
        val username = (json.opt("username") as? String)?.trim()?.takeIf { it.isNotEmpty() }
        return Account(stored && username != null, if (stored) username else null)
    }

    // ---------------------------------------------------------------- library

    enum class Access { OWNED, GAME_PASS }

    data class Entitlement(
        val productId: String,
        val status: String,
        val isTrial: Boolean,
        val sharingSource: String,
        val endDate: String,
        val satisfiedByProductId: String?,
        val special: Boolean,
    )

    data class Library(
        val checkedAt: Instant,
        val entitlements: Map<String, Entitlement>,
        val skipped: Int,
    ) {
        /**
         * Product → access, for products the account can actually play.
         * A parent chain must itself be licensed; trials, shared or expired rows are not ownership.
         */
        fun access(now: Instant = Instant.now()): Map<String, Access> {
            val result = LinkedHashMap<String, Access>()
            for (product in entitlements.keys) accessOf(product, now, HashSet())?.let { result[product] = it }
            return result
        }

        private fun accessOf(productId: String, now: Instant, visited: MutableSet<String>): Access? {
            if (productId in visited || visited.size >= 16) return null
            val item = entitlements[productId] ?: return null
            val end = parseTimestamp(item.endDate) ?: return null
            if (item.status != "Active" || item.isTrial || item.special || item.sharingSource != "None" || !end.isAfter(now)) return null
            val parent = item.satisfiedByProductId ?: return if (productId in SUBSCRIPTION_PRODUCTS) null else Access.OWNED
            if (parent in SUBSCRIPTION_PRODUCTS) return Access.GAME_PASS
            visited += productId
            return accessOf(parent, now, visited)
        }
    }

    /** `xodus-cli library --json --market M`, schema 1, for [account] in [market]. */
    fun parseLibrary(output: String, account: String, market: String): Library {
        if (output.length > 8_000_000) throw XodusProtocolException("Account library response is too large.")
        val line = lastJsonLine(output) ?: throw XodusProtocolException("Invalid account library response.")
        val json = JSONObject(line)
        val rows = json.optJSONArray("entitlements")
        val checkedAt = parseTimestamp(json.optString("checkedAt"))
        if (json.optInt("schema", -1) != 1 || json.optString("account") != account || json.optString("market") != market ||
            checkedAt == null || rows == null || rows.length() > 10_000
        ) {
            throw XodusProtocolException("Invalid account library response.")
        }
        val skippedByBackend = if (json.has("skippedEntitlements")) {
            val value = json.opt("skippedEntitlements") as? Number
            if (value == null || value.toLong() < 0 || value.toLong() > 200_000) throw XodusProtocolException("Invalid skipped entitlement count.")
            value.toInt()
        } else {
            0
        }
        val entries = LinkedHashMap<String, Entitlement>()
        val seen = HashSet<String>()
        val blocked = HashSet<String>()
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val productId = row.opt("productId") as? String
            if (!isProductId(productId)) continue
            productId!!
            val duplicate = !seen.add(productId)
            val status = row.opt("status") as? String
            val isTrial = row.opt("isTrial") as? Boolean
            val special = row.opt("special") as? Boolean
            val sharing = row.opt("sharingSource") as? String
            val endDate = row.opt("endDate") as? String
            val parent = if (row.isNull("satisfiedByProductId")) null else row.opt("satisfiedByProductId")
            val valid = !duplicate && status != null && status.length < 40 && isTrial != null && special != null &&
                sharing != null && sharing.length < 40 && endDate != null && endDate.length < 50 &&
                parseTimestamp(endDate) != null &&(parent == null || (parent is String && isProductId(parent)))
            if (!valid) {
                blocked += productId
                entries.remove(productId)
                continue
            }
            entries[productId] = Entitlement(productId, status, isTrial, sharing, endDate, parent, special)
        }
        // Quarantine descendants of invalid rows: a bad parent never turns into access.
        val children = HashMap<String, MutableList<String>>()
        for ((product, row) in entries) row.satisfiedByProductId?.let { children.getOrPut(it) { mutableListOf() } += product }
        val queue = ArrayDeque(blocked)
        while (queue.isNotEmpty()) {
            for (child in children[queue.removeFirst()].orEmpty()) {
                if (blocked.add(child)) {
                    entries.remove(child)
                    queue.addLast(child)
                }
            }
        }
        val skipped = minOf(200_000, skippedByBackend + rows.length() - entries.size)
        return Library(checkedAt, entries, skipped)
    }

    // ---------------------------------------------------------------- package plans

    enum class PlanStatus { READY, CHOICE, UNAVAILABLE }

    data class PackageChoice(
        val productId: String,
        val contentId: String,
        val title: String,
        val platform: String,
        val packageFormat: String,
        val kind: String,
        val supported: Boolean,
    )

    data class PackagePlan(val rootProductId: String, val market: String, val status: PlanStatus, val choices: List<PackageChoice>) {
        /** The content to install without asking, when the plan is unambiguous. */
        val automaticContentId: String?
            get() = if (status == PlanStatus.READY) choices.first { it.supported }.contentId else null
        val supportedChoices: List<PackageChoice> get() = choices.filter { it.supported }
    }

    /** `xodus-cli resolve-package PRODUCT --json --market M`. */
    fun parsePackagePlan(output: String, productId: String, market: String): PackagePlan {
        if (output.toByteArray().size > 256_000) throw XodusProtocolException("Store package response is too large.")
        val json = JSONObject(lastJsonLine(output) ?: throw XodusProtocolException("Invalid Store package response."))
        val rows = json.optJSONArray("choices")
        val status = PlanStatus.entries.firstOrNull { it.name.lowercase() == json.optString("status") }
        if (json.optInt("schema", -1) != 1 || json.optString("rootProductId") != productId || json.optString("market") != market ||
            status == null || rows == null || rows.length() > 128
        ) {
            throw XodusProtocolException("Invalid Store package response.")
        }
        val choices = ArrayList<PackageChoice>()
        val seen = HashSet<String>()
        fun label(value: Any?): String? = (value as? String)?.takeIf { it.length <= 500 && it.none { c -> c.code < 0x20 || c.code == 0x7f } }
        for (index in 0 until rows.length()) {
            val item = rows.optJSONObject(index) ?: throw XodusProtocolException("Invalid Store package choice.")
            val choice = PackageChoice(
                productId = (item.opt("productId") as? String)?.takeIf(::isProductId) ?: throw XodusProtocolException("Invalid Store package choice."),
                contentId = (item.opt("contentId") as? String)?.takeIf(::isContentId) ?: throw XodusProtocolException("Invalid Store package choice."),
                title = label(item.opt("title"))?.takeIf { it.isNotEmpty() } ?: throw XodusProtocolException("Invalid Store package choice."),
                platform = label(item.opt("platform")) ?: throw XodusProtocolException("Invalid Store package choice."),
                packageFormat = label(item.opt("packageFormat")) ?: throw XodusProtocolException("Invalid Store package choice."),
                kind = label(item.opt("kind")) ?: throw XodusProtocolException("Invalid Store package choice."),
                supported = item.opt("supported") as? Boolean ?: throw XodusProtocolException("Invalid Store package choice."),
            )
            if (choice.supported && (choice.platform != "Windows.Desktop" || choice.packageFormat != "MSIXVC" || choice.kind !in setOf("Game", "GameAddOn"))) {
                throw XodusProtocolException("Invalid Store package choice.")
            }
            if (!seen.add(choice.contentId)) throw XodusProtocolException("Duplicate Store package choice.")
            choices += choice
        }
        val supported = choices.filter { it.supported }
        val expected = when {
            supported.size == 1 && supported[0].kind == "Game" -> PlanStatus.READY
            supported.isNotEmpty() -> PlanStatus.CHOICE
            else -> PlanStatus.UNAVAILABLE
        }
        if (expected != status) throw XodusProtocolException("Inconsistent Store package response.")
        return PackagePlan(productId, market, status, choices)
    }

    internal fun lastJsonLine(output: String): String? =
        output.lineSequence().map { it.trim() }.filter { it.startsWith("{") }.lastOrNull()
}

class XodusProtocolException(message: String) : Exception(message)
