package com.yuanbao.minioperit.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val personaId: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(
        entity = SessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("sessionId")]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val role: String,
    val content: String,
    /** 是否已被折叠进摘要（不再进入上下文） */
    val summarized: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "personas")
data class PersonaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val systemPrompt: String,
    val isDefault: Boolean = false
)

@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val summary: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface ChatDao {
    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC")
    fun sessions(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM messages WHERE sessionId = :sid ORDER BY createdAt ASC")
    fun messages(sid: Long): Flow<List<MessageEntity>>

    @Insert fun insertSession(s: SessionEntity): Long
    @Insert fun insertMessage(m: MessageEntity): Long
    @Update fun updateSession(s: SessionEntity)

    @Query("DELETE FROM sessions WHERE id = :id")
    fun deleteSession(id: Long)

    @Query("UPDATE messages SET summarized = 1 WHERE sessionId = :sid AND id <= :upTo")
    fun markSummarized(sid: Long, upTo: Long)

    @Query("SELECT * FROM personas ORDER BY isDefault DESC, id ASC")
    fun personas(): Flow<List<PersonaEntity>>
    @Insert fun insertPersona(p: PersonaEntity): Long
    @Update fun updatePersona(p: PersonaEntity)
    @Query("DELETE FROM personas WHERE id = :id")
    fun deletePersona(id: Long)

    @Query("SELECT * FROM memories WHERE sessionId = :sid ORDER BY createdAt DESC LIMIT 1")
    suspend fun latestMemory(sid: Long): MemoryEntity?
    @Insert fun insertMemory(m: MemoryEntity)
}

@Database(
    entities = [SessionEntity::class, MessageEntity::class, PersonaEntity::class, MemoryEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): ChatDao

    companion object {
        @Volatile private var inst: AppDatabase? = null
        fun get(ctx: Context): AppDatabase = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx, AppDatabase::class.java, "minioperit.db")
                .fallbackToDestructiveMigration()
                .build().also { inst = it }
        }
    }
}

/**
 * 记忆管理：对话变长时把早期内容压缩成摘要。
 *
 * 这是防止上下文爆炸的第二道防线——PromptBuilder 是"硬裁剪"，
 * 这里做的是"软压缩"，尽量保留信息量。
 */
class MemoryManager(private val dao: ChatDao) {

    /** 超过这个条数就触发压缩 */
    private val triggerCount = 20
    /** 压缩时保留最近这么多条原文 */
    private val keepRecent = 6

    suspend fun maybeCompress(sessionId: Long): String? {
        // 取一次快照：Room 的 Flow 第一次发射就是当前全量数据
        val all = dao.messages(sessionId).first()
        if (all.size < triggerCount) return null

        val cutIndex = all.size - keepRecent
        val old = all.take(cutIndex)
        if (old.isEmpty()) return null

        val existing = dao.latestMemory(sessionId)?.summary.orEmpty()
        val digest = old.joinToString("\n") { "${it.role}: ${it.content.take(200)}" }
        val newSummary = buildString {
            if (existing.isNotBlank()) appendLine(existing)
            appendLine("【${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())} 的对话摘要】")
            appendLine(digest.take(4000))
        }.trim()

        dao.insertMemory(MemoryEntity(sessionId = sessionId, summary = newSummary))
        dao.markSummarized(sessionId, old.last().id)
        return newSummary
    }
}
