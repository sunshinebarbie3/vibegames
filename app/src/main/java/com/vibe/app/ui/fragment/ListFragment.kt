package com.vibe.app.ui.fragment

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.vibe.app.R
import com.vibe.app.databinding.FragmentListBinding
import com.vibe.app.ui.*
import com.vibe.app.ui.viewmodel.VibeViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import java.text.DateFormat

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ListFragment : Fragment(R.layout.fragment_list) {
    private val vm: VibeViewModel by activityViewModels { VibeViewModel.factory(container) }
    override fun onViewCreated(view: View, state: Bundle?) {
        val b = FragmentListBinding.bind(view)
        val mode = arguments?.getString("mode") ?: "groups"
        val groupId = arguments?.getString("groupId")?.takeUnless { it == "all" }
        val plans = if (mode == "plans") (if (groupId == null) vm.groups else vm.groups.map { rows -> rows.filter { it.id == groupId } }).flatMapLatest { groups ->
            val flows = groups.map { vm.c.memoryRepository.observePlans(it.id) }
            if (flows.isEmpty()) flowOf(emptyList()) else combine(flows) { arrays -> arrays.flatMap { it.toList() } }
        } else flowOf(emptyList())
        var plansNow = emptyList<com.vibe.app.domain.Plan>()
        val adapter = CardAdapter { item ->
            when (mode) {
                "groups" -> navigate(R.id.groupFragment, "groupId" to item.id)
                "activities" -> navigate(R.id.activityFragment, "entityId" to item.id)
                "memories" -> navigate(R.id.memoryFragment, "entityId" to item.id)
                "plans" -> { val p = plansNow.find { it.id == item.id }; if (p?.completed == true) navigate(R.id.memoriesFragment) else navigate(R.id.completePlanFragment,"entityId" to item.id) }
                "notifications" -> { vm.action({ vm.c.notificationRepository.markRead(item.id); com.vibe.app.core.VibeResult.Ok(Unit) }); val n = vm.notifications.value.find { it.id == item.id }; n?.groupId?.let { navigate(R.id.groupFragment, "groupId" to it) } }
            }
        }
        b.list.layoutManager = LinearLayoutManager(requireContext()); b.list.adapter = adapter
        fun render(items: List<CardItem>) {
            adapter.submitList(items); b.empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            b.emptyTitle.setText(when (mode) { "memories" -> R.string.memories_empty_title; "activities" -> R.string.activity_empty; "notifications" -> R.string.notifications_title; else -> R.string.home_empty_title })
            b.emptyBody.setText(when (mode) { "memories" -> R.string.memories_empty_body; "activities" -> R.string.activity_empty; "notifications" -> R.string.notifications_empty; "plans" -> R.string.plans_empty; else -> R.string.home_empty_body })
            b.emptyAction.visibility = if (mode == "groups" || mode == "activities") View.VISIBLE else View.GONE
            b.emptyAction.setText(if (mode == "activities") R.string.group_add_idea else R.string.action_create_group)
        }
        b.emptyAction.setOnClickListener { if (mode == "groups") navigate(R.id.groupFormFragment) else navigate(R.id.activityFormFragment, "groupId" to (groupId ?: "all")) }
        b.root.setOnRefreshListener { vm.refresh(); b.root.isRefreshing = false }
        when (mode) {
            "groups" -> collect(vm.groups) { render(it.map { g -> CardItem(g.id, g.icon, g.name, resources.getQuantityString(R.plurals.group_members, g.memberCount, g.memberCount)) }) }
            "activities" -> collect(vm.activities) { rows -> render(rows.filter { groupId == null || it.groupId == groupId }.map { CardItem(it.id, it.icon, it.title, vm.groups.value.find { g -> g.id == it.groupId }?.name.orEmpty(), if (it.favourite) "★" else "") }) }
            "plans" -> collect(plans) { rows -> plansNow = rows; render(rows.map { p -> CardItem(p.id,p.activityIcon,p.activityTitle,p.scheduledAtEpochMillis?.let { DateFormat.getDateTimeInstance().format(it) } ?: getString(R.string.plan_no_date),if (p.completed) "✓" else "") }) }
            "memories" -> collect(vm.memories) { render(it.map { m -> CardItem(m.id, m.activityIcon, m.activityTitle, m.caption, if (m.rating > 0) "★ ${m.rating}" else "") }) }
            else -> collect(vm.notifications) { render(it.map { n -> CardItem(n.id, if (n.read) "✓" else "●", n.title, n.body) }) }
        }
    }
}
