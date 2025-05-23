package com.square.aircommand.handlandmarkdetector

import android.content.Context
import android.graphics.Bitmap
import com.square.aircommand.tflite.AIHubDefaults
import com.square.aircommand.tflite.TFLiteHelpers
import com.square.aircommand.utils.FileUtils
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvException
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.osgi.OpenCVNativeLoader
import org.tensorflow.lite.Delegate
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.collections.MutableList
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.component3
import kotlin.collections.forEach
import kotlin.collections.listOf
import kotlin.collections.mutableListOf
import kotlin.collections.mutableMapOf

class HandLandmarkDetector(
    private val context: Context,
    modelPath: String,
    delegatePriorityOrder: Array<Array<TFLiteHelpers.DelegateType>>
) : AutoCloseable {

    private val interpreter: Interpreter
    private val delegateStore: Map<TFLiteHelpers.DelegateType, Delegate>
    private var isClosed = false

    val inputWidth: Int
    val inputHeight: Int
    var isCollecting: Boolean = true
        private set

    private val inputBuffer: ByteBuffer
    private val inputFloatArray: FloatArray
    private val inputMatAbgr: Mat
    private val inputMatRgb: Mat
    private val landmarkSequence = mutableListOf<List<Triple<Double, Double, Double>>>()
    private var frameCounter = 0
    private val frameInterval = 3
    private val client = OkHttpClient()

    val lastLandmarks = mutableListOf<Triple<Double, Double, Double>>()
    var normalizedLandmarks = mutableListOf<Triple<Double, Double, Double>>()
    var lastHandedness: String = "Right"

    init {
        OpenCVNativeLoader().init()

        // 내부 저장소 모델 우선 사용
        val localModelFile = FileUtils.getModelFile(context)
        val (modelBuffer, hash) = if (localModelFile.exists()) {
            val buffer = FileInputStream(localModelFile).channel.map(
                FileChannel.MapMode.READ_ONLY,
                0,
                localModelFile.length()
            )
            Pair(buffer, localModelFile.name)
        } else {
            TFLiteHelpers.loadModelFile(context.assets, modelPath)
        }

        val (i, delegates) = TFLiteHelpers.CreateInterpreterAndDelegatesFromOptions(
            modelBuffer, delegatePriorityOrder,
            AIHubDefaults.numCPUThreads,
            context.applicationInfo.nativeLibraryDir,
            context.cacheDir.absolutePath, hash
        )
        interpreter = i
        delegateStore = delegates

        val inputShape = interpreter.getInputTensor(0).shape()
        inputHeight = inputShape[1]
        inputWidth = inputShape[2]

        inputFloatArray = FloatArray(inputHeight * inputWidth * 3)
        inputBuffer = ByteBuffer.allocateDirect(4 * inputFloatArray.size).order(ByteOrder.nativeOrder())
        inputMatAbgr = Mat(inputHeight, inputWidth, CvType.CV_8UC4)
        inputMatRgb = Mat(inputHeight, inputWidth, CvType.CV_8UC3)
    }

    override fun close() {
        if (!isClosed) {
            interpreter.close()
            delegateStore.values.forEach { it.close() }
            isClosed = true
        }
    }

    fun predict(image: Bitmap, sensorOrientation: Int): Bitmap {
        if (isClosed) return image
        preprocessImage(image, sensorOrientation)
        val (landmarks, score, handedness) = runInference() ?: return image

        lastLandmarks.clear()
        if (score < 0.005f) return image

        lastHandedness = if (handedness > 0.5f) "Right" else "Left"
        extractLandmarks(landmarks, image.width, image.height)
        drawLandmarks(image.width, image.height)
        return convertMatToBitmap(image)
    }

    fun transfer(image: Bitmap, sensorOrientation: Int, gestureName: String): Bitmap {
        if (isClosed) return image
        if (!isCollecting) return image

        preprocessImage(image, sensorOrientation)
        val (landmarks, score, handedness) = runInference() ?: return image
        lastLandmarks.clear()
        if (score < 0.005f) return image

        lastHandedness = if (handedness > 0.5f) "Right" else "Left"
        extractLandmarks(landmarks, image.width, image.height)

        if (landmarkSequence.size < 100) {
            if (frameCounter % frameInterval == 0) {
                extract63Landmarks(landmarks, image.width, image.height)
            }
            frameCounter++
        }

        if (landmarkSequence.size == 100) {
            val modelCode = getSavedModelCode(context)
            sendTrainData(modelCode, gestureName, landmarkSequence) { newModelCode, labelJson ->
                saveModelCode(context, newModelCode)
                FileUtils.saveJsonToFile(FileUtils.getLabelFile(context), labelJson)
            }
            stopCollecting()
        }

        drawLandmarks(image.width, image.height)
        return convertMatToBitmap(image)
    }

    private fun runInference(): Triple<Array<Array<FloatArray>>, Float, Float>? {
        if (isClosed) return null

        val outputLandmarks = Array(1) { Array(21) { FloatArray(3) } }
        val outputScores = FloatArray(1)
        val outputLR = FloatArray(1)

        val outputMap = mutableMapOf<Int, Any>().apply {
            for (i in 0 until interpreter.outputTensorCount) {
                when (interpreter.getOutputTensor(i).name()) {
                    "landmarks" -> put(i, outputLandmarks)
                    "scores" -> put(i, outputScores)
                    "lr" -> put(i, outputLR)
                }
            }
        }

        interpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputMap)
        return Triple(outputLandmarks, outputScores[0], outputLR[0])
    }

    fun startCollecting() {
        isCollecting = true
        landmarkSequence.clear()
    }

    fun stopCollecting() {
        isCollecting = false
    }

    private fun preprocessImage(image: Bitmap, sensorOrientation: Int) {
        Utils.bitmapToMat(image, inputMatAbgr)
        Imgproc.cvtColor(inputMatAbgr, inputMatRgb, Imgproc.COLOR_BGRA2RGB)

        when (sensorOrientation) {
            90 -> Core.rotate(inputMatRgb, inputMatRgb, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(inputMatRgb, inputMatRgb, Core.ROTATE_180)
            270 -> Core.rotate(inputMatRgb, inputMatRgb, Core.ROTATE_90_COUNTERCLOCKWISE)
        }

        val resizedInputMat = Mat()
        Imgproc.resize(inputMatRgb, resizedInputMat, Size(inputWidth.toDouble(), inputHeight.toDouble()))
        resizedInputMat.convertTo(resizedInputMat, CvType.CV_32FC3, 1.0 / 255.0)
        resizedInputMat.get(0, 0, inputFloatArray)

        inputBuffer.rewind()
        inputBuffer.asFloatBuffer().put(inputFloatArray)
    }

    private fun extractLandmarks(landmarks: Array<Array<FloatArray>>, imgW: Int, imgH: Int) {
        for (i in 0 until 21) {
            val (fx, fy, fz) = landmarks[0][i]
            lastLandmarks.add(Triple(fx.toDouble(), fy.toDouble(), fz.toDouble()))
        }
    }

    private fun extract63Landmarks(landmarks: Array<Array<FloatArray>>, imgW: Int, imgH: Int) {
        val frameLandmarks = mutableListOf<Triple<Double, Double, Double>>()
        for (i in 0 until 21) {
            val (fx, fy, fz) = landmarks[0][i]
            frameLandmarks.add(Triple(fx.toDouble(), fy.toDouble(), fz.toDouble()))
        }
        landmarkSequence.add(frameLandmarks)
    }

    private fun drawLandmarks(imgW: Int, imgH: Int) {
        for ((x, y, _) in lastLandmarks) {
            val px = (x * imgW).toInt().coerceIn(0, imgW - 1)
            val py = (y * imgH).toInt().coerceIn(0, imgH - 1)
            Imgproc.circle(inputMatRgb, Point(px.toDouble(), py.toDouble()), 5, Scalar(0.0, 255.0, 0.0), -1)
        }
    }

    private fun convertMatToBitmap(original: Bitmap): Bitmap {
        val matW = inputMatRgb.cols()
        val matH = inputMatRgb.rows()
        if (matW <= 0 || matH <= 0) return original

        val outputBitmap = Bitmap.createBitmap(matW, matH, Bitmap.Config.ARGB_8888)
        Imgproc.cvtColor(inputMatRgb, inputMatAbgr, Imgproc.COLOR_RGB2BGRA)
        return try {
            Utils.matToBitmap(inputMatAbgr, outputBitmap)
            outputBitmap
        } catch (e: CvException) {
            original
        }
    }

    private fun sendTrainData(
        modelCode: String,
        gesture: String,
        landmarkSequence: MutableList<List<Triple<Double, Double, Double>>>,
        onSuccess: (modelCode: String, labelJson: String) -> Unit
    ) {
        val landmarksJsonArray = JSONArray()
        for (frame in landmarkSequence) {
            val frameArray = JSONArray()
            frame.forEach { (x, y, z) -> frameArray.put(JSONArray(listOf(x, y, z))) }
            landmarksJsonArray.put(frameArray)
        }

        val json = JSONObject().apply {
            put("model_code", modelCode)
            put("gesture", gesture)
            put("landmarks", landmarksJsonArray)
        }

        val body = RequestBody.create("application/json; charset=utf-8".toMediaTypeOrNull(), json.toString())
        val request = Request.Builder()
            .url("http://192.168.0.5:8000/train_model/")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                println("서버 전송 실패: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: "{}"
                    val resultJson = JSONObject(bodyStr)

                    val modelCode = resultJson.optString("model_code", "cnns")
                    val modelUrl = resultJson.optString("model_url", null)

                    if (modelUrl != null) {
                        FileUtils.downloadModel(context, modelUrl,
                            onSuccess = {
                                saveModelCode(context, modelCode)
                                FileUtils.saveJsonToFile(FileUtils.getLabelFile(context), bodyStr)
                                stopCollecting()
                                println("✅ 모델 다운로드 및 저장 완료")
                            },
                            onFailure = { error ->
                                println("❌ 모델 다운로드 실패: $error")
                            }
                        )
                    } else {
                        println("⚠️ 서버 응답에 model_url 없음")
                    }
                } else {
                    println("응답 오류 코드: ${response.code}")
                }
            }
        })
    }

    private fun saveModelCode(context: Context, modelCode: String) {
        context.getSharedPreferences("gesture_prefs", Context.MODE_PRIVATE)
            .edit().putString("model_code", modelCode).apply()
    }

    private fun getSavedModelCode(context: Context): String {
        return context.getSharedPreferences("gesture_prefs", Context.MODE_PRIVATE)
            .getString("model_code", "basic") ?: "basic"
    }
}
