package com.square.aircommand.utils

import android.util.Log

object PerformanceLogger {
    private var startTime = 0L
    private var memoryStart = 0L

    // ✅ 최초 모델 로드 시간 측정
    fun startLoad(tag: String) {
        startTime = System.nanoTime()
        memoryStart = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        Log.i("Benchmark", "🟡 [$tag] 모델 로드 시작")
    }

    fun endLoad(tag: String) {
        val endTime = System.nanoTime()
        val memoryEnd = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

        val elapsedMs = (endTime - startTime) / 1_000_000.0
        val memoryUsedMb = (memoryEnd - memoryStart) / (1024.0 * 1024.0)

        Log.i("Benchmark", "✅ [$tag] 모델 로드 시간 = %.2f ms, 메모리 사용 = %.1f MB"
            .format(elapsedMs, memoryUsedMb))
    }

    // ✅ 추론 속도, FPS, 평균 메모리 사용량 (50회 평균 기준)
    fun logInferenceMetrics(tag: String, inferenceTimesNs: List<Long>, memoryRecords: List<Long>) {
        val avgMs = inferenceTimesNs.average() / 1_000_000.0
        val fps = if (avgMs > 0) 1000.0 / avgMs else 0.0
        val avgMemMb = memoryRecords.average() / (1024.0 * 1024.0)

        val estimatedEnergy = avgMs * 0.15 // ✅ 간단한 에너지 추정 공식

        Log.i("Benchmark", "📈 [$tag] 평균 추론 시간 = %.2f ms, FPS = %.2f, 평균 메모리 사용 = %.1f MB"
            .format(avgMs, fps, avgMemMb))
        Log.i("Benchmark", "🔋 [$tag] 예상 에너지 소비 ≈ %.4f mJ".format(estimatedEnergy)) // ✅ 추가 로그
    }


}

