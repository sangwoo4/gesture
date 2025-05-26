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
import com.square.aircommand.tflite.ModelRepository
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

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate() {
        super.onCreate()
        Log.d(tag, "✅ CameraService onCreate() 호출됨")

        // 알림 채널 및 포그라운드 서비스 시작
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(1, createNotification())
        }

        // 접근성 권한이 없으면 초기화 중단 (설정으로 이동)
        if (!ensureAccessibilityServiceEnabled()) return

        // 모델 초기화 및 카메라 분석기 설정
        initModels()
        initAnalyzer()
        startCamera()
    }

    // 모델들 초기화 (HandDetector, LandmarkDetector, GestureClassifier)
    private fun initModels() {
        ModelRepository.initModels(applicationContext)
        handDetector = ModelRepository.getHandDetector()
        landmarkDetector = ModelRepository.getLandmarkDetector()
        gestureClassifier = ModelRepository.getGestureClassifier()
    }

    // 카메라 분석기 초기화
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
            onGestureDetected = { gestureLabel ->
                if (gestureLabel != GestureLabel.NONE) {
                    val action = GestureActionMapper.getSavedGestureAction(this, gestureLabel)
                    GestureActionExecutor.execute(action, this)
                    Log.d(tag, "🙌 제스처: $gestureLabel → 동작: $action 실행됨")
                }
            }
        )
    }

    // 카메라 스트림을 백그라운드에서 시작
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(Executors.newSingleThreadExecutor(), handAnalyzer)
                }

            try {
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

    // 접근성 권한 확인 및 안내 처리 → 접근성 미설정 시 설정 화면으로 이동
    private fun ensureAccessibilityServiceEnabled(): Boolean {
        val prefs = getSharedPreferences("air_command_prefs", Context.MODE_PRIVATE)

        // ✅ 이미 안내한 경우 → 계속 진행
        if (prefs.getBoolean("accessibility_permission_checked", false)) {
            return true
        }

        // ✋ 접근성 서비스가 꺼져 있는 경우 안내 후 설정 이동
        if (GestureAccessibilityService.instance == null) {
            Toast.makeText(
                this,
                "스와이프 기능을 위해 접근성 권한이 필요합니다. 설정 화면으로 이동합니다.",
                Toast.LENGTH_LONG
            ).show()

            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)

            return false // 👉 초기화 중단
        }

        // ✅ 안내는 한 번만 표시
        prefs.edit().putBoolean("accessibility_permission_checked", true).apply()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        handDetector.close()
        landmarkDetector.close()
        gestureClassifier.close()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // 포그라운드 서비스 알림 생성
    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotification(): Notification {
        return Notification.Builder(this, channelId)
            .setContentTitle("Gesture Camera Service")
            .setContentText("손 제스처를 백그라운드에서 분석 중입니다.")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
    }

    // 알림 채널 생성
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