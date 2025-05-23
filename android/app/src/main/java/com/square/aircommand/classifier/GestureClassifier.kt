package com.square.aircommand.classifier

import android.content.Context
import com.square.aircommand.tflite.AIHubDefaults
import com.square.aircommand.tflite.TFLiteHelpers
import com.square.aircommand.utils.FileUtils
import com.square.aircommand.utils.ThrottledLogger
import org.json.JSONObject
import org.tensorflow.lite.Delegate
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.sqrt

class GestureLabelMapper(context: Context, fileName: String = "gesture_labels.json") {
    private val labelMap: Map<Int, String>

    init {
        // ✅ filesDir에 있는 gesture_labels.json이 우선적으로 사용됨
        val labelFile = FileUtils.getLabelFile(context)
        val json = if (labelFile.exists()) {
            labelFile.readText()
        } else {
            context.assets.open(fileName).bufferedReader().use { it.readText() }
        }

        // ✅ model_code는 무시하고 숫자 키만 파싱
        val jsonObject = JSONObject(json)
        val map = mutableMapOf<Int, String>()
        for (key in jsonObject.keys()) {
            if (key != "model_code") {
                map[key.toInt()] = jsonObject.getString(key)
            }
        }
        labelMap = map
    }

    fun getLabel(index: Int): String = labelMap[index] ?: "Unknown"

    fun getAllLabels(): Map<Int, String> = labelMap
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
        // ✅ model_code를 SharedPreferences에서 불러옴
        val modelCode = context.getSharedPreferences("gesture_prefs", Context.MODE_PRIVATE)
            .getString("model_code", "cnns") ?: "cnns"

        // ✅ 우선 filesDir의 모델을 시도하고, 없으면 assets에서 불러옴
        val localModelFile = FileUtils.getModelFile(context)
        val (modelBuffer, hash) = if (localModelFile.exists()) {
            // 파일을 직접 메모리 매핑 방식으로 로드
            val fileInputStream = localModelFile.inputStream()
            val mappedBuffer = fileInputStream.channel.map(
                java.nio.channels.FileChannel.MapMode.READ_ONLY,
                0,
                localModelFile.length()
            )
            fileInputStream.close()
            Pair(mappedBuffer, localModelFile.name)
        } else {
            val modelName = "update_gesture_model_${modelCode}.tflite"
            TFLiteHelpers.loadModelFile(context.assets, modelName)
        }

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

        // ✅ 양자화 파라미터 추출
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
        val norm = landmarks.map { (x, y, z) ->
            floatArrayOf(
                (x - base.first).toFloat(),
                (y - base.second).toFloat(),
                (z - base.third).toFloat()
            )
        }

        val scale = listOf(
            distance(norm[0], norm[9]),
            distance(norm[0], norm[5]),
            distance(norm[0], norm[17])
        ).average().toFloat()

        val normalized = if (scale > 0f) norm.map { arr -> FloatArray(3) { i -> arr[i] / scale } } else norm

        val inputFloats = normalized.flatMap { it.toList() }
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

        val confidences = output.map {
            ((it.toInt() and 0xFF) - outputZeroPoint) * outputScale
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
        return sqrt((a[0] - b[0]).toDouble().pow(2.0) +
                (a[1] - b[1]).toDouble().pow(2.0) +
                (a[2] - b[2]).toDouble().pow(2.0)).toFloat()
    }

    override fun close() {
        interpreter.close()
        delegateStore.values.forEach { it.close() }
    }
}
