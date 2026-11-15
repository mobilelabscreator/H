package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ChatSessionEntity::class,
        ChatMessageEntity::class,
        SourceEntity::class,
        KnowledgeEntryEntity::class,
        SkillEntity::class,
        SkillVersionEntity::class,
        EvalQuestionEntity::class,
        AgentLogEntity::class,
        ImprovementProposalEntity::class,
        GitHubPrEntity::class,
        BrainTransitionEntity::class,
        TrainingCheckpointEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class AgentLabDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun sourceDao(): SourceDao
    abstract fun knowledgeDao(): KnowledgeDao
    abstract fun skillDao(): SkillDao
    abstract fun evalDao(): EvalDao
    abstract fun agentLogDao(): AgentLogDao
    abstract fun improvementProposalDao(): ImprovementProposalDao
    abstract fun gitHubPrDao(): GitHubPrDao
    abstract fun brainDao(): BrainDao
    abstract fun trainingDao(): TrainingDao

    companion object {
        fun build(context: Context): AgentLabDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AgentLabDatabase::class.java,
                "agent_lab.db",
            )
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
