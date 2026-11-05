package com.vibe.app.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * RoomDB: the offline cache and sync queue required by the PoE.
 *
 * Groups, members, activities, decisions, votes, plans, memories and the
 * notification inbox are all cached here, and every offline mutation leaves a
 * row in [SyncItemEntity] so nothing is lost while the device is disconnected.
 * Timestamps are epoch milliseconds so a queued action keeps the exact time the
 * member acted, no matter when it eventually reaches the API.
 */

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val username: String,
    val email: String,
    val language: String,
    val themeMode: String,
    val notificationsEnabled: Boolean,
    val privacyMembersOnly: Boolean,
    val photoUri: String?,
    val createdAt: Long,
)

@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val icon: String,
    val inviteCode: String,
    val ownerId: String,
    val createdAt: Long,
    val role: String,
    val memberCount: Int,
    val activityCount: Int,
    val syncState: String,
    val cachedAt: Long,
)

@Entity(tableName = "group_members", primaryKeys = ["groupId", "userId"])
data class GroupMemberEntity(
    val groupId: String,
    val userId: String,
    val displayName: String,
    val role: String,
    val photoUri: String?,
    val joinedAt: Long,
)

@Entity(tableName = "activities")
data class ActivityEntity(
    @PrimaryKey val id: String,
    val groupId: String,
    val title: String,
    val description: String,
    val icon: String,
    val status: String,
    val createdBy: String,
    val createdByName: String,
    val createdAt: Long,
    val favourite: Boolean,
    val yesVotes: Int,
    val participantCount: Int,
    val syncState: String,
)

@Entity(tableName = "decisions")
data class DecisionEntity(
    @PrimaryKey val id: String,
    val groupId: String,
    val roundNumber: Int,
    val state: String,
    val deadlineEpochMillis: Long?,
    val winnerActivityId: String?,
    val startedBy: String,
    val startedAt: Long,
    val talliesCsv: String,
    val survivorIdsCsv: String,
    val eliminatedIdsCsv: String,
    val roundActivityIdsCsv: String,
)

@Entity(tableName = "votes", primaryKeys = ["decisionId", "activityId", "userId"])
data class VoteEntity(
    val decisionId: String,
    val activityId: String,
    val userId: String,
    val voteId: String,
    val choice: Boolean,
    val epochMillis: Long,
    val syncState: String,
)

@Entity(tableName = "plans")
data class PlanEntity(
    @PrimaryKey val id: String,
    val groupId: String,
    val activityId: String,
    val activityTitle: String,
    val activityIcon: String,
    val scheduledAtEpochMillis: Long?,
    val completed: Boolean,
    val createdBy: String,
)

@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey val id: String,
    val groupId: String,
    val planId: String,
    val activityId: String,
    val activityTitle: String,
    val activityIcon: String,
    val completedAtEpochMillis: Long,
    val caption: String,
    val rating: Int,
    val photoUrisCsv: String,
    val createdBy: String,
    val syncState: String,
)

@Entity(tableName = "notifications")
data class NotificationEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val title: String,
    val body: String,
    val type: String,
    val read: Boolean,
    val createdAt: Long,
    val groupId: String?,
)

/** One queued offline action. `state` is a [com.vibe.app.domain.SyncState]. */
@Entity(tableName = "sync_queue")
data class SyncItemEntity(
    @PrimaryKey val id: String,
    val action: String,
    val entityId: String,
    val payloadJson: String,
    val queuedAt: Long,
    val state: String,
    val attempts: Int,
    val lastError: String?,
    val serverPayloadJson: String?,
)

@Dao
interface UserDao {
    @Query("SELECT * FROM users WHERE id = :userId LIMIT 1")
    fun observeById(userId: String): Flow<UserEntity?>

    @Query("SELECT * FROM users WHERE id = :userId LIMIT 1")
    suspend fun byId(userId: String): UserEntity?

