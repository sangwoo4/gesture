package com.square.aircommand.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.square.aircommand.R
import com.square.aircommand.camera.CameraScreen
import com.square.aircommand.classifier.GestureClassifier
import com.square.aircommand.databinding.FragmentGestureShootingBinding
import com.square.aircommand.handdetector.HandDetector
import com.square.aircommand.handlandmarkdetector.HandLandmarkDetector
import com.square.aircommand.tflite.TFLiteHelpers

class GestureShootingFragment : Fragment() {

    private var _binding: FragmentGestureShootingBinding? = null
    private val binding get() = _binding!!

    private lateinit var handDetector: HandDetector
    private lateinit var landmarkDetector: HandLandmarkDetector
    private lateinit var gestureClassifier: GestureClassifier

    private val gestureStatusText = mutableStateOf("제스처 수집 중...") // ✅ 상태 추가

    // 🔄 전달받은 사용자 정의 제스처 이름 (없으면 "unknown")
    private val gestureName by lazy {
        arguments?.getString("gesture_name") ?: "unknown"
    }

    // 🧭 토스트 중복 방지 플래그
    private var toastShown = false

    companion object {
        private const val CAMERA_PERMISSION_REQUEST_CODE = 10
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGestureShootingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 🔙 뒤로가기 버튼
        binding.backButton.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        // 💾 저장 버튼 → 사용자 제스처 등록 화면으로 이동
        binding.saveButton.setOnClickListener {
            findNavController().navigate(R.id.action_gestureShooting_to_userGesture)
        }

        // 📷 카메라 권한 확인 후 초기화
        if (allPermissionsGranted()) {
            initModels()         // 👉 모델 로딩
            startTraining()      // 👉 전이 학습 시작
            showCameraCompose()  // 👉 카메라 UI 표시
        } else {
            ActivityCompat.requestPermissions(
                requireActivity(),
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST_CODE
            )
        }
    }

    // ✅ 카메라 권한 확인
    private fun allPermissionsGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    // ✅ 모델 초기화 (HandDetector, HandLandmarkDetector, GestureClassifier)
    private fun initModels() {
        val delegateOrder = arrayOf(
            arrayOf(TFLiteHelpers.DelegateType.QNN_NPU),
            arrayOf(TFLiteHelpers.DelegateType.GPUv2),
            arrayOf()
        )

        handDetector = HandDetector(
            context = requireContext(),
            modelPath = "mediapipe_hand-handdetector.tflite",
            delegatePriorityOrder = delegateOrder
        )

        landmarkDetector = HandLandmarkDetector(
            context = requireContext(),
            modelPath = "mediapipe_hand-handlandmarkdetector.tflite",
            delegatePriorityOrder = delegateOrder
        )

        gestureClassifier = GestureClassifier(
            context = requireContext(),
            modelPath = "update_gesture_model_cnns.tflite",
            delegatePriorityOrder = delegateOrder
        )
    }

    // ✅ 학습 시작 명시
    private fun startTraining() {
        // 🟢 반드시 호출해야 `transfer()`가 작동함
        landmarkDetector.startCollecting()
    }

    // ✅ Jetpack Compose 기반 카메라 화면
    private fun showCameraCompose() {
        binding.landmarkOverlay.setContent {
            CameraScreen(
                handDetector = handDetector,
                landmarkDetector = landmarkDetector,
                gestureClassifier = gestureClassifier,
                isTrainingMode = true,
                trainingGestureName = gestureName,
                onTrainingComplete = {
                    // 🎉 학습 완료 시 토스트 한 번만 표시
                    if (!toastShown) {
                        toastShown = true
                        requireActivity().runOnUiThread {
                            Toast.makeText(requireContext(), "학습이 완료되었습니다.", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                gestureStatusText = gestureStatusText // ✅ 상태 전달
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 리소스 해제
        _binding = null
        handDetector.close()
        landmarkDetector.close()
        gestureClassifier.close()
    }
}