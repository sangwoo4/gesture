package com.square.aircommand.tflite

import android.content.Context
import android.util.Log
import com.square.aircommand.BuildConfig
import com.square.aircommand.classifier.GestureClassifier
import com.square.aircommand.handdetector.HandDetector
import com.square.aircommand.handlandmarkdetector.HandLandmarkDetector
import java.io.File
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

object ModelRepository {
    private var handDetector: HandDetector? = null
    private var landmarkDetector: HandLandmarkDetector? = null
    private var gestureClassifier: GestureClassifier? = null

    private var initialized = false

    fun initModels(context: Context) {
        if (initialized) return

        fun loadMappedBuffer(modelFile: File): MappedByteBuffer {
            val inputStream = FileInputStream(modelFile)
            val fileChannel = inputStream.channel
            val startOffset = 0L
            val declaredLength = modelFile.length()
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        }

        fun loadDelegateOrderFromModelFile(modelFile: File): Array<Array<TFLiteHelpers.DelegateType>> {
            val mappedBuffer = loadMappedBuffer(modelFile)
            val inputType = TFLiteHelpers.getModelInputType(mappedBuffer)
            return TFLiteHelpers.getDelegatePriorityOrderFromInputType(inputType)
        }

        fun loadDelegateOrder(modelName: String): Array<Array<TFLiteHelpers.DelegateType>> {
            val modelBuffer = TFLiteHelpers.loadModelFile(context.assets, modelName).first
            val inputType = TFLiteHelpers.getModelInputType(modelBuffer)
            return TFLiteHelpers.getDelegatePriorityOrderFromInputType(inputType)
        }

        try {
            handDetector = HandDetector(
                context,
                BuildConfig.HAND_DETECTOR_MODEL,
                loadDelegateOrder(BuildConfig.HAND_DETECTOR_MODEL)
            )

            landmarkDetector = HandLandmarkDetector(
                context,
                BuildConfig.HAND_LANDMARK_MODEL,
                loadDelegateOrder(BuildConfig.HAND_LANDMARK_MODEL)
            )

            // ✅ filesDir 모델이 있으면 우선 사용
            val gestureModelFile = File(context.filesDir, BuildConfig.GESTURE_CLASSIFIER_MODEL)
            val gestureModelNameOrPath: String
            val delegateOrder: Array<Array<TFLiteHelpers.DelegateType>>

            if (gestureModelFile.exists()) {
                Log.i("ModelRepository", "📂 사용자 모델 사용: ${gestureModelFile.absolutePath}")
                gestureModelNameOrPath = gestureModelFile.absolutePath
                delegateOrder = loadDelegateOrderFromModelFile(gestureModelFile)
            } else {
                Log.i("ModelRepository", "📦 기본 모델(assets) 사용: ${BuildConfig.GESTURE_CLASSIFIER_MODEL}")
                gestureModelNameOrPath = BuildConfig.GESTURE_CLASSIFIER_MODEL
                delegateOrder = loadDelegateOrder(BuildConfig.GESTURE_CLASSIFIER_MODEL)
            }

            gestureClassifier = GestureClassifier(
                context,
                gestureModelNameOrPath,
                delegateOrder
            )

            initialized = true
        } catch (e: Exception) {
            Log.e("ModelRepository", "❌ 모델 초기화 실패: ${e.message}", e)
            throw RuntimeException("모델 초기화 실패: ${e.message}")
        }
    }

    fun getHandDetector(): HandDetector {
        return handDetector ?: throw IllegalStateException("Model not initialized")
    }

    fun getLandmarkDetector(): HandLandmarkDetector {
        return landmarkDetector ?: throw IllegalStateException("Model not initialized")
    }

    fun getGestureClassifier(): GestureClassifier {
        return gestureClassifier ?: throw IllegalStateException("Model not initialized")
    }

    fun closeAll() {
        handDetector?.close()
        landmarkDetector?.close()
        gestureClassifier?.close()
        initialized = false
    }
}
