package com.square.aircommand.gesture

import android.content.Context

/**
 * 제스처 ID와 동작을 매핑하거나, 사용자 설정된 displayName 기반 매핑을 지원하는 객체
 */
object GestureActionMapper {

    /**
     * displayName(String)을 GestureAction으로 변환
     */
    fun fromDisplayName(name: String): GestureAction {
        return GestureAction.entries.find { it.displayName == name } ?: GestureAction.NONE
    }

    /**
     * GestureAction을 displayName(String)으로 변환
     */
    fun toDisplayName(action: GestureAction): String {
        return action.displayName
    }

    /**
     * 제스처 레이블에 대한 기본 매핑
     */
    val defaultMapping = mapOf(
        GestureLabel.SCISSORS to GestureAction.SWIPE_RIGHT,
        GestureLabel.ROCK to GestureAction.TOGGLE_FLASH,
        GestureLabel.PAPER to GestureAction.VOLUME_UP,
        GestureLabel.ONE to GestureAction.SWIPE_DOWN,
        GestureLabel.NONE to GestureAction.NONE
    )

    /**
     * SharedPreferences에서 사용자가 설정한 액션을 가져옴
     */
    fun getSavedGestureAction(context: Context, gestureLabel: GestureLabel): GestureAction {
        val prefs = context.getSharedPreferences("gesture_prefs", Context.MODE_PRIVATE)
        val key = when (gestureLabel) {
            GestureLabel.PAPER -> "gesture_paper_action"
            GestureLabel.ROCK -> "gesture_rock_action"
            GestureLabel.SCISSORS -> "gesture_scissors_action"
            GestureLabel.ONE -> "gesture_one_action"
            GestureLabel.NONE -> return GestureAction.NONE
        }

        val savedDisplayName = prefs.getString(key, null)
        return if (savedDisplayName != null) {
            fromDisplayName(savedDisplayName)
        } else {
            defaultMapping[gestureLabel] ?: GestureAction.NONE
        }
    }
}