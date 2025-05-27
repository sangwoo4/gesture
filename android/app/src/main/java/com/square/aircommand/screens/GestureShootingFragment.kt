package com.square.aircommand.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

    private val gestureStatusText = mutableStateOf("제스처 수집 ...")

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

        binding.backButton.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        binding.retakeButton.setOnClickListener {
            progress = 0
            binding.numberProgress.progress = 0
            binding.statusMessage.text = ""
            landmarkDetector.resetCollection()
            showCameraCompose()
        }

        binding.saveButton.setOnClickListener {
            landmarkDetector.sendToServerIfReady(requireContext()) {
                // ✅ 저장
                saveIfDefaultGesture(requireContext(), gestureName)

                Handler(Looper.getMainLooper()).post {
                    findNavController().navigate(R.id.action_gestureShooting_to_userGesture)
                }
            }
        }

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
                    binding.statusMessage.text = status
                    when (status) {
                        "⬇️ 모델 다운로드 중..." -> {
                            binding.lottieLoadingView.visibility = View.VISIBLE
                            binding.lottieLoadingView.playAnimation()
                            binding.lottieSuecessView.visibility = View.GONE
                            binding.lottieSuecessView.pauseAnimation()
                        }
                        "✅ 모델 적용 완료!" -> {
                            binding.lottieLoadingView.visibility = View.GONE
                            binding.lottieLoadingView.pauseAnimation()
                            binding.lottieSuecessView.visibility = View.VISIBLE
                            binding.lottieSuecessView.repeatCount = 0
                            binding.lottieSuecessView.playAnimation()
                        }
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
                binding.statusMessage.text = "촬영을 완료하였습니다. 저장하기를 눌러주세요"
                binding.landmarkOverlay.setContent {}
                binding.lottieLoadingView.visibility = View.VISIBLE
                binding.lottieLoadingView.playAnimation()
            } else {
                binding.lottieLoadingView.visibility = View.GONE
                binding.lottieLoadingView.pauseAnimation()
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

    private fun initModels() {
        ModelRepository.initModels(requireContext())
        handDetector = ModelRepository.getHandDetector()
        landmarkDetector = ModelRepository.getLandmarkDetector()
        gestureClassifier = ModelRepository.getGestureClassifier()
    }

    private fun startTraining() {
        landmarkDetector.startCollecting()
    }

    override fun onDestroyView() {
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