    @Query("SELECT * FROM users WHERE email = :email COLLATE NOCASE LIMIT 1")
    suspend fun byEmail(email: String): UserEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(user: UserEntity)

    @Query("DELETE FROM users")
    suspend fun clear()
}

@Dao
interface GroupDao {
    @Query("SELECT * FROM groups ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM groups WHERE id = :groupId LIMIT 1")
    fun observeById(groupId: String): Flow<GroupEntity?>

    @Query("SELECT * FROM groups WHERE id = :groupId LIMIT 1")
    suspend fun byId(groupId: String): GroupEntity?

    @Query("SELECT * FROM groups WHERE inviteCode = :code LIMIT 1")
    suspend fun byInviteCode(code: String): GroupEntity?

    @Query("SELECT * FROM groups")
    suspend fun all(): List<GroupEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(groups: List<GroupEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(group: GroupEntity)

    @Query("UPDATE groups SET memberCount = :members, activityCount = :activities WHERE id = :groupId")
    suspend fun updateCounts(groupId: String, members: Int, activities: Int)

    @Query("DELETE FROM groups WHERE id = :groupId")
    suspend fun delete(groupId: String)

    @Query("SELECT * FROM group_members WHERE groupId = :groupId ORDER BY joinedAt ASC")
    fun observeMembers(groupId: String): Flow<List<GroupMemberEntity>>

    @Query("SELECT * FROM group_members WHERE groupId = :groupId ORDER BY joinedAt ASC")
    suspend fun members(groupId: String): List<GroupMemberEntity>

    @Query("SELECT * FROM group_members WHERE userId = :userId")
    suspend fun membershipsOf(userId: String): List<GroupMemberEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMembers(members: List<GroupMemberEntity>)

    @Query("DELETE FROM group_members WHERE groupId = :groupId AND userId = :userId")
    suspend fun deleteMember(groupId: String, userId: String)

    @Query("SELECT COUNT(*) FROM group_members WHERE groupId = :groupId")
    suspend fun memberCount(groupId: String): Int
}

@Dao
interface ActivityDao {
    @Query("SELECT * FROM activities WHERE groupId = :groupId ORDER BY createdAt DESC")
    fun observeByGroup(groupId: String): Flow<List<ActivityEntity>>

