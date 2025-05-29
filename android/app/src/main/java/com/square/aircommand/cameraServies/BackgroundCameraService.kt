package com.square.aircommand.cameraServies

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service

import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

import android.util.Log

import androidx.annotation.RequiresApi
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.square.aircommand.cameraUtil.DummyLifecycleOwner
import com.square.aircommand.cameraUtil.HandAnalyzer
import com.square.aircommand.classifier.GestureClassifier
import com.square.aircommand.classifier.GestureLabelMapper
import com.square.aircommand.gesture.GestureActionExecutor
import com.square.aircommand.gesture.GestureActionMapper

import com.square.aircommand.handdetector.HandDetector
import com.square.aircommand.handlandmarkdetector.HandLandmarkDetector
import com.square.aircommand.tflite.ModelRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.Executors

class BackgroundCameraService : Service() {

    private val tag = "CameraService"
    private val channelId = "camera_service_channel"

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var handDetector: HandDetector? = null
    private var landmarkDetector: HandLandmarkDetector? = null
    private var gestureClassifier: GestureClassifier? = null
    private var handAnalyzer: ImageAnalysis.Analyzer? = null

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
    }

    // ✅ 서비스 시작 시 모델 및 카메라 재초기화
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(tag, "📦 onStartCommand 호출됨 (flags=$flags, startId=$startId)")

        // ✅ 이전 리소스 정리
        stopResources()
        // ✅ 모델 강제 재초기화

        initModels()
        initAnalyzer()
        startCamera()

        return START_STICKY
    }

    private fun initModels() {
        ModelRepository.resetModels(applicationContext)
        handDetector = ModelRepository.getHandDetector()
        landmarkDetector = ModelRepository.getLandmarkDetector()
        gestureClassifier = ModelRepository.getGestureClassifier()
    }

    private fun initAnalyzer() {
        val gestureText = mutableStateOf("제스처 없음")
        val detectionFrameCount = mutableIntStateOf(0)
        val landmarksState = mutableStateOf<List<Triple<Double, Double, Double>>>(emptyList())

        handAnalyzer = HandAnalyzer(
            context = this,
            handDetector = handDetector!!,
            landmarkDetector = landmarkDetector!!,
            gestureClassifier = gestureClassifier!!,
            gestureLabelMapper = GestureLabelMapper(this),
            gestureText = gestureText,
            detectionFrameCount = detectionFrameCount,
            landmarksState = landmarksState,
            validDetectionThreshold = 50,
            onGestureDetected = { gestureName ->
                if (gestureName != "NONE") {
                    val action = GestureActionMapper.getSavedGestureAction(this, gestureName)
                    GestureActionExecutor.execute(action, this)
                    Log.d(tag, "🙌 제스처: $gestureName → 동작: $action 실행됨")
                }
            }
        )
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .apply {
                        setAnalyzer(Executors.newSingleThreadExecutor(), handAnalyzer!!)
                    }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(DummyLifecycleOwner(), cameraSelector, analysis)

                Log.i(tag, "📸 백그라운드 카메라 및 분석기 연결 완료")
            } catch (e: Exception) {
                Log.e(tag, "❌ 카메라 연결 실패: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ✅ 기존 리소스 정리
    private fun stopResources() {
        try {
            ProcessCameraProvider.getInstance(this).get().unbindAll()
        } catch (e: Exception) {
            Log.w(tag, "⚠️ 카메라 해제 중 오류 발생: ${e.message}")
        }

        handAnalyzer = null
        ModelRepository.closeAll()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopResources()
        serviceScope.cancel()
        Log.d(tag, "🛑 CameraService 종료 및 리소스 해제 완료")
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