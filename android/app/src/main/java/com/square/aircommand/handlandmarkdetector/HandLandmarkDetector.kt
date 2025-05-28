package com.square.aircommand.handlandmarkdetector

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import com.square.aircommand.tflite.AIHubDefaults
import com.square.aircommand.tflite.TFLiteHelpers
import com.square.aircommand.ui.theme.listener.TrainingProgressListener
import com.square.aircommand.utils.ModelStorageManager
import com.square.aircommand.utils.ModelStorageManager.getSavedModelCode
import com.square.aircommand.utils.ModelStorageManager.saveModelCode
import com.square.aircommand.utils.ModelStorageManager.updateLabelMap
import com.square.aircommand.utils.ThrottledLogger
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.osgi.OpenCVNativeLoader
import org.tensorflow.lite.Delegate
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

class HandLandmarkDetector(
    private val context: Context,
    modelPath: String,
    delegatePriorityOrder: Array<Array<TFLiteHelpers.DelegateType>>,
    private var progressListener: TrainingProgressListener? = null
) : AutoCloseable {

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
    private val _landmarkSequence = mutableStateListOf<List<Triple<Double, Double, Double>>>()
    private var frameCounter = 0
    private val frameInterval = 3
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS) // 서버 연결 타임아웃
        .readTimeout(30, TimeUnit.SECONDS)    // 응답 대기 타임아웃
        .writeTimeout(30, TimeUnit.SECONDS)   // 요청 전송 타임아웃
        .build()

    val lastLandmarks = mutableListOf<Triple<Double, Double, Double>>()
    var lastHandedness: String = "Left" // 원래 Right임
    private var isReadyToSend = false
    private var savedGestureName: String? = null
    var percent = 0

    init {
        OpenCVNativeLoader().init()

        val (modelBuffer, hash) = TFLiteHelpers.loadModelFile(context.assets, modelPath)
        val (i, delegates) = TFLiteHelpers.createInterpreterAndDelegatesFromOptions(
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
        if (score < 0.005f) {
            return image
        }

        lastHandedness = if (handedness > 0.5f) "Right" else "Left"
        extractLandmarks(landmarks)
        drawLandmarks(image.width, image.height)

        return convertMatToBitmap(image)
    }

    fun sendToServerIfReady(context: Context, onFinished: () -> Unit) {
        if (!isReadyToSend || savedGestureName == null || _landmarkSequence.size != 100) {
            Log.w("HandLandmarkDetector", "❗ 저장 조건 불충분 - 서버에 전송하지 않음")
            return
        }

        val modelCode = getSavedModelCode(context)
        progressListener?.onTrainingStarted()

        sendTrainData(
            modelCode = modelCode,
            gesture = savedGestureName!!,
            landmarkSequence = _landmarkSequence
        ) { newModelCode, modelUrl ->
            // 1) 모델 코드 저장
            saveModelCode(context, newModelCode)

            // 2) 라벨 저장
            updateLabelMap(context, savedGestureName!!)

            // 3) model_url.json 생성
            val modelUrlFile = File(context.filesDir, "model_url.json")
            modelUrlFile.writeText(JSONObject().put("model_url", modelUrl).toString())

            // 4) 모델 다운로드 및 교체
            progressListener?.onModelDownloadStarted()
            ModelStorageManager.downloadAndReplaceModel(context)
            progressListener?.onModelDownloadComplete()

            Log.d("HandLandmarkDetector", "✅ 서버 전송 및 모델 업데이트 완료")

            // 상태 초기화
            isReadyToSend = false
            savedGestureName = null
            _landmarkSequence.clear()
        }
    }

//    fun retryLastSend(name: String) {
//        if (_landmarkSequence.size == 100) {
//            savedGestureName = name
//            isReadyToSend = true
//            Log.d("HandLandmarkDetector", "✅ 재시도 준비 완료")
//        } else {
//            Log.w("HandLandmarkDetector", "⚠️ 재시도 불가: landmarkSequence 부족")
//        }
//    }




    fun resetCollection() {
        _landmarkSequence.clear()
        frameCounter = 0
        isReadyToSend = false
        savedGestureName = ""
        isCollecting = true
        progressListener?.onCollectionProgress(0)
        Log.d("HandLandmarkDetector", "🔄 랜드마크 수집 상태 초기화됨")
    }

    fun transfer(
        image: Bitmap,
        sensorOrientation: Int,
        gestureName: String,
        trainingProgressListener: TrainingProgressListener?
    ): Bitmap {

        progressListener = trainingProgressListener

        if (!isCollecting) {
            ThrottledLogger.log("HandLandmarkDetector", "⏸️ 현재 수집 중이 아님 (isCollecting = false)")
            return image
        }

        preprocessImage(image, sensorOrientation)
        val (landmarks, score, handedness) = runInference()

        lastLandmarks.clear()
        if (score < 0.005f) {
            ThrottledLogger.log("HandLandmarkDetector", "❌ 유효하지 않은 점수 ($score)")
            return image
        }

        lastHandedness = if (handedness > 0.5f) "Right" else "Left"
        extractLandmarks(landmarks)

        if (_landmarkSequence.size < 100) {
            if (frameCounter % frameInterval == 0) {
                extract63Landmarks(landmarks, image.width, image.height)
                percent = (_landmarkSequence.size * 100) / 100
                Log.d("HandLandmarkDetector", "랜드마크 수집 중 (총 ${_landmarkSequence.size})")
                progressListener?.onCollectionProgress(percent) // 👈 콜백 호출
            }
            frameCounter++
        }

        if (_landmarkSequence.size == 100) {
            isReadyToSend = true
            savedGestureName = gestureName
            stopCollecting()
            Log.d("HandLandmarkDetector", "🛑 랜드마크 수집 완료 - 저장 버튼 대기 상태")
        }
        drawLandmarks(image.width, image.height)
        return convertMatToBitmap(image)
    }

    fun startCollecting() {
        isCollecting = true
        _landmarkSequence.clear()
    }

    fun stopCollecting() {
        isCollecting = false
    }

    private fun extract63Landmarks(landmarks: Array<Array<FloatArray>>, imgW: Int, imgH: Int) {
        val frameLandmarks = mutableListOf<Triple<Double, Double, Double>>()
        for (i in 0 until 21) {
            val (fx, fy, fz) = landmarks[0][i]
            frameLandmarks.add(Triple(fx.toDouble(), fy.toDouble(), fz.toDouble()))
        }
        _landmarkSequence.add(frameLandmarks)
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

        return Triple(outputLandmarks, outputScores[0], outputLR[0])
    }

    private fun extractLandmarks(landmarks: Array<Array<FloatArray>>) {
        for (i in 0 until 21) {
            val (fx, fy, fz) = landmarks[0][i]
            lastLandmarks.add(Triple(fx.toDouble(), fy.toDouble(), fz.toDouble()))
        }
    }

    private fun drawLandmarks(imgW: Int, imgH: Int) {
        lastLandmarks.forEachIndexed { idx, (x, y, _) ->
            val px = (x * imgW).toInt().coerceIn(0, imgW - 1)
            val py = (y * imgH).toInt().coerceIn(0, imgH - 1)
            Imgproc.circle(inputMatRgb, Point(px.toDouble(), py.toDouble()), 5, Scalar(0.0, 255.0, 0.0), -1)
        }
    }

    private fun convertMatToBitmap(original: Bitmap): Bitmap {
        val matW = inputMatRgb.cols()
        val matH = inputMatRgb.rows()

        if (matW <= 0 || matH <= 0) {
            Log.e("LandmarkDetector", "잘못된 이미지 크기")
            return original
        }

        val outputBitmap = Bitmap.createBitmap(matW, matH, Bitmap.Config.ARGB_8888)
        Imgproc.cvtColor(inputMatRgb, inputMatAbgr, Imgproc.COLOR_RGB2BGRA)

        return try {
            Utils.matToBitmap(inputMatAbgr, outputBitmap)
            outputBitmap
        } catch (e: CvException) {

            Log.e("LandmarkDetector", "matToBitmap 실패: ${e.message}", e)
            original
        }
    }

    fun sendTrainData(
        modelCode: String,
        gesture: String,
        landmarkSequence: MutableList<List<Triple<Double, Double, Double>>>,
        onSuccess: (newModelCode: String, modelUrl: String) -> Unit,
    ) {
        val landmarksJsonArray = JSONArray()
        for (frame in landmarkSequence) {
            val frameArray = JSONArray()
            frame.forEach { (x, y, z) ->
                frameArray.put(JSONArray(listOf(x, y, z)))
            }
            landmarksJsonArray.put(frameArray)
        }

        val json = JSONObject().apply {
            put("model_code", modelCode)
            put("gesture", gesture)
            put("landmarks", landmarksJsonArray)
        }

        val body = RequestBody.create(
            "application/json; charset=utf-8".toMediaTypeOrNull(),
            json.toString()
        )

        val request = Request.Builder()
            .url("http://13.125.161.99:8000/train_model/")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                progressListener?.fail()
                println("서버 전송 실패: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: "{}"
                    val resultJson = JSONObject(bodyStr)

                    val modelCode = resultJson.optString("new_model_code", "basic")
                    val modelUrl = resultJson.optString("new_tflite_model_url", "")

                    // 콜백에 전달
                    onSuccess(modelCode, modelUrl)
                } else {
                    println("응답 오류 코드: ${response.code}")
                }
            }
        })
    }
}

