package app.gamenative.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.gamenative.service.xbox.XodusProtocol

/**
 * Xbox (Microsoft Store PC) game entity for Room database.
 *
 * Follows the Epic/Amazon pattern:
 * - [appId]: Auto-generated Int primary key for internal use (events, intents, container IDs).
 *   SQLite AUTOINCREMENT, so an ID is never reused once a product has had it.
 * - [productId]: the Store's 12-character product ID, used for every backend call.
 */
@Entity(tableName = "xbox_games", indices = [Index(value = ["product_id"], unique = true)])
data class XboxGame(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo("app_id")
    val appId: Int = 0,

    /** Store product ID, e.g. "9PK087LNGJC5". */
    @ColumnInfo("product_id")
    val productId: String,

    @ColumnInfo("title")
    val title: String = "",

    @ColumnInfo("box_art_url")
    val boxArt: String? = null,

    @ColumnInfo("hero_url")
    val hero: String? = null,

    @ColumnInfo("publisher")
    val publisher: String? = null,

    /** How the account may play it ([XodusProtocol.Access] name); null when it no longer can. */
    @ColumnInfo("access")
    val accessName: String? = null,

    @ColumnInfo(name = "is_play_anywhere", defaultValue = "0")
    val isPlayAnywhere: Boolean = false,

    @ColumnInfo(name = "has_pc_package", defaultValue = "0")
    val hasPcPackage: Boolean = false,

    @ColumnInfo(name = "is_installed", defaultValue = "0")
    val isInstalled: Boolean = false,

    @ColumnInfo("install_path")
    val installPath: String? = null,

    /** Package content ID that was installed, when the product offers several. */
    @ColumnInfo("content_id")
    val contentId: String? = null,

    /** Executable named by the package's MicrosoftGame.config. */
    @ColumnInfo("executable")
    val executable: String? = null,

    @ColumnInfo("installed_version")
    val installedVersion: String? = null,

    @ColumnInfo("title_id")
    val titleId: String? = null,

    /** On-disk installed size in bytes. */
    @ColumnInfo("install_size")
    val sizeBytes: Long? = null,

    /** Epoch millis of the last time the game was launched. 0 = never played. */
    @ColumnInfo(name = "last_played", defaultValue = "0")
    val lastPlayed: Long = 0,

    /** Cumulative play time in minutes. */
    @ColumnInfo(name = "play_time_minutes", defaultValue = "0")
    val playTimeMinutes: Long = 0,
) {
    val access: XodusProtocol.Access?
        get() = accessName?.let { name -> XodusProtocol.Access.entries.firstOrNull { it.name == name } }

    val containerId: String get() = "$CONTAINER_PREFIX$appId"

    companion object {
        const val CONTAINER_PREFIX = "XBOX_"
    }
}
