package com.square.aircommand.handlandmarkdetector

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.square.aircommand.tflite.AIHubDefaults
import com.square.aircommand.tflite.TFLiteHelpers
import com.square.aircommand.utils.ThrottledLogger
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
import java.nio.ByteBuffer
import java.nio.ByteOrder

class HandLandmarkDetector(
    context: Context,
    modelPath: String,
    delegatePriorityOrder: Array<Array<TFLiteHelpers.DelegateType>>
) : AutoCloseable {

    private val interpreter: Interpreter
    private val delegateStore: Map<TFLiteHelpers.DelegateType, Delegate>

    val inputWidth: Int
    val inputHeight: Int

    private val inputBuffer: ByteBuffer
    private val inputFloatArray: FloatArray
    private val inputMatAbgr: Mat
    private val inputMatRgb: Mat

    val lastLandmarks = mutableListOf<Triple<Double, Double, Double>>()
    var normalizedLandmarks = mutableListOf<Triple<Double, Double, Double>>()
    var lastHandedness: String = "Right"

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