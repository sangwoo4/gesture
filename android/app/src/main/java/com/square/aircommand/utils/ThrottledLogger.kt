package com.square.aircommand.utils

import android.util.Log

object ThrottledLogger {
    // 태그별로 마지막 출력 시간을 저장함
    private val lastLogTimes = mutableMapOf<String, Long>()

    // 기본 출력 간격을 2초(2000ms)로 설정함
    private const val defaultIntervalMs = 4000L

    // 지정된 간격보다 늦은 경우에만 로그를 출력함
    fun log(tag: String, message: String, intervalMs: Long = defaultIntervalMs) {
        val currentTime = System.currentTimeMillis()
        val lastTime = lastLogTimes[tag] ?: 0L

        if (currentTime - lastTime > intervalMs) {
            Log.d(tag, message)
            lastLogTimes[tag] = currentTime
        }
    }
}