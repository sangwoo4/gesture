package com.square.aircommand

import android.content.Context
import android.graphics.Bitmap
import com.square.aircommand.classifier.GestureClassifier
import com.square.aircommand.handdetector.HandDetector
import com.square.aircommand.handlandmarkdetector.HandLandmarkDetector
import com.square.aircommand.tflite.TFLiteHelpers
import com.square.aircommand.utils.PerformanceLogger

object BenchmarkRunner {

    fun runAllBenchmarks(context: Context, dummyBitmap: Bitmap) {
        benchmarkGestureClassifier(context)
        benchmarkHandDetector(context, dummyBitmap)
        benchmarkHandLandmarkDetector(context, dummyBitmap)
    }

    private fun benchmarkGestureClassifier(context: Context) {
        val dummyLandmarks = MutableList(21) { Triple(0.5, 0.5, 0.0) }

        for ((label, delegateOrder) in TFLiteHelpers.delegates) {
            // ✅ QNN_QUANT 항목만 성능 모드별로 반복 측정
            if (label == "QNN_QUANT") {
                for (mode in TFLiteHelpers.qnnPerformanceModes) {
                    val modeTag = "${label}_${mode.name}"
                    PerformanceLogger.startLoad(modeTag)

                    // ✅ delegatePriorityOrder를 "성능 모드"와 무관하게 유지함
                    val classifier = GestureClassifier(
                        context,
                        "update_gesture_model_cnn.tflite",
                        delegateOrder
                    )

                    PerformanceLogger.endLoad(modeTag)

                    val times = mutableListOf<Long>()
                    val memories = mutableListOf<Long>()
                    repeat(50) {
                        val start = System.nanoTime()
                        classifier.classify(dummyLandmarks, "Right")
                        val end = System.nanoTime()
                        times.add(end - start)
                        val mem =
                            Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
                        memories.add(mem)
                    }

                    PerformanceLogger.logInferenceMetrics(modeTag, times, memories)
                    classifier.close()
                }
            } else {
                val tag = "GestureClassifier_$label"
                PerformanceLogger.startLoad(tag)
                val classifier = GestureClassifier(
                    context,
                    "update_gesture_model_cnn.tflite",
                    delegateOrder
                )
                PerformanceLogger.endLoad(tag)

                val times = mutableListOf<Long>()
                val memories = mutableListOf<Long>()
                repeat(50) {
                    val start = System.nanoTime()
                    classifier.classify(dummyLandmarks, "Right")
                    val end = System.nanoTime()
                    times.add(end - start)
                    val mem = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
                    memories.add(mem)
                }

                PerformanceLogger.logInferenceMetrics(tag, times, memories)
                classifier.close()
            }
        }
    }


    private fun benchmarkHandDetector(context: Context, bitmap: Bitmap) {
        for ((label, delegateOrder) in TFLiteHelpers.delegates) {
            if (label == "QNN_QUANT") continue

            val tag = "HandDetector_$label"

            PerformanceLogger.startLoad(tag)
            val detector =
                HandDetector(context, "mediapipe_hand-handdetector.tflite", delegateOrder)
            PerformanceLogger.endLoad(tag)

            val times = mutableListOf<Long>()
            val memories = mutableListOf<Long>()
            repeat(50) {
                val start = System.nanoTime()
                detector.detect(bitmap)
                val end = System.nanoTime()
                times.add(end - start)

                val mem = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
                memories.add(mem)
            }
            PerformanceLogger.logInferenceMetrics(tag, times, memories)
            detector.close()
        }
    }

    private fun benchmarkHandLandmarkDetector(context: Context, bitmap: Bitmap) {
        for ((label, delegateOrder) in TFLiteHelpers.delegates) {
            if (label == "QNN_QUANT") continue

            val tag = "HandLandmark_$label"

            PerformanceLogger.startLoad(tag)
            val landmark = HandLandmarkDetector(
                context,
                "mediapipe_hand-handlandmarkdetector.tflite",
                delegateOrder
            )
            PerformanceLogger.endLoad(tag)

            val times = mutableListOf<Long>()
            val memories = mutableListOf<Long>()
            repeat(50) {
                val start = System.nanoTime()
                landmark.predict(bitmap, 0)
                val end = System.nanoTime()
                times.add(end - start)

                val mem = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
                memories.add(mem)
            }
            PerformanceLogger.logInferenceMetrics(tag, times, memories)
            landmark.close()
        }
    }
}