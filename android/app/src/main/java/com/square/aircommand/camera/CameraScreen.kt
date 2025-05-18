package com.square.aircommand.camera

import android.content.ContentValues.TAG
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
import com.square.aircommand.gesture.GestureLabel
import com.square.aircommand.handdetector.HandDetector
import com.square.aircommand.handlandmarkdetector.HandLandmarkDetector
import com.square.aircommand.overlay.HandLandmarkOverlay
import com.square.aircommand.utils.ThrottledLogger
import com.square.aircommand.utils.toBitmapCompat
import java.util.concurrent.Executor
import java.util.concurrent.Executors

// 실제 카메라 화면을 띄우는 Composable 함수
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

    // 손 분석 로직을 처리하는 analyzer 초기화
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
            validDetectionThreshold = 20,
        )
    }

    // 카메라 뷰 + 랜드마크 오버레이 + 제스처 텍스트 출력 UI
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

        // 손이 감지되었을 경우에만 랜드마크와 텍스트 출력
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

// 카메라 초기화 및 바인딩 (CameraX 사용)
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

            // 프리뷰 설정
            val preview = Preview.Builder().build().apply {
                setSurfaceProvider(previewView.surfaceProvider)
            }

            // 이미지 분석 설정 (손 분석 처리)
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(executor, analyzer)
                }

            // 기존 카메라 연결 해제 후 새로 바인딩
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_FRONT_CAMERA, // 원래 후면 이엇음
                preview,
                analysis
            )
//            Log.d(TAG, "백그라운드 카메라 서비스 시작됨")
        }, ContextCompat.getMainExecutor(context))
    }
}

// 손 감지 및 제스처 분류를 수행하는 분석기
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

// 전면 카메라의 센서 회전 각도를 가져오는 함수
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