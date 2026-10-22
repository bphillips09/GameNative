package app.gamenative.service.xbox

import java.io.File
import kotlin.io.path.createTempDirectory
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class XboxStoreTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = createTempDirectory("xbox-store").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun catalog_parsesTitleArtPcAvailabilityAndPlayAnywhere() {
        val json = JSONObject().put(
            "Products",
            JSONArray()
                .put(
                    JSONObject()
                        .put("ProductId", "9NOWNEDGAME1")
                        .put("ProductType", "Game")
                        .put("AlternateIds", JSONArray().put(JSONObject().put("IdType", "XboxTitleId").put("Value", "123456789")))
                        .put("Properties", JSONObject().put("Attributes", JSONArray().put(JSONObject().put("Name", "XPA"))))
                        .put(
                            "LocalizedProperties",
                            JSONArray().put(
                                JSONObject()
                                    .put("ProductTitle", "Grounded")
                                    .put("PublisherName", "Xbox Game Studios")
                                    .put("ShortDescription", "Small.")
                                    .put(
                                        "Images",
                                        JSONArray()
                                            .put(JSONObject().put("ImagePurpose", "Screenshot").put("Uri", "//store-images.s-microsoft.com/s1"))
                                            .put(JSONObject().put("ImagePurpose", "BoxArt").put("Uri", "//store-images.s-microsoft.com/box"))
                                            .put(JSONObject().put("ImagePurpose", "SuperHeroArt").put("Uri", "http://insecure.example/hero")),
                                    ),
                            ),
                        )
                        .put(
                            "DisplaySkuAvailabilities",
                            JSONArray().put(
                                JSONObject().put(
                                    "Sku",
                                    JSONObject().put(
                                        "Properties",
                                        JSONObject().put(
                                            "Packages",
                                            JSONArray().put(
                                                JSONObject().put("PlatformDependencies", JSONArray().put(JSONObject().put("PlatformName", "Windows.Desktop"))),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                )
                .put(JSONObject().put("ProductId", "not-an-id"))
                .put(JSONObject().put("ProductId", "9CONSOLEONLY").put("LocalizedProperties", JSONArray().put(JSONObject().put("ProductTitle", "Console")))),
        ).toString()
        val products = XboxCatalog.parse(json)
        assertEquals(listOf("9NOWNEDGAME1", "9CONSOLEONLY"), products.map { it.productId })
        val game = products[0]
        assertEquals("Grounded", game.title)
        assertEquals("https://store-images.s-microsoft.com/box", game.boxArt)
        assertNull("only https art", game.hero)
        assertEquals(listOf("https://store-images.s-microsoft.com/s1"), game.screenshots)
        assertTrue(game.hasPcPackage)
        assertTrue(game.isPlayAnywhere)
        assertEquals("123456789", game.xboxTitleId)
        assertFalse(products[1].hasPcPackage)
        assertTrue(XboxCatalog.url(listOf("9A", "9B"), "US", "en-US").contains("bigIds=9A%2C9B&market=US&languages=en-US"))
    }

    private fun stageOverlay(vararg files: Pair<String, String?>): XboxRuntimeOverlay {
        val staging = File(dir, "overlay").apply { mkdirs() }
        File(staging, "system32").mkdirs()
        val dlls = JSONArray()
        for ((name, override) in files) {
            val file = File(staging, "system32/$name").apply { writeText("MZ fake $name") }
            dlls.put(JSONObject().put("name", name).put("sha256", XboxRuntimeOverlay.sha256(file)).put("override", override ?: JSONObject.NULL))
        }
        File(staging, "overlay.json").writeText(JSONObject().put("schema", 1).put("dlls", dlls).toString())
        return XboxRuntimeOverlay(staging)
    }

    @Test
    fun overlay_installsVerifiedDllsIntoThePrefix() {
        val overlay = stageOverlay("xgameruntime.dll" to "n", "winhttp.dll" to "n,b", "libnghttp2.dll" to null)
        val prefix = File(dir, "prefix").apply { File(this, "drive_c/windows/system32").mkdirs() }
        val installed = overlay.installInto(prefix)
        assertEquals(3, installed.size)
        assertEquals("MZ fake winhttp.dll", File(prefix, "drive_c/windows/system32/winhttp.dll").readText())
        // Idempotent, and tampering with the staged copy is detected.
        overlay.installInto(prefix)
        File(dir, "overlay/system32/winhttp.dll").writeText("tampered")
        assertThrows(IllegalStateException::class.java) { overlay.installInto(prefix) }
        assertThrows(IllegalArgumentException::class.java) { overlay.installInto(File(dir, "not-a-prefix")) }
    }

    @Test
    fun overlay_rejectsPathTraversalNames() {
        val overlay = stageOverlay("..\\evil.dll" to "n")
        assertThrows(IllegalArgumentException::class.java) { overlay.dlls() }
    }

    @Test
    fun overlay_mergesDllOverridesWithoutDuplicates() {
        val dlls = listOf(
            XboxRuntimeOverlay.Dll("xgameruntime.dll", "x", "n"),
            XboxRuntimeOverlay.Dll("winhttp.dll", "x", "n,b"),
            XboxRuntimeOverlay.Dll("libnghttp2.dll", "x", null),
        )
        assertEquals("xgameruntime=n;winhttp=n,b", XboxRuntimeOverlay.mergeDllOverrides(null, dlls))
        assertEquals(
            "dxgi=n;d3d11,d3d12=n,b;xgameruntime=n;winhttp=n,b",
            XboxRuntimeOverlay.mergeDllOverrides("dxgi=n; winhttp=b ;d3d11,WinHTTP,d3d12=n,b;;xgameruntime.dll=d", dlls),
        )
    }
}
