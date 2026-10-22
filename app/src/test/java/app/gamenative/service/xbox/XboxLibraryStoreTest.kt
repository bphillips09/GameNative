package app.gamenative.service.xbox

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.gamenative.db.PluviaDatabase
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class XboxLibraryStoreTest {
    private lateinit var db: PluviaDatabase
    private lateinit var store: XboxLibraryStore
    private lateinit var dir: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PluviaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = XboxLibraryStore(db.xboxGameDao())
        dir = createTempDirectory("xbox-library").toFile()
    }

    @After
    fun tearDown() {
        db.close()
        dir.deleteRecursively()
    }

    private fun installedPackage(name: String): File = File(dir, "games/$name").apply {
        mkdirs()
        File(this, "MicrosoftGame.config").writeText(
            """<Game><Identity Name="P.G" Version="1.0.0.0"/><TitleId>ABCD1234</TitleId><ExecutableList><Executable Name="Bin\G.exe"/></ExecutableList><ShellVisuals DefaultDisplayName="G"/></Game>""",
        )
        File(this, ".xodus-streaming.msixvc").writeText("x")
    }

    @Test
    fun assignsStableAppIdsAndTracksInstalls() {
        val a = store.upsertFromLibrary("9NOWNEDGAME1", XodusProtocol.Access.OWNED, null)
        val b = store.upsertFromLibrary("9GAMEPASSGM1", XodusProtocol.Access.GAME_PASS, null)
        assertEquals(1, a.appId)
        assertEquals(2, b.appId)
        assertEquals("XBOX_1", a.containerId)
        // Refresh keeps the ID.
        assertEquals(1, store.upsertFromLibrary("9NOWNEDGAME1", XodusProtocol.Access.OWNED, null).appId)

        store.markInstalled("9NOWNEDGAME1", installedPackage("9NOWNEDGAME1"), "11111111-2222-3333-4444-555555555555")
        val game = store.byAppId(1)!!
        assertTrue(game.isInstalled)
        assertEquals("Bin\\G.exe", game.executable)
        assertEquals("ABCD1234", game.titleId)
        assertEquals("G", game.title)
        assertEquals(XodusProtocol.Access.GAME_PASS, store.byProductId("9GAMEPASSGM1")!!.access)

        // A library refresh keeps install state.
        assertTrue(store.upsertFromLibrary("9NOWNEDGAME1", XodusProtocol.Access.OWNED, null).isInstalled)

        // New products never reuse an ID, even after uninstall.
        store.markUninstalled("9NOWNEDGAME1")
        assertFalse(store.byAppId(1)!!.isInstalled)
        assertNull(store.byAppId(1)!!.executable)
        assertEquals(3, store.upsertFromLibrary("9THIRDGAME01", null, null).appId)
    }

    @Test
    fun clearsAccessForLapsedProductsButKeepsInstalledOnes() {
        store.upsertFromLibrary("9KEPTGAME001", XodusProtocol.Access.OWNED, null)
        store.upsertFromLibrary("9LAPSEDGAME1", XodusProtocol.Access.GAME_PASS, null)
        store.upsertFromLibrary("9INSTALLED01", XodusProtocol.Access.GAME_PASS, null)
        store.markInstalled("9INSTALLED01", installedPackage("9INSTALLED01"), "11111111-2222-3333-4444-555555555555")

        store.clearAccessExcept(listOf("9KEPTGAME001"))

        assertEquals(XodusProtocol.Access.OWNED, store.byProductId("9KEPTGAME001")!!.access)
        assertNull(store.byProductId("9LAPSEDGAME1")!!.access)
        assertEquals(XodusProtocol.Access.GAME_PASS, store.byProductId("9INSTALLED01")!!.access)
    }

    @Test
    fun reconcileMarksGamesWhoseFilesAreGone() {
        store.upsertFromLibrary("9GONEGAME001", XodusProtocol.Access.OWNED, null)
        val install = installedPackage("9GONEGAME001")
        store.markInstalled("9GONEGAME001", install, "11111111-2222-3333-4444-555555555555")
        assertTrue(store.reconcileInstalls().isEmpty())

        install.deleteRecursively()
        assertEquals(listOf("9GONEGAME001"), store.reconcileInstalls().map { it.productId })
        assertFalse(store.byProductId("9GONEGAME001")!!.isInstalled)
    }

    @Test
    fun importsLegacyJsonKeepingAppIds() {
        val install = installedPackage("9PK087LNGJC5")
        val legacy = File(dir, "library.json").apply {
            writeText(
                """
                {"schema":1,"nextAppId":457,"games":[
                  {"appId":455,"productId":"9PK087LNGJC5","title":"Balatro","access":"GAME_PASS","isPlayAnywhere":false,
                   "hasPcPackage":true,"installPath":"${install.absolutePath}","executable":"love.exe","sizeBytes":123},
                  {"appId":456,"productId":"9NOTINSTALL1","title":"Other","access":"OWNED","hasPcPackage":true},
                  {"appId":0,"productId":"9BADAPPID001","title":"Bad"},
                  {"appId":7,"productId":"not-a-product","title":"Bad"}
                ]}
                """.trimIndent(),
            )
        }

        assertEquals(2, store.importLegacy(legacy))
        assertFalse(legacy.exists())
        assertTrue(File(dir, "library.json.imported").isFile)

        val balatro = store.byAppId(455)!!
        assertEquals("9PK087LNGJC5", balatro.productId)
        assertEquals("XBOX_455", balatro.containerId)
        assertTrue(balatro.isInstalled)
        assertEquals("love.exe", balatro.executable)
        assertEquals(XodusProtocol.Access.GAME_PASS, balatro.access)
        assertFalse(store.byAppId(456)!!.isInstalled)
        assertNull(store.byProductId("9BADAPPID001"))

        // New products continue after the imported IDs, and importing again is a no-op.
        assertEquals(457, store.upsertFromLibrary("9NEWGAME0001", null, null).appId)
        assertEquals(0, store.importLegacy(legacy))
    }

    @Test
    fun flowEmitsTheLibrarySortedByTitle() = runBlocking {
        store.upsertFromLibrary("9ZZZZZZZZZZ1", null, null)
        store.update("9ZZZZZZZZZZ1") { it.copy(title = "zeta") }
        store.upsertFromLibrary("9AAAAAAAAAA1", null, null)
        store.update("9AAAAAAAAAA1") { it.copy(title = "Alpha") }
        assertEquals(listOf("Alpha", "zeta"), store.flow().first().map { it.title })
    }
}
