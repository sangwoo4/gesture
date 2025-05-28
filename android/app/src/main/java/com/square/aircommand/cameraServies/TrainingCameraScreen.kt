package com.square.aircommand.cameraServies

import android.content.Context
import android.graphics.PointF
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.square.aircommand.cameraUtil.HandAnalyzer
import com.square.aircommand.classifier.GestureClassifier
import com.square.aircommand.classifier.GestureLabelMapper
import com.square.aircommand.handdetector.HandDetector
import com.square.aircommand.handlandmarkdetector.HandLandmarkDetector
import com.square.aircommand.ui.theme.listener.TrainingProgressListener
import com.square.aircommand.utils.GestureStatus
import java.util.concurrent.Executor
import java.util.concurrent.Executors

@Composable
fun TrainingCameraScreen(
    handDetector: HandDetector,
    landmarkDetector: HandLandmarkDetector,
    gestureClassifier: GestureClassifier,
    isTrainingMode: Boolean = true,
    trainingGestureName: String = "",
    gestureStatusText: MutableState<GestureStatus>? = null,
    onTrainingComplete: (() -> Unit)? = null,

    // 상태바 초기화
    onProgressUpdate: ((Int) -> Unit)? = null,
    onModelDownloadStarted: (() -> Unit)? = null, // ⬅️ 추가
    onModelDownloadComplete: (() -> Unit)? = null  // ⬅️ 추가

) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    val gestureLabelMapper = remember { GestureLabelMapper(context) }
    val gestureText = remember { mutableStateOf("제스처 없음") }
    val detectionFrameCount = remember { mutableIntStateOf(0) }
    val latestPoints = remember { mutableStateListOf<PointF>() }
    val landmarksState = remember { mutableStateOf<List<Triple<Double, Double, Double>>>(emptyList()) }

    val trainingListener = remember {
        object : TrainingProgressListener {
            override fun onCollectionProgress(percent: Int) {
                gestureStatusText?.value = GestureStatus.Collecting
                // 상태바 퍼센티지 연동
                onProgressUpdate?.invoke(percent)
            }

            override fun onTrainingStarted() {
                gestureStatusText?.value = GestureStatus.Training
            }

            override fun onModelDownloadStarted() {
                gestureStatusText?.value = GestureStatus.DownloadingModel
                onModelDownloadStarted?.invoke() // ✅ 시작 신호
            }

            override fun onModelDownloadComplete() {
                gestureStatusText?.value = GestureStatus.ModelApplied
                onModelDownloadComplete?.invoke() // ✅ 완료 신호
            }
        }
    }

    val analyzer = remember(
        context, handDetector, landmarkDetector, gestureClassifier,
        gestureLabelMapper, gestureText, detectionFrameCount,
        latestPoints, landmarksState, isTrainingMode, onTrainingComplete
    ) {
        HandAnalyzer(
            context = context,
            handDetector = handDetector,
            landmarkDetector = landmarkDetector,
            gestureClassifier = gestureClassifier,
            gestureLabelMapper = gestureLabelMapper,
            gestureText = gestureText,
            detectionFrameCount = detectionFrameCount,
            landmarksState = landmarksState,
            validDetectionThreshold = 20,
            isTrainingMode = isTrainingMode,
            trainingGestureName = trainingGestureName,
            onGestureDetected = {},
            trainingProgressListener = trainingListener,
            onTrainingComplete = {
                onTrainingComplete?.invoke()
            },
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
    }

    DisposableEffect(Unit) {
        onDispose {
            analysisExecutor.shutdown()
            try {
                val cameraProvider = ProcessCameraProvider.getInstance(context).get()
                cameraProvider.unbindAll()
            } catch (e: Exception) {
                e.printStackTrace()
            }
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
                .setTargetResolution(Size(256, 256))
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
