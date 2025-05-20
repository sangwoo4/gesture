package com.square.aircommand

import android.content.Context
import android.graphics.Bitmap
import com.square.aircommand.classifier.GestureClassifier
import com.square.aircommand.handdetector.HandDetector
import com.square.aircommand.handlandmarkdetector.HandLandmarkDetector
import com.square.aircommand.tflite.TFLiteHelpers
import com.square.aircommand.utils.PerformanceLogger

object BenchmarkRunner {

    private val delegates = listOf(
        "CPU" to arrayOf<Array<TFLiteHelpers.DelegateType>>(arrayOf()),
        "GPU" to arrayOf(arrayOf(TFLiteHelpers.DelegateType.GPUv2)),
        "QNN_FP16" to arrayOf(arrayOf(TFLiteHelpers.DelegateType.QNN_NPU_FP16)),
        "QNN_QUANT" to arrayOf(arrayOf(TFLiteHelpers.DelegateType.QNN_NPU_QUANTIZED))
    )

    fun runAllBenchmarks(context: Context, dummyBitmap: Bitmap) {
        benchmarkGestureClassifier(context)
        benchmarkHandDetector(context, dummyBitmap)
        benchmarkHandLandmarkDetector(context, dummyBitmap)
    }

    private fun benchmarkGestureClassifier(context: Context) {
        val dummyLandmarks = MutableList(21) { Triple(0.5, 0.5, 0.0) }

        for ((label, delegateOrder) in delegates) {
            if (label == "QNN_FP16") continue // 이 모델은 UINT8이라 FP16 안 됨

            val tag = "GestureClassifier_$label"
            PerformanceLogger.start("${tag}_Load")
            val classifier = GestureClassifier(context, "update_gesture_model_cnn.tflite", delegateOrder)
            PerformanceLogger.end("${tag}_Load")

            val times = mutableListOf<Long>()
            repeat(50) {
                val start = System.nanoTime()
                classifier.classify(dummyLandmarks, "Right")
                times.add(System.nanoTime() - start)
            }
            PerformanceLogger.logInferenceTime(tag, times)
            classifier.close()
        }
    }

    private fun benchmarkHandDetector(context: Context, bitmap: Bitmap) {
        for ((label, delegateOrder) in delegates) {
            if (label == "QNN_QUANT") continue  // ⚠️ float32 모델은 quantized delegate 불가
            val tag = "HandDetector_$label"
            PerformanceLogger.start("${tag}_Load")
            val detector = HandDetector(context, "mediapipe_hand-handdetector.tflite", delegateOrder)
            PerformanceLogger.end("${tag}_Load")

            val times = mutableListOf<Long>()
            repeat(50) {
                val start = System.nanoTime()
                detector.detect(bitmap)
                times.add(System.nanoTime() - start)
            }
            PerformanceLogger.logInferenceTime(tag, times)
            detector.close()
        }
    }

    private fun benchmarkHandLandmarkDetector(context: Context, bitmap: Bitmap) {
        for ((label, delegateOrder) in delegates) {
            if (label == "QNN_QUANT") continue  // ⚠️ float32 모델은 quantized delegate 불가
            val tag = "HandLandmark_$label"
            PerformanceLogger.start("${tag}_Load")
            val landmark = HandLandmarkDetector(context, "mediapipe_hand-handlandmarkdetector.tflite", delegateOrder)
            PerformanceLogger.end("${tag}_Load")

            val times = mutableListOf<Long>()
            repeat(50) {
                val start = System.nanoTime()
                landmark.predict(bitmap, 0) // orientation은 0으로 가정
                times.add(System.nanoTime() - start)
            }
            PerformanceLogger.logInferenceTime(tag, times)
            landmark.close()
        }
    }
}
