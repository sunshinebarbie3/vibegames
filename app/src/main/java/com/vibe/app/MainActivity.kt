package com.vibe.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.vibe.app.core.LocaleManager
import com.vibe.app.databinding.ActivityMainBinding
import com.vibe.app.domain.ThemeMode
import com.vibe.app.notifications.VibeMessagingService
import com.vibe.app.ui.viewmodel.VibeViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var nav: NavController
    private val vm: VibeViewModel by viewModels { VibeViewModel.factory((application as VibeApplication).container) }
    private var ready = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater); setContentView(binding.root)
        val host = supportFragmentManager.findFragmentById(R.id.navHost) as NavHostFragment
        nav = host.navController
        binding.bottomNav.inflateMenu(R.menu.bottom_nav)
        binding.toolbar.setNavigationOnClickListener { if (!nav.popBackStack()) finish() }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom); insets
        }
        nav.addOnDestinationChangedListener { _, dest, _ ->
            val roots = setOf(R.id.homeFragment, R.id.groupsFragment, R.id.memoriesFragment, R.id.profileFragment)
            val auth = dest.id in setOf(R.id.onboardingFragment, R.id.loginFragment)
            binding.bottomNav.visibility = if (dest.id in roots) android.view.View.VISIBLE else android.view.View.GONE
            binding.toolbar.visibility = if (auth) android.view.View.GONE else android.view.View.VISIBLE
            binding.toolbar.title = dest.label
            binding.toolbar.navigationIcon = if (dest.id in roots || auth) null else androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_nav_back)
            if (dest.id in roots) binding.bottomNav.menu.findItem(dest.id).isChecked = true
        }
        binding.bottomNav.setOnItemSelectedListener { item ->
            if (item.itemId == R.id.quickAdd) {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle(R.string.cd_quick_add).setItems(arrayOf(getString(R.string.action_create_group), getString(R.string.action_join_group))) { _, i -> nav.navigate(if (i == 0) R.id.groupFormFragment else R.id.joinFragment) }.show()
                false
            } else { openRoot(item.itemId); true }
        }
        lifecycleScope.launch {
            val settings = vm.c.settings.settings.first()
            applyTheme(settings.themeMode); LocaleManager.apply(settings.language)
            val session = vm.c.settings.session.first()
            val graph = nav.navInflater.inflate(R.navigation.vibe_nav_graph)
            graph.setStartDestination(if (session != null) R.id.homeFragment else if (settings.onboarded) R.id.loginFragment else R.id.onboardingFragment)
            nav.graph = graph; ready = true
            handleIntent(intent)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.settings.collect { applyTheme(it.themeMode); LocaleManager.apply(it.language) } }
                launch { vm.session.collect { if (ready && it == null && nav.currentDestination?.id !in setOf(R.id.loginFragment, R.id.onboardingFragment)) openRoot(R.id.loginFragment) } }
                launch { vm.online.collect { updateBanner() } }
                launch { vm.pending.collect { updateBanner() } }
            }
        }
        binding.offlineBanner.setOnClickListener { if (ready) nav.navigate(R.id.settingsFragment) }
    }
    private fun applyTheme(theme: ThemeMode) {
        val mode = when (theme) { ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES; ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO; ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM }
        if (AppCompatDelegate.getDefaultNightMode() != mode) AppCompatDelegate.setDefaultNightMode(mode)
    }
    private fun updateBanner() {
        val pending = vm.pending.value
        binding.offlineBanner.visibility = if (!vm.online.value || pending > 0) android.view.View.VISIBLE else android.view.View.GONE
        binding.offlineBanner.text = if (!vm.online.value) getString(R.string.state_offline) + " · " + resources.getQuantityString(R.plurals.sync_pending_count, pending, pending) else resources.getQuantityString(R.plurals.sync_pending_count, pending, pending)
    }
    private fun openRoot(id: Int) {
        nav.navigate(id, null, androidx.navigation.NavOptions.Builder().setPopUpTo(R.id.vibeGraph, false).setLaunchSingleTop(true).build())
    }
    fun signedIn() { openRoot(R.id.homeFragment); vm.refresh() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); if (ready) handleIntent(intent) }
    private fun handleIntent(intent: Intent) {
        if (vm.session.value != null && intent.getBooleanExtra(VibeMessagingService.EXTRA_OPEN_INBOX, false)) nav.navigate(R.id.notificationsFragment)
    }
}
