package com.vibe.app.data.firebase

import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.QueryDocumentSnapshot
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.WriteBatch
import com.vibe.app.domain.Activity
import com.vibe.app.domain.ActivityStatus
import com.vibe.app.domain.Decision
import com.vibe.app.domain.DecisionState
import com.vibe.app.domain.Group
import com.vibe.app.domain.GroupMember
import com.vibe.app.domain.GroupRole
import com.vibe.app.domain.Memory
import com.vibe.app.domain.Plan
import com.vibe.app.domain.User
import com.vibe.app.domain.VibeNotification
import com.vibe.app.domain.Vote
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cloud Firestore provider.
 *
 * Replaces all /api/v1/* REST calls to the Node.js server. Reads and writes go
 * directly to Firestore, which handles offline caching and sync automatically
 * when persistence is enabled.
 *
 * Collection structure:
 *   /users/{uid}                      - profile
 *   /groups/{gid}                     - group doc: {name, icon, ownerId, memberIds: [], members: {uid: {joinedAt}}}
 *   /activities/{aid}                 - idea doc: {groupId, title, description, icon, status, createdBy, createdByName, createdAt, favouriteBy: []}
 *   /decisions/{did}                  - round doc: {groupId, roundNumber, state, activityIds: [], votes: {uid: [{activityId, choice, epochMillis, userId}]}, ...}
 *   /plans/{pid}                      - plan doc: {groupId, activityId, scheduledAtEpochMillis, completed, createdBy}
 *   /memories/{mid}                   - memory doc: {groupId, planId, activityId, caption, rating, photoUris: [], createdBy, completedAtEpochMillis}
 *   /notifications/{nid}              - inbox doc: {userId, groupId, title, body, type, read, createdAtEpochMillis}
 *   /invites/{code}                   - invite code -> groupId
 *   /devices/{deviceId}               - FCM registration token
 *
 * Vote document IDs are predictable: `{decisionId}:{activityId}:{userId}`.
 * This makes it trivial to enforce "one vote per member per idea" in security rules
 * and to detect duplicate votes client-side.
 */
