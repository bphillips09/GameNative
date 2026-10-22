package app.gamenative.service.xbox

import java.io.File
import java.io.RandomAccessFile

/**
 * Picks a container's DX wrapper for an Xbox game from its executable.
 *
 * Steam containers get a D3D12 game's wrapper from its Direct3D version at creation
 * (ContainerUtils); Xbox titles have no such metadata and their executable is only
 * readable once Xodus has decrypted it at launch, so the choice is made then, once:
 * D3D12 needs VKD3D, and the DXVK default leaves Wine's builtin d3d12 in place.
 */
object XboxGraphics {
    /** Container extra recording that the automatic choice was made ("vkd3d" or "kept"). */
    const val AUTO_DX_WRAPPER_EXTRA = "xboxDxWrapperAuto"
    const val VKD3D = "vkd3d"

    /**
     * The wrapper to switch to, or null to leave the container alone. Decides only once
     * ([decided] is the recorded choice), and only while the container still has the
     * default wrapper, so a wrapper changed by hand is kept.
     */
    fun autoDxWrapper(decided: String?, current: String, default: String, usesD3D12: Boolean): String? =
        if (decided == null && usesD3D12 && current == default && !current.startsWith(VKD3D)) VKD3D else null

    /** Whether [executable] imports d3d12.dll, directly or delay-loaded. */
    fun importsD3D12(executable: File): Boolean =
        runCatching { importedDlls(executable).contains("d3d12.dll") }.getOrDefault(false)

    /** Lower-case names of the DLLs a PE32+ image imports and delay-loads. */
    fun importedDlls(file: File): Set<String> = RandomAccessFile(file, "r").use { pe ->
        fun u16(at: Long): Int { pe.seek(at); return pe.readUnsignedByte() or (pe.readUnsignedByte() shl 8) }
        fun u32(at: Long): Long { pe.seek(at); return (0..3).fold(0L) { acc, i -> acc or (pe.readUnsignedByte().toLong() shl (8 * i)) } }

        require(u16(0) == 0x5A4D) { "Not a PE image" }
        val header = u32(0x3C)
        require(u32(header) == 0x00004550L) { "Not a PE image" }
        val sectionCount = u16(header + 6)
        val optional = header + 24
        val optionalSize = u16(header + 20)
        require(u16(optional) == 0x20B) { "Not a PE32+ image" }
        val sections = (0 until sectionCount).map { i ->
            val at = optional + optionalSize + i * 40L
            // virtual address, virtual size, raw size, raw offset
            longArrayOf(u32(at + 12), u32(at + 8), u32(at + 16), u32(at + 20))
        }
        fun offset(rva: Long): Long? = sections.firstOrNull { (va, vsize, rsize) -> rva >= va && rva < va + maxOf(vsize, rsize) }
            ?.let { (va, _, rsize, raw) -> if (rva - va < rsize) rva - va + raw else null }
        fun name(rva: Long): String? = offset(rva)?.let { at ->
            pe.seek(at)
            val bytes = generateSequence { pe.read().takeIf { it > 0 } }.take(256).map { it.toByte() }.toList()
            String(bytes.toByteArray(), Charsets.US_ASCII).lowercase()
        }

        val names = LinkedHashSet<String>()
        // Data directory 1: imports (20-byte descriptors, name at +12).
        offset(u32(optional + 112 + 8))?.let { table ->
            var at = table
            while (names.size < 1024) {
                val nameRva = u32(at + 12)
                if (nameRva == 0L) break
                name(nameRva)?.let(names::add)
                at += 20
            }
        }
        // Data directory 13: delay imports (32-byte descriptors, name at +4).
        offset(u32(optional + 112 + 13 * 8))?.let { table ->
            var at = table
            while (names.size < 1024) {
                val nameRva = u32(at + 4)
                if (nameRva == 0L) break
                name(nameRva)?.let(names::add)
                at += 32
            }
        }
        names
    }
}
