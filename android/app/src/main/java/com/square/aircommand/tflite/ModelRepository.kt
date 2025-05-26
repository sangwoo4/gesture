package com.square.aircommand.tflite

import android.content.Context
import android.util.Log
import com.square.aircommand.BuildConfig
import com.square.aircommand.classifier.GestureClassifier
import com.square.aircommand.handdetector.HandDetector
import com.square.aircommand.handlandmarkdetector.HandLandmarkDetector
import java.io.File
import java.io.FileNotFoundException

object ModelRepository {
    private var handDetector: HandDetector? = null
    private var landmarkDetector: HandLandmarkDetector? = null
    private var gestureClassifier: GestureClassifier? = null

    private var initialized = false

    /** ✅ 모델 초기화 (이미 되어있으면 스킵) */
    fun initModels(context: Context) {
        if (initialized) return

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

            // ✅ GestureClassifier만 내부 저장소에서 로드
            val internalModelName = "update_gesture_model_cnns.tflite"
            val internalModelFile = File(context.filesDir, internalModelName)
            if (!internalModelFile.exists()) {
                throw FileNotFoundException("내부 저장소에 $internalModelName 파일이 없습니다.")
            }
            gestureClassifier = GestureClassifier(
                context,
                internalModelName,
                loadDelegateOrder(internalModelName)
            )

            initialized = true
        } catch (e: Exception) {
            Log.e("ModelRepository", "❌ 모델 초기화 실패: ${e.message}", e)
            throw RuntimeException("모델 초기화 실패: ${e.message}")
        }
    }

    /** ✅ 모델을 강제로 재초기화 */
    fun resetModels(context: Context) {
        closeAll()
        initModels(context)
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