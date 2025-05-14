package com.square.aircommand.camera

import android.content.Context
import android.graphics.PointF
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.square.aircommand.classifier.GestureClassifier
import com.square.aircommand.classifier.GestureLabelMapper
import com.square.aircommand.handdetector.HandDetector
import com.square.aircommand.handlandmarkdetector.HandLandmarkDetector
import com.square.aircommand.overlay.HandLandmarkOverlay
import com.square.aircommand.utils.ThrottledLogger
import com.square.aircommand.utils.toBitmapCompat
import java.util.concurrent.Executor
import java.util.concurrent.Executors

fun getBackCameraSensorOrientation(context: Context): Int {
    val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    for (cameraId in manager.cameraIdList) {
        val characteristics = manager.getCameraCharacteristics(cameraId)
        if (characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
            return characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        }
    }
    return 0
}

@Composable
fun CameraScreen(
    handDetector: HandDetector,
    landmarkDetector: HandLandmarkDetector,
    gestureClassifier: GestureClassifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    val gestureLabelMapper = remember { GestureLabelMapper(context) }
    val gestureText = remember { mutableStateOf("제스처 없음") }
    val detectionFrameCount = remember { mutableIntStateOf(0) }
    val latestPoints = remember { mutableStateListOf<PointF>() }
    val landmarksState = remember { mutableStateOf<List<Triple<Double, Double, Double>>>(emptyList()) }

    val analyzer = remember {
        HandAnalyzer(
            context = context,
            handDetector = handDetector,
            landmarkDetector = landmarkDetector,
            gestureClassifier = gestureClassifier,
            gestureLabelMapper = gestureLabelMapper,
            gestureText = gestureText,
            detectionFrameCount = detectionFrameCount,
            latestPoints = latestPoints,
            landmarksState = landmarksState,
            validDetectionThreshold = 50
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
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

object CameraInitializer {
    fun startCamera(
        context: Context,
        previewView: PreviewView,
        lifecycleOwner: LifecycleOwner,
        executor: Executor,
        analyzer: ImageAnalysis.Analyzer
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().apply {
                setSurfaceProvider(previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(executor, analyzer)
                }

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis
            )
        }, ContextCompat.getMainExecutor(context))
    }
}


class HandAnalyzer(
    private val context: Context,
    private val handDetector: HandDetector,
    private val landmarkDetector: HandLandmarkDetector,
    private val gestureClassifier: GestureClassifier,
    private val gestureLabelMapper: GestureLabelMapper,
    private val gestureText: MutableState<String>,
    private val detectionFrameCount: MutableState<Int>,
    private val latestPoints: SnapshotStateList<PointF>,
    private val landmarksState: MutableState<List<Triple<Double, Double, Double>>>,
    private val validDetectionThreshold: Int
) : ImageAnalysis.Analyzer {

    override fun analyze(imageProxy: ImageProxy) {
        try {
            val bitmap = imageProxy.toBitmapCompat()
            val points = handDetector.detect(bitmap)
            val orientation = getBackCameraSensorOrientation(context)

            if (points.isNotEmpty()) {
                detectionFrameCount.value += 1

                if (detectionFrameCount.value >= validDetectionThreshold) {
                    ThrottledLogger.log("HandAnalyzer", "🟢 유효 손 감지 (${points.size})")
                    latestPoints.clear()
                    latestPoints.addAll(points)

                    for (point in points) {
                        landmarkDetector.predict(bitmap, orientation)
                        val landmarks = landmarkDetector.lastLandmarks

                        if (landmarks.size == 21) {
                            landmarksState.value = landmarks.toList()
                            val (gestureIndex, confidence) = gestureClassifier.classify(
                                landmarks,
                                landmarkDetector.lastHandedness
                            )
                            val gestureName = gestureLabelMapper.getLabel(gestureIndex)
                            gestureText.value = "$gestureName (${(confidence * 100).toInt()}%)"
                            ThrottledLogger.log("HandAnalyzer", "✋ $gestureName ($gestureIndex, ${"%.2f".format(confidence)})")
                        } else {
                            gestureText.value = "제스처 없음"
                            ThrottledLogger.log("HandAnalyzer", "🚫 랜드마크 부족")
                        }
                    }
                } else {
                    ThrottledLogger.log("HandAnalyzer", "⏳ 누적 중 (${detectionFrameCount.value})")
                }
            } else {
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