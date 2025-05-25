package com.square.aircommand.camera

import android.content.Context
import android.graphics.PointF
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.square.aircommand.classifier.GestureClassifier
import com.square.aircommand.classifier.GestureLabelMapper
import com.square.aircommand.gesture.GestureLabel
import com.square.aircommand.handdetector.HandDetector
import com.square.aircommand.handlandmarkdetector.HandLandmarkDetector
import com.square.aircommand.utils.ThrottledLogger
import com.square.aircommand.utils.toBitmapCompat

/**
 * HandAnalyzers
 * - 프레임 단위로 손을 감지하고, 실시간 제스처 분류 또는 전이 학습을 수행합니다.
 */
class HandAnalyzers(
    private val context: Context,
    private val handDetector: HandDetector,
    private val landmarkDetector: HandLandmarkDetector,
    private val gestureClassifier: GestureClassifier,
    private val gestureLabelMapper: GestureLabelMapper,
    private val gestureText: MutableState<String>,
    private val detectionFrameCount: MutableState<Int>,
    private val latestPoints: SnapshotStateList<PointF>,
    private val landmarksState: MutableState<List<Triple<Double, Double, Double>>>,
    private val validDetectionThreshold: Int,
    private val isTrainingMode: Boolean = false,
    private val trainingGestureName: String = "",
    private val onGestureDetected: ((GestureLabel) -> Unit)? = null,
    private val onTrainingComplete: (() -> Unit)? = null
) : ImageAnalysis.Analyzer {

    override fun analyze(imageProxy: ImageProxy) {
        try {
            val bitmap = imageProxy.toBitmapCompat()
            val points = handDetector.detect(bitmap)
            val orientation = getBackCameraSensorOrientation(context)

            if (points.isNotEmpty()) {
                detectionFrameCount.value += 1

                if (detectionFrameCount.value >= validDetectionThreshold) {
                    ThrottledLogger.log("HandAnalyzer", "손이 감지되었습니다 (${points.size}개)")

                    latestPoints.clear()
                    latestPoints.addAll(points)

                    for (point in points) {

                        // ✅ 일반 모드에서는 예측만 수행
                        landmarkDetector.predict(bitmap, orientation)


                        val landmarks = landmarkDetector.lastLandmarks

                        if (!isTrainingMode && landmarks.size == 21) {
                            landmarksState.value = landmarks.toList()
                            val (gestureIndex, confidence) = gestureClassifier.classify(
                                landmarks,
                                landmarkDetector.lastHandedness
                            )

                            val gestureName = gestureLabelMapper.getLabel(gestureIndex)
                            gestureText.value = "$gestureName (${(confidence * 100).toInt()}%)"

                            ThrottledLogger.log(
                                "HandAnalyzer",
                                "제스처 인식됨: $gestureName (index=$gestureIndex, 신뢰도=${String.format("%.2f", confidence)})"
                            )

                            onGestureDetected?.invoke(GestureLabel.fromId(gestureIndex))
                        } else if (!isTrainingMode) {
                            gestureText.value = "제스처 없음"
                            ThrottledLogger.log("HandAnalyzer", "랜드마크 포인트가 부족합니다")
                        }
                    }
                } else {
                    ThrottledLogger.log("HandAnalyzer", "손 감지 누적 중 (${detectionFrameCount.value}/${validDetectionThreshold})")
                }
            } else {
                detectionFrameCount.value = 0
                landmarksState.value = emptyList()
                ThrottledLogger.log("HandAnalyzer", "손이 감지되지 않았습니다")
            }
        } catch (e: Exception) {
            Log.e("HandAnalyzer", "프레임 분석 중 오류 발생: ${e.message}", e)
        } finally {
            imageProxy.close()
        }
    }
}