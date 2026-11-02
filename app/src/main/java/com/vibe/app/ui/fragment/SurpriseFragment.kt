package com.vibe.app.ui.fragment

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.vibe.app.R
import com.vibe.app.databinding.FragmentDecisionBinding
import com.vibe.app.domain.DecisionEngine
import com.vibe.app.ui.*
import com.vibe.app.ui.viewmodel.VibeViewModel

class SurpriseFragment : Fragment(R.layout.fragment_decision) {
    private val vm: VibeViewModel by activityViewModels { VibeViewModel.factory(container) }
    private val rejected = mutableSetOf<String>()
    override fun onViewCreated(view: View, state: Bundle?) {
        val b = FragmentDecisionBinding.bind(view)
        val groupId = requireArguments().getString("groupId").orEmpty()
        b.progress.visibility = View.GONE; b.participation.setText(R.string.decision_surprise_title)
        b.yes.setText(R.string.decision_accept); b.no.setText(R.string.decision_skip); b.primary.visibility = View.GONE; b.secondary.visibility = View.GONE
        fun propose() {
            val idea = DecisionEngine.surpriseMe(vm.activities.value.filter { it.groupId == groupId }.map { it.toIdea() },rejected)
            b.icon.text = idea?.icon ?: "🌱"; b.title.text = idea?.title ?: getString(R.string.decision_surprise_empty); b.body.text = vm.activities.value.find { it.id == idea?.id }?.description.orEmpty()
            b.yes.visibility = if (idea == null) View.GONE else View.VISIBLE; b.no.visibility = b.yes.visibility
            b.yes.setOnClickListener { idea?.let { navigate(R.id.planFragment,"entityId" to it.id,"groupId" to groupId) } }
            b.no.setOnClickListener { idea?.let { rejected += it.id }; propose() }
        }
        collect(vm.activities) { if (rejected.isEmpty()) propose() }
    }
}
