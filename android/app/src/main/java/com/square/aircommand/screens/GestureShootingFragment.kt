package com.square.aircommand.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

        // 뒤로가기 버튼 클릭 시 이전 프래그먼트로 이동
        binding.backButton.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        // 저장 버튼 클릭 -> UserGestureFragment로 이동
        binding.saveButton.setOnClickListener {
            findNavController().navigate(R.id.action_gestureShooting_to_userGesture)
        }

        if (allPermissionsGranted()) {
            initModels()
            showCameraCompose()
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
        val delegateOrder = arrayOf(
            arrayOf(TFLiteHelpers.DelegateType.QNN_NPU),
            arrayOf(TFLiteHelpers.DelegateType.GPUv2),
            arrayOf() // CPU fallback
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
