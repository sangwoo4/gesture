package com.square.aircommand.gesture

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings.Global.putString

import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.provider.Settings

import android.util.Log
import android.widget.Toast
import com.samsung.android.sdk.samsungpay.v2.PartnerInfo
import com.samsung.android.sdk.samsungpay.v2.SamsungPay
import com.samsung.android.sdk.samsungpay.v2.SpaySdk
import com.skt.Tmap.TMapTapi
import com.square.aircommand.R
import com.square.aircommand.utils.ThrottledLogger

/**
 * 제스처에 대응하는 실제 기능을 실행하는 객체
 */
object GestureActionExecutor {

    // 마지막 실행 시간 기록용 맵
    private val lastActionTimeMap = mutableMapOf<GestureAction, Long>()

    // 제스처별 쿨다운 시간 (ms) - 없으면 기본값 사용
    private val cooldownPerAction = mapOf(
        GestureAction.TOGGLE_FLASH to 1500L,
        GestureAction.SWIPE_RIGHT to 1500L,
        GestureAction.SWIPE_DOWN to 1500L,

        GestureAction.SWIPE_LEFT to 1500L,
        GestureAction.SWIPE_UP to 1500L,

        GestureAction.VOLUME_UP to 1500L,
        GestureAction.VOLUME_DOWN to 1500L,
        GestureAction.GO_HOME to 3000L,
        GestureAction.GO_OFFICE to 3000L,

        GestureAction.PLAY_PAUSE_MUSIC to 1500L,

        GestureAction.OPEN_NOTES to 1500L,

        GestureAction.OPEN_CALCULATOR to 1500L
        )


    // 기본 쿨다운 시간
    private const val DEFAULT_COOLDOWN_MS = 1000L

    /**
     * 지정된 제스처 동작을 실행
     */
    fun execute(action: GestureAction, context: Context) {
        val now = System.currentTimeMillis()
        val lastTime = lastActionTimeMap[action] ?: 0L
        val cooldown = cooldownPerAction[action] ?: DEFAULT_COOLDOWN_MS

        if (now - lastTime < cooldown) {
            ThrottledLogger.log(
                "GestureAction",
                "⏱️ $action 쿨다운 중 (${now - lastTime}ms < $cooldown ms)"
            )
            return
        }

        lastActionTimeMap[action] = now // 실행 시간 갱신

        when (action) {
            GestureAction.VOLUME_UP -> adjustVolume(context, true)
            GestureAction.VOLUME_DOWN -> adjustVolume(context, false)
            GestureAction.TOGGLE_FLASH -> toggleFlash(context)
            GestureAction.SWIPE_RIGHT -> swipeRight()
            GestureAction.SWIPE_DOWN -> swipeDown()

            GestureAction.SWIPE_LEFT -> swipeLeft()
            GestureAction.SWIPE_UP -> swipeUp()

            GestureAction.GO_HOME -> invokeTmapGoHome(context)
            GestureAction.GO_OFFICE -> invokeTmapGoCompany(context)

            GestureAction.PLAY_PAUSE_MUSIC -> playOrPauseMusic(context)

            GestureAction.OPEN_NOTES -> launchNoteApp(context)

            GestureAction.OPEN_CALCULATOR -> launchCalculatorApp(context)

            GestureAction.NONE -> ThrottledLogger.log("GestureAction", "🛑제스처에 아무 기능도 할당되지 않음")
        }
    }

