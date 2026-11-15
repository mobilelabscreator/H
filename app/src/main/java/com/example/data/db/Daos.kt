package com.example.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_sessions ORDER BY updatedAt DESC")
    fun observeSessions(): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_sessions ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getLatestSession(): ChatSessionEntity?

    @Insert
    suspend fun insertSession(session: ChatSessionEntity): Long

    @Query("UPDATE chat_sessions SET title = :title, updatedAt = :updatedAt WHERE id = :sessionId")
    suspend fun updateSessionTitle(sessionId: Long, title: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE chat_sessions SET updatedAt = :updatedAt WHERE id = :sessionId")
    suspend fun touchSession(sessionId: Long, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM chat_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: Long)

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY createdAt ASC, id ASC")
    fun observeMessages(sessionId: Long): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY createdAt ASC, id ASC")
    suspend fun getMessages(sessionId: Long): List<ChatMessageEntity>

    @Insert
    suspend fun insertMessage(message: ChatMessageEntity): Long

    @Query("UPDATE chat_messages SET content = :content, citationsJson = :citationsJson WHERE id = :messageId")
    suspend fun updateMessageContent(messageId: Long, content: String, citationsJson: String)

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun clearSessionMessages(sessionId: Long)
}

@Dao
interface SourceDao {
    @Query("SELECT * FROM sources ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources WHERE enabled = 1 ORDER BY createdAt ASC")
    suspend fun getEnabledSources(): List<SourceEntity>

    @Query("SELECT * FROM sources")
    suspend fun getAll(): List<SourceEntity>

    @Query("SELECT * FROM sources WHERE id = :id")
    suspend fun getById(id: Long): SourceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(source: SourceEntity): Long

    @Update
    suspend fun update(source: SourceEntity)

    @Delete
    suspend fun delete(source: SourceEntity)

    @Query("UPDATE sources SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE sources SET status = :status, lastError = :lastError WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, lastError: String?)

    @Query("UPDATE sources SET status = :status, lastReadAt = :lastReadAt, entryCount = :entryCount, lastError = :lastError WHERE id = :id")
    suspend fun markReadResult(
        id: Long,
        status: String,
        lastReadAt: Long,
        entryCount: Int,
        lastError: String?,
    )
}

@Dao
interface KnowledgeDao {
    @Query("SELECT * FROM knowledge_entries ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<KnowledgeEntryEntity>>

    @Query("SELECT * FROM knowledge_entries ORDER BY updatedAt DESC")
    suspend fun getAll(): List<KnowledgeEntryEntity>

    @Query("SELECT COUNT(*) FROM knowledge_entries")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM knowledge_entries WHERE id = :id")
    suspend fun getById(id: Long): KnowledgeEntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: KnowledgeEntryEntity): Long

    @Update
    suspend fun update(entry: KnowledgeEntryEntity)

    @Delete
    suspend fun delete(entry: KnowledgeEntryEntity)

    @Query("DELETE FROM knowledge_entries WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface SkillDao {
    @Query("SELECT * FROM skills ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<SkillEntity>>

    @Query("SELECT * FROM skills ORDER BY name ASC")
    suspend fun getAll(): List<SkillEntity>

    @Query("SELECT * FROM skills WHERE slug = :slug LIMIT 1")
    suspend fun getBySlug(slug: String): SkillEntity?

    @Query("SELECT * FROM skills WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): SkillEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSkill(skill: SkillEntity): Long

    @Update
    suspend fun updateSkill(skill: SkillEntity)

    @Delete
    suspend fun deleteSkill(skill: SkillEntity)

    @Query("SELECT * FROM skill_versions WHERE skillId = :skillId ORDER BY version DESC")
    fun observeVersions(skillId: Long): Flow<List<SkillVersionEntity>>

    @Query("SELECT * FROM skill_versions WHERE skillId = :skillId ORDER BY version DESC")
    suspend fun getVersions(skillId: Long): List<SkillVersionEntity>

    @Query("SELECT * FROM skill_versions WHERE skillId = :skillId AND isWorking = 1 ORDER BY version DESC LIMIT 1")
    suspend fun getLastWorkingVersion(skillId: Long): SkillVersionEntity?

    @Insert
    suspend fun insertVersion(version: SkillVersionEntity): Long

    @Query("UPDATE skill_versions SET isWorking = :isWorking WHERE id = :versionId")
    suspend fun setVersionWorking(versionId: Long, isWorking: Boolean)
}

@Dao
interface EvalDao {
    @Query("SELECT * FROM eval_questions ORDER BY isAutoGenerated ASC, id ASC")
    fun observeAll(): Flow<List<EvalQuestionEntity>>

    @Query("SELECT * FROM eval_questions ORDER BY isAutoGenerated ASC, id ASC")
    suspend fun getAll(): List<EvalQuestionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(question: EvalQuestionEntity): Long

    @Update
    suspend fun update(question: EvalQuestionEntity)

    @Delete
    suspend fun delete(question: EvalQuestionEntity)

    @Query("UPDATE eval_questions SET lastScore = :score, lastResponse = :response, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateResult(id: Long, score: Float, response: String, updatedAt: Long = System.currentTimeMillis())
}

@Dao
interface AgentLogDao {
    @Query("SELECT * FROM agent_logs ORDER BY id DESC LIMIT 400")
    fun observeRecentLogs(): Flow<List<AgentLogEntity>>

    @Insert
    suspend fun insert(log: AgentLogEntity): Long

    @Query("DELETE FROM agent_logs")
    suspend fun clearAll()
}

@Dao
interface ImprovementProposalDao {
    @Query("SELECT * FROM improvement_proposals ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ImprovementProposalEntity>>

    @Query("SELECT * FROM improvement_proposals WHERE status = 'PENDING' ORDER BY createdAt DESC")
    fun observePending(): Flow<List<ImprovementProposalEntity>>

    @Query("SELECT * FROM improvement_proposals WHERE id = :id")
    suspend fun getById(id: Long): ImprovementProposalEntity?

    @Insert
    suspend fun insert(proposal: ImprovementProposalEntity): Long

    @Query("UPDATE improvement_proposals SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)
}

@Dao
interface GitHubPrDao {
    @Query("SELECT * FROM github_prs ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<GitHubPrEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(pr: GitHubPrEntity): Long

    @Update
    suspend fun update(pr: GitHubPrEntity)
}

@Dao
interface BrainDao {
    @Query("SELECT COUNT(*) FROM brain_transitions")
    fun observeTransitionCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM brain_transitions")
    suspend fun getTransitionCount(): Int

    @Query("SELECT * FROM brain_transitions WHERE contextKey = :contextKey ORDER BY weight DESC LIMIT :limit")
    suspend fun getTopNextTokens(contextKey: String, limit: Int = 12): List<BrainTransitionEntity>

    @Query("SELECT * FROM brain_transitions WHERE contextKey = :contextKey AND nextToken = :nextToken LIMIT 1")
    suspend fun findTransition(contextKey: String, nextToken: String): BrainTransitionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<BrainTransitionEntity>)

    @Query("DELETE FROM brain_transitions")
    suspend fun clearAll()
}

