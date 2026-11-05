package com.vibe.app.ui.fragment

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vibe.app.R
import com.vibe.app.databinding.FragmentHomeBinding
import com.vibe.app.domain.GroupRole
import com.vibe.app.ui.*
import com.vibe.app.ui.viewmodel.VibeViewModel

class HomeFragment : Fragment(R.layout.fragment_home) {
    private val vm: VibeViewModel by activityViewModels { VibeViewModel.factory(container) }
    override fun onViewCreated(view: View, state: Bundle?) {
        val b = FragmentHomeBinding.bind(view)
        val adapter = CardAdapter { navigate(R.id.groupFragment, "groupId" to it.id) }
        b.groups.layoutManager = LinearLayoutManager(requireContext()); b.groups.adapter = adapter
        b.create.setOnClickListener { navigate(R.id.groupFormFragment) }; b.join.setOnClickListener { navigate(R.id.joinFragment) }
        b.notifications.setOnClickListener { navigate(R.id.notificationsFragment) }
        b.activities.setOnClickListener { navigate(R.id.activitiesFragment) }
        fun choose(ownerOnly: Boolean, block: (String) -> Unit) {
            val groups = vm.groups.value.filter { !ownerOnly || it.ownerId == vm.session.value?.userId }
            if (groups.isEmpty()) { snack(if (ownerOnly) R.string.error_owner_only else R.string.home_empty_body); navigate(R.id.groupFormFragment); return }
            if (groups.size == 1) block(groups.first().id) else MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.groups_title).setItems(groups.map { "${it.icon} ${it.name}" }.toTypedArray()) { _, i -> block(groups[i].id) }.show()
        }
        b.decide.setOnClickListener { choose(true) { navigate(R.id.groupFragment, "groupId" to it) } }
        b.surprise.setOnClickListener { choose(false) { navigate(R.id.surpriseFragment, "groupId" to it) } }
        fun renderActions() {
            val groups = vm.groups.value
            val uid = vm.session.value?.userId
            b.decide.visibility = if (groups.any { it.ownerId == uid }) View.VISIBLE else View.GONE
            b.surprise.visibility = if (groups.isEmpty()) View.GONE else View.VISIBLE
        }
        collect(vm.session) { renderActions() }
        collect(vm.profile) { profile ->
            val name = profile?.displayName?.takeIf { it.isNotBlank() } ?: vm.session.value?.displayName
            b.greeting.text = if (name.isNullOrBlank()) getString(R.string.app_name) else getString(R.string.home_greeting, name)
        }
        collect(vm.groups) { groups ->
            adapter.submitList(groups.map {
                CardItem(it.id, it.icon, it.name, resources.getQuantityString(R.plurals.group_members, it.memberCount, it.memberCount), if (it.syncState.name == "PENDING") "⏳" else "")
            })
            b.empty.visibility = if (groups.isEmpty()) View.VISIBLE else View.GONE
            b.groupHeading.setText(if (groups.isEmpty()) R.string.home_empty_title else R.string.home_your_groups)
            renderActions()
        }
        collect(vm.notifications) { rows ->
            val count = rows.count { !it.read }
            b.notifications.text = if (count > 0) count.toString() else ""
            b.notifications.iconPadding = if (count > 0) (8 * resources.displayMetrics.density).toInt() else 0
            val label = getString(R.string.notifications_title)
            b.notifications.contentDescription = if (count > 0) "$label ($count)" else label
        }
    }
}