package com.square.aircommand.classifier

import android.content.Context
import com.square.aircommand.tflite.AIHubDefaults
import com.square.aircommand.tflite.TFLiteHelpers
import com.square.aircommand.utils.ThrottledLogger
import org.json.JSONObject
import org.tensorflow.lite.Delegate
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.sqrt
import android.util.Log
import org.tensorflow.lite.DataType

class GestureLabelMapper(context: Context, assetFileName: String = "gesture_labels.json") {
    private val labelMap: Map<Int, String>

    init {
        // 내부 저장소에 gesture_labels.json이 존재하면 그것을 먼저 사용
        val labelFile = File(context.filesDir, assetFileName)
        val json = if (labelFile.exists()) {
            labelFile.readText()
        } else {
            context.assets.open(assetFileName).bufferedReader().use { it.readText() }
        }

        // model_code 키는 제외하고 index → label만 맵으로 구성
        val jsonObject = JSONObject(json)
        val map = mutableMapOf<Int, String>()
        for (key in jsonObject.keys()) {
            if (key != "model_code") {
                map[key.toInt()] = jsonObject.getString(key)
            }
        }
        labelMap = map
    }

    fun getLabel(index: Int): String {
        return labelMap[index] ?: "Unknown"
    }

    fun getAllLabels(): Map<Int, String> {
        return labelMap
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
        // 1. SharedPreferences에서 저장된 model_code를 불러옴
        val modelCode = context.getSharedPreferences("gesture_prefs", Context.MODE_PRIVATE)
            .getString("model_code", "cnns") ?: "cnns"

        Log.d("GestureClassifier", "modelCode = $modelCode")
        // 2. model_code에 따라 모델 파일 경로 설정
        val resolvedModelPath = "update_gesture_model_${modelCode}.tflite"

        // 3. 지정된 모델 경로로 모델 로딩
        val (modelBuffer, hash) = TFLiteHelpers.loadModelFile(context.assets, resolvedModelPath)
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
        require(inputTensor.dataType() == DataType.UINT8) { "GestureClassifier는 UINT8 양자화 모델만 지원됩니다." }

        val inputQuant = inputTensor.quantizationParams()
        inputScale = inputQuant.scale
        inputZeroPoint = inputQuant.zeroPoint

        val outputTensor = interpreter.getOutputTensor(0)
        val outputQuant = outputTensor.quantizationParams()
        outputScale = outputQuant.scale
        outputZeroPoint = outputQuant.zeroPoint
        numClasses = outputTensor.shape()[1]
    }

    fun classify(landmarks: MutableList<Triple<Double, Double, Double>>, handedness: String): Pair<Int, Float> {
        if (landmarks.size != 21) throw IllegalArgumentException("❌ 랜드마크는 21개여야 합니다")

        // ✅ 기준점: wrist(0)
        val base = landmarks[0]
        val norm = landmarks.map { (x, y, z) ->
            floatArrayOf(
                (x - base.first).toFloat(),
                (y - base.second).toFloat(),
                (z - base.third).toFloat()
            )
        }

        // ✅ 정규화 기준: wrist ~ middle_finger_mcp(9)
        val scale = sqrt(
            (norm[0][0] - norm[9][0]).pow(2) +
                    (norm[0][1] - norm[9][1]).pow(2) +
                    (norm[0][2] - norm[9][2]).pow(2)
        )

        val normalized = if (scale > 0f) {
            norm.map { arr -> FloatArray(3) { i -> arr[i] / scale } }
        } else norm

        // ✅ (21, 3) → (1, 21, 3, 1) 형식으로 reshape
        val reshaped = Array(1) { Array(21) { Array(3) { ByteArray(1) } } }

        for (i in 0 until 21) {
            for (j in 0 until 3) {
                val floatVal = normalized[i][j]
                val quantized = ((floatVal / inputScale) + inputZeroPoint).toInt().coerceIn(0, 255)
                reshaped[0][i][j][0] = quantized.toByte()
            }
        }

        val inputBuffer = ByteBuffer.allocateDirect(21 * 3 * 1).order(ByteOrder.nativeOrder())
        for (i in 0 until 21) {
            for (j in 0 until 3) {
                inputBuffer.put(reshaped[0][i][j][0])
            }
        }
        inputBuffer.rewind()

        // ✅ 출력 준비 및 추론 실행
        val outputBuffer = ByteBuffer.allocateDirect(numClasses).order(ByteOrder.nativeOrder())
        interpreter.run(inputBuffer, outputBuffer)
        outputBuffer.rewind()

        // ✅ 후처리
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

    override fun close() {
        interpreter.close()
        delegateStore.values.forEach { it.close() }
    }
}