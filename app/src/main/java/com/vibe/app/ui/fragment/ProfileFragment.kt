package com.vibe.app.ui.fragment

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import coil.load
import com.vibe.app.R
import com.vibe.app.databinding.FragmentProfileBinding
import com.vibe.app.ui.*
import com.vibe.app.ui.viewmodel.VibeViewModel
import kotlinx.coroutines.flow.first

class ProfileFragment : Fragment(R.layout.fragment_profile) {
    private val vm: VibeViewModel by activityViewModels { VibeViewModel.factory(container) }
    override fun onViewCreated(view: View, state: Bundle?) {
        val b = FragmentProfileBinding.bind(view)
        b.edit.setOnClickListener { navigate(R.id.editProfileFragment) }; b.groups.setOnClickListener { navigate(R.id.groupsFragment) }
        b.activities.setOnClickListener { navigate(R.id.activitiesFragment) }; b.memories.setOnClickListener { navigate(R.id.memoriesFragment) }
        b.plans.setOnClickListener { navigate(R.id.plansFragment) }; b.settings.setOnClickListener { navigate(R.id.settingsFragment) }; b.help.setOnClickListener { navigate(R.id.helpFragment) }
        collect(vm.profile) { user ->
            b.name.text = user?.displayName ?: vm.session.value?.displayName; b.email.text = user?.email ?: vm.session.value?.email
            b.photo.setImageResource(R.drawable.ic_vibe_logo); user?.photoUri?.let { b.photo.load(it) }
            val stats = user?.let { vm.c.authRepository.stats(it.id) }
            b.stats.text = getString(R.string.profile_stats_decisions)+": ${stats?.decisions ?: 0}   ·   "+getString(R.string.profile_stats_activities)+": ${stats?.activities ?: 0}   ·   "+getString(R.string.profile_stats_groups)+": ${stats?.groups ?: 0}"
        }
    }
}
