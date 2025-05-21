package com.square.aircommand.handlandmarkdetector

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.square.aircommand.tflite.AIHubDefaults
import com.square.aircommand.tflite.TFLiteHelpers
import com.square.aircommand.utils.ThrottledLogger
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
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class HandLandmarkDetector(
    context: Context,
    modelPath: String,
    delegatePriorityOrder: Array<Array<TFLiteHelpers.DelegateType>>
) : AutoCloseable {



    fun sendTrainData(modelCode: String, gesture: String, landmarkSequence: MutableList<List<Triple<Double, Double, Double>>>) {
        // 1. landmarks를 이중배열로 변환
        val landmarksJsonArray = JSONArray()
        for (frame in landmarkSequence) {
            val frameArray = JSONArray()
            frame.forEach { frameArray.put(it) }
            landmarksJsonArray.put(frameArray)
        }

        // 2. 최종 JSON 구성
        val json = JSONObject().apply {
            put("model_code", modelCode)
            put("gesture", gesture)
            put("landmarks", landmarksJsonArray)
        }

        // 3. Request 생성
        val body = RequestBody.create(
            "application/json; charset=utf-8".toMediaTypeOrNull(),
            json.toString()
        )

        val request = Request.Builder()
            .url("http://192.168.0.5:8000/train_model/") // 에뮬레이터라면 10.0.2.2, 실기기라면 PC IP
            .post(body)
            .build()


        // 4. 비동기 전송
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                println("❌ 서버 전송 실패: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    println("✅ 응답 성공: ${response.body?.string()}")
                } else {
                    println("⚠️ 응답 오류 코드: ${response.code}")
                }
            }
        })
    }


    private val interpreter: Interpreter
    private val delegateStore: Map<TFLiteHelpers.DelegateType, Delegate>

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
    var lastHandedness: String = "Right" // TODO: 전면은 거울모드라서 이 변수를 왼쪽으로 설정을 해야 오른손에 맞게 랜드마크가 뽑히나?

    init {
        OpenCVNativeLoader().init()
        val (modelBuffer, hash) = TFLiteHelpers.loadModelFile(context.assets, modelPath)
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
        interpreter.close()
        delegateStore.values.forEach { it.close() }
    }

    fun predict(image: Bitmap, sensorOrientation: Int): Bitmap {
        preprocessImage(image, sensorOrientation)
        val (landmarks, score, handedness) = runInference()

        lastLandmarks.clear()
        if (score < 0.005f) return image

        lastHandedness = if (handedness > 0.5f) "Right" else "Left"
        extractLandmarks(landmarks, image.width, image.height)
        drawLandmarks(image.width, image.height)

        return convertMatToBitmap(image)
    }

    private fun extract63Landmarks(
        landmarks: Array<Array<FloatArray>>,
        imgW: Int,
        imgH: Int
    ) {
        // landmarks[0]의 크기가 63이어야 함
        val frameLandmarks = mutableListOf<Triple<Double, Double, Double>>()

        for (i in 0 until 21) {
            val (fx, fy, fz) = landmarks[0][i]
            frameLandmarks.add(Triple(fx.toDouble(), fy.toDouble(), fz.toDouble()))
        }

        landmarkSequence.add(frameLandmarks)
    }

    fun startCollecting() {
        isCollecting = true
        landmarkSequence.clear()
    }

    fun stopCollecting() {
        isCollecting = false
    }


    fun transfer(image: Bitmap, sensorOrientation: Int): Bitmap {
        if (!isCollecting) return image  // 🔒 추론 중단 상태면 바로 반환

        preprocessImage(image, sensorOrientation)
        val (landmarks, score, handedness) = runInference()

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

        println("전체 프레임 수: ${landmarkSequence.size}")
        landmarkSequence.forEachIndexed { index, frame ->
            if (frame.size != 21) {
                println("❌ 프레임 $index: 랜드마크 개수 = ${frame.size} (오류!)")
            }
        }

        if (landmarkSequence.size == 100) {
            sendTrainData(
                modelCode = "basic",
                gesture = "kk",
                landmarkSequence = landmarkSequence
            )
            stopCollecting()  // ✅ 수집 완료 후 추론 정지
        }

        drawLandmarks(image.width, image.height)
        return convertMatToBitmap(image)
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

    private fun runInference(): Triple<Array<Array<FloatArray>>, Float, Float> {
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

        ThrottledLogger.log("LandmarkDetector", "🎯 outputScore = ${outputScores[0]}")
        ThrottledLogger.log("LandmarkDetector", "✋ handedness = ${if (outputLR[0] > 0.5f) "Right" else "Left"}")

        return Triple(outputLandmarks, outputScores[0], outputLR[0])
    }

    // TODO:
    private fun extractLandmarks(landmarks: Array<Array<FloatArray>>, imgW: Int, imgH: Int) {
        for (i in 0 until 21) {
            val (fx, fy, fz) = landmarks[0][i]
            lastLandmarks.add(Triple(fx.toDouble(), fy.toDouble(), fz.toDouble()))
        }
        ThrottledLogger.log("LandmarkDetector", "🖐️ 랜드마크: $lastLandmarks")
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

        if (matW <= 0 || matH <= 0) {
            Log.e("LandmarkDetector", "❌ 잘못된 이미지 크기: ${matW}x${matH} → Bitmap 생성 생략")
            return original
        }

        val outputBitmap = Bitmap.createBitmap(matW, matH, Bitmap.Config.ARGB_8888)
        Imgproc.cvtColor(inputMatRgb, inputMatAbgr, Imgproc.COLOR_RGB2BGRA)

        return try {
            Utils.matToBitmap(inputMatAbgr, outputBitmap)
            outputBitmap
        } catch (e: CvException) {
            Log.e("LandmarkDetector", "❌ matToBitmap 실패: ${e.message}", e)
            original
        }
    }
}