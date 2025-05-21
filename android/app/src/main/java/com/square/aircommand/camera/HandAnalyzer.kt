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

// 손 감지 및 제스처 분류를 수행하는 분석기
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
    private val onGestureDetected: ((GestureLabel) -> Unit)? = null // ✅ 추가됨
) : ImageAnalysis.Analyzer {

    // 분석 로직 실행 (프레임마다 호출됨)
    override fun analyze(imageProxy: ImageProxy) {
        try {
            val bitmap = imageProxy.toBitmapCompat()
            val points = handDetector.detect(bitmap)
            val orientation = getBackCameraSensorOrientation(context)

            if (points.isNotEmpty()) {
                // 손이 감지되면 누적 프레임 수 증가
                detectionFrameCount.value += 1

                if (detectionFrameCount.value >= validDetectionThreshold) {
                    ThrottledLogger.log("HandAnalyzer", "🟢 유효 손 감지 (${points.size})")
                    latestPoints.clear()
                    latestPoints.addAll(points)

                    for (point in points) {
                        // 랜드마크 예측
                        landmarkDetector.predict(bitmap, orientation)
                        val landmarks = landmarkDetector.lastLandmarks

                        // 랜드마크가 21개 일 때만 제스처 분류
                        if (landmarks.size == 21) {
                            landmarksState.value = landmarks.toList()
                            val (gestureIndex, confidence) = gestureClassifier.classify(
                                landmarks,
                                landmarkDetector.lastHandedness
                            )
                            val gestureName = gestureLabelMapper.getLabel(gestureIndex)
                            gestureText.value = "$gestureName (${(confidence * 100).toInt()}%)"
                            ThrottledLogger.log("HandAnalyzer", "✋ $gestureName ($gestureIndex, ${"%.2f".format(confidence)})")

                            onGestureDetected?.invoke(GestureLabel.fromId(gestureIndex)) // ✅ 제스처 콜백 실행 추가
                        } else {
                            // 일정 프레임 이상 감지되지 않으면 대기
                            gestureText.value = "제스처 없음"
                            ThrottledLogger.log("HandAnalyzer", "🚫 랜드마크 부족")
                        }
                    }
                } else {
                    // 일정 프레임 이상 감지되지 않으면 대기
                    ThrottledLogger.log("HandAnalyzer", "⏳ 누적 중 (${detectionFrameCount.value})")
                }
            } else {
                // 손이 감지되지 않으면 상태 초기화
                detectionFrameCount.value = 0
                landmarksState.value = emptyList()
                ThrottledLogger.log("HandAnalyzer", "🔴 손 감지 안됨")
            }
        } catch (e: Exception) {
            Log.e("HandAnalyzer", "❌ 분석 실패: ${e.message}", e)
        } finally {
            imageProxy.close()
        }
    }
}
