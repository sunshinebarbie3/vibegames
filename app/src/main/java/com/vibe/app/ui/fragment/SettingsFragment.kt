package com.vibe.app.ui.fragment

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vibe.app.R
import com.vibe.app.core.VibeResult
import com.vibe.app.databinding.FragmentSettingsBinding
import com.vibe.app.domain.AppLanguage
import com.vibe.app.domain.SyncItem
import com.vibe.app.domain.ThemeMode
import com.vibe.app.ui.*
import com.vibe.app.ui.viewmodel.VibeViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class SettingsFragment : Fragment(R.layout.fragment_settings) {
    private val vm: VibeViewModel by activityViewModels { VibeViewModel.factory(container) }
    private var googleLinked = false
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> vm.updateSettings(notifications = granted) }
    override fun onViewCreated(view: View, state: Bundle?) {
        val b = FragmentSettingsBinding.bind(view)
        var updating = false
        collect(vm.settings) { s ->
            updating = true; b.notifications.isChecked = s.notificationsEnabled; b.privacy.isChecked = s.privacyMembersOnly
            if (b.themes.childCount == 0) listOf(ThemeMode.DARK to R.string.settings_theme_dark,ThemeMode.LIGHT to R.string.settings_theme_light,ThemeMode.SYSTEM to R.string.settings_theme_system).forEach { (mode,label) ->
                val chip = Chip(requireContext()).apply { id = View.generateViewId(); text = getString(label); isCheckable = true; setOnClickListener { vm.updateSettings(theme = mode) } }; chip.tag = mode; b.themes.addView(chip)
            }
            for (i in 0 until b.themes.childCount) { val chip = b.themes.getChildAt(i) as Chip; chip.isChecked = chip.tag == s.themeMode }
            updating = false
        }
        b.notifications.setOnCheckedChangeListener { _, checked -> if (!updating) {
            if (checked && Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(requireContext(),Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else vm.updateSettings(notifications = checked)
        } }
        b.privacy.setOnCheckedChangeListener { _, checked -> if (!updating) vm.updateSettings(privacy = checked) }
        b.sync.setOnClickListener { vm.sync() }
        collect(combine(vm.pending,vm.conflicts) { p,c -> p to c }) { (pending,conflicts) -> b.syncStatus.text = resources.getQuantityString(R.plurals.sync_pending_count,pending,pending) + "\n" + getString(R.string.settings_cloud_status); b.conflicts.visibility = if (conflicts.isEmpty()) View.GONE else View.VISIBLE }
        b.conflicts.setOnClickListener { showConflicts(vm.conflicts.value) }
        b.language.setOnClickListener { navigate(R.id.languageFragment) }; b.password.setOnClickListener { navigate(R.id.passwordFragment) }; b.editProfile.setOnClickListener { navigate(R.id.editProfileFragment) }
        b.logout.setOnClickListener { vm.signOut { showResult(it) } }
        b.deleteAccount.visibility = View.GONE // Enabled only after the backend deletion capability is configured.
        collect(vm.online) { online -> if (online) vm.action({ vm.c.authRepository.providers() }) { if (it is VibeResult.Ok) { googleLinked = "google.com" in it.value.providers; b.linkGoogle.setText(if (googleLinked) R.string.auth_google_unlink else R.string.auth_google_link) } } }
        b.linkGoogle.setOnClickListener {
            if (googleLinked) vm.action({ vm.c.authRepository.unlinkGoogle() }) { if (showResult(it)) { googleLinked = false; b.linkGoogle.setText(R.string.auth_google_link) } }
            else vm.action({ val token = vm.c.googleSignIn.requestIdToken(requireContext()).getOrElse { return@action VibeResult.Problem(detail = it.message) }; vm.c.authRepository.linkGoogle(token) }) { if (showResult(it,R.string.auth_google_linked)) { googleLinked = true; b.linkGoogle.setText(R.string.auth_google_unlink) } }
        }
    }
    private fun showConflicts(items: List<SyncItem>) {
        if (items.isEmpty()) return
        val item = items.first()
        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.conflict_title).setMessage(getString(R.string.conflict_body,item.action.wire)+"\n\n"+getString(R.string.conflict_local,item.payloadJson)+"\n\n"+getString(R.string.conflict_server,item.serverPayloadJson.orEmpty())).setNeutralButton(R.string.conflict_keep_theirs) { _,_ -> viewLifecycleOwner.lifecycleScope.launch { vm.c.syncManager.resolveKeepingServer(item); showConflicts(items.drop(1)) } }.setPositiveButton(R.string.conflict_keep_mine) { _,_ -> viewLifecycleOwner.lifecycleScope.launch { vm.c.syncManager.resolveKeepingLocal(item); showConflicts(items.drop(1)) } }.setNegativeButton(R.string.action_close,null).show()
    }
}