class FirestoreProvider(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    init {
        // Enable offline persistence so the app keeps working without a connection
        // and syncs automatically when it returns. This replaces the custom sync queue.
        val settings = FirebaseFirestoreSettings.Builder()
            .setPersistenceEnabled(true)
            .setCacheSizeBytes(FirebaseFirestoreSettings.CACHE_SIZE_UNLIMITED)
            .build()
        firestore.firestoreSettings = settings
    }

    // ------------------------------------------------------------------ users

    suspend fun setUserProfile(user: User) = withContext(io) {
        firestore.collection("users").document(user.id).set(
            mapOf(
                "id" to user.id,
                "displayName" to user.displayName,
                "username" to user.username,
                "email" to user.email,
                "language" to user.language.tag,
                "themeMode" to user.themeMode.name,
                "notificationsEnabled" to user.notificationsEnabled,
                "privacyMembersOnly" to user.privacyMembersOnly,
                "photoUri" to user.photoUri,
                "createdAt" to user.createdAt,
                "updatedAt" to System.currentTimeMillis(),
            ),
            SetOptions.merge(),
        ).await()
    }

    suspend fun getUserProfile(uid: String): User? = withContext(io) {
        val snap = firestore.collection("users").document(uid).get().await()
        if (!snap.exists) null else snap.toObject(User::class.java)!!
    }

    // ------------------------------------------------------------------ groups

    suspend fun createGroup(group: Group): Group = withContext(io) {
        val data = mutableMapOf<String, Any?>(
            "name" to group.name,
            "icon" to group.icon,
            "ownerId" to group.ownerId,
            "inviteCode" to group.inviteCode,
            "createdAt" to group.createdAt,
            "memberIds" to listOf(group.ownerId),
            "members" to mapOf(group.ownerId to mapOf("joinedAt" to group.createdAt)),
            "updatedAt" to System.currentTimeMillis(),
        )
        firestore.collection("groups").document(group.id).set(data).await()
        // Record the invite code lookup.
        firestore.collection("invites").document(group.inviteCode).set(mapOf("groupId" to group.id)).await()
        group
    }

    suspend fun joinGroup(groupId: String, uid: String, displayName: String): Group = withContext(io) {
        firestore.runTransaction { transaction ->
            val groupSnap = transaction.get(firestore.collection("groups").document(groupId))
            if (!groupSnap.exists) throw Exception("Group not found")
            val groupData = groupSnap.data ?: throw Exception("Group data missing")
            val memberIds = groupData["memberIds"] as? List<*> ?: emptyList()
            if (uid in memberIds) {
                throw Exception("Already a member")
            }
            val updatedMemberIds = memberIds + uid
            val updatedMembers = (groupData["members"] as? Map<*, *> ?: mutableMapOf()).toMutableMap()
            updatedMembers[uid] = mapOf("joinedAt" to System.currentTimeMillis(), "displayName" to displayName)
            transaction.update(groupSnap.reference, mapOf(
                "memberIds" to updatedMemberIds,
                "members" to updatedMembers,
                "updatedAt" to System.currentTimeMillis(),
            ))
            groupSnap.reference
        }.await()
        getGroup(groupId) ?: error("Group not found after joining")
    }

    suspend fun getGroup(groupId: String): Group? = withContext(io) {
        val snap = firestore.collection("groups").document(groupId).get().await()
        if (!snap.exists) null else snap.toObject(Group::class.java)!!
    }

    suspend fun getGroupsForUser(uid: String): List<Group> = withContext(io) {
        val snaps = firestore.collection("groups")
            .whereArrayContains("memberIds", uid)
            .get().await()
        snaps.mapNotNull { it.toObject(Group::class.java) }
    }

    suspend fun deleteGroup(groupId: String) = withContext(io) {
        firestore.collection("groups").document(groupId).delete().await()
        // Clean up related subcollections could be batched here.
    }

    suspend fun leaveGroup(groupId: String, uid: String) = withContext(io) {
        firestore.runTransaction { transaction ->
            val groupSnap = transaction.get(firestore.collection("groups").document(groupId))
            if (!groupSnap.exists) throw Exception("Group not found")
            val groupData = groupSnap.data ?: throw Exception("Group data missing")
            val ownerId = groupData["ownerId"] as? String ?: throw Exception("No owner")
            if (uid == ownerId) throw Exception("Owner cannot leave")
            val memberIds = (groupData["memberIds"] as? List<*> ?: emptyList()).filter { it != uid }
            val members = (groupData["members"] as? Map<*, *> ?: mutableMapOf()).toMutableMap()
            members.remove(uid)
            transaction.update(groupSnap.reference, mapOf(
                "memberIds" to memberIds,
                "members" to members,
                "updatedAt" to System.currentTimeMillis(),
            ))
            groupSnap.reference
        }.await()
    }

    // ------------------------------------------------------------------ activities

    suspend fun createActivity(activity: Activity): Activity = withContext(io) {
        val data = mutableMapOf<String, Any?>(
            "groupId" to activity.groupId,
            "title" to activity.title,
            "description" to activity.description,
            "icon" to activity.icon,
            "status" to activity.status.name,
            "createdBy" to activity.createdBy,
            "createdByName" to activity.createdByName,
            "createdAt" to activity.createdAt,
            "favouriteBy" to emptyList<String>(),
            "yesVotes" to activity.yesVotes,
            "participantCount" to activity.participantCount,
            "updatedAt" to System.currentTimeMillis(),
        )
        firestore.collection("activities").document(activity.id).set(data).await()
        activity
    }

    suspend fun updateActivity(activity: Activity): Activity = withContext(io) {
        firestore.collection("activities").document(activity.id).set(
            mapOf(
                "title" to activity.title,
                "description" to activity.description,
                "icon" to activity.icon,
                "status" to activity.status.name,
                "updatedAt" to System.currentTimeMillis(),
            ),
            SetOptions.merge(),
        ).await()
        activity
    }

    suspend fun deleteActivity(activityId: String) = withContext(io) {
        firestore.collection("activities").document(activityId).delete().await()
    }

    suspend fun getActivitiesForGroup(groupId: String): List<Activity> = withContext(io) {
        val snaps = firestore.collection("activities")
            .whereEqualTo("groupId", groupId)
            .get().await()
        snaps.mapNotNull { it.toObject(Activity::class.java) }
    }

    // ------------------------------------------------------------------ decisions / votes

    /**
     * Creates a new decision round. The round doc stores activityIds and an empty
     * votes map. Vote documents are written individually under /votes/{voteId}
     * where voteId = "{decisionId}:{activityId}:{userId}".
     */
    suspend fun createDecision(decision: Decision): Decision = withContext(io) {
        val data = mutableMapOf<String, Any?>(
            "groupId" to decision.groupId,
            "roundNumber" to decision.roundNumber,
            "state" to decision.state.name,
            "deadlineEpochMillis" to decision.deadlineEpochMillis,
            "winnerActivityId" to decision.winnerActivityId,
            "startedBy" to decision.startedBy,
            "startedAt" to decision.startedAt,
            "activityIds" to emptyList<String>(),
            "votes" to emptyMap<String, Any?>(),
            "updatedAt" to System.currentTimeMillis(),
        )
        firestore.collection("decisions").document(decision.id).set(data).await()
        decision
    }

    suspend fun getDecision(decisionId: String): Decision? = withContext(io) {
        val snap = firestore.collection("decisions").document(decisionId).get().await()
        if (!snap.exists) null else snap.toObject(Decision::class.java)!!
    }

    suspend fun updateDecision(decision: Decision) = withContext(io) {
        firestore.collection("decisions").document(decision.id).set(
            mapOf(
                "state" to decision.state.name,
                "winnerActivityId" to decision.winnerActivityId,
                "updatedAt" to System.currentTimeMillis(),
            ),
            SetOptions.merge(),
        ).await()
    }

    /** Cast a vote. Creates/overwrites the vote doc at the predictable ID. */
    suspend fun castVote(decisionId: String, activityId: String, userId: String, choice: Boolean, epochMillis: Long): Vote {
        val voteId = "$decisionId:$activityId:$userId"
        val vote = Vote(
            id = voteId,
            decisionId = decisionId,
            activityId = activityId,
            userId = userId,
            choice = choice,
            epochMillis = epochMillis,
        )
        withContext(io) {
            firestore.collection("votes").document(voteId).set(
                mapOf(
                    "decisionId" to vote.decisionId,
                    "activityId" to vote.activityId,
                    "userId" to vote.userId,
                    "choice" to vote.choice,
                    "epochMillis" to vote.epochMillis,
                    "createdAt" to System.currentTimeMillis(),
                )
            ).await()
        }
        return vote
    }

    suspend fun getVotesForDecision(decisionId: String): List<Vote> = withContext(io) {
        val snaps = firestore.collection("votes")
            .whereEqualTo("decisionId", decisionId)
            .get().await()
        snaps.mapNotNull { it.toObject(Vote::class.java) }
    }

    suspend fun getVote(decisionId: String, activityId: String, userId: String): Vote? = withContext(io) {
        val voteId = "$decisionId:$activityId:$userId"
        val snap = firestore.collection("votes").document(voteId).get().await()
        if (!snap.exists) null else snap.toObject(Vote::class.java)!!
    }

    // ------------------------------------------------------------------ plans and memories

    suspend fun savePlan(plan: Plan): Plan = withContext(io) {
        val data = mapOf<String, Any?>(
            "groupId" to plan.groupId,
            "activityId" to plan.activityId,
            "activityTitle" to plan.activityTitle,
            "activityIcon" to plan.activityIcon,
            "scheduledAtEpochMillis" to plan.scheduledAtEpochMillis,
            "completed" to plan.completed,
            "createdBy" to plan.createdBy,
            "updatedAt" to System.currentTimeMillis(),
        )
        firestore.collection("plans").document(plan.id).set(data, SetOptions.merge()).await()
        plan
    }

    suspend fun getPlansForGroup(groupId: String): List<Plan> = withContext(io) {
        val snaps = firestore.collection("plans")
            .whereEqualTo("groupId", groupId)
            .get().await()
        snaps.mapNotNull { it.toObject(Plan::class.java) }
    }

    suspend fun completePlan(planId: String, completed: Boolean) = withContext(io) {
        firestore.collection("plans").document(planId).update("completed", completed).await()
    }

    suspend fun saveMemory(memory: Memory): Memory = withContext(io) {
        val data = mapOf<String, Any?>(
            "groupId" to memory.groupId,
            "planId" to memory.planId,
            "activityId" to memory.activityId,
            "activityTitle" to memory.activityTitle,
            "activityIcon" to memory.activityIcon,
            "completedAtEpochMillis" to memory.completedAtEpochMillis,
            "caption" to memory.caption,
            "rating" to memory.rating,
            "photoUris" to memory.photoUris,
            "createdBy" to memory.createdBy,
            "updatedAt" to System.currentTimeMillis(),
        )
        firestore.collection("memories").document(memory.id).set(data, SetOptions.merge()).await()
        memory
    }

    suspend fun getMemoriesForGroup(groupId: String): List<Memory> = withContext(io) {
        val snaps = firestore.collection("memories")
            .whereEqualTo("groupId", groupId)
            .get().await()
        snaps.mapNotNull { it.toObject(Memory::class.java) }
    }

    // ------------------------------------------------------------------ notifications

    suspend fun saveNotification(notification: VibeNotification) = withContext(io) {
        val data = mapOf<String, Any?>(
            "userId" to notification.userId,
            "groupId" to notification.groupId,
            "title" to notification.title,
            "body" to notification.body,
            "type" to notification.type.name,
            "read" to notification.read,
            "createdAtEpochMillis" to notification.createdAt,
            "updatedAt" to System.currentTimeMillis(),
        )
        firestore.collection("notifications").document(notification.id).set(data).await()
    }

    suspend fun getNotificationsForUser(uid: String): List<VibeNotification> = withContext(io) {
        val snaps = firestore.collection("notifications")
            .whereEqualTo("userId", uid)
            .orderBy("createdAtEpochMillis", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(100)
            .get().await()
        snaps.mapNotNull { it.toObject(VibeNotification::class.java) }
    }

    suspend fun markNotificationRead(notificationId: String, read: Boolean) = withContext(io) {
        firestore.collection("notifications").document(notificationId).update("read", read).await()
    }

    // ------------------------------------------------------------------ FCM devices

    suspend fun registerDevice(deviceId: String, fcmToken: String, uid: String) = withContext(io) {
        firestore.collection("devices").document(deviceId).set(
            mapOf(
                "id" to deviceId,
                "token" to fcmToken,
                "userId" to uid,
                "platform" to "android",
                "updatedAt" to System.currentTimeMillis(),
            ),
            SetOptions.merge(),
        ).await()
    }

    companion object {
        private const val TAG = "FirestoreProvider"
    }
}
