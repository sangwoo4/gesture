package com.square.aircommand.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.square.aircommand.tflite.ModelRepository
import com.square.aircommand.ui.theme.listener.TrainingProgressListener

class GestureShootingFragment : Fragment() {

    private var _binding: FragmentGestureShootingBinding? = null
    private val binding get() = _binding!!

    private lateinit var handDetector: HandDetector
    private lateinit var landmarkDetector: HandLandmarkDetector
    private lateinit var gestureClassifier: GestureClassifier

    private val gestureStatusText = mutableStateOf("제스처 수집 중...") // ✅ 상태 추가

    // ✅ 모델 초기화 (HandDetector, HandLandmarkDetector, GestureClassifier)
    private fun initModels() {
        ModelRepository.initModels(requireContext())
        handDetector = ModelRepository.getHandDetector()
        landmarkDetector = ModelRepository.getLandmarkDetector()
        gestureClassifier = ModelRepository.getGestureClassifier()
    }

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

        //저장하기
        binding.saveButton.setOnClickListener {
            landmarkDetector.sendToServerIfReady(requireContext()) {
                Handler(Looper.getMainLooper()).post {
                    findNavController().navigate(R.id.action_gestureShooting_to_userGesture)
                }
            }
        }

        // 다시 촬영
        binding.retakeButton.setOnClickListener {
            landmarkDetector.resetCollection()
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
                gestureStatusText = gestureStatusText // ✅ 상태 전달
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        ModelRepository.closeAll() // 👉 모든 모델 리소스를 일괄 해제
    }
}