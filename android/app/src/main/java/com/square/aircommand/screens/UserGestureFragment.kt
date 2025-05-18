package com.square.aircommand.screens

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.square.aircommand.R
import com.square.aircommand.databinding.FragmentUserGestureBinding

/**
 * [사용자 제스처 등록 화면]
 * - 사용자가 새 제스처의 이름을 등록하고, 중복 확인을 거친 후 촬영을 시작할 수 있는 화면
 */
class UserGestureFragment : Fragment() {

    private var _binding: FragmentUserGestureBinding? = null
    private val binding get() = _binding!!

    private lateinit var gestureNameList: List<String> // 📌 string-array에 정의된 기존 제스처 이름 목록

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // 🔧 뷰 바인딩 초기화
        _binding = FragmentUserGestureBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 🔄 기존 등록된 제스처 이름 목록 불러오기 (res/values/strings.xml > gesture_name_list)
        gestureNameList = resources.getStringArray(R.array.gesture_name_list).toList()

        // 🚫 제스처 촬영 버튼은 이름이 중복되지 않을 때만 활성화됨
        binding.btnStartGestureShooting.isEnabled = false

        // 🔙 뒤로가기 버튼 → 이전 화면으로 이동
        binding.backButton.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        // ✅ [중복 확인] 버튼 클릭 이벤트 처리
        binding.btnCheckDuplicate.setOnClickListener {
            val gestureName = binding.gestureNameEditText.text.toString().trim() // 사용자가 입력한 이름

            when {
                gestureName.isEmpty() -> {
                    // 입력값 없음
                    binding.duplicateCheckResultText.text = "이름을 작성해주세요."
                    binding.btnStartGestureShooting.isEnabled = false
                }
                isGestureNameDuplicate(gestureName) -> {
                    // 이름이 기존 목록과 중복됨
                    binding.duplicateCheckResultText.text = "중복된 이름입니다."
                    binding.btnStartGestureShooting.isEnabled = false
                }
                else -> {
                    // 사용 가능한 이름
                    binding.duplicateCheckResultText.text = "등록할 수 있는 이름입니다. [제스처 촬영]을 눌러 촬영을 시작해주세요"
                    binding.btnStartGestureShooting.isEnabled = true
                }
            }

            // 결과 문구 표시
            binding.duplicateCheckResultText.visibility = View.VISIBLE
        }

        // 🎥 [제스처 촬영] 버튼 클릭 → 촬영 화면(GestureShootingFragment)으로 이동
        binding.btnStartGestureShooting.setOnClickListener {
            findNavController().navigate(R.id.action_userGestureFragment_to_gestureShootingFragment)
        }
    }

    /**
     * 🔎 입력한 이름이 기존 제스처 이름과 중복되는지 검사
     */
    private fun isGestureNameDuplicate(name: String): Boolean {
        return gestureNameList.contains(name)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 🧹 메모리 누수 방지를 위한 뷰 바인딩 정리
        _binding = null
    }
}