    @Query("SELECT * FROM activities ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ActivityEntity>>

    @Query("SELECT * FROM activities WHERE createdBy = :userId ORDER BY createdAt DESC")
    fun observeMine(userId: String): Flow<List<ActivityEntity>>

    @Query("SELECT * FROM activities WHERE createdBy = :userId ORDER BY createdAt DESC")
    suspend fun mine(userId: String): List<ActivityEntity>

    @Query("SELECT * FROM activities WHERE id = :activityId LIMIT 1")
    fun observeById(activityId: String): Flow<ActivityEntity?>

    @Query("SELECT * FROM activities WHERE id = :activityId LIMIT 1")
    suspend fun byId(activityId: String): ActivityEntity?

    @Query("SELECT * FROM activities WHERE groupId = :groupId")
    suspend fun byGroup(groupId: String): List<ActivityEntity>

    @Query("SELECT * FROM activities WHERE status IN ('SUGGESTED', 'ACTIVE') AND groupId = :groupId ORDER BY createdAt ASC")
    suspend fun eligibleForRound(groupId: String): List<ActivityEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(activities: List<ActivityEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(activity: ActivityEntity)

    @Query("UPDATE activities SET status = :status WHERE id = :activityId")
    suspend fun updateStatus(activityId: String, status: String)

    @Query("UPDATE activities SET favourite = :favourite WHERE id = :activityId")
    suspend fun updateFavourite(activityId: String, favourite: Boolean)

    @Query("UPDATE activities SET syncState = :state WHERE id = :activityId")
    suspend fun updateSyncState(activityId: String, state: String)

    @Query("DELETE FROM activities WHERE id = :activityId")
    suspend fun delete(activityId: String)

    @Query("DELETE FROM activities WHERE groupId = :groupId")
    suspend fun deleteByGroup(groupId: String)

    @Query("SELECT COUNT(*) FROM activities WHERE createdBy = :userId")
    suspend fun countByUser(userId: String): Int

    @Query("SELECT COUNT(*) FROM activities WHERE groupId = :groupId")
    suspend fun countByGroup(groupId: String): Int
}

@Dao
interface DecisionDao {
    @Query("SELECT * FROM decisions WHERE id = :decisionId LIMIT 1")
    fun observeById(decisionId: String): Flow<DecisionEntity?>

    @Query("SELECT * FROM decisions WHERE id = :decisionId LIMIT 1")
    suspend fun byId(decisionId: String): DecisionEntity?

    @Query("SELECT * FROM decisions WHERE groupId = :groupId ORDER BY startedAt DESC")
    fun observeByGroup(groupId: String): Flow<List<DecisionEntity>>

    @Query("SELECT * FROM decisions WHERE groupId = :groupId ORDER BY startedAt DESC LIMIT 1")
    suspend fun latestForGroup(groupId: String): DecisionEntity?

    @Query("SELECT * FROM decisions WHERE groupId = :groupId AND state = 'OPEN' ORDER BY startedAt DESC LIMIT 1")
    fun observeLatestOpen(groupId: String): Flow<DecisionEntity?>

    @Query("SELECT id FROM decisions WHERE groupId = :groupId AND state = 'OPEN' ORDER BY startedAt DESC LIMIT 1")
    suspend fun latestOpenId(groupId: String): String?

    @Query("SELECT * FROM decisions WHERE groupId = :groupId AND state = 'SETTLED' ORDER BY startedAt DESC LIMIT 1")
    fun observeLatestSettled(groupId: String): Flow<DecisionEntity?>

    @Query("SELECT COUNT(*) FROM decisions WHERE startedBy = :userId")
    suspend fun countByUser(userId: String): Int

    @Query("SELECT COUNT(*) FROM decisions d INNER JOIN group_members m ON m.groupId = d.groupId WHERE m.userId = :userId")
    suspend fun countForMember(userId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(decision: DecisionEntity)
}

@Dao
interface VoteDao {
    @Query("SELECT * FROM votes WHERE decisionId = :decisionId")
    fun observeByDecision(decisionId: String): Flow<List<VoteEntity>>

    @Query("SELECT * FROM votes WHERE decisionId = :decisionId")
    suspend fun byDecision(decisionId: String): List<VoteEntity>

    @Query("SELECT * FROM votes WHERE decisionId = :decisionId AND activityId = :activityId AND userId = :userId LIMIT 1")
    suspend fun find(decisionId: String, activityId: String, userId: String): VoteEntity?

    @Query("SELECT * FROM votes WHERE syncState != 'SYNCED'")
    suspend fun pending(): List<VoteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(vote: VoteEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(votes: List<VoteEntity>)

    @Query("UPDATE votes SET syncState = :state WHERE decisionId = :decisionId AND activityId = :activityId AND userId = :userId")
    suspend fun updateSyncState(decisionId: String, activityId: String, userId: String, state: String)

    @Query("SELECT COUNT(*) FROM votes WHERE userId = :userId")
    suspend fun countByUser(userId: String): Int
}

@Dao
interface PlanDao {
    @Query("SELECT * FROM plans WHERE groupId = :groupId ORDER BY COALESCE(scheduledAtEpochMillis, 0) DESC")
    fun observeByGroup(groupId: String): Flow<List<PlanEntity>>

    @Query("SELECT * FROM plans WHERE id = :planId LIMIT 1")
    suspend fun byId(planId: String): PlanEntity?

    @Query("SELECT * FROM plans WHERE activityId = :activityId ORDER BY COALESCE(scheduledAtEpochMillis, 0) DESC LIMIT 1")
    suspend fun latestForActivity(activityId: String): PlanEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(plan: PlanEntity)
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories ORDER BY completedAtEpochMillis DESC")
    fun observeAll(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE groupId = :groupId ORDER BY completedAtEpochMillis DESC")
    fun observeByGroup(groupId: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE id = :memoryId LIMIT 1")
    fun observeById(memoryId: String): Flow<MemoryEntity?>

    @Query("SELECT * FROM memories WHERE id = :memoryId LIMIT 1")
    suspend fun byId(memoryId: String): MemoryEntity?

    @Query("SELECT * FROM memories WHERE syncState != 'SYNCED'")
    suspend fun pending(): List<MemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(memories: List<MemoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(memory: MemoryEntity)

    @Query("UPDATE memories SET syncState = :state WHERE id = :memoryId")
    suspend fun updateSyncState(memoryId: String, state: String)

    @Query("SELECT COUNT(*) FROM memories")
    suspend fun count(): Int
}

@Dao
interface NotificationDao {
    @Query("SELECT * FROM notifications WHERE id = :notificationId LIMIT 1")
    suspend fun byId(notificationId: String): NotificationEntity?

    @Query("SELECT * FROM notifications ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<NotificationEntity>>

    @Query("SELECT COUNT(*) FROM notifications WHERE read = 0")
    fun observeUnreadCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(notifications: List<NotificationEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(notification: NotificationEntity)

    @Query("UPDATE notifications SET read = 1 WHERE id = :notificationId")
    suspend fun markRead(notificationId: String)

    @Query("UPDATE notifications SET read = 1")
    suspend fun markAllRead()
}

@Dao
interface SyncQueueDao {
    @Query("SELECT * FROM sync_queue ORDER BY queuedAt ASC")
    fun observeAll(): Flow<List<SyncItemEntity>>

    @Query("SELECT * FROM sync_queue WHERE state = 'PENDING' ORDER BY queuedAt ASC")
    suspend fun pending(): List<SyncItemEntity>

    @Query("SELECT * FROM sync_queue ORDER BY queuedAt ASC")
    suspend fun all(): List<SyncItemEntity>

    @Query("SELECT COUNT(*) FROM sync_queue")
    suspend fun count(): Int

    @Query("SELECT * FROM sync_queue WHERE state = 'CONFLICT' ORDER BY queuedAt ASC")
    fun observeConflicts(): Flow<List<SyncItemEntity>>

    @Query("SELECT COUNT(*) FROM sync_queue WHERE state = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT * FROM sync_queue WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): SyncItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: SyncItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(items: List<SyncItemEntity>)

    @Update
    suspend fun update(item: SyncItemEntity)

    @Query("UPDATE sync_queue SET state = :state, lastError = :error, attempts = attempts + 1 WHERE id = :id")
    suspend fun markState(id: String, state: String, error: String?)

    @Query("UPDATE sync_queue SET state = :state, serverPayloadJson = :serverPayload WHERE id = :id")
    suspend fun markConflict(id: String, serverPayload: String?, state: String = "CONFLICT")

    @Query("DELETE FROM sync_queue WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM sync_queue WHERE id = :id AND state = 'PENDING'")
    suspend fun deleteIfPending(id: String)

    @Query("DELETE FROM sync_queue WHERE state = 'SYNCED'")
    suspend fun clearSynced()
}

@Database(
    entities = [
        UserEntity::class,
        GroupEntity::class,
        GroupMemberEntity::class,
        ActivityEntity::class,
        DecisionEntity::class,
        VoteEntity::class,
        PlanEntity::class,
        MemoryEntity::class,
        NotificationEntity::class,
        SyncItemEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class VibeDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun groupDao(): GroupDao
    abstract fun activityDao(): ActivityDao
    abstract fun decisionDao(): DecisionDao
    abstract fun voteDao(): VoteDao
    abstract fun planDao(): PlanDao
    abstract fun memoryDao(): MemoryDao
    abstract fun notificationDao(): NotificationDao
    abstract fun syncQueueDao(): SyncQueueDao
}
