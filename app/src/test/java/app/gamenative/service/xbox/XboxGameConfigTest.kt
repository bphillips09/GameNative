package app.gamenative.service.xbox

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class XboxGameConfigTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = createTempDirectory("xbox-config").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun readsMicrosoftGameConfigCaseInsensitively() {
        File(dir, "MicrosoftGame.Config").writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <Game configVersion="1">
              <Identity Name="Publisher.Game" Publisher="CN=Pub" Version="1.2.3.0"/>
              <TitleId>1A2B3C4D</TitleId>
              <StoreId>9NOWNEDGAME1</StoreId>
              <ExecutableList>
                <Executable Name="Game\Binaries\WinGDK\Game-WinGDK-Shipping.exe" TargetDeviceFamily="PC" Id="Game"/>
                <Executable Name="Tools\Crash.exe" TargetDeviceFamily="PC"/>
              </ExecutableList>
              <ShellVisuals DefaultDisplayName="The Game" PublisherDisplayName="Pub Studio" Square150x150Logo="Logo.png"/>
            </Game>
            """.trimIndent(),
        )
        val config = XboxGameConfig.read(dir)!!
        assertEquals("The Game", config.title)
        assertEquals("Game\\Binaries\\WinGDK\\Game-WinGDK-Shipping.exe", config.executable)
        assertEquals("Game/Binaries/WinGDK/Game-WinGDK-Shipping.exe", config.executableRelativePath)
        assertEquals("9NOWNEDGAME1", config.storeId)
        assertEquals("1A2B3C4D", config.titleId)
        assertEquals("1.2.3.0", config.version)
        assertEquals("Publisher.Game", config.packageName)
        assertEquals("Pub Studio", config.publisher)
    }

    @Test
    fun fallsBackToAppxManifestAndRejectsUnsafePaths() {
        File(dir, "appxmanifest.xml").writeText(
            """
            <Package xmlns="http://schemas.microsoft.com/appx/manifest/foundation/windows10" xmlns:uap="x">
              <Identity Name="Old.Game" Version="2.0.0.0"/>
              <Properties><DisplayName>Old Game</DisplayName><PublisherDisplayName>Old Pub</PublisherDisplayName></Properties>
              <Applications><Application Id="App" Executable="..\outside.exe"/></Applications>
            </Package>
            """.trimIndent(),
        )
        val config = XboxGameConfig.read(dir)!!
        assertEquals("Old Game", config.title)
        assertEquals("2.0.0.0", config.version)
        assertNull("parent paths never leave the install dir", config.executableRelativePath)
    }

    @Test
    fun missingOrHostileManifests() {
        assertNull(XboxGameConfig.read(dir))
        File(dir, "MicrosoftGame.config").writeText(
            """<?xml version="1.0"?><!DOCTYPE g [<!ENTITY x SYSTEM "file:///etc/passwd">]><Game><StoreId>&x;</StoreId></Game>""",
        )
        // DOCTYPE is refused outright: no external entity is ever resolved.
        assertNull(XboxGameConfig.read(dir))
    }
}
