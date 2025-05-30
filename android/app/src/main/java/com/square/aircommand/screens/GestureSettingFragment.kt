package com.square.aircommand.screens


import android.app.AlertDialog
import android.content.Intent

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Typeface
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.widget.PopupWindowCompat.showAsDropDown
import androidx.fragment.app.Fragment
import com.bumptech.glide.load.model.ByteArrayLoader
import com.skydoves.powermenu.CircularEffect
import com.skydoves.powermenu.MenuAnimation
import com.skydoves.powermenu.PowerMenu
import com.skydoves.powermenu.PowerMenuItem
import com.square.aircommand.R
import com.square.aircommand.classifier.GestureLabelMapper
import com.square.aircommand.databinding.FragmentGestureSettingBinding
import com.square.aircommand.gesture.GestureAction
import com.square.aircommand.gesture.GestureActionExecutor

class GestureSettingFragment : Fragment() {

    private var _binding: FragmentGestureSettingBinding? = null
    private val binding get() = _binding!!

    private val prefsName = "gesture_prefs"
    private val selectedActions = mutableMapOf<String, GestureAction>()
    private val powerMenus = mutableMapOf<String, PowerMenu>()

    private lateinit var gestureLabelMapper: GestureLabelMapper

    object Converter {
        fun dpToPx(context: Context, dp: Int): Int {
            return (dp * context.resources.displayMetrics.density).toInt()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGestureSettingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        gestureLabelMapper = GestureLabelMapper(requireContext())
        val prefs = requireContext().getSharedPreferences(prefsName, 0)
        val options = resources.getStringArray(R.array.gesture_action_options)
        val noneDisplay = GestureAction.NONE.displayName
        val allLabels = gestureLabelMapper.getAllLabels().values

        val defaultOrder = listOf("paper", "rock", "scissors", "one")
        val basicGestures = allLabels.filter { it.lowercase() in defaultOrder }
        for (label in basicGestures) {
            val (rowLayout, labelView) = createGestureRow(label)
            binding.customGestureContainer.addView(rowLayout)

            val prefsKey = "gesture_${label.lowercase()}_action"
            val savedValue = prefs.getString(prefsKey, noneDisplay) ?: noneDisplay
            setupGestureDropdown(labelView, label, savedValue, prefsKey, options)
        }

        val excludedLabels = listOf("none", "unknown") + defaultOrder
        val userGestures = allLabels.filter { it.lowercase() !in excludedLabels }.sorted()
        for (label in userGestures) {
            val (rowLayout, labelView) = createGestureRow(label)
            binding.customGestureContainer.addView(rowLayout)

            val prefsKey = "gesture_${label.lowercase()}_action"
            val savedValue = prefs.getString(prefsKey, noneDisplay) ?: noneDisplay
            setupGestureDropdown(labelView, label, savedValue, prefsKey, options)
        }

        binding.backButton.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
    }

    private fun setupGestureDropdown(
        targetView: TextView,
        label: String,
        initialValue: String,
        prefsKey: String,
        options: Array<String>
    ) {
        val prefs = requireContext().getSharedPreferences(prefsName, 0)
        targetView.text = initialValue
        powerMenus[label]?.dismiss()

        targetView.setOnClickListener {
            powerMenus[label]?.dismiss()
            val currentText = targetView.text.toString()

            val typeface = ResourcesCompat.getFont(requireContext(), R.font.binggrae1)

            val powerMenu = PowerMenu.Builder(requireContext())
                .addItemList(options.map { PowerMenuItem(it, it == currentText) })
                .setAnimation(MenuAnimation.SHOW_UP_CENTER)
                .setMenuRadius(50f)
                .setMenuShadow(15f)
                .setCircularEffect(CircularEffect.BODY)
                .setTextColor(ContextCompat.getColor(requireContext(), R.color.menu_text_color))
                .setTextGravity(Gravity.CENTER)
                .setTextTypeface(typeface)
                .setSelectedTextColor(0xFFFFFFFF.toInt())
                .setMenuColor(ContextCompat.getColor(requireContext(), R.color.menu_color))
                .setSelectedMenuColor(
                    ContextCompat.getColor(
                        requireContext(),
                        R.color.colorPrimary
                    )
                )
                .setOnMenuItemClickListener { _, item ->
                    targetView.text = item.title
                    prefs.edit().putString(prefsKey, item.title.toString()).apply()
                    val selectedAction =
                        GestureAction.entries.firstOrNull { it.displayName == item.title }
                            ?: GestureAction.NONE
                    if (selectedAction == GestureAction.NONE) {
                        selectedActions.remove(label)
                    } else {
                        selectedActions[label] = selectedAction
                    }

                    // 🎵 [추가]: 음악 제어 기능을 선택한 경우 권한 안내
                    if (selectedAction == GestureAction.PLAY_PAUSE_MUSIC &&
                        !GestureActionExecutor.hasNotificationAccess(requireContext())
                    ) {
                        AlertDialog.Builder(requireContext())
                            .setTitle("알림 접근 권한 필요")
                            .setMessage("음악 제어 기능을 사용하려면 알림 접근 권한이 필요합니다.")
                            .setPositiveButton("설정으로 이동") { _, _ ->
                                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                startActivity(intent)
                            }
                            .setNegativeButton("취소", null)
                            .show()
                    }

                    powerMenus[label]?.dismiss()
                }
                .setWidth(Converter.dpToPx(requireContext(), 200))
                .build()

            powerMenus[label] = powerMenu
            powerMenu.showAtCenter(targetView)
        }
    }

    private fun createGestureRow(label: String): Pair<LinearLayout, TextView> {
        val context = requireContext()
        val displayLabel = when (label.lowercase()) {
            "paper" -> "보 제스처"
            "rock" -> "주먹 제스처"
            "scissors" -> "가위 제스처"
            "one" -> "하나 제스처"
            else -> "$label 제스처"
        }

        val rowLayout = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dp(12), 0, dp(12))
            }
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        val labelText = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = displayLabel
            textSize = 12f
            setTextColor(context.getColor(R.color.black))
        }

        val actionTextView = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            id = View.generateViewId()
            setBackgroundResource(R.drawable.spinner_background)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setTextColor(context.getColor(R.color.black))
        }

        rowLayout.addView(labelText)
        rowLayout.addView(actionTextView)

        return Pair(rowLayout, actionTextView)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        powerMenus.values.forEach { it.dismiss() }
        powerMenus.clear()
    }
}