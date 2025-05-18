package com.square.aircommand.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.square.aircommand.R
import com.square.aircommand.backgroundcamera.CameraService
import com.square.aircommand.databinding.FragmentAirCommandBinding

class AirCommandFragment : Fragment() {

    private var _binding: FragmentAirCommandBinding? = null
    private val binding get() = _binding!!

    // 시간 옵션 리스트 (사용자 선택용 팝업 메뉴에 사용됨)
    private val timeOptions = listOf("설정 안 함", "1시간", "2시간", "4시간", "끄지 않음")

    // 카메라 사용을 위한 필수 권한 목록
    private val CAMERA_PERMISSIONS = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.FOREGROUND_SERVICE_CAMERA
    )

    // 권한 요청 식별 코드
    private val REQUEST_CAMERA_PERMISSIONS = 1001

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // ViewBinding 초기화
        _binding = FragmentAirCommandBinding.inflate(inflater, container, false)

        // SharedPreferences에서 저장된 시간 설정 불러오기
        val prefs = requireContext().getSharedPreferences("air_command_prefs", Context.MODE_PRIVATE)
        val savedTime = prefs.getString("selected_time", "설정 안 함")
        binding.btnSelectTime.text = savedTime

        // 시간 선택 버튼 클릭 시 팝업 메뉴 표시
        binding.btnSelectTime.setOnClickListener {
            val popup = PopupMenu(requireContext(), binding.btnSelectTime)
            timeOptions.forEachIndexed { index, option ->
                popup.menu.add(0, index, index, option)
            }

            // 사용자가 선택한 시간을 SharedPreferences에 저장
            popup.setOnMenuItemClickListener { item ->
                val selectedTime = timeOptions[item.itemId]
                binding.btnSelectTime.text = selectedTime
                prefs.edit().putString("selected_time", selectedTime).apply()
                true
            }

            popup.show()
        }

        // 'AirCommand 기능 사용' 스위치의 상태에 따라 텍스트 표시
        binding.switchUse.setOnCheckedChangeListener { _, isChecked ->
            binding.tvUseStatus.text = if (isChecked) "사용 중" else "사용 안 함"
        }

        // ✅ 백그라운드 카메라 서비스 사용 토글 스위치
        binding.switchCamera.setOnCheckedChangeListener { _, isChecked ->
            val context = requireContext()
            val intent = Intent(context, CameraService::class.java)

            if (isChecked) {
                // 카메라 권한이 있으면 서비스 시작, 없으면 권한 요청
                if (hasCameraPermissions()) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    ActivityCompat.requestPermissions(
                        requireActivity(),
                        CAMERA_PERMISSIONS,
                        REQUEST_CAMERA_PERMISSIONS
                    )
                }
            } else {
                // 스위치 OFF 시 서비스 중단
                context.stopService(intent)
            }
        }

        // 제스처 설정 화면 이동 버튼
        binding.btnGestureSetting.setOnClickListener {
            findNavController().navigate(R.id.action_airCommand_to_gestureSetting)
        }

        // 사용자 정의 제스처 화면 이동 버튼
        binding.btnUserGesture.setOnClickListener {
            findNavController().navigate(R.id.action_airCommand_to_userGesture)
        }

        // 테스트 화면 이동 버튼
        binding.btnTest.setOnClickListener {
            findNavController().navigate(R.id.action_airCommand_to_testFragment)
        }

        return binding.root
    }

    // 📸 카메라 및 포그라운드 서비스 권한이 있는지 확인하는 함수
    private fun hasCameraPermissions(): Boolean {
        val context = requireContext()
        return CAMERA_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    // 🛡️ 권한 요청 결과 처리
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_CAMERA_PERMISSIONS) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                // 권한 승인 시 백그라운드 카메라 서비스 시작
                val intent = Intent(requireContext(), CameraService::class.java)
                ContextCompat.startForegroundService(requireContext(), intent)
            } else {
                // 권한 거부 시 스위치 OFF 처리
                binding.switchCamera.isChecked = false
            }
        }
    }

    // ViewBinding 메모리 누수 방지를 위한 해제 처리
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}