package com.vibe.app.ui

import com.vibe.app.core.ProblemKind

import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import com.vibe.app.R
import com.vibe.app.VibeApplication
import com.vibe.app.core.VibeContainer
import com.vibe.app.core.VibeResult
import com.vibe.app.databinding.ItemCardBinding
import com.vibe.app.domain.FieldError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import android.view.ViewGroup
import android.view.LayoutInflater
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

val Fragment.container: VibeContainer get() = (requireActivity().application as VibeApplication).container
fun Fragment.navigate(id: Int, vararg args: Pair<String, String>) = findNavController().navigate(id, android.os.Bundle().apply { args.forEach { putString(it.first, it.second) } })
fun Fragment.back() { if (!findNavController().popBackStack()) requireActivity().finish() }
fun Fragment.snack(res: Int) { Snackbar.make(requireView(), res, Snackbar.LENGTH_LONG).show() }
fun <T> Fragment.collect(flow: Flow<T>, block: suspend (T) -> Unit) {
    viewLifecycleOwner.lifecycleScope.launch { viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { flow.collectLatest { block(it) } } }
}
fun VibeResult.Problem.messageRes(): Int = error?.let { when (it) {
    FieldError.NAME_REQUIRED, FieldError.GROUP_NAME_REQUIRED, FieldError.ACTIVITY_TITLE_REQUIRED -> R.string.form_required
    FieldError.NAME_TOO_LONG -> R.string.error_name_length
    FieldError.EMAIL_INVALID -> R.string.error_email_invalid
    FieldError.PASSWORD_SHORT -> R.string.error_password_short
    FieldError.PASSWORD_DIGIT -> R.string.error_password_digit
    FieldError.PASSWORD_MISMATCH -> R.string.error_password_mismatch
    FieldError.CURRENT_PASSWORD_REQUIRED -> R.string.field_current_password
    FieldError.INVITE_CODE_INVALID -> R.string.error_invite_invalid
    FieldError.CAPTION_TOO_LONG -> R.string.error_caption_length
    FieldError.RATING_OUT_OF_RANGE -> R.string.error_rating
    FieldError.NOT_ENOUGH_ACTIVITIES -> R.string.error_not_enough_activities
    FieldError.OWNER_ONLY -> R.string.error_owner_only
} } ?: when (kind) {
    ProblemKind.NETWORK -> R.string.error_network
    ProblemKind.UNAUTHORISED -> R.string.error_auth
    ProblemKind.SERVER -> R.string.error_server
    else -> R.string.error_generic
}
fun Fragment.showResult(result: VibeResult<*>, success: Int? = null): Boolean {
    if (result is VibeResult.Problem) { snack(result.messageRes()); return false }
    success?.let { snack(it) }; return true
}
fun Fragment.confirm(title: Int, message: Int, action: () -> Unit) {
    com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setTitle(title).setMessage(message).setNegativeButton(R.string.action_cancel, null).setPositiveButton(R.string.action_confirm) { _, _ -> action() }.show()
}

data class CardItem(val id: String, val icon: String, val title: String, val subtitle: String = "", val badge: String = "")
class CardAdapter(private val click: (CardItem) -> Unit) : ListAdapter<CardItem, CardAdapter.Holder>(Diff) {
    object Diff : DiffUtil.ItemCallback<CardItem>() {
        override fun areItemsTheSame(a: CardItem, b: CardItem) = a.id == b.id
        override fun areContentsTheSame(a: CardItem, b: CardItem) = a == b
    }
    class Holder(val binding: ItemCardBinding) : RecyclerView.ViewHolder(binding.root)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(ItemCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
        holder.binding.title.text = item.title; holder.binding.icon.text = item.icon; holder.binding.subtitle.text = item.subtitle; holder.binding.badge.text = item.badge
        holder.binding.subtitle.visibility = if (item.subtitle.isBlank()) View.GONE else View.VISIBLE
        holder.binding.root.setOnClickListener { click(item) }
    }
}
