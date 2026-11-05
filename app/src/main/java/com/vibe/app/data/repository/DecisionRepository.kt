package com.vibe.app.data.repository

import com.vibe.app.core.*
import com.vibe.app.data.local.*
import com.vibe.app.data.prefs.SettingsStore
import com.vibe.app.data.remote.*
import com.vibe.app.data.sync.SyncQueue
import com.vibe.app.data.sync.onlineMutation
import com.vibe.app.domain.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

@OptIn(ExperimentalCoroutinesApi::class)
class DecisionRepository(
    private val api: VibeApi,
    private val database: VibeDatabase,
    private val queue: SyncQueue,
    private val settings: SettingsStore,
    private val time: TimeProvider,
) {
    fun observeRound(id: String): Flow<DecisionRound?> = combine(database.decisionDao().observeById(id),database.voteDao().observeByDecision(id),database.activityDao().observeAll()) { d,v,a -> Triple(d,v,a) }.flatMapLatest { (d,v,a) -> flowOf(d?.let { buildRound(it,v,a) }) }
    fun observeCurrentRound(groupId: String) = database.decisionDao().observeLatestOpen(groupId).flatMapLatest { if (it == null) flowOf(null) else observeRound(it.id) }
    fun observeLatestWinner(groupId: String) = database.decisionDao().observeLatestSettled(groupId).flatMapLatest { if (it == null) flowOf(null) else observeRound(it.id) }
    fun observeHistory(groupId: String) = database.decisionDao().observeByGroup(groupId).map { rows -> rows.map { it.toDomain() } }
    suspend fun currentRoundId(groupId: String) = database.decisionDao().latestOpenId(groupId)

    suspend fun startRound(groupId: String, activityIds: List<String> = emptyList(), roundNumber: Int = 1): VibeResult<String> {
        val uid = settings.currentUserId() ?: return VibeResult.Problem(detail = "Please sign in")
        val group = database.groupDao().byId(groupId) ?: return VibeResult.Problem(detail = "Group not found")
        Validators.canStartRound(if (group.ownerId == uid) GroupRole.OWNER else GroupRole.MEMBER)?.let { return VibeResult.Problem(it) }
        val eligible = database.activityDao().eligibleForRound(groupId).filter { activityIds.isEmpty() || it.id in activityIds }
        Validators.roundActivities(eligible.size)?.let { return VibeResult.Problem(it) }
        currentRoundId(groupId)?.let { return VibeResult.Ok(it) }
        val id = Ids.newId(); val ids = eligible.map { it.id }
        val payload = StartDecisionRequest(ids, null, id, groupId, roundNumber)
        database.decisionDao().upsert(DecisionEntity(id,groupId,roundNumber,"OPEN",payload.deadlineEpochMillis,null,uid,time.nowMillis(),"","","",Codecs.encodeList(ids)))
        val item = queue.enqueue(SyncAction.START_DECISION,id,payload,StartDecisionRequest::class.java)
        return when (val r = onlineMutation(queue,item) { api.startDecision(groupId,payload) }) {
            is ApiResult.Success -> { applySnapshot(r.data); VibeResult.Ok(r.data.id) }
            is ApiResult.Failure -> if (r.kind == ApiErrorKind.NETWORK || r.kind == ApiErrorKind.SERVER) VibeResult.Ok(id) else r.toProblem()
        }
    }
    suspend fun castVote(id: String, activityId: String, choice: Boolean): VibeResult<String> {
        val uid = settings.currentUserId() ?: return VibeResult.Problem(detail = "Please sign in")
        val decision = database.decisionDao().byId(id) ?: return VibeResult.Problem(detail = "Round not found")
        if (decision.state != "OPEN") return VibeResult.Problem(kind = ApiErrorKind.CONFLICT)
        if (activityId !in Codecs.decodeList(decision.roundActivityIdsCsv)) return VibeResult.Problem(kind = ApiErrorKind.VALIDATION)
        database.voteDao().find(id,activityId,uid)?.let { return if (it.choice == choice) VibeResult.Ok(id) else VibeResult.Problem(kind = ApiErrorKind.CONFLICT) }
        val timestamp = time.nowMillis(); val clientId = Ids.syncClientId(SyncAction.CAST_VOTE,"$id:$activityId",timestamp)
        database.voteDao().upsert(VoteEntity(id,activityId,uid,clientId,choice,timestamp,SyncState.PENDING.name))
        val payload = CastVoteRequest(activityId,choice,timestamp,clientId)
        val item = queue.enqueue(SyncAction.CAST_VOTE,"$id:$activityId",payload,CastVoteRequest::class.java)
        return when (val r = onlineMutation(queue,item) { api.castVote(id,payload) }) {
            is ApiResult.Success -> { database.voteDao().updateSyncState(id,activityId,uid,SyncState.SYNCED.name); applySnapshot(r.data.decision); VibeResult.Ok(id) }
            is ApiResult.Failure -> if (r.kind == ApiErrorKind.NETWORK || r.kind == ApiErrorKind.SERVER) VibeResult.Ok(id) else r.toProblem()
        }
    }
    suspend fun closeRound(id: String): VibeResult<String> {
        val d = database.decisionDao().byId(id) ?: return VibeResult.Problem(detail = "Round not found")
        val item = queue.enqueue(SyncAction.CLOSE_DECISION,id,EmptyPayload(),EmptyPayload::class.java)
        return when (val r = onlineMutation(queue,item) { api.closeDecision(id) }) {
            is ApiResult.Success -> { applySnapshot(r.data); VibeResult.Ok(id) }
            is ApiResult.Failure -> if (r.kind == ApiErrorKind.NETWORK || r.kind == ApiErrorKind.SERVER) VibeResult.Ok(id) else r.toProblem()
        }
    }
    suspend fun refreshRound(id: String): VibeResult<DecisionRound> {
        if (database.syncQueueDao().count() > 0) return VibeResult.Problem(kind = ApiErrorKind.NETWORK)
        return when (val r = apiResult { api.decision(id) }) { is ApiResult.Success -> { applySnapshot(r.data); VibeResult.Ok(r.data.toRound()) }; is ApiResult.Failure -> r.toProblem() }
    }
    suspend fun surprise(id: String,rejected: List<String>): VibeResult<Activity> = when (val r = apiResult { api.surprise(id,SurpriseRequest(rejected)) }) { is ApiResult.Success -> VibeResult.Ok(r.data.toDomain()); is ApiResult.Failure -> r.toProblem() }
    suspend fun queueOfflineRoundStart(groupId: String,ids: List<String>) = startRound(groupId,ids)

    private suspend fun buildRound(entity: DecisionEntity,votes: List<VoteEntity>,activities: List<ActivityEntity>): DecisionRound {
        val ids = Codecs.decodeList(entity.roundActivityIdsCsv)
        val ideas = activities.filter { it.id in ids }.map { it.toDomain().toIdea().copy(status = ActivityStatus.ACTIVE) }
        val members = database.groupDao().members(entity.groupId).map { it.userId }
        val e = DecisionEngine.evaluate(RoundInput(entity.roundNumber,if (entity.state == "OPEN") RoundState.OPEN else RoundState.SETTLED,time.nowMillis(),members,ideas,votes.map { VoteChoice(it.activityId,it.userId,it.choice,it.epochMillis) },entity.deadlineEpochMillis))
        if (entity.state != "OPEN") {
            val survivors = Codecs.decodeList(entity.survivorIdsCsv)
            val outcome = entity.winnerActivityId?.let { RoundOutcome.Winner(it) } ?: when { survivors.size > 1 && entity.state == "CLOSED" -> RoundOutcome.ContinueRounds(survivors,entity.roundNumber+1); survivors.size > 1 -> RoundOutcome.Tie(survivors); else -> RoundOutcome.NoWinner }
            return DecisionRound(entity.toDomain(),e.participation,Codecs.decodeTallies(entity.talliesCsv),survivors,Codecs.decodeList(entity.eliminatedIdsCsv),true,false,outcome)
        }
        return DecisionRound(entity.toDomain(),e.participation,if (e.votesRevealed) e.tallies else emptyList(),if (e.votesRevealed) e.survivorIds else emptyList(),if (e.votesRevealed) e.eliminatedIds else emptyList(),e.votesRevealed,e.canCloseEarly,e.outcome)
    }
    private suspend fun applySnapshot(dto: DecisionDto) {
        val old = database.decisionDao().byId(dto.id)
        val ids = dto.activityIds.ifEmpty { old?.let { Codecs.decodeList(it.roundActivityIdsCsv) }.orEmpty() }
        database.decisionDao().upsert(dto.toDomain().toEntity(dto.tallies.map { it.toDomain() },dto.survivorIds,dto.eliminatedIds,ids))
        val uid = settings.currentUserId()
        for (v in dto.myVotes) {
            val existing = database.voteDao().find(dto.id,v.activityId,uid.orEmpty())
            if (existing?.syncState != SyncState.PENDING.name) database.voteDao().upsert(VoteEntity(dto.id,v.activityId,uid.orEmpty(),"${dto.id}:${v.activityId}:$uid",v.choice,v.epochMillis,SyncState.SYNCED.name))
        }
        dto.eliminatedIds.forEach { database.activityDao().updateStatus(it,ActivityStatus.ELIMINATED.name) }
        dto.winnerActivityId?.let { database.activityDao().updateStatus(it,ActivityStatus.WINNER.name) }
        if (dto.state != "OPEN") dto.survivorIds.forEach { database.activityDao().updateStatus(it,ActivityStatus.ACTIVE.name) }
    }
    fun isRoundDecided(round: DecisionRound) = round.outcome is RoundOutcome.Winner || round.outcome is RoundOutcome.Tie || round.outcome is RoundOutcome.NoWinner
}
data class EmptyPayload(val placeholder: Boolean = true)
