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
 * 제스처 기능 설정 화면 (GestureSettingFragment)
 * - gesture_labels.json 파일에서 제스처 목록을 불러옴
 * - NONE 및 UNKNOWN만 제외하고 모든 제스처 표시
 */
class GestureSettingFragment : Fragment() {

    private var _binding: FragmentGestureSettingBinding? = null
    private val binding get() = _binding!!

    private val prefsName = "gesture_prefs"
    private val selectedActions = mutableMapOf<String, GestureAction>()

    private lateinit var gestureLabelMapper: GestureLabelMapper

    // 제외할 기본 제스처
    private val excludedLabels = listOf("NONE", "UNKNOWN")

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

        // ✅ 1. 기본 제스처 (상단 고정)
        val defaultOrder = listOf("paper", "rock", "scissors", "one")
        val basicGestures = allLabels.filter { it.lowercase() in defaultOrder }

        for (label in basicGestures) {
            val (rowLayout, spinner) = createGestureRow(label)
            binding.customGestureContainer.addView(rowLayout)

            val prefsKey = "gesture_${label.lowercase()}_action"
            val savedValue = prefs.getString(prefsKey, noneDisplay) ?: noneDisplay
            setupSpinner(spinner, label, savedValue, prefsKey, options)
        }

        // ✅ 2. 사용자 정의 제스처 (알파벳순, 하단에 추가)
        val excludedLabels = listOf("none", "unknown") + defaultOrder
        val userGestures = allLabels
            .filter { it.lowercase() !in excludedLabels }
            .sorted()

        for (label in userGestures) {
            val (rowLayout, spinner) = createGestureRow(label)
            binding.customGestureContainer.addView(rowLayout)

            val prefsKey = "gesture_${label.lowercase()}_action"
            val savedValue = prefs.getString(prefsKey, noneDisplay) ?: noneDisplay
            setupSpinner(spinner, label, savedValue, prefsKey, options)
        }

        // 🔙 뒤로가기 버튼 처리
        binding.backButton.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
    }

    /**
     * Spinner UI 설정 및 SharedPreferences 저장 처리
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

        // 초기 선택값 설정
        spinner.setSelection(options.indexOf(initialValue).coerceAtLeast(0))

        // 선택 항목에 따라 SharedPreferences 저장
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedDisplayName = options[position]
                val selectedAction = GestureAction.entries.firstOrNull {
                    it.displayName == selectedDisplayName
                } ?: GestureAction.NONE

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
     * 제스처 행 동적 생성 (TextView + Spinner)
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
            textSize = 15f
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