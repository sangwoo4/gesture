package com.square.aircommand.screens

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.skydoves.powermenu.MenuAnimation
import com.skydoves.powermenu.PowerMenu
import com.skydoves.powermenu.PowerMenuItem
import com.square.aircommand.R
import com.square.aircommand.databinding.FragmentGestureSettingBinding
import com.square.aircommand.gesture.GestureAction
import com.square.aircommand.gesture.GestureLabel
import android.graphics.Typeface
import com.skydoves.powermenu.CircularEffect


/**
 * 사용자가 제스처(PAPER, ROCK, SCISSORS, ONE)와 기능(GestureAction)을 매핑할 수 있는 화면(Fragment)
 * 각 제스처에 대해 Spinner를 통해 동작을 선택하면 SharedPreferences에 저장됨
 */
class GestureSettingFragment : Fragment() {

    private var _binding: FragmentGestureSettingBinding? = null
    private val binding get() = _binding!!

    private val prefsName = "gesture_prefs"
    private val selectedActions = mutableMapOf<GestureLabel, GestureAction>()

    // PowerMenu를 Gesture마다 따로 관리하기 위해 Map으로 관리
    private val powerMenus = mutableMapOf<GestureLabel, PowerMenu>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGestureSettingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val prefs = requireContext().getSharedPreferences(prefsName, 0)
        val options = resources.getStringArray(R.array.gesture_action_options)
        val noneDisplay = GestureAction.NONE.displayName

        // 각 제스처별 이전에 저장된 값 불러오기
        val paperAction = prefs.getString("gesture_paper_action", noneDisplay) ?: noneDisplay
        val rockAction = prefs.getString("gesture_rock_action", noneDisplay) ?: noneDisplay
        val scissorsAction = prefs.getString("gesture_scissors_action", noneDisplay) ?: noneDisplay
        val oneAction = prefs.getString("gesture_one_action", noneDisplay) ?: noneDisplay

        // 텍스트뷰 + 파워메뉴 방식으로 설정
        setupGestureDropdown(binding.paperTextView, GestureLabel.PAPER, paperAction, "gesture_paper_action", options)
        setupGestureDropdown(binding.rockTextView, GestureLabel.ROCK, rockAction, "gesture_rock_action", options)
        setupGestureDropdown(binding.scissorsTextView, GestureLabel.SCISSORS, scissorsAction, "gesture_scissors_action", options)
        setupGestureDropdown(binding.oneTextView, GestureLabel.ONE, oneAction, "gesture_one_action", options)

        binding.backButton.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
    }

    /**
     * TextView를 클릭하면 PowerMenu가 나오고, 선택 시 텍스트 변경 및 SharedPreferences 저장
     */
    private fun setupGestureDropdown(
        targetView: TextView,
        label: GestureLabel,
        initialValue: String,
        prefsKey: String,
        options: Array<String>
    ) {
        val prefs = requireContext().getSharedPreferences(prefsName, 0)

        // 초기 텍스트 셋팅
        targetView.text = initialValue

        // 기존에 저장된 PowerMenu 있으면 제거
        powerMenus[label]?.dismiss()

        targetView.setOnClickListener {
            // 클릭 시마다 이전 메뉴 닫기
            powerMenus[label]?.dismiss()

            val currentText = targetView.text.toString()

            // 클릭 시점의 현재 텍스트 기준으로 선택 상태 표시하며 PowerMenu 생성
            val powerMenu = PowerMenu.Builder(requireContext())
                .addItemList(options.map { PowerMenuItem(it, it == currentText) })
                .setAnimation(MenuAnimation.SHOWUP_TOP_LEFT)
                .setMenuRadius(50f)
                .setMenuShadow(15f)
                .setCircularEffect(CircularEffect.BODY)
                .setTextColor(ContextCompat.getColor(requireContext(), R.color.menu_text_color))
                .setTextGravity(Gravity.CENTER)
                .setTextTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
                .setSelectedTextColor(0xFFFFFFFF.toInt())  // 흰색
//                .setMenuColor(0xFF000000.toInt())
                .setMenuColor(ContextCompat.getColor(requireContext(), R.color.menu_color))

                .setSelectedMenuColor(ContextCompat.getColor(requireContext(), R.color.colorPrimary))
                .setOnMenuItemClickListener { position, item ->
                    targetView.text = item.title
                    prefs.edit().putString(prefsKey, item.title.toString()).apply()

                    val selectedAction = GestureAction.entries.firstOrNull { it.displayName == item.title }
                        ?: GestureAction.NONE

                    if (selectedAction == GestureAction.NONE) {
                        selectedActions.remove(label)
                    } else {
                        selectedActions[label] = selectedAction
                    }

                    powerMenus[label]?.dismiss()
                }
                .build()

            powerMenus[label] = powerMenu
            powerMenu.showAsAnchorLeftBottom(it)  // 메뉴 표시
        }
    }



    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        // 모든 PowerMenu 종료
        powerMenus.values.forEach { it.dismiss() }
        powerMenus.clear()
    }
}

