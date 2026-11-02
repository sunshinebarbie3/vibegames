package com.vibe.app.ui.fragment

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.vibe.app.R
import com.vibe.app.databinding.FragmentOnboardingBinding
import com.vibe.app.ui.container
import com.vibe.app.ui.navigate
import kotlinx.coroutines.launch

class OnboardingFragment : Fragment(R.layout.fragment_onboarding) {
    override fun onViewCreated(view: View, state: Bundle?) {
        val b = FragmentOnboardingBinding.bind(view)
        b.primary.setOnClickListener { lifecycleScope.launch { container.settings.setOnboarded(true); navigate(R.id.loginFragment) } }
    }
}
