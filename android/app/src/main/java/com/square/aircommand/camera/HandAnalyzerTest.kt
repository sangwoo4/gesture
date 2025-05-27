package com.square.aircommand.camera

import android.content.Context
import android.graphics.PointF
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner
import com.square.aircommand.classifier.GestureClassifier
import com.square.aircommand.classifier.GestureLabelMapper
import com.square.aircommand.gesture.GestureLabel
import com.square.aircommand.handdetector.HandDetector
import com.square.aircommand.handlandmarkdetector.HandLandmarkDetector
import com.square.aircommand.overlay.HandLandmarkOverlay
import com.square.aircommand.ui.theme.listener.TrainingProgressListener
import com.square.aircommand.utils.ThrottledLogger
import com.square.aircommand.utils.toBitmapCompat
import java.util.concurrent.Executors

@Composable
fun CameraScreenTest(
    handDetector: HandDetector,
    landmarkDetector: HandLandmarkDetector,
    gestureClassifier: GestureClassifier,
    isTrainingMode: Boolean = false,
    trainingGestureName: String = "",
    gestureStatusText: MutableState<String>? = null,
    onTrainingComplete: (() -> Unit)? = null,
    lifecycleOwner: LifecycleOwner
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    val gestureLabelMapper = remember { GestureLabelMapper(context) }
    val gestureText = remember { mutableStateOf("제스처 없음") }
    val detectionFrameCount = remember { mutableIntStateOf(0) }
    val latestPoints = remember { mutableStateListOf<PointF>() }
    val landmarksState = remember { mutableStateOf<List<Triple<Double, Double, Double>>>(emptyList()) }

    val analyzer = remember(
        context, handDetector, landmarkDetector, gestureClassifier,
        gestureLabelMapper, gestureText, detectionFrameCount,
        latestPoints, landmarksState, isTrainingMode,
        trainingGestureName, gestureStatusText, onTrainingComplete
    ) {
        HandAnalyzerTest(
            context = context,
            handDetector = handDetector,
            landmarkDetector = landmarkDetector,
            gestureClassifier = gestureClassifier,
            gestureLabelMapper = gestureLabelMapper,
            gestureText = gestureText,
            detectionFrameCount = detectionFrameCount,
            latestPoints = latestPoints,
            landmarksState = landmarksState,
            validDetectionThreshold = 20,
            isTrainingMode = isTrainingMode,
            onGestureDetected = {}
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                CameraInitializer.startCamera(
                    ctx,
                    previewView,
                    lifecycleOwner,
                    analysisExecutor,
                    analyzer
                )
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        if (landmarksState.value.isNotEmpty()) {
            HandLandmarkOverlay(
                landmarks = landmarksState.value,
                modifier = Modifier.fillMaxSize()
            )

            Text(
                text = gestureText.value,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 32.dp),
                color = Color.Black,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

class HandAnalyzerTest(
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
    private val isTrainingMode: Boolean,
    private val onGestureDetected: ((String) -> Unit)? = null,
    private val trainingGestureName: String = "",
    private val onTrainingComplete: (() -> Unit)? = null,
    private val trainingProgressListener: TrainingProgressListener? = null

    ) : ImageAnalysis.Analyzer {
    override fun analyze(imageProxy: ImageProxy) {
        try {
            val bitmap = imageProxy.toBitmapCompat()
            val orientation = getBackCameraSensorOrientation(context)

            // 1. detectHandAndGetInfo 사용(탑-1 손, crop/회전 포함)
            val detectionResult = handDetector.detectHandAndGetInfo(bitmap, orientation)
            if (detectionResult != null) {
                detectionFrameCount.value += 1

                if (detectionFrameCount.value >= validDetectionThreshold) {
                    ThrottledLogger.log("HandAnalyzer", "손 감지 성공")

                    // 2. crop된 손 이미지만 사용
                    val croppedHand = detectionResult.croppedHand

                    if (isTrainingMode) {
                        landmarkDetector.transfer(croppedHand, 0, trainingGestureName, trainingProgressListener)
                        if (!landmarkDetector.isCollecting) {
                            onTrainingComplete?.invoke()
                        }
                    } else {
                        landmarkDetector.predict(croppedHand, 0)
                    }

                    // 3. landmark 결과 활용
                    val landmarks = landmarkDetector.lastLandmarks

                    if (!isTrainingMode && landmarks.size == 21) {
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
}
//    override fun analyze(imageProxy: ImageProxy) {
//        try {
//            val bitmap = imageProxy.toBitmapCompat()
//            val points = handDetector.detect(bitmap)
//            val orientation = getBackCameraSensorOrientation(context)
//
//            if (points.isNotEmpty()) {
//                detectionFrameCount.value += 1
//                if (detectionFrameCount.value >= validDetectionThreshold) {
//                    ThrottledLogger.log("HandAnalyzer", "손 감지 성공: ${points.size}")
//                    latestPoints.clear()
//                    latestPoints.addAll(points)
//
//                    // ✅ 일반 모드에서는 예측만 수행
//                    landmarkDetector.predict(bitmap, orientation)
//                    val landmarks = landmarkDetector.lastLandmarks
//
//                    if (!isTrainingMode && landmarks.size == 21) {
//                        landmarksState.value = landmarks.toList()
//                        val (gestureIndex, confidence) = gestureClassifier.classify(
//                            landmarks,
//                            landmarkDetector.lastHandedness
//                        )
//
//                        val gestureName = gestureLabelMapper.getLabel(gestureIndex)
//                        gestureText.value = "$gestureName (${(confidence * 100).toInt()}%)"
//
//                        ThrottledLogger.log(
//                            "HandAnalyzer",
//                            "제스처 인식됨: $gestureName (index=$gestureIndex, 신뢰도=${String.format("%.2f", confidence)})"
//                        )
//
//                        onGestureDetected?.invoke(GestureLabel.fromId(gestureIndex).toString())
//                    } else if (!isTrainingMode) {
//                        gestureText.value = "제스처 없음"
//                        ThrottledLogger.log("HandAnalyzer", "랜드마크 포인트가 부족합니다")
//                    }
//                } else {
//                    ThrottledLogger.log("HandAnalyzer", "감지 누적 중 (${detectionFrameCount.value})")
//                }
//            } else {
//                detectionFrameCount.value = 0
//                landmarksState.value = emptyList()
//                ThrottledLogger.log("HandAnalyzer", "손 감지 안됨")
//            }
//        } catch (e: Exception) {
//            Log.e("HandAnalyzer", "분석 실패: ${e.message}", e)
//        } finally {
//            imageProxy.close()
//        }
//    }
//}