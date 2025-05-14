package com.square.aircommand.screens

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.square.aircommand.R
import com.square.aircommand.databinding.FragmentGestureShootingBinding

class GestureShootingFragment : Fragment() {

    private var _binding: FragmentGestureShootingBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGestureShootingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 뒤로가기 버튼 클릭 시 이전 프래그먼트로 이동
        binding.backButton.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        // 저장 버튼 클릭 -> UserGestureFragment로 이동
        binding.saveButton.setOnClickListener {
            findNavController().navigate(R.id.action_gestureShooting_to_userGesture)
        }

        // 다시 촬영 버튼 클릭 -> 카메라 다시 실행
//        binding.retakeButton.setOnClickListener {
//            restartCamera()
//        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
