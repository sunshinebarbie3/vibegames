package com.vibe.app.ui.fragment

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.vibe.app.MainActivity
import com.vibe.app.R
import com.vibe.app.databinding.FragmentAuthBinding
import com.vibe.app.ui.*
import com.vibe.app.ui.viewmodel.VibeViewModel

class AuthFragment : Fragment(R.layout.fragment_auth) {
    private val vm: VibeViewModel by activityViewModels { VibeViewModel.factory(container) }
    override fun onViewCreated(view: View, state: Bundle?) {
        val b = FragmentAuthBinding.bind(view)
        val register = arguments?.getBoolean("register") == true
        b.heading.setText(if (register) R.string.register_title else R.string.login_title)
        b.primary.setText(if (register) R.string.action_register else R.string.action_login)
        b.switchMode.setText(if (register) R.string.action_login else R.string.action_register)
        b.displayNameBox.visibility = if (register) View.VISIBLE else View.GONE
        b.confirmationBox.visibility = if (register) View.VISIBLE else View.GONE
        b.forgot.visibility = if (register) View.GONE else View.VISIBLE
        b.subtitle.setText(if (register) R.string.auth_password_policy else R.string.login_subtitle)
        fun value(field: TextInputEditText) = field.text?.toString().orEmpty()
        b.primary.setOnClickListener {
            b.error.visibility = View.GONE
            vm.action({ if (register) vm.c.authRepository.register(value(b.displayName), value(b.email), value(b.password), value(b.confirmation)) else vm.c.authRepository.login(value(b.email), value(b.password)) }) { result ->
                if (showResult(result)) { b.password.text?.clear(); b.confirmation.text?.clear(); (requireActivity() as MainActivity).signedIn() }
                else { b.error.setText((result as com.vibe.app.core.VibeResult.Problem).messageRes()); b.error.visibility = View.VISIBLE }
            }
        }
        b.switchMode.setOnClickListener { findNavController().navigate(R.id.loginFragment, android.os.Bundle().apply { putBoolean("register", !register) }, androidx.navigation.NavOptions.Builder().setPopUpTo(R.id.loginFragment, true).build()) }
        b.google.setOnClickListener {
            vm.action({
                val token = vm.c.googleSignIn.requestIdToken(requireContext()).getOrElse { return@action com.vibe.app.core.VibeResult.Problem(detail = it.message) }
                vm.c.authRepository.signInWithGoogle(token)
            }) { if (showResult(it)) (requireActivity() as MainActivity).signedIn() }
        }
        b.forgot.setOnClickListener {
            val layout = TextInputLayout(requireContext()).apply { hint = getString(R.string.field_email) }
            val field = TextInputEditText(layout.context).apply { inputType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS; setText(value(b.email)) }
            layout.addView(field)
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.password_forgot).setView(layout).setNegativeButton(R.string.action_cancel, null).setPositiveButton(R.string.action_send) { _, _ ->
                vm.action({ vm.c.authRepository.requestPasswordReset(field.text?.toString().orEmpty()) }) { showResult(it, R.string.auth_reset_sent) }
            }.show()
        }
        collect(vm.busy) { b.progress.visibility = if (it) View.VISIBLE else View.GONE; b.primary.isEnabled = !it; b.google.isEnabled = !it }
    }
}
