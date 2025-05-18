package com.square.aircommand.gesture

// com.square.aircommand.gesture.GestureAction.kt
/**
 * 제스처로 수행 가능한 기기 제어 동작 열거형
 * displayName은 UI에 표시되는 한글 이름으로 매핑됨
 */
enum class GestureAction(val displayName: String) {
    NONE("동작 없음"),
    TOGGLE_FLASH("플래시 토글"),
//    TAKE_PHOTO("사진 촬영"),
    VOLUME_UP("볼륨 올리기"),
    VOLUME_DOWN("볼륨 내리기"),
    SWIPE_RIGHT("오른쪽으로 스와이프"),
    SWIPE_DOWN("아래로 스와이프")
}