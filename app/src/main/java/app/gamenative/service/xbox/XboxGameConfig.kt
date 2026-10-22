package app.gamenative.service.xbox

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The parts of an installed GDK package's `MicrosoftGame.config` (or, for older
 * packages, `appxmanifest.xml`) that GameNative needs: what to launch and how
 * to label it. Element and attribute names are matched case-insensitively and
 * without namespace prefixes, like Windows does.
 */
data class XboxGameConfig(
    val title: String?,
    val executable: String?,
    val storeId: String?,
    val titleId: String?,
    val version: String?,
    val packageName: String?,
    val publisher: String?,
) {
    /** Executable path as a relative Unix path inside the install directory. */
    val executableRelativePath: String?
        get() = executable?.replace('\\', '/')?.trimStart('/')?.takeIf { path ->
            path.isNotEmpty() && path.split('/').none { it == ".." || it == "." || it.contains(':') }
        }

    companion object {
        fun read(installDir: File): XboxGameConfig? {
            val entries = installDir.listFiles()?.associateBy { it.name.lowercase() } ?: return null
            val config = entries["microsoftgame.config"]?.let(::parse)
            val appx = entries["appxmanifest.xml"]?.let(::parse)
            if (config == null && appx == null) return null
            return XboxGameConfig(
                title = config?.attr("ShellVisuals", "DefaultDisplayName") ?: appx?.text("DisplayName"),
                executable = config?.attr("Executable", "Name") ?: appx?.attr("Application", "Executable"),
                storeId = config?.text("StoreId"),
                titleId = config?.text("TitleId"),
                version = config?.attr("Identity", "Version") ?: appx?.attr("Identity", "Version"),
                packageName = config?.attr("Identity", "Name") ?: appx?.attr("Identity", "Name"),
                publisher = config?.attr("ShellVisuals", "PublisherDisplayName") ?: appx?.text("PublisherDisplayName"),
            )
        }

        fun parse(text: String): Element? = runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                isExpandEntityReferences = false
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            }
            factory.newDocumentBuilder().parse(text.byteInputStream()).documentElement
        }.getOrNull()

        private fun parse(file: File): Element? =
            if (file.length() in 1..4_000_000) parse(file.readText()) else null

        private fun localName(node: Node): String = node.nodeName.substringAfterLast(':').lowercase()

        private fun Element.find(name: String): Element? {
            if (localName(this) == name.lowercase()) return this
            val children = childNodes
            for (i in 0 until children.length) {
                val child = children.item(i) as? Element ?: continue
                child.find(name)?.let { return it }
            }
            return null
        }

        private fun Element.attr(element: String, attribute: String): String? {
            val node = find(element) ?: return null
            val attributes = node.attributes
            for (i in 0 until attributes.length) {
                val item = attributes.item(i)
                if (localName(item) == attribute.lowercase()) return item.nodeValue?.trim()?.takeIf { it.isNotEmpty() }
            }
            return null
        }

        private fun Element.text(element: String): String? = find(element)?.textContent?.trim()?.takeIf { it.isNotEmpty() }
    }
}
