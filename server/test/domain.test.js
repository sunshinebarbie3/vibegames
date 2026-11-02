import test from 'node:test';
import assert from 'node:assert/strict';
import { evaluate, publicDecision } from '../src/domain.js';
const ideas = ['pizza','bowling','braai'].map(id => ({ id, title:id, status:'SUGGESTED' }));
const vote = (activityId,userId,choice) => ({ activityId,userId,choice,epochMillis:100 });
test('results stay hidden until every member votes on every idea', () => {
  const e = evaluate({ activities:ideas, memberIds:['a','b'], votes:[vote('pizza','a',true)] });
  assert.equal(e.votesRevealed,false); assert.equal(e.outcome,'inProgress');
  const d = publicDecision({ id:'round',activityIds:ideas.map(x=>x.id),votes:{a:[vote('pizza','a',true)]} },e);
  assert.deepEqual(d.tallies,[]); assert.equal(d.winnerActivityId,null); assert.equal(d.votes,undefined);
  assert.deepEqual(d.myVotes,undefined); assert.equal(d.activityIds.length,3); assert.equal(e.participation.votedCount,0);
  const completeVotes=['a','b'].flatMap(u=>ideas.map(i=>vote(i.id,u,i.id==='pizza')));
  const complete=evaluate({activities:ideas,memberIds:['a','b'],votes:completeVotes}); assert.equal(complete.votesRevealed,true); assert.equal(complete.outcome,'winner');
});
test('one majority winner settles the round', () => {
  const e=evaluate({activities:ideas,memberIds:['a','b'],votes:['a','b'].flatMap(u=>[vote('pizza',u,true),vote('bowling',u,false),vote('braai',u,false)])});
  assert.equal(e.outcome,'winner'); assert.equal(e.winnerActivityId,'pizza'); assert.equal(e.participation.votedCount,2); assert.equal(e.eliminatedIds.length,2);
});
test('survivors continue, all-no has no winner, final tied round is a tie', () => {
  assert.equal(evaluate({activities:ideas,memberIds:['a'],votes:[vote('pizza','a',true),vote('bowling','a',true),vote('braai','a',false)]}).outcome,'continue');
  assert.equal(evaluate({activities:ideas,memberIds:['a'],votes:ideas.map(i=>vote(i.id,'a',false))}).outcome,'none');
  assert.equal(evaluate({activities:ideas,memberIds:['a'],roundNumber:5,votes:ideas.map(i=>vote(i.id,'a',true))}).outcome,'tie');
});
test('deadline and nonparticipants cannot create extra votes', () => {
  const e=evaluate({activities:ideas,memberIds:['a','b'],deadline:100,now:101,votes:[vote('pizza','a',true),vote('pizza','ghost',true),vote('pizza','a',false)]});
  assert.equal(e.votesRevealed,true); assert.equal(e.tallies[0].yes,0); assert.equal(e.tallies[0].no,1); assert.equal(e.tallies[0].pending,1);
});
