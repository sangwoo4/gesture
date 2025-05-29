package com.square.aircommand.cameraUtil

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.compose.runtime.MutableState
import com.square.aircommand.cameraServies.getBackCameraSensorOrientation
import com.square.aircommand.classifier.GestureClassifier
import com.square.aircommand.classifier.GestureLabelMapper
import com.square.aircommand.handdetector.HandDetector
import com.square.aircommand.handlandmarkdetector.HandLandmarkDetector
import com.square.aircommand.ui.theme.listener.TrainingProgressListener
import com.square.aircommand.utils.ThrottledLogger
import com.square.aircommand.utils.toBitmapCompat

/**
 * HandAnalyzers
 * - 프레임 단위로 손을 감지하고, 실시간 제스처 분류 또는 전이 학습을 수행합니다.
 */
class HandAnalyzer(
    private val context: Context,
    private val handDetector: HandDetector,
    private val landmarkDetector: HandLandmarkDetector,
    private val gestureClassifier: GestureClassifier,
    private val gestureLabelMapper: GestureLabelMapper,
    private val gestureText: MutableState<String>,
    private val detectionFrameCount: MutableState<Int>,
    private val landmarksState: MutableState<List<Triple<Double, Double, Double>>>,
    private val validDetectionThreshold: Int = 20,
    private val isTrainingMode: Boolean = false,
    private val trainingGestureName: String = "",
    private val onGestureDetected: ((String) -> Unit)? = null,
    private val onTrainingComplete: (() -> Unit)? = null,
    private val trainingProgressListener: TrainingProgressListener? = null
) : ImageAnalysis.Analyzer {

    override fun analyze(imageProxy: ImageProxy) {
        try {
            if (handDetector.isClosed()) {
                ThrottledLogger.log("HandAnalyzer", "❌ HandDetector가 닫혀있습니다.")
                imageProxy.close()
                return
            }
            Log.d("validDetectionThreshold", validDetectionThreshold.toString())
            val bitmap = imageProxy.toBitmapCompat()
            val orientation = getBackCameraSensorOrientation(context)
            val detectionResult = handDetector.detectHandAndGetInfo(bitmap, orientation)

            if (detectionResult != null) {
                detectionFrameCount.value += 1

                if (detectionFrameCount.value >= validDetectionThreshold) {
                    ThrottledLogger.log("HandAnalyzer", "손 감지 성공")
                    val croppedHand = detectionResult.croppedHand

                    if (isTrainingMode) {
                        handleTrainingMode(croppedHand)
                    } else {
                        handleInferenceMode(croppedHand)
                    }
                } else {
                    ThrottledLogger.log("HandAnalyzer", "감지 누적 중 (${detectionFrameCount.value})")
                }
            } else {
                detectionFrameCount.value = 0
                landmarksState.value = emptyList()
                ThrottledLogger.log("HandAnalyzer", "손 감지 안됨")
            }
        } catch (e: Exception) {
            Log.e("HandAnalyzer", "분석 실패: ${e.message}", e)
        } finally {
            imageProxy.close()
        }
    }

    private fun handleTrainingMode(croppedHand: Bitmap) {
        Log.d("HandAnalyzer", "[학습 모드] transfer 호출")
        landmarkDetector.transfer(croppedHand, 0, trainingGestureName, trainingProgressListener)

        if (!landmarkDetector.isCollecting) {
            Log.d("HandAnalyzer", "[학습 모드] 수집 종료 → onTrainingComplete()")
            onTrainingComplete?.invoke()
        }
    }

    private fun handleInferenceMode(croppedHand: Bitmap) {
        landmarkDetector.predict(croppedHand, 0)
        val landmarks = landmarkDetector.lastLandmarks

        if (landmarks.size == 21) {
            landmarksState.value = landmarks.toList()

            val (gestureIndex, confidence) = gestureClassifier.classify(
                landmarks,
                landmarkDetector.lastHandedness
            )
            val gestureName = gestureLabelMapper.getLabel(gestureIndex)
            gestureText.value = "$gestureName (${(confidence * 100).toInt()}%)"

            ThrottledLogger.log("HandAnalyzer", "$gestureName ($gestureIndex, $confidence)")
            onGestureDetected?.invoke(gestureName)
        }
    }
}