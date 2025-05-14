package com.square.aircommand.screens

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.square.aircommand.databinding.FragmentAirCommandBinding
import com.square.aircommand.R

class AirCommandFragment : Fragment() {
    private var _binding: FragmentAirCommandBinding? = null
    private val binding get() = _binding!!

    private val timeOptions = listOf("설정 안 함", "1시간", "2시간", "4시간", "끄지 않음")

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAirCommandBinding.inflate(inflater, container, false)

        // SharedPreferences에서 저장된 값 불러오기
        val prefs = requireContext().getSharedPreferences("air_command_prefs", Context.MODE_PRIVATE)
        val savedTime = prefs.getString("selected_time", "설정 안 함")
        binding.btnSelectTime.text = savedTime

        // 드롭다운 버튼 설정
        binding.btnSelectTime.setOnClickListener {
            val popup = PopupMenu(requireContext(), binding.btnSelectTime)
            timeOptions.forEachIndexed { index, option ->
                popup.menu.add(0, index, index, option)
            }

            popup.setOnMenuItemClickListener { item ->
                val selectedTime = timeOptions[item.itemId]
                binding.btnSelectTime.text = selectedTime

                // 선택값 저장
                prefs.edit().putString("selected_time", selectedTime).apply()

                true
            }

            popup.show()
        }

        binding.switchUse.setOnCheckedChangeListener { _, isChecked ->
            binding.tvUseStatus.text = if (isChecked) "사용 중" else "사용 안 함"
        }

        binding.switchCamera.setOnCheckedChangeListener { _, isChecked ->
            // 카메라 권한 처리
        }

        binding.btnGestureSetting.setOnClickListener {
            findNavController().navigate(R.id.action_airCommand_to_gestureSetting)
        }

        binding.btnUserGesture.setOnClickListener {
            findNavController().navigate(R.id.action_airCommand_to_userGesture)
        }

        binding.btnTest.setOnClickListener {
            findNavController().navigate(R.id.action_airCommand_to_testFragment)
        }

        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
