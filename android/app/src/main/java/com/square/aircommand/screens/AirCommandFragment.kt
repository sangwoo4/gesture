package com.square.aircommand.screens

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.transition.TransitionInflater
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.getkeepsafe.taptargetview.TapTarget
import com.getkeepsafe.taptargetview.TapTargetView
import com.shashank.sony.fancytoastlib.FancyToast
import com.square.aircommand.R
import com.square.aircommand.backgroundcamera.CameraService
import com.square.aircommand.databinding.FragmentAirCommandBinding

class AirCommandFragment : Fragment() {

    private var _binding: FragmentAirCommandBinding? = null
    private val binding get() = _binding!!

    private val timeOptions = listOf("설정 안 함", "15초", "1시간", "2시간", "4시간")

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

        val context = requireContext()
        val prefs = context.getSharedPreferences("air_command_prefs", Context.MODE_PRIVATE)
        var savedTime = prefs.getString("selected_time", null)
        if (savedTime == null) {
            savedTime = "설정 안 함"
            prefs.edit { putString("selected_time", savedTime) }
        }
        binding.btnSelectTime.text = savedTime

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
            val intent = Intent(context, CameraService::class.java)

            if (isChecked) {
                if (!isAccessibilityServiceEnabled(context)) {
                    FancyToast.makeText(context, "접근성 권한이 필요합니다!", FancyToast.LENGTH_SHORT, FancyToast.WARNING, true).show()

                    val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_accessibility, null)
                    val dialog = AlertDialog.Builder(context).setView(dialogView).setCancelable(false).create()

                    dialog.window?.apply {
                        setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        setGravity(Gravity.CENTER)
                        setBackgroundDrawableResource(android.R.color.transparent)
                    }

                    dialogView.findViewById<Button>(R.id.btn_cancel).setOnClickListener {
                        binding.switchUse.isChecked = false
                        dialog.dismiss()
                    }

                    dialogView.findViewById<Button>(R.id.btn_open_settings).setOnClickListener {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        })
                        dialog.dismiss()
                    }

                    dialog.show()
                    binding.tvUseStatus.text = "사용 안 함"
                    return@setOnCheckedChangeListener
                }

                if (hasCameraPermissions()) {
                    ContextCompat.startForegroundService(context, intent)
                    binding.tvUseStatus.text = "사용 중"
                    prefs.edit { putBoolean(PREFS_KEY_CAMERA_ENABLED, true) }

                    val selectedTime = prefs.getString("selected_time", "설정 안 함") ?: "설정 안 함"
                    val timeout = getSelectedTimeMillis(selectedTime)
                    if (timeout > 0) {
                        Log.d("AirCommandFragment", "⏰ ${timeout}ms 후 자동 종료 예약됨")
                        Handler(Looper.getMainLooper()).postDelayed({
                            context.stopService(intent)
                            binding.switchUse.isChecked = false
                            binding.tvUseStatus.text = "사용 안 함"
                            prefs.edit { putBoolean(PREFS_KEY_CAMERA_ENABLED, false) }
                            Log.d("AirCommandFragment", "📴 카메라 서비스 자동 종료됨")
                        }, timeout)
                    }
                }
            } else {
                context.stopService(intent)
                binding.tvUseStatus.text = "사용 안 함"
                prefs.edit { putBoolean(PREFS_KEY_CAMERA_ENABLED, false) }
            }
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

        binding.root.findViewById<ImageView>(R.id.developer_circle).setOnClickListener {
            TapTargetView.showFor(
                requireActivity(),
                TapTarget.forView(it, "Hansung University", "2025 Computer Engineering \n Capstone Design\n\n 박상우, 박흥준, 장도윤, 최현혜")
                    .outerCircleColor(R.color.white)
                    .outerCircleAlpha(0.90f)
                    .targetCircleColor(R.color.white)
                    .titleTextColor(R.color.black)
                    .descriptionTextColor(R.color.black)
                    .dimColor(R.color.black)
                    .drawShadow(true)
                    .cancelable(false)
                    .tintTarget(true)
                    .transparentTarget(true)
                    .targetRadius(50),
                null
            )
        }

        binding.root.findViewById<TextView>(R.id.description_circle).setOnClickListener {
            TapTargetView.showFor(
                requireActivity(),
                TapTarget.forView(it, "제스처 제어 앱 서비스", "터치 없이 나만의 제스처를 등록해 사용")
                    .outerCircleColor(R.color.white)
                    .outerCircleAlpha(0.90f)
                    .targetCircleColor(R.color.white)
                    .titleTextColor(R.color.black)
                    .descriptionTextSize(13)
                    .descriptionTextColor(R.color.black)
                    .textTypeface(Typeface.SANS_SERIF)
                    .dimColor(R.color.black)
                    .drawShadow(true)
                    .cancelable(false)
                    .tintTarget(true)
                    .transparentTarget(true)
                    .targetRadius(50),
                null
            )
        }

        return binding.root
    }

    override fun onResume() {
        super.onResume()
        Log.d("AirCommandFragment", "🔁 onResume 호출됨")

        val context = requireContext()
        val prefs = context.getSharedPreferences("air_command_prefs", Context.MODE_PRIVATE)
        val autoStartEnabled = prefs.getBoolean(PREFS_KEY_CAMERA_ENABLED, false)
        val accessibility = isAccessibilityServiceEnabled(context)
        val cameraGranted = hasCameraPermissions()

        if (!cameraGranted) {
            ActivityCompat.requestPermissions(requireActivity(), CAMERA_PERMISSIONS, REQUEST_CAMERA_PERMISSIONS)
            return
        }

        if (autoStartEnabled && accessibility && cameraGranted) {
            Log.d("AirCommandFragment", "✅ 접근성 + 권한 + 자동시작 설정 만족 → CameraService 실행")
            val serviceIntent = Intent(context, CameraService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)

            // UI 상태 일치시킴
            binding.switchUse.isChecked = true
            binding.tvUseStatus.text = "사용 중"
        } else {
            binding.switchUse.isChecked = false
            binding.tvUseStatus.text = "사용 안 함"
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_PERMISSIONS && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            Log.d("AirCommandFragment", "📸 카메라 권한 승인됨")
            ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), CameraService::class.java))
        } else {
            Log.w("AirCommandFragment", "❌ 카메라 권한 거부됨")
            binding.switchUse.isChecked = false
        }
    }

    private fun hasCameraPermissions(): Boolean {
        val context = requireContext()
        return CAMERA_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

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

    private fun getSelectedTimeMillis(selected: String): Long {
        return when (selected) {
            "15초" -> 15 * 1000L
//            "1분" -> 1 * 60 * 1000L
            "1시간" -> 1 * 60 * 60 * 1000L
            "2시간" -> 2 * 60 * 60 * 1000L
            "4시간" -> 4 * 60 * 60 * 1000L
            else -> 0L
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