    /**
     * 볼륨 조절 함수
     */
    private fun adjustVolume(context: Context, up: Boolean) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val direction = if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            direction,
            AudioManager.FLAG_SHOW_UI // 👉 볼륨 UI 표시
        )
        Log.d("GestureAction", if (up) "🔊 볼륨 증가" else "🔉 볼륨 감소")
    }

    /**
     * 플래시 토글 기능
     */
    private fun toggleFlash(context: Context) {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraIdList = cameraManager.cameraIdList

            val cameraId = cameraIdList.firstOrNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val hasFlash =
                    characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val isBack =
                    characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                hasFlash && isBack
            }

            if (cameraId != null) {
                val currentState = flashState ?: false
                cameraManager.setTorchMode(cameraId, !currentState)
                flashState = !currentState

                Log.d("GestureAction", if (flashState == true) "💡 플래시 켜짐" else "🔦 플래시 꺼짐")
            } else {
                Log.w("GestureAction", "⚠️ 후면 플래시 지원 카메라를 찾을 수 없습니다.")
            }

        } catch (e: Exception) {
            Log.e("GestureAction", "❌ 플래시 토글 중 예외 발생: ${e.message}", e)
        }
    }

    // 플래시 상태 기억용
    private var flashState: Boolean? = null


    /**
     * 👉 오른쪽으로 스와이프 제스처 실행
     * - X축으로 더 긴 이동 거리와 긴 duration 설정
     */
    private fun swipeRight() {
        GestureAccessibilityService.swipeGesture(
            startXRatio = 0.2f,
            startYRatio = 0.5f,
            endXRatio = 0.8f,
            endYRatio = 0.5f,
            durationMs = 500L // 이전보다 긴 지속 시간
        )
        ThrottledLogger.log("GestureAction", "👉 오른쪽으로 스와이프 실행 요청")
    }

    /**
     * 👇 아래로 스와이프 제스처 실행
     * - Y축으로 더 긴 이동 거리와 긴 duration 설정
     */
    private fun swipeDown() {
        GestureAccessibilityService.swipeGesture(
            startXRatio = 0.5f,
            startYRatio = 0.2f,
            endXRatio = 0.5f,
            endYRatio = 0.8f,
            durationMs = 500L // 이전보다 긴 지속 시간
        )
        ThrottledLogger.log("GestureAction", "👇 아래로 스와이프 실행 요청")
    }

    /**
     * 👈 왼쪽 스와이프
     */
    private fun swipeLeft() {
        GestureAccessibilityService.swipeGesture(
            startXRatio = 0.8f,
            startYRatio = 0.5f,
            endXRatio = 0.2f,
            endYRatio = 0.5f,
            durationMs = 500L
        )
        ThrottledLogger.log("GestureAction", "👈 왼쪽으로 스와이프 실행 요청")
    }

    /**
     * 👆 위로 스와이프
     */
    private fun swipeUp() {
        GestureAccessibilityService.swipeGesture(
            startXRatio = 0.5f,
            startYRatio = 0.8f,
            endXRatio = 0.5f,
            endYRatio = 0.2f,
            durationMs = 500L
        )
        ThrottledLogger.log("GestureAction", "👆 위로 스와이프 실행 요청")
    }


    /*
    * 음악 재생 / 일시정지
    * */
    private fun playOrPauseMusic(context: Context) {
        try {
            val mediaSessionManager =
                context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager

            if (!hasNotificationAccess(context)) {
                Log.w("GestureActionExecutor", "🔒 알림 접근 권한 없음 - 음악 제어 불가")
                return
            }

            // ✅ 앱의 NotificationListenerService 이름 지정
            val componentName = ComponentName(context, NotificationListener::class.java)

            val controllers: List<MediaController> =
                mediaSessionManager.getActiveSessions(componentName)

            for (controller in controllers) {
                val playbackState = controller.playbackState
                if (playbackState != null) {
                    val transportControls = controller.transportControls
                    if (playbackState.state == android.media.session.PlaybackState.STATE_PLAYING) {
                        transportControls.pause()
                    } else {
                        transportControls.play()
                    }
                    Log.d("GestureActionExecutor", "🎵 음악 재생 상태 토글 성공")
                    return
                }
            }

            Log.d("GestureActionExecutor", "🎵 제어 가능한 미디어 세션 없음")

        } catch (e: SecurityException) {
            Log.e("GestureActionExecutor", "❌ 보안 예외 발생: ${e.message}", e)
        } catch (e: Exception) {
            Log.e("GestureActionExecutor", "❌ 음악 제어 중 예외 발생: ${e.message}", e)
        }
    }

    fun hasNotificationAccess(context: Context): Boolean {
        val enabledListeners = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        return enabledListeners.contains(context.packageName)
    }

    private fun launchNoteApp(context: Context) {
        try {
            val intent = Intent().apply {
                component = ComponentName(
                    "com.samsung.android.app.notes",
                    "com.samsung.android.app.notes.memolist.MemoListActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)
            Log.d("GestureActionExecutor", "📓 삼성 노트 앱 실행 성공")

        } catch (e: Exception) {
            Log.e("GestureActionExecutor", "❌ 삼성 노트 앱 실행 실패: ${e.message}", e)
        }
    }

    private fun launchCalculatorApp(context: Context) {
        try {
            val intent = Intent().apply {
                // 삼성 기본 계산기 앱 기준
                component = ComponentName(
                    "com.sec.android.app.popupcalculator",
                    "com.sec.android.app.popupcalculator.Calculator"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.d("GestureActionExecutor", "🧮 계산기 앱 실행 성공")

        } catch (e: Exception) {
            Log.e("GestureActionExecutor", "❌ 계산기 앱 실행 실패: ${e.message}", e)
        }
    }

}

    // T맵 설치 여부
    private fun isTmapInstalled(context: Context): Boolean {
        val pm = context.packageManager
        val pkgs = listOf(
            "com.skt.tmap.ku",
            "com.skt.Tmap",
            "com.skt.skaf.l001mtm091"
        )
        return pkgs.any { pkg ->
            pm.getLaunchIntentForPackage(pkg) != null
        }
    }

    // 즐겨찾기 함수 (집으로)
    private fun invokeTmapGoHome(context: Context) {
        val tMap = getTMapTapi(context)

        tMap.invokeGoHome()

        if (!isTmapInstalled(context)) {
            showToast(context, "TMap 앱이 설치되어 있지 않습니다.")
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.skt.tmap.ku"))
                    .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            )
            return
        }
    }

    // 즐겨찾기 함수 (회사로)
    private fun invokeTmapGoCompany(context: Context) {
        val tMap = getTMapTapi(context)

        tMap.invokeGoCompany()

        if (!isTmapInstalled(context)) {
            showToast(context, "TMap 앱이 설치되어 있지 않습니다.")
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.skt.tmap.ku"))
                    .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            )
            return
        }
    }
    // T맵 인증 확인
    private fun getTMapTapi(context: Context): TMapTapi =
        TMapTapi(context).apply {
            setSKTMapAuthentication(context.getString(R.string.tmap_api_key))
        }
    // UI 알림 스레드
    private fun runOnUiThread(action: () -> Unit) =
        Handler(Looper.getMainLooper()).post { action() }

    // UI 알림 팝업 창
    private fun showToast(context: Context, msg: String) =
        runOnUiThread { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }


//    /**
//     * 사진 촬영 기능 (추후 구현)
//     */
//    private fun takePhoto() {
//        Log.d("GestureAction", "📷 사진 촬영 기능은 아직 구현되지 않았습니다.")
//        // TODO: 카메라 캡처 트리거 로직 추가
//    }
//
//    /**
//     * 스크린샷 기능 (추후 구현)
//     */
//    private fun takeScreenshot(context: Context) {
//        Log.d("GestureAction", "🖼️ 스크린샷 기능은 아직 구현되지 않았습니다.")
//        // TODO: MediaProjection API 또는 shell command 사용
//    }