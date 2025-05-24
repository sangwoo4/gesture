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
        binding.numberProgress.progress = 0

        // 진행 상태바 초기화
        binding.numberProgress.progress = 0

        // 상태바 애니메이션 예제 (1초마다 10씩 증가)
        val handler = android.os.Handler()
        var progress = 0
        val runnable = object : Runnable {
            override fun run() {
                if (progress <= 100) {
                    binding.numberProgress.progress = progress

                    // 100%가 되면 메시지 업데이트
                    if (progress == 100) {
                        binding.statusMessage.text = "촬영을 완료하였습니다. 저장하기를 눌러주세요"

                        // 완료되면 카메라 꺼지고 빈 화면이 됨
                        binding.landmarkOverlay.setContent {
                            // 아무것도 없는 빈 화면
                        }
                    }

                    progress += 10
                    handler.postDelayed(this, 800) // 1초마다 반복
                }
            }
        }
        handler.post(runnable)

        // 뒤로가기 버튼 클릭 시 이전 프래그먼트로 이동
        binding.backButton.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        binding.retakeButton.setOnClickListener {
            // 상태 초기화
            progress = 0
            binding.numberProgress.progress = 0

            // 카메라 다시 실행
            showCameraCompose()

            // 진행률 다시 시작
            handler.removeCallbacks(runnable) // 기존 루프 제거.
            handler.post(runnable)
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
            modelPath = "update_gesture_model_cnn.tflite",
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
