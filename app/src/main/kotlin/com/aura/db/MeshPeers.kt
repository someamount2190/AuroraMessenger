package com.aura.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Padding IPs from /find responses — future ShadowMesh bootstrap peers (Phase 3). */
@Entity(tableName = "mesh_peers")
data class MeshPeerEntity(
    @PrimaryKey val ipPort: String,
    val ip: String,
    val port: Int,
    val firstSeenMs: Long,
    val lastSeenMs: Long
)

@Dao
interface MeshPeerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(peers: List<MeshPeerEntity>)

    @Query("SELECT * FROM mesh_peers ORDER BY lastSeenMs DESC")
    suspend fun all(): List<MeshPeerEntity>

    @Query("SELECT COUNT(*) FROM mesh_peers")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM mesh_peers")
    suspend fun deleteAll()
}
