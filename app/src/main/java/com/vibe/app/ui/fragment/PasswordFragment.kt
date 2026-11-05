package com.vibe.app.ui.fragment

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.vibe.app.R
import com.vibe.app.databinding.FragmentPasswordBinding
import com.vibe.app.ui.*
import com.vibe.app.ui.viewmodel.VibeViewModel

class PasswordFragment : Fragment(R.layout.fragment_password) {
    private val vm: VibeViewModel by activityViewModels { VibeViewModel.factory(container) }
    override fun onViewCreated(view: View, state: Bundle?) {
        val b = FragmentPasswordBinding.bind(view)
        b.primary.setOnClickListener { vm.action({ vm.c.authRepository.changePassword(b.current.text.toString(),b.password.text.toString(),b.confirmation.text.toString()) }) {
            if (showResult(it,R.string.password_updated)) { b.current.text?.clear(); b.password.text?.clear(); b.confirmation.text?.clear(); back() }
        } }
        collect(vm.busy) { b.primary.isEnabled = !it }
    }
}
