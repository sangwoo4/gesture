package com.square.aircommand.screens

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.square.aircommand.R
import com.square.aircommand.databinding.FragmentUserGestureBinding


class UserGestureFragment : Fragment() {

    private var _binding: FragmentUserGestureBinding? = null
    private val binding get() = _binding!!

    private lateinit var gestureNameList: List<String> // 제스처 이름 목록

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): androidx.constraintlayout.widget.ConstraintLayout {
        _binding = FragmentUserGestureBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // string-array에서 제스처 이름 목록 불러오기
        gestureNameList = resources.getStringArray(R.array.gesture_name_list).toList()

        // 초기에는 제스처 촬영 버튼 비활성화
        binding.btnStartGestureShooting.isEnabled = false

        // 뒤로가기 버튼 클릭 시 이전 프래그먼트로 이동
        binding.backButton.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        // 중복 검사 버튼 클릭 이벤트
        binding.btnCheckDuplicate.setOnClickListener {
            val gestureName = binding.gestureNameEditText.text.toString().trim()

            when {
                gestureName.isEmpty() -> {
                    binding.duplicateCheckResultText.text = "이름을 작성해주세요."
                    binding.btnStartGestureShooting.isEnabled = false
                }
                isGestureNameDuplicate(gestureName) -> {
                    binding.duplicateCheckResultText.text = "중복된 이름입니다."
                    binding.btnStartGestureShooting.isEnabled = false
                }
                else -> {
                    binding.duplicateCheckResultText.text = "등록할 수 있는 이름입니다. [제스처 촬영]을 눌러 촬영을 시작해주세요"
                    binding.btnStartGestureShooting.isEnabled = true
                }
            }

            binding.duplicateCheckResultText.visibility = View.VISIBLE
        }

        // 제스처 촬영 버튼 클릭 시 프래그먼트 이동
        binding.btnStartGestureShooting.setOnClickListener {
            findNavController().navigate(R.id.action_userGestureFragment_to_gestureShootingFragment)
        }
    }

    // 문자열 배열 기반 중복 확인 함수
    private fun isGestureNameDuplicate(name: String): Boolean {
        return gestureNameList.contains(name)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}