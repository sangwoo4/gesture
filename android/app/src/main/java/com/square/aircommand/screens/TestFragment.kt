package com.square.aircommand.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.square.aircommand.camera.CameraScreen
import com.square.aircommand.classifier.GestureClassifier
import com.square.aircommand.databinding.FragmentTestBinding
import com.square.aircommand.handdetector.HandDetector
import com.square.aircommand.handlandmarkdetector.HandLandmarkDetector
import com.square.aircommand.tflite.TFLiteHelpers
import org.tensorflow.lite.DataType

class TestFragment : Fragment() {

    private var _binding: FragmentTestBinding? = null
    private val binding get() = _binding!!

    private lateinit var handDetector: HandDetector
    private lateinit var landmarkDetector: HandLandmarkDetector
    private lateinit var gestureClassifier: GestureClassifier

    companion object {
        private const val CAMERA_PERMISSION_REQUEST_CODE = 10
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTestBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.backButton.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        if (allPermissionsGranted()) {
            try {
                initModels()
                showCameraCompose()
            } catch (e: Exception) {
                Log.e("TestFragment", "❌ 모델 초기화 실패: ${e.message}", e)
                Toast.makeText(requireContext(), "모델 초기화 실패: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            ActivityCompat.requestPermissions(
                requireActivity(),
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        requireContext(), Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    private fun initModels() {
        fun loadDelegateOrder(modelName: String): Array<Array<TFLiteHelpers.DelegateType>> {
            val modelBuffer = TFLiteHelpers.loadModelFile(requireContext().assets, modelName).first
            val inputType: DataType = TFLiteHelpers.getModelInputType(modelBuffer)
            Log.i("TestFragment", "📌 [$modelName] 입력 타입: $inputType")
            val delegateOrder = TFLiteHelpers.getDelegatePriorityOrderFromInputType(inputType)
            Log.i("TestFragment", "🧩 [$modelName] Delegate 우선순위: ${delegateOrder.joinToString { it.joinToString(" + ") }}")
            return delegateOrder
        }

        try {
            handDetector = HandDetector(
                context = requireContext(),
                modelPath = "mediapipe_hand-handdetector.tflite",
                delegatePriorityOrder = loadDelegateOrder("mediapipe_hand-handdetector.tflite")
            )
        } catch (e: Exception) {
            Log.e("TestFragment", "❌ HandDetector 초기화 실패: ${e.message}", e)
            throw RuntimeException("HandDetector 초기화 실패: ${e.message}")
        }

        try {
            landmarkDetector = HandLandmarkDetector(
                context = requireContext(),
                modelPath = "mediapipe_hand-handlandmarkdetector.tflite",
                delegatePriorityOrder = loadDelegateOrder("mediapipe_hand-handlandmarkdetector.tflite")
            )
        } catch (e: Exception) {
            Log.e("TestFragment", "❌ HandLandmarkDetector 초기화 실패: ${e.message}", e)
            throw RuntimeException("HandLandmarkDetector 초기화 실패: ${e.message}")
        }

        try {
            gestureClassifier = GestureClassifier(
                context = requireContext(),
                modelPath = "update_gesture_model_cnn.tflite",
                delegatePriorityOrder = loadDelegateOrder("update_gesture_model_cnn.tflite")
            )
        } catch (e: Exception) {
            Log.e("TestFragment", "❌ GestureClassifier 초기화 실패: ${e.message}", e)
            throw RuntimeException("GestureClassifier 초기화 실패: ${e.message}")
        }
    }

    private fun showCameraCompose() {
        binding.landmarkOverlay.setContent {
            CameraScreen(
                handDetector = handDetector,
                landmarkDetector = landmarkDetector,
                gestureClassifier = gestureClassifier
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        handDetector.close()
        landmarkDetector.close()
        gestureClassifier.close()
    }
}