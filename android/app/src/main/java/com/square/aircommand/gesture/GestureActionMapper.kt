package com.square.aircommand.gesture

import android.content.Context
import android.util.Log

/**
 * 제스처 ID와 동작을 매핑하거나,
 * SharedPreferences 기반 사용자 설정 displayName을 지원하는 객체
 */
object GestureActionMapper {

    /**
     * displayName(String) → GestureAction 변환 함수
     */
    private fun fromDisplayName(name: String): GestureAction {
        return GestureAction.entries.find { it.displayName == name } ?: GestureAction.NONE
    }

    /**
     * SharedPreferences에서 사용자가 설정한 제스처-액션 매핑을 가져옴
     */
    fun getSavedGestureAction(context: Context, gestureLabel: GestureLabel): GestureAction {
        val prefs = context.getSharedPreferences("gesture_prefs", Context.MODE_PRIVATE)

        // 제스처별 key 정의
        val key = when (gestureLabel) {
            GestureLabel.PAPER -> "gesture_paper_action"
            GestureLabel.ROCK -> "gesture_rock_action"
            GestureLabel.SCISSORS -> "gesture_scissors_action"
            GestureLabel.ONE -> "gesture_one_action"
            GestureLabel.NONE -> return GestureAction.NONE
        }

        // 저장된 displayName 불러오기
        val savedDisplayName = prefs.getString(key, null)

        // 디버깅용 로그 출력
        Log.d("GestureActionMapper", "🔍 gesture=$gestureLabel, key=$key, saved=$savedDisplayName")

        return if (savedDisplayName != null) {
            fromDisplayName(savedDisplayName)
        } else {
            GestureAction.NONE
        }
    }

    /**
     * 기본 매핑 (초기값 설정 시 사용 가능하나,
     * 앱 동작 시에는 SharedPreferences가 우선됨)
     */
    val defaultMapping = mapOf(
        GestureLabel.SCISSORS to GestureAction.SWIPE_RIGHT,
        GestureLabel.ROCK to GestureAction.TOGGLE_FLASH,
        GestureLabel.PAPER to GestureAction.VOLUME_UP,
        GestureLabel.ONE to GestureAction.SWIPE_DOWN
        // GestureLabel.NONE 은 NONE 처리됨
    )
}