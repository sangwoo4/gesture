package com.square.aircommand.backgroundcamera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PointF
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.square.aircommand.camera.HandAnalyzers
import com.square.aircommand.classifier.GestureClassifier
import com.square.aircommand.classifier.GestureLabelMapper
import com.square.aircommand.gesture.GestureActionExecutor
import com.square.aircommand.gesture.GestureActionMapper
import com.square.aircommand.gesture.GestureLabel
import com.square.aircommand.gesture.GestureAccessibilityService
import com.square.aircommand.handdetector.HandDetector
import com.square.aircommand.handlandmarkdetector.HandLandmarkDetector
import com.square.aircommand.tflite.TFLiteHelpers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.Executors

class CameraService : Service() {

    private val tag = "CameraService"
    private val channelId = "camera_service_channel"
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var handDetector: HandDetector
    private lateinit var landmarkDetector: HandLandmarkDetector
    private lateinit var gestureClassifier: GestureClassifier
    private lateinit var handAnalyzer: ImageAnalysis.Analyzer

    private var isModelInitialized = false

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate() {
        super.onCreate()
        Log.d(tag, "✅ CameraService onCreate() 호출됨")

        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(1, createNotification())
        }

        if (!ensureAccessibilityServiceEnabled()) return


        initModels()
        initAnalyzer()
        startCamera()
    }

    private fun initModels() {
        if (::handDetector.isInitialized) {
            handDetector.close()
        }
        if (::landmarkDetector.isInitialized) {
            landmarkDetector.close()
        }
        if (::gestureClassifier.isInitialized) {
            gestureClassifier.close()
        }

        val delegateOrder = arrayOf(
            arrayOf(TFLiteHelpers.DelegateType.QNN_NPU),
            arrayOf(TFLiteHelpers.DelegateType.GPUv2),
            arrayOf()
        )

        handDetector = HandDetector(this, "mediapipe_hand-handdetector.tflite", delegateOrder)
        landmarkDetector = HandLandmarkDetector(this, "mediapipe_hand-handlandmarkdetector.tflite", delegateOrder)
        gestureClassifier = GestureClassifier(this, "update_gesture_model_cnns.tflite", delegateOrder)

        isModelInitialized = true
    }

    private fun initAnalyzer() {
        val gestureText = mutableStateOf("제스처 없음")
        val detectionFrameCount = mutableIntStateOf(0)
        val latestPoints = mutableStateListOf<PointF>()
        val landmarksState = mutableStateOf<List<Triple<Double, Double, Double>>>(emptyList())

        handAnalyzer = HandAnalyzers(
            context = this,
            handDetector = handDetector,
            landmarkDetector = landmarkDetector,
            gestureClassifier = gestureClassifier,
            gestureLabelMapper = GestureLabelMapper(this),
            gestureText = gestureText,
            detectionFrameCount = detectionFrameCount,
            latestPoints = latestPoints,
            landmarksState = landmarksState,
            validDetectionThreshold = 20,
            isActive = { isModelInitialized },
            onGestureDetected = { gestureLabel ->
                if (gestureLabel != GestureLabel.NONE) {
                    val action = GestureActionMapper.getSavedGestureAction(this, gestureLabel)
                    GestureActionExecutor.execute(action, this)
                    Log.d(tag, "🙌 제스처: $gestureLabel → 동작: $action 실행됨")
                }
            }
        )
    }

    private fun startCamera() {

        // TODO: HandAnalyzer를 만들기 전에 모델 초기화 여부 체크 -> 백그라운드 권한 관련
        if (!isModelInitialized) {
            return
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(Executors.newSingleThreadExecutor(), handAnalyzer)
                    }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    DummyLifecycleOwner(),
                    cameraSelector,
                    analysis
                )
                Log.i(tag, "📸 백그라운드 카메라 및 분석기 연결 완료")
            } catch (e: Exception) {
                Log.e(tag, "❌ 카메라 연결 실패: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun ensureAccessibilityServiceEnabled(): Boolean {
        val prefs = getSharedPreferences("air_command_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("accessibility_permission_checked", false)) return true

        if (GestureAccessibilityService.instance == null) {
            Toast.makeText(
                this,
                "스와이프 기능을 위해 접근성 권한이 필요합니다. 설정 화면으로 이동합니다.",
                Toast.LENGTH_LONG
            ).show()

            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)

            return false
        }

        prefs.edit().putBoolean("accessibility_permission_checked", true).apply()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()

        try {
            ProcessCameraProvider.getInstance(this).get().unbindAll()
            Log.d(tag, "📷 카메라 및 분석기 해제 완료")
        } catch (e: Exception) {
            Log.w(tag, "unbindAll 실패: ${e.message}")
        }

        try {
            if (::handDetector.isInitialized) handDetector.close()
            if (::landmarkDetector.isInitialized) landmarkDetector.close()
            if (::gestureClassifier.isInitialized) gestureClassifier.close()
            Log.d(tag, "🧹 모델 close() 완료")
        } catch (e: Exception) {
            Log.e(tag, "❌ 모델 해제 중 오류: ${e.message}", e)
        }

        serviceScope.cancel()
        isModelInitialized = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotification(): Notification {
        return Notification.Builder(this, channelId)
            .setContentTitle("Gesture Camera Service")
            .setContentText("손 제스처를 백그라운드에서 분석 중입니다.")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            channelId,
            "Camera Service Channel",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}