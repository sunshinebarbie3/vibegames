package com.vibe.app.ui.fragment

import android.os.Bundle
import android.view.View
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import coil.load
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.vibe.app.R
import com.vibe.app.core.VibeResult
import com.vibe.app.databinding.FragmentFormBinding
import com.vibe.app.domain.Ids
import com.vibe.app.ui.*
import com.vibe.app.ui.viewmodel.VibeViewModel
import kotlinx.coroutines.flow.first
import java.text.DateFormat
import java.util.Calendar
import java.util.TimeZone

class FormFragment : Fragment(R.layout.fragment_form) {
    private val vm: VibeViewModel by activityViewModels { VibeViewModel.factory(container) }
    private var selectedPhoto: String? = null
    private var photos = listOf<String>()
    private var icon = "🎉"
    private var scheduled: Long? = null
    private var prefilled = false
    private lateinit var b: FragmentFormBinding
    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@registerForActivityResult
        if (photos.size >= 10) { snack(R.string.memory_photo_limit); return@registerForActivityResult }
        selectedPhoto = uri.toString()
        if (arguments?.getString("mode") != "profile") photos = photos + uri.toString()
        b.photoPreview.visibility = View.VISIBLE; b.photoPreview.load(uri)
    }
    override fun onViewCreated(view: View, state: Bundle?) {
        b = FragmentFormBinding.bind(view)
        val mode = arguments?.getString("mode") ?: "createGroup"
        val entityId = arguments?.getString("entityId").orEmpty()
        val groupId = arguments?.getString("groupId").orEmpty()
        selectedPhoto = state?.getString("photo"); photos = state?.getStringArrayList("photos") ?: photos
        scheduled = state?.getLong("scheduled", -1)?.takeIf { it >= 0 }
        icon = state?.getString("icon") ?: icon
        fun show(vararg ids: View) { ids.forEach { it.visibility = View.VISIBLE } }
        listOf<View>(b.nameBox,b.usernameBox,b.descriptionInputBox,b.codeBox,b.pickIcon,b.photo,b.photoPreview,b.dateTime,b.dateLabel,b.rating,b.secondary).forEach { it.visibility = View.GONE }
        b.primary.setText(R.string.action_save)
        when (mode) {
            "createGroup" -> { b.nameBox.hint = getString(R.string.group_name_label); show(b.nameBox,b.pickIcon); b.description.setText(R.string.group_create_help) }
            "joinGroup" -> { show(b.codeBox); b.description.setText(R.string.join_help); b.primary.setText(R.string.action_join_group) }
            "activity" -> { b.nameBox.hint = getString(R.string.activity_title_label); show(b.nameBox,b.descriptionInputBox,b.pickIcon); b.description.setText(R.string.activity_add_help) }
            "profile" -> { show(b.nameBox,b.usernameBox,b.photo); b.description.setText(R.string.profile_photo_cloud_note) }
            "plan" -> { show(b.dateTime,b.dateLabel); b.description.setText(R.string.plan_pick_datetime); scheduled?.let { b.dateLabel.text = DateFormat.getDateTimeInstance().format(it) } }
            "completePlan", "memory" -> { show(b.descriptionInputBox,b.photo,b.rating); b.descriptionInputBox.hint = getString(R.string.memory_caption_label); b.description.setText(R.string.memory_photo_pending); b.primary.setText(R.string.memory_save) }
        }
        b.pickIcon.text = "$icon  ${getString(R.string.group_icon_label)}"
        b.pickIcon.setOnClickListener {
            val icons = resources.getStringArray(if (mode == "activity") R.array.activity_icons else R.array.group_icons)
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.group_icon_label).setItems(icons) { _, i -> icon = icons[i]; b.pickIcon.text = "$icon  ${getString(R.string.group_icon_label)}" }.show()
        }
        b.photo.setOnClickListener { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        b.dateTime.setOnClickListener {
            val date = MaterialDatePicker.Builder.datePicker().setSelection(scheduled ?: MaterialDatePicker.todayInUtcMilliseconds()).build()
            date.addOnPositiveButtonClickListener { utc ->
                val local = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utc }
                val time = MaterialTimePicker.Builder().setTimeFormat(TimeFormat.CLOCK_24H).build()
                time.addOnPositiveButtonClickListener {
                    val value = Calendar.getInstance().apply { set(local.get(Calendar.YEAR),local.get(Calendar.MONTH),local.get(Calendar.DAY_OF_MONTH),time.hour,time.minute,0); set(Calendar.MILLISECOND,0) }
                    scheduled = value.timeInMillis; b.dateLabel.text = DateFormat.getDateTimeInstance().format(value.time)
                }
                time.show(childFragmentManager,"time")
            }; date.show(childFragmentManager,"date")
        }
        b.secondary.setOnClickListener { back() }
        collect(vm.busy) { b.progress.visibility = if (it) View.VISIBLE else View.GONE; b.primary.isEnabled = !it }
        // Populate only once; an emission from sync must not overwrite a draft.
        collect(vm.profile) { user -> if (!prefilled && mode == "profile" && user != null) { prefilled = true; b.name.setText(user.displayName); b.username.setText(user.username); user.photoUri?.let { b.photoPreview.visibility = View.VISIBLE; b.photoPreview.load(it) } } }
        if (mode == "activity" && entityId.isNotBlank()) collect(vm.activity(entityId)) { a -> if (!prefilled && a != null) { prefilled = true; b.name.setText(a.title); b.descriptionInput.setText(a.description); icon = a.icon; b.pickIcon.text = "$icon  ${getString(R.string.group_icon_label)}" } }
        if (mode == "memory") collect(vm.memory(entityId)) { m -> if (!prefilled && m != null) { prefilled = true; b.descriptionInput.setText(m.caption); b.rating.rating = m.rating.toFloat(); photos = m.photoUris; photos.firstOrNull()?.let { b.photoPreview.visibility = View.VISIBLE; b.photoPreview.load(it) } } }
        b.primary.setOnClickListener {
            val name = b.name.text?.toString().orEmpty(); val description = b.descriptionInput.text?.toString().orEmpty()
            vm.action({
                when (mode) {
                    "createGroup" -> vm.c.groupRepository.createGroup(name,icon)
                    "joinGroup" -> vm.c.groupRepository.joinGroup(b.code.text?.toString().orEmpty())
                    "activity" -> if (entityId.isBlank()) vm.c.activityRepository.addActivity(groupId,name,description,icon) else {
                        val a = vm.c.activityRepository.observeById(entityId).first() ?: return@action VibeResult.Problem(detail = "Not found")
                        vm.c.activityRepository.updateActivity(a,name,description,icon)
                    }
                    "profile" -> vm.c.authRepository.updateProfile(name,b.username.text?.toString(),selectedPhoto)
                    "plan" -> if (scheduled == null) VibeResult.Problem(detail = "Pick a date") else vm.c.memoryRepository.savePlan(groupId,entityId,scheduled)
                    "completePlan" -> vm.c.memoryRepository.completePlan(entityId,b.rating.rating.toInt(),description,photos)
                    "memory" -> vm.c.memoryRepository.updateMemory(entityId,b.rating.rating.toInt(),description,photos)
                    else -> VibeResult.Problem(detail = "Unknown form")
                }
            }) { result ->
                if (result is VibeResult.Problem) { b.error.visibility = View.VISIBLE; b.error.setText(result.messageRes()) }
                else when (result) {
                    is VibeResult.Ok<*> -> {
                        when (mode) {
                            "createGroup", "joinGroup" -> { val g = result.value as com.vibe.app.domain.Group; navigate(R.id.groupFragment,"groupId" to g.id) }
                            "plan" -> { snack(R.string.plan_saved); back() }
                            "completePlan" -> { snack(R.string.plan_completed_message); back() }
                            else -> { snack(R.string.action_saved); back() }
                        }
                    }
                    else -> Unit
                }
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); outState.putString("photo",selectedPhoto); outState.putStringArrayList("photos",ArrayList(photos)); outState.putString("icon",icon); scheduled?.let { outState.putLong("scheduled",it) } }
}
