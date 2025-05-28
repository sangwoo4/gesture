package com.square.aircommand.screens

import android.Manifest
import android.R.attr.tag

import android.content.Context

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.airbnb.lottie.LottieAnimationView
import com.square.aircommand.R
import com.square.aircommand.cameraServies.TrainingCameraScreen
import com.square.aircommand.classifier.GestureClassifier
import com.square.aircommand.databinding.FragmentGestureShootingBinding
import com.square.aircommand.handdetector.HandDetector
import com.square.aircommand.handlandmarkdetector.HandLandmarkDetector
import com.square.aircommand.tflite.ModelRepository
import com.square.aircommand.utils.GestureStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class GestureShootingFragment : Fragment() {

    private var _binding: FragmentGestureShootingBinding? = null
    private val binding get() = _binding!!

    private var progress = 0
    private var toastShown = false

    private lateinit var handDetector: HandDetector
    private lateinit var landmarkDetector: HandLandmarkDetector
    private lateinit var gestureClassifier: GestureClassifier

    private val gestureStatusText = mutableStateOf(GestureStatus.Idle)

    private val gestureName by lazy {
        arguments?.getString("gesture_name") ?: "unknown"
    }

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
        setupUI()
        if (allPermissionsGranted()) {
            initModels()
            startTraining()
            showCameraCompose()
        } else {
            ActivityCompat.requestPermissions(
                requireActivity(),
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST_CODE
            )
        }
        observeGestureStatusText()
    }

    private fun setupUI() {
        binding.numberProgress.progress = 0
        progress = 0

        binding.saveButton.setDisabled()

        binding.backButton.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        binding.retakeButton.setOnClickListener {
            resetProgress()
            binding.statusMessage.text = "📷 촬영을 다시 시작합니다! 손을 카메라에 잘 보여주세요"
            binding.lottieLoadingView.cancelAndHide()
            landmarkDetector.resetCollection()
            showCameraCompose()
            binding.saveButton.setDisabled()
        }

        binding.saveButton.setOnClickListener {
            binding.saveButton.setFalsed()
            binding.retakeButton.setFalsed()
            binding.moveButton.visibility = View.VISIBLE
            binding.moveButton.setDisabled()
            binding.landmarkOverlay.setContent { }

            landmarkDetector.sendToServerIfReady(requireContext()) {
                gestureStatusText.value = GestureStatus.Training
                saveIfDefaultGesture(requireContext(), gestureName)
                Handler(Looper.getMainLooper()).post {
                    findNavController().navigate(R.id.action_gestureShooting_to_userGesture)
                }
            }
        }


        binding.moveButton.setOnClickListener {
            findNavController().navigate(R.id.action_gestureShooting_to_airCommand)
        }
    }

    private fun initModels() {
        ModelRepository.initModels(requireContext())
        handDetector = ModelRepository.getHandDetector()
        landmarkDetector = ModelRepository.getLandmarkDetector()
        gestureClassifier = ModelRepository.getGestureClassifier()
    }

    private fun allPermissionsGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun resetProgress() {
        requireActivity().runOnUiThread {
            progress = 0
            binding.numberProgress.progress = 0
        }
    }

    private fun updateProgress(percent: Int) {
        requireActivity().runOnUiThread {
            progress = percent.coerceAtMost(100)
            binding.numberProgress.progress = progress

            if (progress >= 100) {
                binding.statusMessage.text = "촬영 완료! ✅ 저장하기를 눌러주세요."
                binding.saveButton.setEnabled()
            }
        }
    }



    private fun startTraining() {
        landmarkDetector.startCollecting()
    }

    private fun observeGestureStatusText() {
        lifecycleScope.launch {
            snapshotFlow { gestureStatusText.value }
                .distinctUntilChanged()
                .collectLatest { status ->
                    with(binding) {
                        when (status) {
                            GestureStatus.Training -> {
                                lottieLoadingView.playAndShow()
                                lottieSuecessView.cancelAndHide()
                                lottieFailView.cancelAndHide()
                                binding.statusMessage.text = "촬영된 제스처 학습 중... ⏳"
                            }

                            GestureStatus.ModelApplied -> {
                                lottieLoadingView.cancelAndHide()
                                lottieSuecessView.playAndShow()
                                lottieFailView.cancelAndHide()
                                moveButton.setEnabled()
                                binding.statusMessage.text = "학습 및 다운로드 완료! 🎉"
                            }

                            GestureStatus.Failure -> {
                                lottieFailView.playAndShow()
                                lottieLoadingView.cancelAndHide()
                                lottieSuecessView.cancelAndHide()
                                binding.statusMessage.text = "⚠️ 다운로드 실패. 저장 버튼을 다시 눌러주세요."

                                saveButton.setEnabled()
                                retakeButton.setDisabled()
                                moveButton.setFalsed()

                                // ✅ 전송 재시도 가능하도록 상태 복구
//                                landmarkDetector.retryLastSend(gestureName)
                            }

                            else -> {
                                lottieLoadingView.cancelAndHide()
                                lottieSuecessView.cancelAndHide()
                                lottieFailView.cancelAndHide()
                            }
                        }
                    }
                }
        }
    }

    private fun showCameraCompose() {
        binding.landmarkOverlay.setContent {
            TrainingCameraScreen(
                handDetector = handDetector,
                landmarkDetector = landmarkDetector,
                gestureClassifier = gestureClassifier,
                isTrainingMode = true,
                trainingGestureName = gestureName,
                gestureStatusText = gestureStatusText,
                onTrainingComplete = {
                    if (!toastShown) {
                        toastShown = true
                        vibrateOnce()
                    }
                },
                onProgressUpdate = { updateProgress(it) }
            )
        }
    }

    private fun vibrateOnce() {
        val vibrator = ContextCompat.getSystemService(requireContext(), android.os.Vibrator::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(android.os.VibrationEffect.createOneShot(50, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(50)
        }
    }

    private fun saveIfDefaultGesture(context: Context, label: String) {
        val allowed = listOf("paper", "rock", "scissors", "one")
        val labelLower = label.lowercase()
        if (labelLower !in allowed) return

        val file = File(context.filesDir, "gesture_labels.json")
        val jsonObject = if (file.exists()) JSONObject(file.readText()) else JSONObject()

        val alreadyExists = jsonObject.keys().asSequence()
            .any { key -> jsonObject.optString(key) == label }

        if (!alreadyExists) {
            val nextIndex = jsonObject.keys().asSequence()
                .mapNotNull { it.toIntOrNull() }
                .maxOrNull()?.plus(1) ?: 0
            jsonObject.put(nextIndex.toString(), label)
            file.writeText(jsonObject.toString())
        }
    }

    override fun onDestroyView() {
        Log.d(tag, "🛑 onDestroyView() 호출됨 - 리소스 정리 시작")

        // 1. Compose 내 카메라 UI 제거
        _binding?.landmarkOverlay?.setContent {}
        Log.d(tag, "📷 Compose 카메라 UI 제거 완료")

        // 2. CameraX 종료 (CameraExecutor, CameraProvider)
        TrainingCameraManager.releaseCamera() // ← 직접 구현 필요
        Log.d(tag, "🎥 CameraX 리소스 해제 완료")

        // 3. 모델 해제
        ModelRepository.closeAll()
        Log.d(tag, "🧠 모델 리소스 해제 완료")

        // 4. 뷰 바인딩 해제
        _binding = null
        Log.d(tag, "🧹 ViewBinding 해제 완료")

        super.onDestroyView()
        Log.d(tag, "✅ onDestroyView() 종료")
    }

    // View Extension Helpers
    private fun View.setEnabled() {
        isEnabled = true
        alpha = 1.0f
    }

    private fun View.setDisabled() {
        isEnabled = false
        alpha = 0.3f
    }

    private fun View.setFalsed() {
        isEnabled = false
        alpha = 0.0f
    }

    private fun LottieAnimationView.playAndShow() {
        visibility = View.VISIBLE
        playAnimation()
    }

    private fun LottieAnimationView.cancelAndHide() {
        cancelAnimation()
        visibility = View.GONE
    }
}
