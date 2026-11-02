/** Pure decision rules shared with Android's DecisionEngine.kt. */
export const MAX_ROUNDS = 5;
export function evaluate({ activities, memberIds, votes = [], roundNumber = 1, state = 'OPEN', now = Date.now(), deadline = null, maxRounds = MAX_ROUNDS }) {
  const members = [...new Set(memberIds)];
  const eligible = activities.filter(a => ['SUGGESTED', 'ACTIVE'].includes(a.status));
  const tallies = eligible.map(a => {
    const byMember = new Map();
    for (const v of votes.filter(v => v.activityId === a.id && members.includes(v.userId))) {
      if (!byMember.has(v.userId) || byMember.get(v.userId).epochMillis <= v.epochMillis) byMember.set(v.userId, v);
    }
    const cast = [...byMember.values()];
    const yes = cast.filter(v => v.choice).length;
    const no = cast.length - yes;
    const pending = members.length - cast.length;
    return { activityId: a.id, yes, no, pending, canProgress: yes > no, couldStillWin: yes + pending > no };
  });
  const voters = new Set(votes.filter(v => members.includes(v.userId) && eligible.some(a => a.id === v.activityId)).map(v => v.userId));
  const participation = { memberCount: members.length, votedCount: voters.size, pendingMemberIds: members.filter(id => !voters.has(id)) };
  const complete = state !== 'OPEN' || (members.length > 0 && voters.size >= members.length) || (deadline !== null && now >= deadline);
  const canCloseEarly = !complete && tallies.filter(t => t.couldStillWin).length <= 1;
  if (!complete) return { tallies, participation, votesRevealed: false, canCloseEarly, survivorIds: eligible.map(a => a.id), eliminatedIds: [], outcome: 'inProgress', winnerActivityId: null };
  const survivorIds = tallies.filter(t => t.canProgress).map(t => t.activityId);
  const eliminatedIds = tallies.filter(t => !t.canProgress).map(t => t.activityId);
  const outcome = survivorIds.length === 1 ? 'winner' : survivorIds.length === 0 ? 'none' : roundNumber >= maxRounds ? 'tie' : 'continue';
  return { tallies, participation, votesRevealed: true, canCloseEarly: false, survivorIds, eliminatedIds, outcome, winnerActivityId: outcome === 'winner' ? survivorIds[0] : null };
}
export function publicDecision(d, evaluation) {
  const revealed = evaluation.votesRevealed;
  return { ...d, ...evaluation, tallies: revealed ? evaluation.tallies : [], survivorIds: revealed ? evaluation.survivorIds : [], eliminatedIds: revealed ? evaluation.eliminatedIds : [], winnerActivityId: revealed ? evaluation.winnerActivityId : null, votes: undefined, memberIds: undefined, activityIds: d.activityIds };
}
