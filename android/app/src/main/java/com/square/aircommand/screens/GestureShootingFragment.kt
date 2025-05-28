package com.square.aircommand.screens

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter

import android.content.Context

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.square.aircommand.R
import com.square.aircommand.camera.CameraScreen
import com.square.aircommand.classifier.GestureClassifier
import com.square.aircommand.databinding.FragmentGestureShootingBinding
import com.square.aircommand.handdetector.HandDetector
import com.square.aircommand.handlandmarkdetector.HandLandmarkDetector
import com.square.aircommand.tflite.ModelRepository
import com.square.aircommand.utils.GestureStatus
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

class GestureShootingFragment : Fragment() {

    private var _binding: FragmentGestureShootingBinding? = null
    private val binding get() = _binding!!

    private var progress = 0

    private lateinit var handDetector: HandDetector
    private lateinit var landmarkDetector: HandLandmarkDetector
    private lateinit var gestureClassifier: GestureClassifier

    private val gestureStatusText = mutableStateOf(GestureStatus.Idle)

    // progress 초기화 함수 분리
    private fun resetProgress() {
        requireActivity().runOnUiThread {
            progress = 0
            binding.numberProgress.progress = 0
        }
    }

    // ✅ 모델 초기화 (HandDetector, HandLandmarkDetector, GestureClassifier)
    private fun initModels() {
        ModelRepository.initModels(requireContext())
        handDetector = ModelRepository.getHandDetector()
        landmarkDetector = ModelRepository.getLandmarkDetector()
        gestureClassifier = ModelRepository.getGestureClassifier()
    }

    private val gestureName by lazy {
        arguments?.getString("gesture_name") ?: "unknown"
    }

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

        binding.numberProgress.progress = 0
        progress = 0

        binding.saveButton.apply {
            isEnabled = false
            alpha = 0.3f
        }

        // 뒤로가기 버튼
        binding.backButton.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        // 다시 촬영
        binding.retakeButton.setOnClickListener {

            updateProgress(0)

            progress = 0
            binding.numberProgress.progress = 0

            binding.statusMessage.text = " "
            binding.statusMessage.text = "📷 촬영을 다시 시작합니다! 손을 카메라에 잘 보여주세요"

            binding.lottieLoadingView.cancelAnimation()
            binding.lottieLoadingView.visibility = View.GONE

            landmarkDetector.resetCollection()
            showCameraCompose()

            binding.saveButton.apply {
                isEnabled = false
                alpha = 0.3f
            }

        }

        // 저장 버튼
        binding.saveButton.setOnClickListener {
            binding.saveButton.apply {
                isEnabled = false
                alpha = 0.0f
            }

            binding.retakeButton.apply {
                isEnabled = false
                alpha = 0.0f
            }

            // 이동 버튼 보이게 설정
            binding.moveButton.visibility = View.VISIBLE

            binding.moveButton.apply {
                isEnabled = false
                alpha = 0.3f
            }

            // 카메라 중지
            binding.landmarkOverlay.setContent { }

            // 서버 전송 시작
            landmarkDetector.sendToServerIfReady(requireContext()) {
                gestureStatusText.value = GestureStatus.Training
                // ✅ 저장
                saveIfDefaultGesture(requireContext(), gestureName)

                Handler(Looper.getMainLooper()).post {
                    findNavController().navigate(R.id.action_gestureShooting_to_userGesture)
                }
            }
        }

        // 이동 버튼 클릭 리스너
        binding.moveButton.setOnClickListener {
            findNavController().navigate(R.id.action_gestureShooting_to_airCommand)
        }


        // 📷 카메라 권한 확인 후 초기화
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

    private fun observeGestureStatusText() {
        lifecycleScope.launch {
            snapshotFlow { gestureStatusText.value }
                .distinctUntilChanged()
                .collectLatest { status ->
                    when (status) {
                        GestureStatus.Training -> {
                            binding.lottieLoadingView.visibility = View.VISIBLE
                            binding.lottieLoadingView.playAnimation()

                            binding.lottieSuecessView.visibility = View.GONE
                            binding.lottieSuecessView.pauseAnimation()

                            binding.statusMessage.text = "촬영된 제스처 다운로드 중... ⏳"
                        }

                        GestureStatus.ModelApplied -> {
                            binding.lottieLoadingView.visibility = View.GONE
                            binding.lottieLoadingView.pauseAnimation()

                            binding.lottieSuecessView.visibility = View.VISIBLE
                            binding.lottieSuecessView.playAnimation()

                            binding.statusMessage.text = "다운로드 완료! 🎉"

                            binding.moveButton.apply {
                                isEnabled = true
                                alpha = 1.0f
                            }
                        }

//                        GestureStatus.DownFailed -> {
//                            binding.lottieLoadingView.visibility = View.GONE
//                            binding.lottieLoadingView.pauseAnimation()
//
//                            binding.lottieSuecessView.visibility = View.GONE
//                            binding.lottieSuecessView.playAnimation()
//
//                            binding.statusMessage.text = "⚠️ 다운로드 실패. 다시 촬영해주세요."
//
//                            binding.saveButton.apply {
//                                isEnabled = false
//                                alpha = 0.3f
//                            }
//
//                            binding.retakeButton.apply {
//                                isEnabled = true
//                                alpha = 1.0f
//                            }
//
//                            // 이동 버튼 보이게 설정
//                            binding.moveButton.visibility = View.GONE
//                        }

                        else -> {
                            binding.lottieLoadingView.visibility = View.GONE
                            binding.lottieLoadingView.pauseAnimation()

                            binding.lottieSuecessView.visibility = View.GONE
                            binding.lottieSuecessView.pauseAnimation()
                        }
                    }
                }
        }
    }

    private fun updateProgress(percent: Int) {
        requireActivity().runOnUiThread {
            progress = percent.coerceAtMost(100)
            binding.numberProgress.progress = progress

            if (progress >= 100) {
                binding.statusMessage.text = "촬영 완료! ✅ 저장하기를 눌러주세요."

                binding.saveButton.apply {
                    isEnabled = true
                    alpha = 1.0f
                }
            }
        }
    }

    private fun showCameraCompose() {
        binding.landmarkOverlay.setContent {
            CameraScreen(
                handDetector = handDetector,
                landmarkDetector = landmarkDetector,
                gestureClassifier = gestureClassifier,
                isTrainingMode = true,
                trainingGestureName = gestureName,
                gestureStatusText = gestureStatusText,
                onTrainingComplete = {
                    if (!toastShown) {
                        toastShown = true
                        val vibrator = ContextCompat.getSystemService(requireContext(), android.os.Vibrator::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            vibrator?.vibrate(android.os.VibrationEffect.createOneShot(50, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                        } else {
                            @Suppress("DEPRECATION")
                            vibrator?.vibrate(50)
                        }
                    }
                },
                onProgressUpdate = { percent -> updateProgress(percent) }
            )
        }
    }

    private fun allPermissionsGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startTraining() {
        landmarkDetector.startCollecting()
    }

    override fun onDestroyView() {
        // ✅ Surface 해제 (Compose 내 Preview 사용 중지)
        _binding?.landmarkOverlay?.setContent {}

        super.onDestroyView()
        _binding = null
        ModelRepository.closeAll()
    }

    /**
     * ✅ 기본 제스처(paper, rock, scissors, one)만 저장
     */
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
}