package com.square.aircommand.screens

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.square.aircommand.R
import com.square.aircommand.backgroundcamera.CameraService
import com.square.aircommand.databinding.FragmentAirCommandBinding

class AirCommandFragment : Fragment() {

    private var _binding: FragmentAirCommandBinding? = null
    private val binding get() = _binding!!

    private val timeOptions = listOf("설정 안 함", "1시간", "2시간", "4시간", "끄지 않음")

    private val CAMERA_PERMISSIONS = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.FOREGROUND_SERVICE_CAMERA
    )

    private val REQUEST_CAMERA_PERMISSIONS = 1001
    private val PREFS_KEY_CAMERA_ENABLED = "camera_service_enabled"

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAirCommandBinding.inflate(inflater, container, false)

        val prefs = requireContext().getSharedPreferences("air_command_prefs", Context.MODE_PRIVATE)
        val savedTime = prefs.getString("selected_time", "설정 안 함")
        binding.btnSelectTime.text = savedTime

        // 백그라운드 자동 종료 시간 선택 팝업
        binding.btnSelectTime.setOnClickListener {
            val popup = PopupMenu(requireContext(), binding.btnSelectTime)
            timeOptions.forEachIndexed { index, option ->
                popup.menu.add(0, index, index, option)
            }

            popup.setOnMenuItemClickListener { item ->
                val selectedTime = timeOptions[item.itemId]
                binding.btnSelectTime.text = selectedTime
                prefs.edit { putString("selected_time", selectedTime) }
                true
            }

            popup.show()
        }

        // 'AirCommand 기능 사용' 스위치 텍스트 상태 설정
        binding.switchUse.setOnCheckedChangeListener { _, isChecked ->
            binding.tvUseStatus.text = if (isChecked) "사용 중" else "사용 안 함"
        }

        // 백그라운드 카메라 스위치 (직접 조작 시)
        binding.switchCamera.setOnCheckedChangeListener { _, isChecked ->
            val context = requireContext()
            val intent = Intent(context, CameraService::class.java)

            // 사용자 설정 저장
            prefs.edit { putBoolean(PREFS_KEY_CAMERA_ENABLED, isChecked) }

            if (isChecked) {
                // 접근성 권한 미허용 시 설정 유도
                if (!isAccessibilityServiceEnabled(context)) {
                    AlertDialog.Builder(context)
                        .setTitle("✔ 접근성 권한이 필요합니다")
                        .setMessage(
                            "1. \"설정 열기\" 버튼을 눌러주세요\n" +
                                    "2. 목록에서 'AirCommand'를 선택\n" +
                                    "3. 스위치를 '사용 중'으로 켜고 확인"
                        )
                        .setPositiveButton("설정 열기") { _, _ ->
                            val settingsIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            settingsIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            startActivity(settingsIntent)
                        }
                        .setNegativeButton("취소") { _, _ ->
                            binding.switchCamera.isChecked = false
                        }
                        .show()
                    return@setOnCheckedChangeListener
                }

                // 권한 여부에 따라 카메라 서비스 시작 or 권한 요청
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
                context.stopService(intent)
            }
        }

        // 이동 버튼
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

    // ✅ 앱 시작 또는 접근성 설정 후 복귀 시 호출
    override fun onResume() {
        super.onResume()
        Log.d("AirCommandFragment", "🔁 onResume 호출됨")

        val context = requireContext()
        val prefs = context.getSharedPreferences("air_command_prefs", Context.MODE_PRIVATE)
        val autoStartEnabled = prefs.getBoolean(PREFS_KEY_CAMERA_ENABLED, false)

        val accessibility = isAccessibilityServiceEnabled(context)
        val cameraGranted = hasCameraPermissions()

        Log.d("AirCommandFragment", "접근성 권한: $accessibility, 카메라 권한: $cameraGranted")

        // ✅ 카메라 권한이 없으면 앱 실행 시 요청 (최초 한 번)
        if (!cameraGranted) {
            ActivityCompat.requestPermissions(
                requireActivity(),
                CAMERA_PERMISSIONS,
                REQUEST_CAMERA_PERMISSIONS
            )
        }

        // ✅ 백그라운드 카메라 서비스 자동 시작 조건 확인
        if (autoStartEnabled && !binding.switchCamera.isChecked && accessibility && cameraGranted) {
            Log.d("AirCommandFragment", "✅ 조건 만족 → CameraService 자동 시작")
            ContextCompat.startForegroundService(context, Intent(context, CameraService::class.java))
            binding.switchCamera.isChecked = true
        }
    }

    // ✅ 카메라 권한 요청 결과 처리
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_CAMERA_PERMISSIONS) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Log.d("AirCommandFragment", "📸 카메라 권한 승인됨")
                val intent = Intent(requireContext(), CameraService::class.java)
                ContextCompat.startForegroundService(requireContext(), intent)
            } else {
                Log.w("AirCommandFragment", "❌ 카메라 권한 거부됨")
                binding.switchCamera.isChecked = false
            }
        }
    }

    // ✅ 카메라 권한 확인
    private fun hasCameraPermissions(): Boolean {
        val context = requireContext()
        return CAMERA_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    // ✅ 접근성 서비스 활성화 여부 확인
    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val serviceId = "${context.packageName}/com.square.aircommand.gesture.GestureAccessibilityService"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val accessibilityEnabled = Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED, 0
        )
        return accessibilityEnabled == 1 && enabledServices.contains(serviceId)
    }

    // ViewBinding 메모리 해제
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}