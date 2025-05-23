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

@Composable
fun CameraScreen(
    handDetector: HandDetector,
    landmarkDetector: HandLandmarkDetector,
    gestureClassifier: GestureClassifier,
    isTrainingMode: Boolean = false,
    trainingGestureName: String = "",
    gestureStatusText: MutableState<String>? = null,
    onTrainingComplete: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }

    val uiState = remember { GestureUIState() }
    val labelMapper = remember { GestureLabelMapper(context) }

    val analyzer = remember {
        HandAnalyzer(
            context = context,
            handDetector = handDetector,
            landmarkDetector = landmarkDetector,
            classifier = gestureClassifier,
            labelMapper = labelMapper,
            uiState = uiState,
            isTraining = isTrainingMode,
            trainingName = trainingGestureName,
            onGestureDetected = {},
            onTrainingDone = {
                uiState.gestureText.value = "🎉 학습 완료"
                gestureStatusText?.value = "✅ 사용자 제스처 학습 완료"
                onTrainingComplete?.invoke()
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = {
                PreviewView(it).apply {
                    CameraInitializer.startCamera(context, this, lifecycleOwner, executor, analyzer)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (uiState.landmarks.value.isNotEmpty()) {
            HandLandmarkOverlay(
                landmarks = uiState.landmarks.value,
                modifier = Modifier.fillMaxSize()
            )

            Text(
                text = uiState.gestureText.value,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 32.dp),
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }

        if (!gestureStatusText?.value.isNullOrBlank()) {
            Text(
                text = gestureStatusText?.value ?: "",
                color = Color.Green,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
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
                CameraSelector.DEFAULT_FRONT_CAMERA,
                preview,
                analysis
            )
        }, ContextCompat.getMainExecutor(context))
    }
}

data class GestureUIState(
    val gestureText: MutableState<String> = mutableStateOf("제스처 없음"),
    val gestureStatusText: MutableState<String?> = mutableStateOf(null),
    val detectionFrameCount: MutableState<Int> = mutableIntStateOf(0),
    val landmarks: MutableState<List<Triple<Double, Double, Double>>> = mutableStateOf(emptyList()),
    val latestPoints: SnapshotStateList<PointF> = mutableStateListOf()
)

class HandAnalyzer(
    private val context: Context,
    private val handDetector: HandDetector,
    private val landmarkDetector: HandLandmarkDetector,
    private val classifier: GestureClassifier,
    private val labelMapper: GestureLabelMapper,
    private val uiState: GestureUIState,
    private val isTraining: Boolean,
    private val trainingName: String,
    private val validThreshold: Int = 20,
    private val onGestureDetected: ((String) -> Unit)? = null,
    private val onTrainingDone: (() -> Unit)? = null
) : ImageAnalysis.Analyzer {

    override fun analyze(imageProxy: ImageProxy) {
        try {
            val bitmap = imageProxy.toBitmapCompat()
            val points = handDetector.detect(bitmap)
            val orientation = getBackCameraSensorOrientation(context)

            if (points.isNotEmpty()) {
                uiState.detectionFrameCount.value++

                if (uiState.detectionFrameCount.value >= validThreshold) {
                    uiState.latestPoints.clear()
                    uiState.latestPoints.addAll(points)

                    for (point in points) {
                        val gestureName = if (isTraining) trainingName else "temp"
                        landmarkDetector.transfer(bitmap, orientation, gestureName)

                        if (isTraining && !landmarkDetector.isCollecting) {
                            onTrainingDone?.invoke()
                            return
                        }

                        val landmarks = landmarkDetector.lastLandmarks
                        if (!isTraining && landmarks.size == 21) {
                            uiState.landmarks.value = landmarks
                            val (index, confidence) = classifier.classify(landmarks, landmarkDetector.lastHandedness)
                            val name = labelMapper.getLabel(index)
                            uiState.gestureText.value = "$name (${(confidence * 100).toInt()}%)"
                            onGestureDetected?.invoke(name)
                        }
                    }
                }
            } else {
                uiState.detectionFrameCount.value = 0
                uiState.landmarks.value = emptyList()
            }
        } catch (e: Exception) {
            Log.e("HandAnalyzer", "분석 실패: ${e.message}", e)
        } finally {
            imageProxy.close()
        }
    }
}

fun getBackCameraSensorOrientation(context: Context): Int {
    val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    for (cameraId in manager.cameraIdList) {
        val characteristics = manager.getCameraCharacteristics(cameraId)
        if (characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) {
            return characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        }
    }
    return 0
}
