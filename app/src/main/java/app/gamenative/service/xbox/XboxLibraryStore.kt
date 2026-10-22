package app.gamenative.service.xbox

import app.gamenative.data.XboxGame
import app.gamenative.db.dao.XboxGameDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File

/**
 * Xbox games GameNative knows about, stored in Room ([XboxGameDao]) like the other stores.
 *
 * GameNative containers are keyed `SOURCE_<int>`, while Store products are
 * 12-character IDs, so each product gets a stable integer [XboxGame.appId] the
 * first time it is seen. IDs are never reused.
 *
 * Calls are synchronous for launch and container code, as AmazonService does;
 * the library UI collects [flow] instead.
 */
class XboxLibraryStore(private val dao: XboxGameDao) {

    private fun <T> io(block: suspend XboxGameDao.() -> T): T = runBlocking(Dispatchers.IO) { dao.block() }

    fun flow(): Flow<List<XboxGame>> = dao.getAll()

    fun all(): List<XboxGame> = io { getAllAsList() }
    fun byProductId(productId: String): XboxGame? = io { getByProductId(productId) }
    fun byAppId(appId: Int): XboxGame? = io { getByAppId(appId) }

    /** Inserts or refreshes catalog/entitlement data, keeping install state and the app ID. */
    fun upsertFromLibrary(productId: String, access: XodusProtocol.Access?, catalog: XboxCatalog.Product?): XboxGame {
        require(XodusProtocol.isProductId(productId))
        return io {
            val existing = getByProductId(productId)
            upsertFromLibrary(
                XboxGame(
                    productId = productId,
                    title = catalog?.title ?: existing?.title ?: productId,
                    boxArt = catalog?.boxArt,
                    hero = catalog?.hero,
                    publisher = catalog?.publisher,
                    accessName = access?.name,
                    isPlayAnywhere = catalog?.isPlayAnywhere ?: existing?.isPlayAnywhere ?: false,
                    hasPcPackage = catalog?.hasPcPackage ?: existing?.hasPcPackage ?: false,
                    titleId = catalog?.xboxTitleId,
                ),
            )
        }
    }

    /** Drops entitlement from products the account no longer has, keeping installed ones. */
    fun clearAccessExcept(playable: Collection<String>) = io { clearAccessExcept(playable.toList()) }

    fun update(productId: String, change: (XboxGame) -> XboxGame): XboxGame? = io {
        val current = getByProductId(productId) ?: return@io null
        change(current).also {
            require(it.appId == current.appId && it.productId == current.productId)
            update(it)
        }
    }

    /** Records a completed install, reading launch facts from the package's own config. */
    fun markInstalled(productId: String, installDir: File, contentId: String): XboxGame? {
        val config = XboxGameConfig.read(installDir)
        return update(productId) {
            it.copy(
                isInstalled = XboxDownload.isInstalled(installDir),
                installPath = installDir.absolutePath,
                contentId = contentId,
                executable = config?.executable,
                installedVersion = config?.version,
                titleId = config?.titleId ?: it.titleId,
                title = if (it.title == it.productId) config?.title ?: it.title else it.title,
                sizeBytes = installDir.walkTopDown().filter { f -> f.isFile }.sumOf { f -> f.length() },
            )
        }
    }

    fun markUninstalled(productId: String): XboxGame? =
        update(productId) {
            it.copy(isInstalled = false, installPath = null, contentId = null, executable = null, installedVersion = null, sizeBytes = null)
        }

    /** Marks games whose files are gone (deleted outside GameNative) as uninstalled. */
    fun reconcileInstalls(): List<XboxGame> = io { getInstalledGames() }
        .filter { game -> game.installPath?.let { !XboxDownload.isInstalled(File(it)) } ?: true }
        .mapNotNull { markUninstalled(it.productId) }

    /**
     * One-time import of the JSON library earlier builds kept, keeping each game's app ID
     * so existing containers (`XBOX_<appId>`) still match. The file is renamed afterwards.
     * Returns the number of games imported.
     */
    fun importLegacy(file: File): Int {
        if (!file.isFile) return 0
        val games = runCatching { parseLegacy(file.readText()) }.getOrDefault(emptyList())
        val imported = io {
            games.count { game ->
                if (getByProductId(game.productId) != null || getByAppId(game.appId) != null) return@count false
                insert(game)
                true
            }
        }
        file.renameTo(File(file.parentFile, "${file.name}.imported"))
        return imported
    }

    companion object {
        const val CONTAINER_PREFIX = XboxGame.CONTAINER_PREFIX

        /** Games from the schema-1 `library.json` earlier builds wrote; invalid entries are skipped. */
        fun parseLegacy(text: String): List<XboxGame> {
            val list = JSONObject(text).optJSONArray("games") ?: return emptyList()
            return (0 until list.length()).mapNotNull { i ->
                val item = list.optJSONObject(i) ?: return@mapNotNull null
                runCatching {
                    val productId = item.getString("productId")
                    require(XodusProtocol.isProductId(productId))
                    val appId = item.getInt("appId")
                    require(appId > 0)
                    fun str(key: String) = if (item.isNull(key)) null else item.optString(key).takeIf { it.isNotEmpty() }
                    val installPath = str("installPath")
                    XboxGame(
                        appId = appId,
                        productId = productId,
                        title = item.optString("title", productId),
                        boxArt = str("boxArt"),
                        hero = str("hero"),
                        publisher = str("publisher"),
                        accessName = str("access")?.takeIf { name -> XodusProtocol.Access.entries.any { it.name == name } },
                        isPlayAnywhere = item.optBoolean("isPlayAnywhere"),
                        hasPcPackage = item.optBoolean("hasPcPackage"),
                        isInstalled = installPath != null && XboxDownload.isInstalled(File(installPath)),
                        installPath = installPath,
                        contentId = str("contentId")?.takeIf(XodusProtocol::isContentId),
                        executable = str("executable"),
                        installedVersion = str("installedVersion"),
                        titleId = str("titleId"),
                        sizeBytes = if (item.has("sizeBytes")) item.optLong("sizeBytes") else null,
                    )
                }.getOrNull()
            }
        }
    }
}
