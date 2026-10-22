package app.gamenative.service.xbox

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * The Windows-side Xbox runtime (Xodus's `xgameruntime.dll` plus the Wine DLLs
 * Xodus patches for it), installed per container as native DLLs.
 *
 * The overlay archive (tools/build-xodus-runtime.sh) is unpacked once into
 * [stagingDir]; [installInto] copies its DLLs into a prefix's system32 and
 * [mergeDllOverrides] enables them for the launch. Nothing outside the Xbox
 * game's own container changes.
 */
class XboxRuntimeOverlay(private val stagingDir: File) {
    data class Dll(val name: String, val sha256: String, val override: String?)

    val manifestFile: File get() = File(stagingDir, "overlay.json")

    val isStaged: Boolean get() = manifestFile.isFile

    fun dlls(): List<Dll> {
        val json = JSONObject(manifestFile.readText())
        require(json.optInt("schema") == 1) { "Unsupported runtime overlay" }
        val list = json.getJSONArray("dlls")
        return (0 until list.length()).map { i ->
            val item = list.getJSONObject(i)
            val name = item.getString("name")
            require(SAFE_NAME.matches(name)) { "Invalid DLL name in overlay" }
            Dll(name, item.getString("sha256"), if (item.isNull("override")) null else item.getString("override"))
        }
    }

    /**
     * Copies the overlay into `<prefix>/drive_c/windows/system32`, verifying
     * each DLL against the manifest. Returns the DLLs installed. Idempotent.
     */
    fun installInto(wineprefix: File): List<Dll> {
        val system32 = File(wineprefix, "drive_c/windows/system32")
        require(system32.isDirectory) { "Not a Wine prefix: $wineprefix" }
        val dlls = dlls()
        for (dll in dlls) {
            val source = File(stagingDir, "system32/${dll.name}")
            check(sha256(source) == dll.sha256) { "Runtime overlay file ${dll.name} is damaged" }
            val target = File(system32, dll.name)
            if (target.isFile && sha256(target) == dll.sha256) continue
            val tmp = File(system32, ".${dll.name}.xbox-tmp")
            source.copyTo(tmp, overwrite = true)
            if (!tmp.renameTo(target)) {
                tmp.delete()
                error("Could not install ${dll.name} into the container")
            }
        }
        return dlls
    }

    companion object {
        private val SAFE_NAME = Regex("^[A-Za-z0-9._-]+\\.dll$")

        const val ACTIVATABLE_CLASS_ROOT = "Software\\Microsoft\\WindowsRuntime\\ActivatableClassId"

        /**
         * WinRT classes the overlay serves. XUser parses Xbox endpoint replies through
         * Windows.Data.Json, which combase only activates when the class is registered
         * (HKLM, `DllPath`). Wine registers these for its own windows.web at prefix
         * creation in newer versions; older prefixes need them written.
         * The JSON classes also need `Windows.Foundation.Collections.PropertySet`,
         * which only Wine 11's wintypes implements (Wine 9/10, and so GameNative's
         * Proton builds, have none), so the overlay ships that wintypes.dll too.
         */
        val ACTIVATABLE_CLASSES = mapOf(
            "Windows.Data.Json.JsonArray" to "windows.web.dll",
            "Windows.Data.Json.JsonObject" to "windows.web.dll",
            "Windows.Data.Json.JsonValue" to "windows.web.dll",
            "Windows.Foundation.Collections.PropertySet" to "wintypes.dll",
        )

        /** `system.reg` key → `DllPath` value for [ACTIVATABLE_CLASSES]. */
        fun activatableClassRegistry(): Map<String, String> =
            ACTIVATABLE_CLASSES.map { (cls, dll) -> "$ACTIVATABLE_CLASS_ROOT\\$cls" to "C:\\windows\\system32\\$dll" }.toMap()

        /**
         * Adds the overlay's overrides to an existing `WINEDLLOVERRIDES` value,
         * replacing any earlier entry for the same DLL (a container or game fix
         * could otherwise force the stock builtin back in).
         */
        fun mergeDllOverrides(existing: String?, dlls: List<Dll>): String {
            val wanted = dlls.mapNotNull { dll -> dll.override?.let { dll.name.removeSuffix(".dll").lowercase() to it } }.toMap()
            val kept = (existing ?: "").split(';')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .mapNotNull { entry ->
                    // Entries look like "a,b,c=n,b"; drop our DLLs from multi-DLL entries.
                    val names = entry.substringBefore('=').split(',').map(String::trim).filter(String::isNotEmpty)
                    val mode = entry.substringAfter('=', "")
                    val remaining = names.filter { it.lowercase().removeSuffix(".dll") !in wanted }
                    if (remaining.isEmpty()) null else remaining.joinToString(",") + if (entry.contains('=')) "=$mode" else ""
                }
            return (kept + wanted.map { (name, mode) -> "$name=$mode" }).joinToString(";")
        }

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
