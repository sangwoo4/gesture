package com.square.aircommand.screens

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.transition.TransitionInflater
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.PopupMenu
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.shashank.sony.fancytoastlib.FancyToast
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedElementEnterTransition =
            TransitionInflater.from(requireContext()).inflateTransition(android.R.transition.move)
    }


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

        binding.switchUse.setOnCheckedChangeListener { _, isChecked ->

            val context = requireContext()
            val prefs = context.getSharedPreferences("air_command_prefs", Context.MODE_PRIVATE)
            val intent = Intent(context, CameraService::class.java)

            if (isChecked) {
                // 스위치 ON → 권한 체크 및 서비스 시작

                if (!isAccessibilityServiceEnabled(context)) {
                    FancyToast.makeText(
                        context,
                        "접근성 권한이 필요합니다!",
                        FancyToast.LENGTH_SHORT,
                        FancyToast.WARNING,
                        true
                    ).show()

                    val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_accessibility, null)

                    val dialog = AlertDialog.Builder(context)
                        .setView(dialogView)
                        .setCancelable(false)
                        .create()

                    val window = dialog.window
                    window?.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    window?.setGravity(Gravity.CENTER)
                    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

                    val cancelBtn = dialogView.findViewById<Button>(R.id.btn_cancel)
                    val openBtn = dialogView.findViewById<Button>(R.id.btn_open_settings)

                    cancelBtn.setOnClickListener {
                        binding.switchUse.isChecked = false
                        dialog.dismiss()
                    }

                    openBtn.setOnClickListener {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        context.startActivity(intent)
                        dialog.dismiss()
                    }

                    dialog.show()

                    // 권한 없으니 상태 텍스트는 "사용 안 함"으로 변경
                    binding.tvUseStatus.text = "사용 안 함"
                    return@setOnCheckedChangeListener
                }

                if (hasCameraPermissions()) {
                    ContextCompat.startForegroundService(context, intent)
                    binding.tvUseStatus.text = "사용 중"
                    prefs.edit { putBoolean(PREFS_KEY_CAMERA_ENABLED, true) }
                } else {
                    ActivityCompat.requestPermissions(
                        requireActivity(),
                        CAMERA_PERMISSIONS,
                        REQUEST_CAMERA_PERMISSIONS
                    )
                    // 권한 요청 중 상태는 '사용 안 함'으로 유지
                    binding.tvUseStatus.text = "사용 안 함"
                    binding.switchUse.isChecked = false
                }

            } else {
                // 스위치 OFF → 서비스 중지
                context.stopService(intent)
                binding.tvUseStatus.text = "사용 안 함"
                prefs.edit { putBoolean(PREFS_KEY_CAMERA_ENABLED, false) }
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
        if (autoStartEnabled && !binding.switchUse.isChecked && accessibility && cameraGranted) {
            Log.d("AirCommandFragment", "✅ 조건 만족 → CameraService 자동 시작")
            ContextCompat.startForegroundService(context, Intent(context, CameraService::class.java))
            binding.switchUse.isChecked = true
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
                binding.switchUse.isChecked = false
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