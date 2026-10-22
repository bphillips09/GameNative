package app.gamenative.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import app.gamenative.data.XboxGame
import kotlinx.coroutines.flow.Flow

/**
 * DAO for Xbox games in the Room database.
 */
@Dao
interface XboxGameDao {

    /** Inserts a new game and returns its row ID, which is its [XboxGame.appId]. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(game: XboxGame): Long

    @Update
    suspend fun update(game: XboxGame)

    @Query("SELECT * FROM xbox_games WHERE product_id = :productId")
    suspend fun getByProductId(productId: String): XboxGame?

    @Query("SELECT * FROM xbox_games WHERE app_id = :appId")
    suspend fun getByAppId(appId: Int): XboxGame?

    @Query("SELECT * FROM xbox_games ORDER BY title COLLATE NOCASE ASC")
    fun getAll(): Flow<List<XboxGame>>

    @Query("SELECT * FROM xbox_games ORDER BY title COLLATE NOCASE ASC")
    suspend fun getAllAsList(): List<XboxGame>

    /** Returns all installed Xbox games sorted by title. */
    @Query("SELECT * FROM xbox_games WHERE is_installed = 1 ORDER BY title COLLATE NOCASE ASC")
    suspend fun getInstalledGames(): List<XboxGame>

    @Query("SELECT COUNT(*) FROM xbox_games")
    suspend fun count(): Int

    @Query("SELECT * FROM xbox_games WHERE product_id IN (:productIds)")
    suspend fun getGamesByProductIds(productIds: List<String>): List<XboxGame>

    /**
     * Inserts or refreshes a product's catalog and entitlement fields, keeping its app ID,
     * install state and play stats. Returns the stored row.
     */
    @Transaction
    suspend fun upsertFromLibrary(game: XboxGame): XboxGame {
        val existing = getByProductId(game.productId)
        if (existing == null) {
            val appId = insert(game.copy(appId = 0)).toInt()
            return game.copy(appId = appId)
        }
        val merged = existing.copy(
            title = game.title,
            boxArt = game.boxArt ?: existing.boxArt,
            hero = game.hero ?: existing.hero,
            publisher = game.publisher ?: existing.publisher,
            accessName = game.accessName,
            isPlayAnywhere = game.isPlayAnywhere,
            hasPcPackage = game.hasPcPackage,
            titleId = existing.titleId ?: game.titleId,
        )
        update(merged)
        return merged
    }

    /** Removes every game that is not installed (sign-out keeps installed games and their IDs). */
    @Query("DELETE FROM xbox_games WHERE is_installed = 0")
    suspend fun deleteAllNonInstalledGames()

    /** Clears entitlement for products the account can no longer play, except installed ones. */
    @Query("UPDATE xbox_games SET access = NULL WHERE is_installed = 0 AND product_id NOT IN (:playable)")
    suspend fun clearAccessExcept(playable: List<String>)
}
