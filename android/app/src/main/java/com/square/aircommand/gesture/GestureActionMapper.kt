package com.square.aircommand.gesture

import android.content.Context
import android.util.Log

/**
 * 제스처 라벨(String 또는 Enum)과 사용자 설정 동작을 매핑하는 객체
 */
object GestureActionMapper {

    private const val PREFS_NAME = "gesture_prefs"

    /**
     * displayName(String) → GestureAction 변환 함수
     */
    private fun fromDisplayName(name: String): GestureAction {
        return GestureAction.entries.find { it.displayName == name } ?: GestureAction.NONE
    }

    /**
     * ✅ 문자열 기반 제스처 라벨을 지원하는 버전 (사용자 정의 포함)
     */
    fun getSavedGestureAction(context: Context, gestureLabel: String): GestureAction {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = "gesture_${gestureLabel.lowercase()}_action"
        val savedDisplayName = prefs.getString(key, null)

        Log.d("GestureActionMapper", "🔍 gesture=$gestureLabel, key=$key, saved=$savedDisplayName")

        return if (savedDisplayName != null) {
            fromDisplayName(savedDisplayName)
        } else {
            GestureAction.NONE
        }
    }

    /**
     * 기존 enum 방식도 그대로 유지 (기본 제스처만 해당)
     */
    fun getSavedGestureAction(context: Context, gestureLabel: GestureLabel): GestureAction {
        return getSavedGestureAction(context, gestureLabel.name)
    }

    /**
     * 기본 매핑 (초기값 설정 시 사용 가능)
     */
    val defaultMapping = mapOf(
        GestureLabel.SCISSORS to GestureAction.SWIPE_RIGHT,
        GestureLabel.ROCK to GestureAction.TOGGLE_FLASH,
        GestureLabel.PAPER to GestureAction.VOLUME_UP,
        GestureLabel.ONE to GestureAction.SWIPE_DOWN
        // GestureLabel.NONE 은 매핑 없음
    )
}