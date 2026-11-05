package com.vibe.app.ui.fragment

import android.os.Bundle
import kotlinx.coroutines.flow.catch
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.vibe.app.R
import com.vibe.app.databinding.FragmentDecisionBinding
import com.vibe.app.domain.RoundOutcome
import com.vibe.app.ui.*
import com.vibe.app.ui.viewmodel.VibeViewModel

class WinnerFragment : Fragment(R.layout.fragment_decision) {
    private val vm: VibeViewModel by activityViewModels { VibeViewModel.factory(container) }
    override fun onViewCreated(view: View, state: Bundle?) {
        val b = FragmentDecisionBinding.bind(view)
        val groupId = requireArguments().getString("groupId").orEmpty()
        collect(vm.c.decisionRepository.remoteRounds(groupId).catch { snack(R.string.error_network) }) { rows ->
            vm.c.activityRepository.refresh(groupId)
            vm.c.groupRepository.refreshGroup(groupId)
            vm.c.decisionRepository.cacheRounds(rows)
        }
        b.no.visibility = View.GONE; b.yes.visibility = View.GONE; b.progress.visibility = View.GONE
        collect(vm.winner(groupId)) { round ->
            if (round == null) { b.icon.text = "🗳️"; b.title.setText(R.string.decision_waiting); b.body.setText(R.string.decision_none); b.primary.visibility = View.GONE; b.secondary.visibility = View.GONE; return@collect }
            val winner = round.outcome as? RoundOutcome.Winner
            val activity = winner?.let { vm.activities.value.find { a -> a.id == it.activityId } }
            b.icon.text = activity?.icon ?: "🌱"; b.title.text = activity?.title ?: getString(if (round.outcome is RoundOutcome.Tie) R.string.decision_tie else R.string.decision_none)
            b.participation.text = getString(if (winner != null) R.string.decision_winner else R.string.group_start_decision)
            b.body.text = round.tallies.joinToString("\n") { t -> (vm.activities.value.find { it.id == t.activityId }?.title ?: t.activityId) + ": " + getString(R.string.decision_summary,t.yes,t.no,t.pending) }
            b.primary.visibility = if (winner != null) View.VISIBLE else View.GONE
            b.primary.setText(R.string.activity_plan); b.primary.setOnClickListener { winner?.let { navigate(R.id.planFragment,"entityId" to it.activityId,"groupId" to groupId) } }
            b.secondary.visibility = View.VISIBLE; b.secondary.setText(R.string.plan_complete_title)
            b.secondary.setOnClickListener {
                vm.action({
                    val plan = winner?.let { vm.c.database.planDao().latestForActivity(it.activityId) }
                    if (plan != null) com.vibe.app.core.VibeResult.Ok(plan.id) else com.vibe.app.core.VibeResult.Problem(detail = "Save a plan first")
                }) { if (it is com.vibe.app.core.VibeResult.Ok) navigate(R.id.completePlanFragment,"entityId" to it.value) else showResult(it) }
            }
        }
    }
}
