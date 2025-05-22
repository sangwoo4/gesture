package com.square.aircommand.tflite

import android.content.Context
import android.util.Log
import com.square.aircommand.BuildConfig
import com.square.aircommand.classifier.GestureClassifier
import com.square.aircommand.handdetector.HandDetector
import com.square.aircommand.handlandmarkdetector.HandLandmarkDetector

object ModelRepository {
    private var handDetector: HandDetector? = null
    private var landmarkDetector: HandLandmarkDetector? = null
    private var gestureClassifier: GestureClassifier? = null

    private var initialized = false

    fun initModels(context: Context) {
        if (initialized) return

        fun loadDelegateOrder(modelName: String): Array<Array<TFLiteHelpers.DelegateType>> {
            val modelBuffer = TFLiteHelpers.loadModelFile(context.assets, modelName).first
            val inputType = TFLiteHelpers.getModelInputType(modelBuffer)

            // 실제 존재하는 값으로만 구성
            val delegateOrder: Array<Array<TFLiteHelpers.DelegateType>> = arrayOf(
                arrayOf(TFLiteHelpers.DelegateType.QNN_NPU_QUANTIZED),
                arrayOf(TFLiteHelpers.DelegateType.QNN_NPU_FP16),
                arrayOf(TFLiteHelpers.DelegateType.GPUv2),
                arrayOf() // CPU fallback (빈 배열로 처리)
            )

            return delegateOrder
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

            gestureClassifier = GestureClassifier(
                context,
                BuildConfig.GESTURE_CLASSIFIER_MODEL,
                loadDelegateOrder(BuildConfig.GESTURE_CLASSIFIER_MODEL)
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
