// ✅ GestureAccessibilityService.kt
package com.square.aircommand.gesture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.res.Resources
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * 접근성 서비스를 활용한 시스템 UI 제어 서비스
 * - 스크롤, 스와이프, 탭 등의 동작을 추후 확장 가능
 */
class GestureAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("GestureService", "✅ 접근성 서비스 연결됨")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 수신된 이벤트를 별도로 처리하지 않음 (명시적 요청만 수행)
    }

    override fun onInterrupt() = Unit

    companion object {
        var instance: GestureAccessibilityService? = null

//        /**
//         * 오른쪽으로 스와이프 (좌→우)
//         */
//        fun swipeRight() {
//            swipeGesture(
//                startXRatio = 0.3f,
//                startYRatio = 0.5f,
//                endXRatio = 0.8f,
//                endYRatio = 0.5f,
//                durationMs = 300L
//            )
//        }
//
//        /**
//         * 아래로 스와이프 (위→아래)
//         */
//        fun swipeDown() {
//            swipeGesture(
//                startXRatio = 0.5f,
//                startYRatio = 0.3f,
//                endXRatio = 0.5f,
//                endYRatio = 0.8f,
//                durationMs = 300L
//            )
//        }

        /**
         * 비율 기반 스와이프 실행 (화면 해상도에 따라 자동 계산)
         */
        fun swipeGesture(
            startXRatio: Float,
            startYRatio: Float,
            endXRatio: Float,
            endYRatio: Float,
            durationMs: Long
        ) {
            val service = instance ?: return
            val dm = Resources.getSystem().displayMetrics
            val startX = startXRatio * dm.widthPixels
            val startY = startYRatio * dm.heightPixels
            val endX = endXRatio * dm.widthPixels
            val endY = endYRatio * dm.heightPixels

            service.performSwipeGesture(startX, startY, endX, endY, durationMs)
        }
    }

    /**
     * 화면 제스처를 시뮬레이션하여 실제 터치 동작을 수행
     */
    fun performSwipeGesture(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 300L
    ) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()

        val success = dispatchGesture(gesture, null, null)
        Log.d("GestureService", "🌀 제스처 실행: $startX,$startY → $endX,$endY | 성공: $success")
    }
}