package com.square.aircommand.screens

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import com.square.aircommand.R
import com.square.aircommand.classifier.GestureLabelMapper
import com.square.aircommand.databinding.FragmentGestureSettingBinding
import com.square.aircommand.gesture.GestureAction

/**
 * 사용자가 제스처와 기능(GestureAction)을 매핑할 수 있는 화면
 * - 기본 제스처(PAPER, ROCK, SCISSORS, ONE)는 고정 Spinner 제공
 * - 사용자 학습 제스처는 gesture_labels.json 기반으로 동적으로 생성
 */
class GestureSettingFragment : Fragment() {

    private var _binding: FragmentGestureSettingBinding? = null
    private val binding get() = _binding!!

    private val prefsName = "gesture_prefs"
    private val selectedActions = mutableMapOf<String, GestureAction>()

    private lateinit var gestureLabelMapper: GestureLabelMapper

    private val defaultGestures = listOf("PAPER", "ROCK", "SCISSORS", "ONE")

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

        // ✅ 1. 기본 제스처 Spinner 연결
        setupSpinner(binding.spinnerPaper, "PAPER", prefs.getString("gesture_paper_action", noneDisplay) ?: noneDisplay, "gesture_paper_action", options)
        setupSpinner(binding.spinnerRock, "ROCK", prefs.getString("gesture_rock_action", noneDisplay) ?: noneDisplay, "gesture_rock_action", options)
        setupSpinner(binding.spinnerScissors, "SCISSORS", prefs.getString("gesture_scissors_action", noneDisplay) ?: noneDisplay, "gesture_scissors_action", options)
        setupSpinner(binding.spinnerOne, "ONE", prefs.getString("gesture_one_action", noneDisplay) ?: noneDisplay, "gesture_one_action", options)

        // ✅ 2. 사용자 학습 제스처만 UI 추가
        val userGestures = gestureLabelMapper.getAllLabels().values
            .filter { it !in defaultGestures }
            .sorted()

        for (label in userGestures) {
            val (rowLayout, spinner) = createGestureRow(label)
            binding.customGestureContainer.addView(rowLayout)

            val savedValue = prefs.getString("gesture_${label.lowercase()}_action", noneDisplay) ?: noneDisplay
            setupSpinner(spinner, label, savedValue, "gesture_${label.lowercase()}_action", options)
        }

        // 🔙 뒤로가기
        binding.backButton.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
    }

    /**
     * Spinner와 기능 연결 설정
     */
    private fun setupSpinner(
        spinner: Spinner,
        label: String,
        initialValue: String,
        prefsKey: String,
        options: Array<String>
    ) {
        val context = requireContext()
        val prefs = context.getSharedPreferences(prefsName, 0)

        spinner.adapter = ArrayAdapter(
            context,
            R.layout.spinner_text,
            options
        ).also {
            it.setDropDownViewResource(R.layout.spinner_text)
        }

        spinner.setSelection(options.indexOf(initialValue).coerceAtLeast(0))

        GestureAction.entries.firstOrNull { it.displayName == initialValue }?.let { action ->
            if (action != GestureAction.NONE) selectedActions[label] = action
        }

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedDisplayName = options[position]
                val selectedAction = GestureAction.entries.firstOrNull { it.displayName == selectedDisplayName }
                    ?: GestureAction.NONE

                prefs.edit().putString(prefsKey, selectedDisplayName).apply()

                if (selectedAction == GestureAction.NONE) {
                    selectedActions.remove(label)
                } else {
                    selectedActions[label] = selectedAction
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /**
     * 사용자 정의 제스처 UI 행 생성 (TextView + Spinner)
     */
    private fun createGestureRow(label: String): Pair<LinearLayout, Spinner> {
        val context = requireContext()

        val rowLayout = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 24, 0, 0)
            }
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12, 0, 12)
        }

        val textView = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = "$label 제스처"
            textSize = 20f
            setTextColor(resources.getColor(R.color.black, null))
        }

        val spinner = Spinner(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(8, 0, 0, 0)
            }
            id = View.generateViewId()
            setPopupBackgroundResource(R.drawable.spinner_background)
        }

        rowLayout.addView(textView)
        rowLayout.addView(spinner)

        return Pair(rowLayout, spinner)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}