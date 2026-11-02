package com.vibe.app.ui.fragment

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.vibe.app.R
import com.vibe.app.databinding.FragmentDetailBinding
import com.vibe.app.ui.*
import com.vibe.app.ui.viewmodel.VibeViewModel

class ActivityFragment : Fragment(R.layout.fragment_detail) {
    private val vm: VibeViewModel by activityViewModels { VibeViewModel.factory(container) }
    override fun onViewCreated(view: View, state: Bundle?) {
        val b = FragmentDetailBinding.bind(view), id = requireArguments().getString("entityId").orEmpty()
        listOf<View>(b.filters,b.list,b.photo,b.secondary,b.tertiary,b.delete,b.extra).forEach { it.visibility = View.GONE }
        collect(vm.activity(id)) { a ->
            a ?: return@collect
            b.icon.text = a.icon; b.heading.text = a.title; b.body.text = a.description
            b.meta.text = getString(R.string.activity_added_by, a.createdByName) + "\n" + getString(R.string.activity_status, a.status.name)
            b.secondary.visibility = View.VISIBLE; b.tertiary.visibility = View.VISIBLE; b.delete.visibility = View.VISIBLE
            b.primary.setText(R.string.activity_plan); b.primary.setOnClickListener { navigate(R.id.planFragment, "entityId" to id, "groupId" to a.groupId) }
            b.secondary.setText(R.string.activity_edit); b.secondary.setOnClickListener { navigate(R.id.activityFormFragment, "entityId" to id, "groupId" to a.groupId) }
            b.tertiary.setText(if (a.favourite) R.string.activity_favourite_remove else R.string.activity_favourite_add); b.tertiary.setOnClickListener { vm.action({ vm.c.activityRepository.setFavourite(a,!a.favourite) }) { showResult(it) } }
            b.delete.setText(R.string.activity_delete); b.delete.setOnClickListener { confirm(R.string.activity_delete, R.string.activity_delete_confirm) { vm.action({ vm.c.activityRepository.deleteActivity(id) }) { if (showResult(it)) back() } } }
        }
    }
}
