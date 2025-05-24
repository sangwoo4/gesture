package com.square.aircommand.gesture

/**
 * 제스처로 수행 가능한 기기 제어 동작 열거형
 * displayName은 UI에 표시되는 한글 이름으로 매핑됨
 */
// ⭐TODO: 스피너 순서 보기 좋게 수정해놓아야함
enum class GestureAction(val displayName: String) {
    NONE("동작 없음"),
    TOGGLE_FLASH("플래시 토글"),
    VOLUME_UP("볼륨 올리기"),
    VOLUME_DOWN("볼륨 내리기"),
    SWIPE_RIGHT("오른쪽으로 스와이프"),
    SWIPE_DOWN("아래쪽으로 스와이프"),

    // TODO: 왼쪽으로, 오른쪽으로 스와이프 기능 추가할 것
    SWIPE_LEFT("왼쪽으로 스와이프"),
    SWIPE_UP("위쪽으로 스와이프")
}