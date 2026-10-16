package com.jacobrobertson.lightchat.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "profile")
data class ProfileEntity(
    @PrimaryKey val slot: Int = 1,
    val subject: String,
    val email: String,
)

@Entity(tableName = "spaces")
data class SpaceEntity(
    @PrimaryKey val name: String,
    val title: String,
    val type: String,
    val accessible: Boolean = true,
    val syncedAt: Long? = null,
)

@Entity(tableName = "messages", indices = [Index(value = ["space", "createdAt"])])
data class MessageEntity(
    @PrimaryKey val name: String,
    val space: String,
    val thread: String,
    val sender: String,
    val text: String,
    val createdAt: Long,
)

@Entity(tableName = "outbox", indices = [Index("space")])
data class OutboxEntity(
    @PrimaryKey val id: String,
    val space: String,
    val thread: String?,
    val text: String,
    val createdAt: Long,
    val error: String? = null,
)

@Dao
interface ChatDao {
    @Query("SELECT * FROM profile WHERE slot = 1")
    fun observeProfile(): Flow<ProfileEntity?>

    @Query("SELECT * FROM profile WHERE slot = 1")
    suspend fun profile(): ProfileEntity?

    @Query("SELECT * FROM spaces ORDER BY title COLLATE NOCASE, name")
    fun observeSpaces(): Flow<List<SpaceEntity>>

    @Query("SELECT * FROM messages ORDER BY createdAt, name")
    fun observeMessages(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM outbox ORDER BY createdAt, id")
    fun observeOutbox(): Flow<List<OutboxEntity>>

    @Query("SELECT * FROM outbox ORDER BY createdAt, id")
    suspend fun pending(): List<OutboxEntity>

    @Query("SELECT * FROM spaces WHERE name = :name")
    suspend fun space(name: String): SpaceEntity?

    @Upsert suspend fun saveProfile(profile: ProfileEntity)
    @Upsert suspend fun saveSpaces(spaces: List<SpaceEntity>)
    @Upsert suspend fun saveMessages(messages: List<MessageEntity>)
    @Upsert suspend fun savePending(message: OutboxEntity)

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun deletePending(id: String)

    @Query("DELETE FROM messages WHERE space = :space")
    suspend fun deleteMessages(space: String)

    @Query("UPDATE spaces SET accessible = 0")
    suspend fun markSpacesUnavailable()

    @Query("DELETE FROM messages WHERE space IN (SELECT name FROM spaces WHERE accessible = 0)")
    suspend fun removeInaccessibleMessages()

    @Query("UPDATE spaces SET syncedAt = :time WHERE name = :space")
    suspend fun setSyncedAt(space: String, time: Long)

    @Query("DELETE FROM outbox") suspend fun clearOutbox()
    @Query("DELETE FROM messages") suspend fun clearMessages()
    @Query("DELETE FROM spaces") suspend fun clearSpaces()
    @Query("DELETE FROM profile") suspend fun clearProfile()
}

@Database(
    entities = [ProfileEntity::class, SpaceEntity::class, MessageEntity::class, OutboxEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
}
