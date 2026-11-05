package com.vibe.app.ui.fragment

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.vibe.app.R
import com.vibe.app.databinding.FragmentLanguageBinding
import com.vibe.app.domain.AppLanguage
import com.vibe.app.ui.*
import com.vibe.app.ui.viewmodel.VibeViewModel

class LanguageFragment : Fragment(R.layout.fragment_language) {
    private val vm: VibeViewModel by activityViewModels { VibeViewModel.factory(container) }
    override fun onViewCreated(view: View, state: Bundle?) {
        val b = FragmentLanguageBinding.bind(view)
        b.langen.setOnClickListener { vm.updateSettings(language = AppLanguage.ENGLISH) }
        b.langzu.setOnClickListener { vm.updateSettings(language = AppLanguage.ISIZULU) }
        b.langst.setOnClickListener { vm.updateSettings(language = AppLanguage.SESOTHO) }
        collect(vm.settings) { b.langen.isChecked = it.language == AppLanguage.ENGLISH; b.langzu.isChecked = it.language == AppLanguage.ISIZULU; b.langst.isChecked = it.language == AppLanguage.SESOTHO }
    }
}
