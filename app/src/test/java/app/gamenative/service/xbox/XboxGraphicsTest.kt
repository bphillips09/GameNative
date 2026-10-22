package app.gamenative.service.xbox

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.path.createTempDirectory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XboxGraphicsTest {
    private val dir = createTempDirectory("xbox-graphics").toFile()

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun autoDxWrapper_switchesOnlyAnUndecidedDefaultContainerForD3D12() {
        assertEquals("vkd3d", XboxGraphics.autoDxWrapper(decided = null, current = "dxvk", default = "dxvk", usesD3D12 = true))
        // Not D3D12, already decided, or changed by hand: leave it.
        assertNull(XboxGraphics.autoDxWrapper(null, "dxvk", "dxvk", usesD3D12 = false))
        assertNull(XboxGraphics.autoDxWrapper("kept", "dxvk", "dxvk", usesD3D12 = true))
        assertNull(XboxGraphics.autoDxWrapper(null, "wined3d", "dxvk", usesD3D12 = true))
        assertNull(XboxGraphics.autoDxWrapper(null, "vkd3d", "vkd3d", usesD3D12 = true))
    }

    @Test
    fun importedDlls_readsImportsAndDelayImports() {
        val direct = pe("direct.exe", imports = listOf("KERNEL32.dll", "D3D12.dll"))
        assertEquals(setOf("kernel32.dll", "d3d12.dll"), XboxGraphics.importedDlls(direct))
        assertTrue(XboxGraphics.importsD3D12(direct))

        val delayed = pe("delayed.exe", imports = listOf("KERNEL32.dll"), delayImports = listOf("d3d12.dll"))
        assertEquals(setOf("kernel32.dll", "d3d12.dll"), XboxGraphics.importedDlls(delayed))
        assertTrue(XboxGraphics.importsD3D12(delayed))

        assertFalse(XboxGraphics.importsD3D12(pe("d3d11.exe", imports = listOf("KERNEL32.dll", "d3d11.dll", "dxgi.dll"))))
    }

    @Test
    fun importsD3D12_isFalseForFilesThatAreNotPeImages() {
        assertFalse(XboxGraphics.importsD3D12(File(dir, "missing.exe")))
        assertFalse(XboxGraphics.importsD3D12(File(dir, "text.exe").apply { writeText("not a program") }))
    }

    /** A minimal PE32+ image with one section holding the import and delay-import tables. */
    private fun pe(name: String, imports: List<String>, delayImports: List<String> = emptyList()): File {
        val sectionRva = 0x1000
        val raw = 0x400
        val b = ByteBuffer.allocate(0x1000).order(ByteOrder.LITTLE_ENDIAN)
        b.putShort(0, 0x5A4D)
        b.putInt(0x3C, 0x80)
        b.putInt(0x80, 0x00004550)
        b.putShort(0x84, 0x8664.toShort())
        b.putShort(0x86, 1) // one section
        b.putShort(0x94, 240) // optional header size
        val optional = 0x98
        b.putShort(optional, 0x20B)
        // Section table: .rdata at RVA 0x1000, file offset 0x400.
        val section = optional + 240
        b.put(section, ".rdata".toByteArray())
        b.putInt(section + 8, 0x800)
        b.putInt(section + 12, sectionRva)
        b.putInt(section + 16, 0x800)
        b.putInt(section + 20, raw)

        var nameAt = 0x400 // names live after the tables, at section offset 0x400
        fun putName(dll: String): Int {
            val rva = sectionRva + nameAt
            b.put(raw + nameAt, dll.toByteArray())
            nameAt += dll.length + 1
            return rva
        }
        // Imports at section offset 0 (20-byte descriptors, null-terminated).
        imports.forEachIndexed { i, dll -> b.putInt(raw + i * 20 + 12, putName(dll)) }
        b.putInt(optional + 112 + 8, sectionRva)
        b.putInt(optional + 112 + 12, (imports.size + 1) * 20)
        // Delay imports at section offset 0x200 (32-byte descriptors, name at +4).
        if (delayImports.isNotEmpty()) {
            delayImports.forEachIndexed { i, dll ->
                b.putInt(raw + 0x200 + i * 32, 1)
                b.putInt(raw + 0x200 + i * 32 + 4, putName(dll))
            }
            b.putInt(optional + 112 + 13 * 8, sectionRva + 0x200)
            b.putInt(optional + 112 + 13 * 8 + 4, (delayImports.size + 1) * 32)
        }
        return File(dir, name).apply { writeBytes(b.array()) }
    }
}
