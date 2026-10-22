package app.gamenative.service.xbox

import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class XodusProtocolTest {

    @Test
    fun authFrames_parseOpenContextCloseAndReady() {
        assertEquals(XodusProtocol.AuthFrame.Ready(1), XodusProtocol.parseAuthFrame("""NEXUS_AUTH_V1 {"type":"ready","version":1}"""))
        val open = XodusProtocol.parseAuthFrame(
            """NEXUS_AUTH_V1 {"type":"open","session":2,"title":"t","url":"https://login.live.com/ppsecure/InlineLogin.srf?x=1","headers":{"HostApp":"CloudExperienceHost"}}""",
        ) as XodusProtocol.AuthFrame.Open
        assertEquals(2L, open.session)
        assertEquals(mapOf("hostapp" to "CloudExperienceHost"), open.headers)
        assertEquals(
            XodusProtocol.AuthFrame.Context(2, "c"),
            XodusProtocol.parseAuthFrame("""NEXUS_AUTH_V1 {"type":"context","session":2,"context":"c"}"""),
        )
        assertEquals(XodusProtocol.AuthFrame.Close(2), XodusProtocol.parseAuthFrame("""NEXUS_AUTH_V1 {"type":"close","session":2}"""))
        assertNull(XodusProtocol.parseAuthFrame("Login successful"))
    }

    @Test
    fun authFrames_rejectUntrustedUrlsHeadersAndSessions() {
        fun open(url: String, headers: String = "{}") =
            """NEXUS_AUTH_V1 {"type":"open","session":1,"url":"$url","headers":$headers}"""
        assertThrows(XodusProtocolException::class.java) { XodusProtocol.parseAuthFrame(open("https://evil.example/login")) }
        assertThrows(XodusProtocolException::class.java) { XodusProtocol.parseAuthFrame(open("http://login.live.com/")) }
        assertThrows(XodusProtocolException::class.java) { XodusProtocol.parseAuthFrame(open("https://login.live.com:8443/")) }
        assertThrows(XodusProtocolException::class.java) { XodusProtocol.parseAuthFrame(open("https://user@login.live.com/")) }
        assertThrows(XodusProtocolException::class.java) {
            XodusProtocol.parseAuthFrame(open("https://login.live.com/", """{"Cookie":"x"}"""))
        }
        assertThrows(XodusProtocolException::class.java) {
            XodusProtocol.parseAuthFrame("""NEXUS_AUTH_V1 {"type":"close","session":0}""")
        }
        assertThrows(XodusProtocolException::class.java) {
            XodusProtocol.parseAuthFrame("""NEXUS_AUTH_V1 {"type":"ready","version":2}""")
        }
        assertThrows(XodusProtocolException::class.java) { XodusProtocol.parseAuthFrame("NEXUS_AUTH_V1 {broken") }
    }

    @Test
    fun contextCallbackScript_isAValidSingleCall() {
        val script = XodusProtocol.contextCallbackScript("ctx \"with\" quotes </script>")
        assertTrue(script.startsWith("globalThis[\"CloudExperienceHost.Bridge.dispatchMessage\"](\""))
        val literal = script.substringAfter("](").removeSuffix(")")
        // The argument is one JSON string literal holding the callback message.
        val message = JSONObject("{\"m\":$literal}").getString("m")
        val parsed = JSONObject(message)
        assertEquals("callback", parsed.getString("type"))
        assertEquals("ctx \"with\" quotes </script>", parsed.getJSONObject("value").getString("context"))
        assertEquals("CloudExperienceHost.getContext", parsed.getJSONObject("value").getString("name"))
    }

    @Test
    fun progress_isMonotonicStrictAndTerminal() {
        val tracker = XodusProtocol.ProgressTracker()
        fun line(seq: Int, phase: String, done: Long, total: String = "100", paused: Boolean = false, canPause: Boolean = true) =
            """NEXUS_PROGRESS {"schema":1,"sequence":$seq,"event":"progress","phase":"$phase","completedBytes":$done,"totalBytes":$total,"bytesPerSecond":5,"paused":$paused,"canPause":$canPause}"""
        val first = tracker.update(line(1, "downloading", 25))!!
        assertEquals(0.25f, first.fraction!!, 0.0001f)
        assertTrue(first.canPause)
        assertNull("stale sequence", tracker.update(line(1, "downloading", 30)))
        assertNull("completed > total", tracker.update(line(2, "downloading", 300)))
        assertNull("unknown phase", tracker.update(line(2, "teleporting", 30)))
        assertNull("not a progress line", tracker.update("downloading 30%"))
        val metadata = tracker.update(line(2, "metadata", 0, total = "null"))!!
        assertNull(metadata.fraction)
        assertFalse("pause only while downloading", metadata.canPause)
        val done = tracker.update(line(3, "complete", 100))!!
        assertTrue(done.terminal)
        assertEquals(0.999f, done.fraction!!, 0.0001f)
        assertNull("nothing after a terminal record", tracker.update(line(4, "downloading", 1)))
    }

    @Test
    fun launchFrame_keepsOnlyXodusVariables() {
        val frame = XodusProtocol.parseLaunchFrame(
            """NEXUS_LAUNCH_V1 {"schema":1,"event":"ready","executable":"/v/x/Game.exe","workingDirectory":"/v/x","view":"/v/x","environment":{"XODUS_XSTS_PLAYFAB":"h","LD_PRELOAD":"/evil.so","xodus_lower":"n"}}""",
        ) as XodusProtocol.LaunchFrame.Ready
        assertEquals(mapOf("XODUS_XSTS_PLAYFAB" to "h"), frame.ready.environment)
        assertEquals("/v/x/Game.exe", frame.ready.executable)
        assertEquals(XodusProtocol.LaunchFrame.Stopped, XodusProtocol.parseLaunchFrame("""NEXUS_LAUNCH_V1 {"schema":1,"event":"stopped"}"""))
        assertThrows(XodusProtocolException::class.java) {
            XodusProtocol.parseLaunchFrame("""NEXUS_LAUNCH_V1 {"schema":1,"event":"ready","executable":"relative.exe"}""")
        }
        assertThrows(XodusProtocolException::class.java) {
            XodusProtocol.parseLaunchFrame("""NEXUS_LAUNCH_V1 {"schema":2,"event":"ready","executable":"/a"}""")
        }
    }

    @Test
    fun credentialErrors_mapOnlyFixedCodes() {
        assertEquals(
            XodusProtocol.CredentialError.DEVICE_AUTHENTICATION,
            XodusProtocol.CredentialError.find("noise\nNEXUS_CREDENTIAL_ERROR_V1 device-authentication\n"),
        )
        assertNull(XodusProtocol.CredentialError.find("NEXUS_CREDENTIAL_ERROR_V1 something-else"))
        assertNull(XodusProtocol.CredentialError.find("panic: token=secret"))
    }

    private val now = Instant.parse("2026-10-19T12:00:00Z")

    private fun row(id: String, parent: String? = null, status: String = "Active", trial: Boolean = false, sharing: String = "None", end: String = "2099-01-01T00:00:00Z") =
        JSONObject()
            .put("productId", id).put("status", status).put("isTrial", trial).put("sharingSource", sharing)
            .put("endDate", end).put("special", false).put("satisfiedByProductId", parent ?: JSONObject.NULL)

    private fun library(vararg rows: JSONObject, account: String = "player@example.com") =
        JSONObject().put("schema", 1).put("account", account).put("market", "US").put("checkedAt", "2026-10-19T11:59:00Z")
            .put("entitlements", org.json.JSONArray(rows.toList())).toString()

    @Test
    fun library_classifiesOwnedGamePassAndRejectsBadChains() {
        val output = "log\n" + library(
            row("9NOWNEDGAME1"),
            row("9GAMEPASSGM1", parent = "CFQ7TTC0KGQ8"),
            row("CFQ7TTC0KGQ8"),
            row("9BUNDLEDGAM1", parent = "9BUNDLEPARNT"),
            row("9BUNDLEPARNT"),
            row("9TRIALGAME01", trial = true),
            row("9SHAREDGAME1", sharing = "Family"),
            row("9EXPIREDGAM1", end = "2020-01-01T00:00:00Z"),
            row("9CHILDOFBAD1", parent = "9BADPARENT01"),
            row("9BADPARENT01").put("isTrial", "yes"),
        )
        val parsed = XodusProtocol.parseLibrary(output, "player@example.com", "US")
        val access = parsed.access(now)
        assertEquals(XodusProtocol.Access.OWNED, access["9NOWNEDGAME1"])
        assertEquals(XodusProtocol.Access.GAME_PASS, access["9GAMEPASSGM1"])
        assertEquals(XodusProtocol.Access.OWNED, access["9BUNDLEDGAM1"])
        assertNull("subscription itself is not a game", access["CFQ7TTC0KGQ8"])
        assertNull(access["9TRIALGAME01"])
        assertNull(access["9SHAREDGAME1"])
        assertNull(access["9EXPIREDGAM1"])
        assertNull("invalid parent quarantines its child", access["9CHILDOFBAD1"])
        assertEquals(2, parsed.skipped)
    }

    @Test
    fun library_rejectsResponsesForAnotherAccountOrMarket() {
        assertThrows(XodusProtocolException::class.java) {
            XodusProtocol.parseLibrary(library(row("9NOWNEDGAME1"), account = "other@example.com"), "player@example.com", "US")
        }
        assertThrows(XodusProtocolException::class.java) {
            XodusProtocol.parseLibrary(library(row("9NOWNEDGAME1")), "player@example.com", "GB")
        }
    }

    @Test
    fun library_acceptsBackendTimestampsWithOffsets() {
        // xodus-cli writes chrono RFC 3339 (nanoseconds, `+00:00`); Android 13's java.time
        // rejects that in Instant.parse, so the parser must not depend on it.
        val output = JSONObject(library(row("9NOWNEDGAME1", end = "2099-01-01T00:00:00+00:00")))
            .put("checkedAt", "2026-10-20T18:00:20.414287872+00:00").toString()
        val parsed = XodusProtocol.parseLibrary(output, "player@example.com", "US")
        assertEquals(Instant.parse("2026-10-20T18:00:20.414287872Z"), parsed.checkedAt)
        assertEquals(XodusProtocol.Access.OWNED, parsed.access(now)["9NOWNEDGAME1"])
        assertNull(XodusProtocol.parseTimestamp("2026-10-20 18:00:20"))
    }

    private fun plan(status: String, vararg choices: JSONObject) =
        JSONObject().put("schema", 1).put("rootProductId", "9NOWNEDGAME1").put("market", "US").put("status", status)
            .put("choices", org.json.JSONArray(choices.toList())).toString()

    private fun choice(contentId: String, kind: String = "Game", supported: Boolean = true, platform: String = "Windows.Desktop") =
        JSONObject().put("productId", "9NOWNEDGAME1").put("contentId", contentId).put("title", "Game")
            .put("platform", platform).put("packageFormat", "MSIXVC").put("kind", kind).put("supported", supported)

    @Test
    fun packagePlan_readyChoiceAndConsistency() {
        val ready = XodusProtocol.parsePackagePlan(
            plan("ready", choice("11111111-2222-3333-4444-555555555555"), choice("aaaaaaaa-2222-3333-4444-555555555555", supported = false, platform = "Xbox.Core")),
            "9NOWNEDGAME1", "US",
        )
        assertEquals("11111111-2222-3333-4444-555555555555", ready.automaticContentId)
        val choiceOutput = plan(
            "choice",
            choice("11111111-2222-3333-4444-555555555555"),
            choice("22222222-2222-3333-4444-555555555555", kind = "GameAddOn"),
        )
        val multi = XodusProtocol.parsePackagePlan(choiceOutput, "9NOWNEDGAME1", "US")
        assertNull(multi.automaticContentId)
        assertEquals(2, multi.supportedChoices.size)
        assertThrows("status must match the choices", XodusProtocolException::class.java) {
            XodusProtocol.parsePackagePlan(plan("ready", choice("11111111-2222-3333-4444-555555555555", kind = "GameAddOn")), "9NOWNEDGAME1", "US")
        }
        assertThrows("supported console packages are invalid", XodusProtocolException::class.java) {
            XodusProtocol.parsePackagePlan(plan("ready", choice("11111111-2222-3333-4444-555555555555", platform = "Xbox.Core")), "9NOWNEDGAME1", "US")
        }
        assertThrows(XodusProtocolException::class.java) {
            XodusProtocol.parsePackagePlan(plan("unavailable"), "9OTHERGAME01", "US")
        }
    }

    @Test
    fun account_requiresStoredUsername() {
        assertEquals(XodusProtocol.Account(true, "a@b.c"), XodusProtocol.parseAccount("x\n{\"stored\":true,\"username\":\" a@b.c \"}"))
        assertEquals(XodusProtocol.Account(false, null), XodusProtocol.parseAccount("{\"stored\":false}"))
        assertEquals(XodusProtocol.Account(false, null), XodusProtocol.parseAccount("{\"stored\":true,\"username\":\"\"}"))
    }
}
