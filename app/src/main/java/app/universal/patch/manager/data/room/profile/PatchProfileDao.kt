package app.universal.patch.manager.data.room.profile

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface PatchProfileDao {
    @Query("SELECT * FROM patch_profiles ORDER BY sort_order ASC, uid DESC")
    fun observeAll(): Flow<List<PatchProfileEntity>>

    @Query("SELECT * FROM patch_profiles WHERE package_name = :packageName ORDER BY sort_order ASC, uid DESC")
    fun observeForPackage(packageName: String): Flow<List<PatchProfileEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: PatchProfileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(profiles: List<PatchProfileEntity>)

    @Query("SELECT * FROM patch_profiles ORDER BY sort_order ASC, uid DESC")
    suspend fun getAll(): List<PatchProfileEntity>

    @Query("SELECT * FROM patch_profiles WHERE package_name = :packageName AND name = :name LIMIT 1")
    suspend fun findByPackageAndName(packageName: String, name: String): PatchProfileEntity?

    @Query("DELETE FROM patch_profiles WHERE uid = :uid")
    suspend fun delete(uid: Int)

    @Query("DELETE FROM patch_profiles WHERE uid IN (:uids)")
    suspend fun delete(uids: List<Int>)

    @Query("SELECT * FROM patch_profiles WHERE uid = :uid")
    suspend fun get(uid: Int): PatchProfileEntity?

    @Query("UPDATE patch_profiles SET payload = :payload WHERE uid = :uid")
    suspend fun updatePayload(uid: Int, payload: PatchProfilePayload)

    /** Apply a background remap only while its inspected payload is still current. */
    @Transaction
    suspend fun updateRemappedPayload(
        uid: Int,
        expectedPayload: PatchProfilePayload,
        payload: PatchProfilePayload
    ): Boolean {
        val current = get(uid) ?: return false
        if (current.payload != expectedPayload) return false
        updatePayload(uid, payload)
        return true
    }

    @Query("SELECT MAX(sort_order) FROM patch_profiles")
    suspend fun getMaxSortOrder(): Int?

    @Query("UPDATE patch_profiles SET sort_order = :sortOrder WHERE uid = :uid")
    suspend fun updateSortOrder(uid: Int, sortOrder: Int)
}
