package com.vibe.app.ui.fragment

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.vibe.app.R
import com.vibe.app.core.VibeResult
import com.vibe.app.databinding.FragmentDecisionBinding
import com.vibe.app.domain.RoundOutcome
import com.vibe.app.ui.*
import com.vibe.app.ui.viewmodel.VibeViewModel
import kotlinx.coroutines.flow.first

class VoteFragment : Fragment(R.layout.fragment_decision) {
    private val vm: VibeViewModel by activityViewModels { VibeViewModel.factory(container) }
    override fun onViewCreated(view: View, state: Bundle?) {
        val b = FragmentDecisionBinding.bind(view), id = requireArguments().getString("entityId").orEmpty()
        b.primary.visibility = View.GONE
        collect(vm.round(id)) { round ->
            round ?: return@collect
            val d = round.decision
            val ids = vm.c.database.decisionDao().byId(id)?.let { com.vibe.app.data.local.Codecs.decodeList(it.roundActivityIdsCsv) }.orEmpty()
            val mine = vm.c.database.voteDao().byDecision(id).filter { it.userId == vm.session.value?.userId }.map { it.activityId }.toSet()
            val current = ids.firstOrNull { it !in mine }
            val activity = current?.let { vm.c.database.activityDao().byId(it) }
            b.round.text = getString(R.string.decision_round,d.roundNumber,((ids.indexOf(current).takeIf { it >= 0 } ?: 0)+1).coerceAtMost(ids.size.coerceAtLeast(1)),ids.size)
            b.progress.max = round.participation.memberCount.coerceAtLeast(1); b.progress.progress = round.participation.votedCount
            b.participation.text = getString(R.string.decision_participation,round.participation.votedCount,round.participation.memberCount)
            b.icon.text = activity?.icon ?: "⏳"; b.title.text = activity?.title ?: getString(R.string.decision_waiting)
            b.body.text = if (round.votesRevealed) round.tallies.joinToString("\n") { t -> (vm.activities.value.find { it.id == t.activityId }?.title ?: t.activityId) + ": " + getString(R.string.decision_summary,t.yes,t.no,t.pending) } else getString(R.string.decision_waiting)
            b.no.visibility = if (current != null && round.isOpen) View.VISIBLE else View.GONE
            b.yes.visibility = b.no.visibility
            fun vote(choice: Boolean) { current?.let { aid -> vm.action({ vm.c.decisionRepository.castVote(id,aid,choice) }) { showResult(it,R.string.decision_vote_saved) } } }
            b.no.setOnClickListener { vote(false) }; b.yes.setOnClickListener { vote(true) }
            b.secondary.visibility = if (round.isOpen && (round.canCloseEarly || vm.groups.value.find { it.id == d.groupId }?.ownerId == vm.session.value?.userId)) View.VISIBLE else View.GONE
            b.secondary.setOnClickListener { vm.action({ vm.c.decisionRepository.closeRound(id) }) { showResult(it) } }
            if (!round.isOpen) { b.primary.visibility = View.VISIBLE; b.primary.setText(R.string.group_view_winner); b.primary.setOnClickListener { navigate(R.id.winnerFragment,"groupId" to d.groupId) } }
        }
    }
}
