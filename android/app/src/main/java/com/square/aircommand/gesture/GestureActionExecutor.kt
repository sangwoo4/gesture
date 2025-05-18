package com.square.aircommand.gesture

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.util.Log
import com.square.aircommand.utils.ThrottledLogger

/**
 * 제스처에 대응하는 실제 기능을 실행하는 객체
 */
object GestureActionExecutor {

    // 마지막 실행 시간 기록용 맵
    private val lastActionTimeMap = mutableMapOf<GestureAction, Long>()

    // 제스처별 쿨다운 시간 (ms) - 없으면 기본값 사용
    private val cooldownPerAction = mapOf(
        GestureAction.TOGGLE_FLASH to 1000L,
        GestureAction.SWIPE_RIGHT to 1000L,
        GestureAction.SWIPE_DOWN to 1000L,
        GestureAction.VOLUME_UP to 500L,
        GestureAction.VOLUME_DOWN to 500L,
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
            Log.d("GestureAction", "⏱️ $action 쿨다운 중 (${now - lastTime}ms < $cooldown ms)")
            return
        }

        lastActionTimeMap[action] = now // 실행 시간 갱신

        when (action) {
            GestureAction.VOLUME_UP -> adjustVolume(context, true)
            GestureAction.VOLUME_DOWN -> adjustVolume(context, false)
            GestureAction.TOGGLE_FLASH -> toggleFlash(context)
            GestureAction.SWIPE_RIGHT -> swipeRight()
            GestureAction.SWIPE_DOWN -> swipeDown()
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
                val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val isBack = characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
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
     * 시스템 오른쪽 스와이프 명령 실행
     */
    private fun swipeRight() {
        try {
            val command = "input swipe 300 500 1000 500"
            Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            Log.d("GestureAction", "👉 오른쪽으로 스와이프 실행됨")
        } catch (e: Exception) {
            Log.e("GestureAction", "❌ 오른쪽 스와이프 실패: ${e.message}", e)
        }
    }

    /**
     * 시스템 아래쪽 스와이프 명령 실행
     */
    private fun swipeDown() {
        try {
            val command = "input swipe 500 300 500 1200"
            Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            Log.d("GestureAction", "👇 아래로 스와이프 실행됨")
        } catch (e: Exception) {
            Log.e("GestureAction", "❌ 아래로 스와이프 실패: ${e.message}", e)
        }
    }
}

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
