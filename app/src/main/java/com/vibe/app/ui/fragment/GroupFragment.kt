package com.vibe.app.ui.fragment

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.vibe.app.R
import com.vibe.app.databinding.FragmentDetailBinding
import com.vibe.app.domain.ActivityStatus
import com.vibe.app.ui.*
import com.vibe.app.ui.viewmodel.VibeViewModel
import kotlinx.coroutines.flow.combine
import androidx.core.view.children

class GroupFragment : Fragment(R.layout.fragment_detail) {
    private val vm: VibeViewModel by activityViewModels { VibeViewModel.factory(container) }
    override fun onViewCreated(view: View, state: Bundle?) {
        val b = FragmentDetailBinding.bind(view), id = requireArguments().getString("groupId").orEmpty()
        listOf<View>(b.filters,b.list,b.photo,b.secondary,b.tertiary,b.delete,b.extra).forEach { it.visibility = View.GONE }
        var filter = "all"
        val adapter = CardAdapter { navigate(R.id.activityFragment, "entityId" to it.id) }
        b.list.layoutManager = LinearLayoutManager(requireContext()); b.list.adapter = adapter
        for ((tag, label) in listOf("all" to R.string.group_filter_all, "favourites" to R.string.group_filter_favourites, "suggested" to R.string.group_filter_suggested, "completed" to R.string.group_filter_completed)) {
            val chip = Chip(requireContext()).apply { text = getString(label); isCheckable = true; isChecked = tag == "all"; setOnClickListener { filter = tag; b.filters.children.forEach { (it as Chip).isChecked = it == this }; render() } }
            b.filters.addView(chip)
        }
        fun render() {
            val rows = vm.activities.value.filter { it.groupId == id }.filter { when (filter) { "favourites" -> it.favourite; "completed" -> it.status == ActivityStatus.COMPLETED; "suggested" -> it.status == ActivityStatus.SUGGESTED; else -> true } }
            adapter.submitList(rows.map { CardItem(it.id, it.icon, it.title, getString(R.string.activity_added_by, it.createdByName), if (it.favourite) "★" else if (it.status == ActivityStatus.COMPLETED) "✓" else "") })
        }
        collect(combine(vm.group(id), vm.activities) { g, _ -> g }) { g ->
            g ?: return@collect
            b.icon.text = g.icon; b.heading.text = g.name
            b.filters.visibility = View.VISIBLE; b.list.visibility = View.VISIBLE; b.secondary.visibility = View.VISIBLE; b.tertiary.visibility = View.VISIBLE; b.delete.visibility = View.VISIBLE
            b.body.text = getString(R.string.group_invite_code, g.inviteCode)
            b.meta.text = getString(if (g.ownerId == vm.session.value?.userId) R.string.group_owner else R.string.group_member) + "\n" + g.members.joinToString(" · ") { it.displayName }
            b.primary.setText(R.string.group_add_idea); b.primary.setOnClickListener { navigate(R.id.activityFormFragment, "groupId" to id) }
            b.secondary.setText(if (g.ownerId == vm.session.value?.userId) R.string.group_start_decision else R.string.group_open_vote)
            b.secondary.setOnClickListener {
                if (g.ownerId == vm.session.value?.userId) vm.action({ vm.c.decisionRepository.startRound(id) }) { result ->
                    if (result is com.vibe.app.core.VibeResult.Ok) navigate(R.id.voteFragment, "entityId" to result.value) else showResult(result)
                } else vm.action({ vm.c.decisionRepository.currentRoundId(id)?.let { com.vibe.app.core.VibeResult.Ok(it) } ?: com.vibe.app.core.VibeResult.Problem(detail = "No open round") }) { if (it is com.vibe.app.core.VibeResult.Ok) navigate(R.id.voteFragment, "entityId" to it.value) else snack(R.string.decision_waiting) }
            }
            b.tertiary.setText(R.string.group_share_invite); b.tertiary.setOnClickListener { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, getString(R.string.group_invite_share_text, g.name, g.inviteCode)), getString(R.string.group_share_invite))) }
            b.delete.setText(R.string.group_view_winner); b.delete.setOnClickListener { navigate(R.id.winnerFragment, "groupId" to id) }
            b.extra.visibility = View.VISIBLE; b.extra.setText(R.string.plans_title); b.extra.setOnClickListener { navigate(R.id.plansFragment, "groupId" to id) }
            render()
        }
    }
}
