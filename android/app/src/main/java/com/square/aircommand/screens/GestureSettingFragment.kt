package com.square.aircommand.screens

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.square.aircommand.R
import com.square.aircommand.databinding.FragmentGestureSettingBinding
import com.square.aircommand.gesture.GestureAction
import com.square.aircommand.gesture.GestureLabel

/**
 * 사용자가 제스처(PAPER, ROCK, SCISSORS, ONE)와 기능(GestureAction)을 매핑할 수 있는 화면(Fragment)
 * 각 제스처에 대해 Spinner를 통해 동작을 선택하면 SharedPreferences에 저장됨
 */
class GestureSettingFragment : Fragment() {

    // ViewBinding 객체 (Fragment의 레이아웃 요소에 접근하기 위함)
    private var _binding: FragmentGestureSettingBinding? = null
    private val binding get() = _binding!!

    // SharedPreferences 저장소 이름 정의
    private val prefsName = "gesture_prefs"

    // 현재 선택된 제스처 → 동작 매핑 (GestureAction.NONE은 제외)
    private val selectedActions = mutableMapOf<GestureLabel, GestureAction>()

    // Fragment의 View 생성 및 바인딩 연결
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGestureSettingBinding.inflate(inflater, container, false)
        return binding.root
    }

    // View 생성 후 초기 설정 수행 (SharedPreferences 불러오기, Spinner 연결 등)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val prefs = requireContext().getSharedPreferences(prefsName, 0)
        val options = resources.getStringArray(R.array.gesture_action_options) // 스피너 항목 목록
        val noneDisplay = GestureAction.NONE.displayName

        // SharedPreferences에서 각 제스처의 초기 설정값 불러오기 (기본값 지정)
        val paperAction = prefs.getString("gesture_paper_action", GestureAction.VOLUME_UP.displayName) ?: noneDisplay
        val rockAction = prefs.getString("gesture_rock_action", GestureAction.TOGGLE_FLASH.displayName) ?: noneDisplay
        val scissorsAction = prefs.getString("gesture_scissors_action", GestureAction.SWIPE_RIGHT.displayName) ?: noneDisplay
        val oneAction = prefs.getString("gesture_one_action", GestureAction.SWIPE_DOWN.displayName) ?: noneDisplay

        // 각 Spinner에 동작 설정 로직 연결
        setupSpinner(binding.spinnerPaper, GestureLabel.PAPER, paperAction, "gesture_paper_action", options)
        setupSpinner(binding.spinnerRock, GestureLabel.ROCK, rockAction, "gesture_rock_action", options)
        setupSpinner(binding.spinnerScissors, GestureLabel.SCISSORS, scissorsAction, "gesture_scissors_action", options)
        setupSpinner(binding.spinnerOne, GestureLabel.ONE, oneAction, "gesture_one_action", options)

        // 뒤로가기 버튼 클릭 시 이전 화면으로 돌아감
        binding.backButton.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
    }

    /**
     * 각 제스처에 대해 Spinner를 초기화하고 선택 이벤트를 처리하는 함수
     * @param spinner 현재 연결할 Spinner
     * @param label 해당 Spinner와 연결된 제스처 라벨
     * @param initialValue 초기 선택값
     * @param prefsKey SharedPreferences에 저장될 키
     * @param options Spinner 항목 목록 (displayName 배열)
     */
    private fun setupSpinner(
        spinner: Spinner,
        label: GestureLabel,
        initialValue: String,
        prefsKey: String,
        options: Array<String>
    ) {
        val context = requireContext()
        val prefs = context.getSharedPreferences(prefsName, 0)

        // Spinner에 커스텀 어댑터 설정 (글자색 포함된 spinner_text.xml 사용)
        spinner.adapter = ArrayAdapter(
            context,
            R.layout.spinner_text,   // Spinner의 기본 표시용 레이아웃 (텍스트 색 포함)
            options
        ).also {
            it.setDropDownViewResource(R.layout.spinner_text) // 드롭다운 항목도 동일한 레이아웃 사용
        }

        // 초기 선택값 설정
        spinner.setSelection(options.indexOf(initialValue).coerceAtLeast(0))

        // 초기 선택값이 NONE이 아니면 Map에 등록
        GestureAction.entries.firstOrNull { it.displayName == initialValue }?.let { action ->
            if (action != GestureAction.NONE) selectedActions[label] = action
        }

        // Spinner 아이템 선택 이벤트 처리
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedDisplayName = options[position]
                val selectedAction = GestureAction.entries.firstOrNull { it.displayName == selectedDisplayName }
                    ?: GestureAction.NONE

                // 다른 제스처에 이미 같은 동작이 설정되어 있는지 중복 검사 (NONE은 허용)
                if (selectedAction != GestureAction.NONE) {
                    val conflict = selectedActions.any { (otherLabel, otherAction) ->
                        otherLabel != label && otherAction == selectedAction
                    }
                    if (conflict) {
                        // 충돌 발생 시 이전 값으로 롤백
                        Toast.makeText(context, "이미 다른 제스처에 설정된 동작입니다.", Toast.LENGTH_SHORT).show()
                        val prevAction = selectedActions[label] ?: GestureAction.NONE
                        spinner.setSelection(options.indexOf(prevAction.displayName).coerceAtLeast(0))
                        return
                    }
                }

                // SharedPreferences에 선택 결과 저장
                prefs.edit().putString(prefsKey, selectedDisplayName).apply()

                // 선택 상태 업데이트
                if (selectedAction == GestureAction.NONE) {
                    selectedActions.remove(label)
                } else {
                    selectedActions[label] = selectedAction
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // 뷰가 파괴될 때 바인딩 해제하여 메모리 누수 방지
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
