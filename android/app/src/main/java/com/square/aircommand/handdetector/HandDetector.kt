package com.square.aircommand.handdetector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import com.square.aircommand.tflite.AIHubDefaults
import com.square.aircommand.tflite.TFLiteHelpers
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.osgi.OpenCVNativeLoader
import org.tensorflow.lite.Delegate
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.sqrt

class HandDetector(
    context: Context,
    modelPath: String,
    delegatePriorityOrder: Array<Array<TFLiteHelpers.DelegateType>>
) : AutoCloseable {

    companion object {
        private const val NUM_ANCHORS = 2944
        private const val NUM_COORDS = 18
        private const val DETECTION_THRESHOLD = 0.3f
        private const val IOU_THRESHOLD = 0.6f

        private fun sigmoid(x: Float): Float = 1.0f / (1.0f + exp(-x))

        private fun arePointsClose(a: PointF, b: PointF, threshold: Float, scale: Float): Boolean {
            val dx = a.x - b.x
            val dy = a.y - b.y
            val distance = sqrt(dx * dx + dy * dy)
            return distance < threshold * scale
        }
    }

    private val interpreter: Interpreter
    private val delegateStore: Map<TFLiteHelpers.DelegateType, Delegate>
    private val inputWidth: Int
    private val inputHeight: Int
    private val inputArray: FloatArray
    private val inputBuffer: ByteBuffer

    init {
        OpenCVNativeLoader().init()
        val (i, d, w, h, inputArr, inputBuf) = initInterpreter(context, modelPath, delegatePriorityOrder)
        interpreter = i
        delegateStore = d
        inputWidth = w
        inputHeight = h
        inputArray = inputArr
        inputBuffer = inputBuf
    }

    override fun close() {
        interpreter.close()
        delegateStore.values.forEach { it.close() }
    }

    fun detect(bitmap: Bitmap): List<PointF> {
        preprocessImage(bitmap)

        val boxCoords = Array(1) { Array(NUM_ANCHORS) { FloatArray(NUM_COORDS) } }
        val boxScores = Array(1) { Array(NUM_ANCHORS) { FloatArray(1) } }

        val outputMap = mapOf(
            interpreter.getOutputIndex("box_coords") to boxCoords,
            interpreter.getOutputIndex("box_scores") to boxScores
        )

        interpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputMap)

        return postProcessDetections(bitmap, boxCoords, boxScores)
    }

    // --- Private Methods ---

    private fun initInterpreter(
        context: Context,
        modelPath: String,
        delegatePriorityOrder: Array<Array<TFLiteHelpers.DelegateType>>
    ): Tuple6<Interpreter, Map<TFLiteHelpers.DelegateType, Delegate>, Int, Int, FloatArray, ByteBuffer> {
        val (modelBuffer, hash) = TFLiteHelpers.loadModelFile(context.assets, modelPath)

        val (interpreter, delegates) = TFLiteHelpers.CreateInterpreterAndDelegatesFromOptions(
            modelBuffer,
            delegatePriorityOrder,
            AIHubDefaults.numCPUThreads,
            context.applicationInfo.nativeLibraryDir,
            context.cacheDir.absolutePath,
            hash
        )

        val inputTensor = interpreter.getInputTensor(0)
        val shape = inputTensor.shape()
        val height = shape[1]
        val width = shape[2]
        val inputArr = FloatArray(height * width * 3)
        val inputBuf = ByteBuffer.allocateDirect(inputArr.size * 4).order(ByteOrder.nativeOrder())

        return Tuple6(interpreter, delegates, width, height, inputArr, inputBuf)
    }

    private fun preprocessImage(bitmap: Bitmap) {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2RGB)

        val resizedMat = Mat()
        Imgproc.resize(mat, resizedMat, Size(inputWidth.toDouble(), inputHeight.toDouble()))
        resizedMat.convertTo(resizedMat, CvType.CV_32FC3, 1.0 / 255.0)

        resizedMat.get(0, 0, inputArray)
        inputBuffer.rewind()
        inputBuffer.asFloatBuffer().put(inputArray)
    }

    private fun postProcessDetections(
        bitmap: Bitmap,
        boxCoords: Array<Array<FloatArray>>,
        boxScores: Array<Array<FloatArray>>
    ): List<PointF> {
        val scaleX = bitmap.width.toFloat() / inputWidth
        val scaleY = bitmap.height.toFloat() / inputHeight

        val candidates = mutableListOf<PointF>()
        val scores = mutableListOf<Float>()

        for (i in 0 until NUM_ANCHORS) {
            val rawScore = boxScores[0][i][0]
            val score = sigmoid(rawScore)
            if (score <= DETECTION_THRESHOLD) continue

            val x = boxCoords[0][i][0] * scaleX
            val y = boxCoords[0][i][1] * scaleY

            candidates.add(PointF(x, y))
            scores.add(score)
        }

        return if (candidates.isEmpty()) {
            emptyList()
        } else {
            nonMaximumSuppression(candidates, scores)
        }
    }

    private fun nonMaximumSuppression(
        points: List<PointF>,
        scores: List<Float>
    ): List<PointF> {
        val picked = mutableListOf<PointF>()
        val indices = scores.indices.sortedByDescending { scores[it] }
        val visited = BooleanArray(points.size)

        for (i in indices) {
            if (visited[i]) continue
            val pointA = points[i]
            picked.add(pointA)
            visited[i] = true

            for (j in indices) {
                if (visited[j]) continue
                val pointB = points[j]
                if (arePointsClose(pointA, pointB, IOU_THRESHOLD, inputWidth.toFloat())) {
                    visited[j] = true
                }
            }
        }
        return picked
    }

    // --- Kotlin helper class for returning multiple values ---
    private data class Tuple6<A, B, C, D, E, F>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
        val fifth: E,
        val sixth: F
    )
}