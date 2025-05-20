package com.square.aircommand.utils

import android.util.Log

object PerformanceLogger {
    private var startTime = 0L
    private var memoryStart = 0L

    fun start(tag: String) {
        startTime = System.nanoTime()
        memoryStart = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        Log.i("Benchmark", "🟡 [$tag] 측정 시작")
    }

    fun end(tag: String) {
        val endTime = System.nanoTime()
        val memoryEnd = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

        val elapsedMs = (endTime - startTime) / 1_000_000.0
        val memoryUsedMb = (memoryEnd - memoryStart) / (1024.0 * 1024.0)

        Log.i("Benchmark", "✅ [$tag] 실행 시간 = %.2f ms, 메모리 사용 = %.1f MB".format(elapsedMs, memoryUsedMb))
    }

    fun logInferenceTime(tag: String, times: List<Long>) {
        val avgMs = times.average() / 1_000_000.0
        val fps = if (avgMs > 0) 1000.0 / avgMs else 0.0
        val runtime = Runtime.getRuntime()
        val memoryUsed = runtime.totalMemory() - runtime.freeMemory()
        val memoryUsedMb = memoryUsed / (1024.0 * 1024.0)

        Log.i("Benchmark", "📈 [$tag] 평균 추론 시간 = %.2f ms, FPS = %.2f, 메모리 사용 = %.1f MB".format(avgMs, fps, memoryUsedMb))
    }

}