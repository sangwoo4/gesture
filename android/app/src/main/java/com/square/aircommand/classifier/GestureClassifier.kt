package com.square.aircommand.classifier

import android.content.Context
import com.square.aircommand.tflite.AIHubDefaults
import com.square.aircommand.tflite.TFLiteHelpers
import com.square.aircommand.utils.ThrottledLogger
import org.json.JSONObject
import org.tensorflow.lite.Delegate
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.sqrt

class GestureLabelMapper(context: Context, assetFileName: String = "gesture_labels.json") {
    private val labelMap: Map<Int, String>

    init {
        val json = context.assets.open(assetFileName).bufferedReader().use { it.readText() }
        val jsonObject = JSONObject(json)
        val map = mutableMapOf<Int, String>()
        for (key in jsonObject.keys()) {
            map[key.toInt()] = jsonObject.getString(key)
        }
        labelMap = map
    }

    fun getLabel(index: Int): String {
        return labelMap[index] ?: "Unknown"
    }
}

class GestureClassifier(
    context: Context,
    modelPath: String,
    delegatePriorityOrder: Array<Array<TFLiteHelpers.DelegateType>>
) : AutoCloseable {

    private val interpreter: Interpreter
    private val delegateStore: Map<TFLiteHelpers.DelegateType, Delegate>
    private val inputScale: Float
    private val inputZeroPoint: Int
    private val outputScale: Float
    private val outputZeroPoint: Int
    private val numClasses: Int

    init {
        val (modelBuffer, hash) = TFLiteHelpers.loadModelFile(context.assets, modelPath)
        val (i, delegates) = TFLiteHelpers.CreateInterpreterAndDelegatesFromOptions(
            modelBuffer,
            delegatePriorityOrder,
            AIHubDefaults.numCPUThreads,
            context.applicationInfo.nativeLibraryDir,
            context.cacheDir.absolutePath,
            hash
        )
        interpreter = i
        delegateStore = delegates

        val inputTensor = interpreter.getInputTensor(0)
        inputScale = inputTensor.quantizationParams().scale
        inputZeroPoint = inputTensor.quantizationParams().zeroPoint

        val outputTensor = interpreter.getOutputTensor(0)
        outputScale = outputTensor.quantizationParams().scale
        outputZeroPoint = outputTensor.quantizationParams().zeroPoint
        numClasses = outputTensor.shape()[1]
    }

    fun classify(landmarks: MutableList<Triple<Double, Double, Double>>, handedness: String): Pair<Int, Float> {
        if (landmarks.size != 21) throw IllegalArgumentException("❌ 랜드마크는 21개여야 합니다")

        val base = landmarks[0]
        val norm: List<FloatArray> = landmarks.map { (x, y, z) ->
            floatArrayOf((x - base.first).toFloat(), (y - base.second).toFloat(), (z - base.third).toFloat())
        }

        // 🔄 더 나은 정규화 기준: 평균 거리
        val scale = listOf(
            distance(norm[0], norm[9]),
            distance(norm[0], norm[5]),
            distance(norm[0], norm[17])
        ).average().toFloat()

        val normalized: List<FloatArray> =
            if (scale > 0f) norm.map { arr -> FloatArray(3) { i -> arr[i] / scale } }
            else norm

        val inputFloats = mutableListOf<Float>()
        for (arr in normalized) {
            inputFloats.addAll(arr.toList())
        }

        val quantizedInput = ByteArray(inputFloats.size) { idx ->
            val q = ((inputFloats[idx] / inputScale) + inputZeroPoint).toInt()
            q.coerceIn(0, 255).toByte()
        }

        val inputBuffer = ByteBuffer.allocateDirect(quantizedInput.size).order(ByteOrder.nativeOrder())
        inputBuffer.put(quantizedInput)
        inputBuffer.rewind()

        val outputBuffer = ByteBuffer.allocateDirect(numClasses).order(ByteOrder.nativeOrder())
        interpreter.run(inputBuffer, outputBuffer)
        outputBuffer.rewind()

        val output = ByteArray(numClasses)
        outputBuffer.get(output)

        val confidences = output.map { byte ->
            (byte.toInt() and 0xFF - outputZeroPoint) * outputScale
        }

        val maxIdx = confidences.indices.maxByOrNull { confidences[it] } ?: -1
        val confidence = if (maxIdx != -1) confidences[maxIdx] else -1f

        if (confidence < 0.4f) {
            ThrottledLogger.log("GestureClassifier", "❗ 신뢰도 낮음 → 분류 생략 (conf=$confidence)")
            return Pair(-1, confidence)
        }

        ThrottledLogger.log("GestureClassifier", "✅ 예측 결과: 클래스 $maxIdx, 신뢰도 ${"%.3f".format(confidence)}")
        return Pair(maxIdx, confidence)
    }

    private fun distance(a: FloatArray, b: FloatArray): Float {
        return sqrt(
            (a[0] - b[0]).toDouble().pow(2.0) +
                    (a[1] - b[1]).toDouble().pow(2.0) +
                    (a[2] - b[2]).toDouble().pow(2.0)
        ).toFloat()
    }

    override fun close() {
        interpreter.close()
        delegateStore.values.forEach { it.close() }
    }
}