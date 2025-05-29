package com.square.aircommand.handdetector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.square.aircommand.tflite.AIHubDefaults
import com.square.aircommand.tflite.TFLiteHelpers
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.osgi.OpenCVNativeLoader
import org.tensorflow.lite.Delegate
import org.tensorflow.lite.Interpreter
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.atan2
import kotlin.math.exp

class HandDetector(
    context: Context,
    modelPath: String,
    delegatePriorityOrder: Array<Array<TFLiteHelpers.DelegateType>>
) : AutoCloseable {

    companion object {
        private const val NUM_ANCHORS = 2944
        private const val NUM_COORDS = 18
        private const val DETECTION_THRESHOLD = 0.9f    // 0.9로 변경
        private const val X_SCALE = 128.0f
        private const val Y_SCALE = 128.0f
        private const val H_SCALE = 128.0f
        private const val W_SCALE = 128.0f

        private fun sigmoid(x: Float): Float = 1.0f / (1.0f + exp(-x))

        private fun rotateBitmap(src: Bitmap, degrees: Int): Bitmap {
            if (degrees % 360 == 0) return src
            val matrix = android.graphics.Matrix().apply { postRotate(degrees.toFloat()) }
            return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        }
    }

    private val interpreter: Interpreter
    private val delegateStore: Map<TFLiteHelpers.DelegateType, Delegate>
    private val inputWidth: Int
    private val inputHeight: Int
    private val inputArray: FloatArray
    private val inputBuffer: ByteBuffer

    private var anchors: List<Anchor>? = null
    private var lastLetterbox: LetterboxResult? = null

    private var isClosedInternal = false

    data class Anchor(
        val cx: Float,
        val cy: Float,
        val w: Float,
        val h: Float
    )

    data class HandDetectionResult(
        val bbox: RectF,
        val wrist: android.graphics.PointF,
        val middle: android.graphics.PointF,
        val rotation: Float,
        val croppedHand: Bitmap
    )

    private data class LetterboxResult(
        val image: Mat,
        val scale: Float,
        val padX: Int,
        val padY: Int
    )

    init {
        OpenCVNativeLoader().init()
        val (i, d, w, h, inputArr, inputBuf) = initInterpreter(context, modelPath, delegatePriorityOrder)
        interpreter = i
        delegateStore = d
        inputWidth = w
        inputHeight = h
        inputArray = inputArr
        inputBuffer = inputBuf

        anchors = loadAnchorsFromJson(context)
    }

    override fun close() {
        if (!isClosedInternal) {
            interpreter.close()
            delegateStore.values.forEach { it.close() }
            isClosedInternal = true
        }
    }

    // 손 BBox, 임시 wrist/middle, 회전까지 한 번에 반환하는 함수 (detect 아래에 추가)
    fun detectHandAndGetInfo(bitmap: Bitmap, rotationDegrees: Int): HandDetectionResult? {
        val (detectedBoxes, cropped) = detect(bitmap, rotationDegrees)
        if (detectedBoxes.isEmpty() || cropped == null) return null

        val bbox = detectedBoxes.first()
        val cx = (bbox.left + bbox.right) / 2f
        val cy = (bbox.top + bbox.bottom) / 2f

        // ⚠️ landmark 추론 전까지는 임시로 bbox 상하단 중심값 사용
        val wrist = android.graphics.PointF(cx, bbox.bottom)
        val middle = android.graphics.PointF(cx, bbox.top)
        val dx = middle.x - wrist.x
        val dy = middle.y - wrist.y
        val angle = atan2(dy.toDouble(), dx.toDouble()).toFloat() // 라디안

        return HandDetectionResult(
            bbox = bbox,
            wrist = wrist,
            middle = middle,
            rotation = angle,
            croppedHand = cropped
        )
    }

    fun isClosed(): Boolean = isClosedInternal

    fun detect(bitmap: Bitmap, rotationDegrees: Int): Pair<List<RectF>, Bitmap?> {
        if (isClosedInternal) {
            Log.w("HandDetector", "⚠️ Attempted to call detect() after interpreter was closed.")
            return Pair(emptyList(), null)
        }

        // 1) 회전 보정 적용
        val rotatedBitmap = rotateBitmap(bitmap, rotationDegrees)

        // 2) 전처리
        preprocessImage(rotatedBitmap)

        val boxCoords = Array(1) { Array(NUM_ANCHORS) { FloatArray(NUM_COORDS) } }
        val boxScores = Array(1) { Array(NUM_ANCHORS) { FloatArray(1) } }

        val outputMap = mapOf(
            runCatching { interpreter.getOutputIndex("box_coords") }.getOrElse {
                return Pair(emptyList(), null)
            } to boxCoords,
            runCatching { interpreter.getOutputIndex("box_scores") }.getOrElse {
                return Pair(emptyList(), null)
            } to boxScores
        )

        runCatching {
            interpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputMap)
        }.onFailure {
            return Pair(emptyList(), null)
        }
        val detectedBoxes = postProcessDetections(rotatedBitmap, boxCoords, boxScores)
        if (detectedBoxes.isEmpty()) {
            return Pair(emptyList(), null)
        }

        // ③ ROI(정사각형) 계산 및 crop (탑-1 박스)
        val handBox = detectedBoxes.first()
        val squareROI = computeSquareROI(handBox, rotatedBitmap.width, rotatedBitmap.height)
        val cropRect = android.graphics.Rect(
            squareROI.left.toInt(),
            squareROI.top.toInt(),
            squareROI.right.toInt(),
            squareROI.bottom.toInt()
        )

        val cropW = cropRect.width().coerceAtLeast(1)
        val cropH = cropRect.height().coerceAtLeast(1)
        val cropped = Bitmap.createBitmap(
            rotatedBitmap,
            cropRect.left,
            cropRect.top,
            cropW,
            cropH
        )
        val landmarkInput = Bitmap.createScaledBitmap(cropped, 256, 256, true)
        return Pair(detectedBoxes, landmarkInput)
    }

    private fun preprocessImage(bitmap: Bitmap) {
        // 1) Bitmap → Mat 변환 & 컬러
        val mat = Mat().also { Utils.bitmapToMat(bitmap, it) }
        Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2RGB)

        lastLetterbox = letterboxResize(mat)
        val (letterboxed, _, _, _) = lastLetterbox!!

        // 3) 0~1 정규화
        letterboxed.convertTo(letterboxed, CvType.CV_32FC3, 1.0 / 255.0)

        // 4) 모델 입력 버퍼에 복사
        letterboxed.get(0, 0, inputArray)
        inputBuffer.rewind()
        inputBuffer.asFloatBuffer().put(inputArray)
    }

    private fun postProcessDetections(
        bitmap: Bitmap,
        boxCoords: Array<Array<FloatArray>>,
        boxScores: Array<Array<FloatArray>>
    ): List<RectF> {
        val anchorList = anchors ?: return emptyList()
        if (anchorList.size != NUM_ANCHORS) {
            Log.e("HandDetector", "⚠️ Anchor 수 불일치: ${anchorList.size}")
            return emptyList()
        }

        val decodedBoxes = decodeBoxes(boxCoords, anchorList)
        val results = mutableListOf<Pair<RectF, Float>>()
        var maxScore = 0f

        for (i in 0 until NUM_ANCHORS) {
            val score = sigmoid(boxScores[0][i][0])
            if (score > DETECTION_THRESHOLD) {
                results.add(decodedBoxes[i] to score)
                if (score > maxScore) maxScore = score
            }
        }


        val lb = lastLetterbox

        val (scale, padXf, padYf) = lb?.let { Triple(it.scale, it.padX.toFloat(), it.padY.toFloat()) } ?: Triple(1f, 0f, 0f)
        return results
            .sortedByDescending { it.second }
            .take(1)
            .map { (normBox, score) ->
                val lx1 = normBox.left   * inputWidth
                val ly1 = normBox.top    * inputHeight
                val lx2 = normBox.right  * inputWidth
                val ly2 = normBox.bottom * inputHeight
                val ox1 = (lx1 - padXf) / scale
                val oy1 = (ly1 - padYf) / scale
                val ox2 = (lx2 - padXf) / scale
                val oy2 = (ly2 - padYf) / scale

                RectF(ox1, oy1, ox2, oy2)
            }
    }


    //Json 파일 로드 함수
    private fun loadAnchorsFromJson(context: Context, fileName: String = "hand_anchors.json"): List<Anchor> {
        return try {
            val inputStream = context.assets.open(fileName)
            val reader = InputStreamReader(inputStream)
            val type = object : TypeToken<List<Anchor>>() {}.type
            Gson().fromJson(reader, type)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun decodeBoxes(
        boxCoords: Array<Array<FloatArray>>,  // [1][2944][18]
        anchors: List<Anchor>
    ): List<RectF> {
        val decodedBoxes = mutableListOf<RectF>()

        for (i in anchors.indices) {
            val anchor = anchors[i]
            val box = boxCoords[0][i]

            val yCenter = (box[0] / Y_SCALE) * anchor.h + anchor.cy
            val xCenter = (box[1] / X_SCALE) * anchor.w + anchor.cx
            val h = exp(box[2] / H_SCALE) * anchor.h
            val w = exp(box[3] / W_SCALE) * anchor.w

            val ymin = yCenter - h / 2f
            val xmin = xCenter - w / 2f
            val ymax = yCenter + h / 2f
            val xmax = xCenter + w / 2f

            decodedBoxes.add(RectF(xmin, ymin, xmax, ymax))
        }

        return decodedBoxes
    }

    //원본 비율 유지 후 padding
    private fun letterboxResize(src: Mat): LetterboxResult {
        val targetW = inputWidth
        val targetH = inputHeight

        val srcH = src.rows()
        val srcW = src.cols()

        // 1) scale 계산 (비율 유지)
        val scale = minOf(targetW.toFloat() / srcW, targetH.toFloat() / srcH)

        // 2) 크기 조정
        val resizedW = (srcW * scale).toInt()
        val resizedH = (srcH * scale).toInt()
        val resized = Mat()
        Imgproc.resize(src, resized, Size(resizedW.toDouble(), resizedH.toDouble()))

        // 3) padding 계산 (좌우, 상하 동일 비율로 패딩)
        val padX = targetW - resizedW
        val padY = targetH - resizedH
        val padLeft = padX / 2
        val padRight = padX - padLeft
        val padTop = padY / 2
        val padBottom = padY - padTop

        // 4) 패딩 적용 (검은색)
        val bordered = Mat()
        Core.copyMakeBorder(
            resized, bordered,
            padTop, padBottom, padLeft, padRight,
            Core.BORDER_CONSTANT,
            Scalar(0.0, 0.0, 0.0)  // RGB 채널 모두 0
        )
        return LetterboxResult(bordered, scale, padLeft, padTop)
    }

    // ROI 계산 함수 추가
// ROI 계산 함수 개선
    private fun computeSquareROI(bbox: RectF, imgWidth: Int, imgHeight: Int): RectF {
        // 1. bbox 중심 계산
        val cx = (bbox.left + bbox.right) / 2f
        val cy = (bbox.top + bbox.bottom) / 2f

        // 2. bbox의 너비와 높이 중 더 큰 값을 기준으로 margin을 적용
        val bboxSize = maxOf(bbox.width(), bbox.height())
        val margin = 1.8f  // 💡 추천값 (실험적으로 1.7~2.0 가능)
        val roiSize = bboxSize * margin

        // 3. 좌상단 기준 임시 ROI 계산
        var roiLeft = cx - roiSize / 2f
        var roiTop = cy - roiSize / 2f
        var roiRight = cx + roiSize / 2f
        var roiBottom = cy + roiSize / 2f

        // 4. 이미지 경계 벗어날 때 중앙 유지하며 ROI 보정
        // (ROI가 이미지 바깥이면 반대쪽으로 이동)
        if (roiLeft < 0f) {
            roiRight += -roiLeft
            roiLeft = 0f
        }
        if (roiTop < 0f) {
            roiBottom += -roiTop
            roiTop = 0f
        }
        if (roiRight > imgWidth.toFloat()) {
            val diff = roiRight - imgWidth.toFloat()
            roiLeft -= diff
            roiRight = imgWidth.toFloat()
            if (roiLeft < 0f) roiLeft = 0f // 완전 바깥이면 0부터 시작
        }
        if (roiBottom > imgHeight.toFloat()) {
            val diff = roiBottom - imgHeight.toFloat()
            roiTop -= diff
            roiBottom = imgHeight.toFloat()
            if (roiTop < 0f) roiTop = 0f
        }

        // 5. (안정성) 정수 보정
        roiLeft = roiLeft.coerceIn(0f, imgWidth.toFloat())
        roiTop = roiTop.coerceIn(0f, imgHeight.toFloat())
        roiRight = roiRight.coerceIn(roiLeft + 1f, imgWidth.toFloat())
        roiBottom = roiBottom.coerceIn(roiTop + 1f, imgHeight.toFloat())

        return RectF(roiLeft, roiTop, roiRight, roiBottom)
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

    private fun initInterpreter(
        context: Context,
        modelPath: String,
        delegatePriorityOrder: Array<Array<TFLiteHelpers.DelegateType>>
    ): Tuple6<Interpreter, Map<TFLiteHelpers.DelegateType, Delegate>, Int, Int, FloatArray, ByteBuffer> {
        val (modelBuffer, hash) = TFLiteHelpers.loadModelFile(context.assets, modelPath)

        val (interpreter, delegates) = TFLiteHelpers.createInterpreterAndDelegatesFromOptions(
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

}