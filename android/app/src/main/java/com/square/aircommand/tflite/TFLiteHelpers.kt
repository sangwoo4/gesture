package com.square.aircommand.tflite

import android.content.res.AssetManager
import android.util.Log
import com.qualcomm.qti.QnnDelegate
import org.tensorflow.lite.Delegate
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.gpu.GpuDelegateFactory
import java.io.FileInputStream
import java.io.IOException
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.security.DigestInputStream
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

object TFLiteHelpers {
    private const val TAG = "QualcommTFLiteHelpers"

    // Delegate 타입을 정의함
    enum class DelegateType {
        GPUv2,
        QNN_NPU_FP16,
        QNN_NPU_QUANTIZED
    }

    // delegate 우선순위를 기준으로 interpreter와 delegate들을 생성함
    fun CreateInterpreterAndDelegatesFromOptions(
        tfLiteModel: MappedByteBuffer,
        delegatePriorityOrder: Array<Array<DelegateType>>,
        numCPUThreads: Int,
        nativeLibraryDir: String,
        cacheDir: String,
        modelIdentifier: String
    ): Pair<Interpreter, Map<DelegateType, Delegate>> {
        val delegates = mutableMapOf<DelegateType, Delegate>()
        val modelCachePath = "$cacheDir/$modelIdentifier.htp_cache"
        val cacheFile = java.io.File(modelCachePath)
        if (cacheFile.exists()) {
            Log.w(TAG, "⚠️ QNN 캐시 존재함: $modelCachePath → 삭제 후 재생성 시도")
            cacheFile.delete()
        }
        val attemptedDelegates = mutableSetOf<DelegateType>()

        // delegate 조합별로 순차적으로 시도함
        for (delegatesToRegister in delegatePriorityOrder) {
            // 이미 시도한 delegate는 건너뜀
            delegatesToRegister.filterNot { attemptedDelegates.contains(it) }.forEach { type ->
                CreateDelegate(type, nativeLibraryDir, cacheDir, modelIdentifier)?.let {
                    delegates[type] = it
                }
                attemptedDelegates.add(type)
            }

            // 이 조합의 모든 delegate가 생성되지 않았으면 다음으로 넘어감
            if (delegatesToRegister.any { !delegates.containsKey(it) }) continue

            // interpreter 생성 시도함
            val pairs = delegatesToRegister.map { Pair(it, delegates[it]) }.toTypedArray()
            val interpreter = CreateInterpreterFromDelegates(pairs, numCPUThreads, tfLiteModel) ?: continue

            // 사용하지 않은 delegate는 해제함
            val used = delegatesToRegister.toSet()
            delegates.keys.filterNot { it in used }.forEach {
                delegates.remove(it)?.close()
            }

            return Pair(interpreter, delegates)
        }

        // 실패 시 예외 발생시킴
        throw RuntimeException("Unable to create interpreter with any delegate combination.")
    }

    // 주어진 delegate 배열로 interpreter를 생성함
    fun CreateInterpreterFromDelegates(
        delegates: Array<Pair<DelegateType, Delegate?>>, numCPUThreads: Int, tfLiteModel: MappedByteBuffer
    ): Interpreter? {
        val options = Interpreter.Options().apply {
            setAllowBufferHandleOutput(true)
            setUseNNAPI(false)
            setNumThreads(numCPUThreads)
            setUseXNNPACK(true) // CPU fallback 대비

            // 각 delegate를 options에 추가함
            delegates.forEach { (type, delegate) ->
                if (delegate != null) {
                    Log.i(TAG, "✅ [$type] delegate가 정상적으로 추가됨")

                    addDelegate(delegate)
                } else {
                    Log.w(TAG, "⚠️ [$type] delegate가 null이라 추가되지 않음")
                }
            }
        }
        val enabledList = delegates.map { it.first.name }
        Log.i(TAG, "🧩 시도된 delegate 조합: ${enabledList.joinToString(" + ")}")

        return try {
            Interpreter(tfLiteModel, options).apply {
                allocateTensors()
                // 📥 입력 텐서 정보 로그
                for (i in 0 until inputTensorCount) {
                    val t = getInputTensor(i)
                    Log.i(TAG, "📥 입력[$i] name=${t.name()}, dtype=${t.dataType()}, shape=${t.shape().contentToString()}")
                }

                // 📤 출력 텐서 정보 로그
                for (i in 0 until outputTensorCount) {
                    val t = getOutputTensor(i)
                    Log.i(TAG, "📤 출력[$i] name=${t.name()}, dtype=${t.dataType()}, shape=${t.shape().contentToString()}")
                }
                Log.i(TAG, "🎯 Interpreter 생성 성공함. 사용된 delegate: ${
                    delegates.map { it.first }.joinToString(", ")
                }")
            }
        } catch (e: Exception) {
            val enabledDelegates = delegates.map { it.first.name }.toMutableList().apply { add("XNNPack") }
            Log.w(TAG, "⚠️ 모든 delegate 실패. XNNPack (CPU fallback) 사용 가능성 있음.")
            Log.e(TAG, "❌ Interpreter 생성 실패함. 시도된 delegate: ${enabledDelegates.joinToString(", ")} | 오류: ${e.message}")
            null
        }
    }

    // assets 폴더에서 모델 파일을 읽고 MD5 해시값을 반환함
    @Throws(IOException::class, NoSuchAlgorithmException::class)
    fun loadModelFile(assets: AssetManager, modelFilename: String): Pair<MappedByteBuffer, String> {
        val fileDescriptor = assets.openFd(modelFilename)
        val buffer: MappedByteBuffer
        val hash: String

        FileInputStream(fileDescriptor.fileDescriptor).use { inputStream ->
            val channel = inputStream.channel
            val startOffset = fileDescriptor.startOffset
            val declaredLength = fileDescriptor.declaredLength
            buffer = channel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)

            // 모델의 MD5 해시를 계산함
            val digest = MessageDigest.getInstance("MD5")
            inputStream.skip(startOffset)
            DigestInputStream(inputStream, digest).use { dis ->
                val data = ByteArray(8192)
                var totalRead = 0
                while (totalRead < declaredLength) {
                    val bytesRead = dis.read(data, 0, minOf(8192, (declaredLength - totalRead).toInt()))
                    if (bytesRead == -1) break
                    totalRead += bytesRead
                }
            }
            hash = digest.digest().joinToString("") { "%02x".format(it) }
        }
        Log.i(TAG, "📦 모델 로드 완료 - 파일명: $modelFilename | MD5 해시: $hash")
        return Pair(buffer, hash)
    }

    // delegate 타입에 따라 해당 delegate를 생성함
    private fun CreateDelegate(
        delegateType: DelegateType,
        nativeLibraryDir: String,
        cacheDir: String,
        modelIdentifier: String
    ): Delegate? {
        return when (delegateType) {
            DelegateType.GPUv2 -> {
                val d = CreateGPUv2Delegate(cacheDir, modelIdentifier)
                if (d != null) Log.i(TAG, "✅ GPUv2 delegate 생성 완료") else Log.w(TAG, "⚠️ GPUv2 delegate 생성 실패")
                d
            }
            DelegateType.QNN_NPU_FP16 -> {
                val d = CreateQNN_NPUDelegate(nativeLibraryDir, cacheDir, modelIdentifier, true)
                if (d != null) Log.i(TAG, "✅ QNN_NPU_FP16 delegate 생성 완료") else Log.w(TAG, "⚠️ QNN_NPU_FP16 delegate 생성 실패")
                d
            }
            DelegateType.QNN_NPU_QUANTIZED -> {
                val d = CreateQNN_NPUDelegate(nativeLibraryDir, cacheDir, modelIdentifier, false)
                if (d != null) Log.i(TAG, "✅ QNN_NPU_QUANTIZED delegate 생성 완료") else Log.w(TAG, "⚠️ QNN_NPU_QUANTIZED delegate 생성 실패")
                d
            }
        }
    }

    private fun CreateQNN_NPUDelegate(
        nativeLibraryDir: String,
        cacheDir: String,
        modelIdentifier: String,
        useFP16: Boolean // ✅ 새 인자
    ): Delegate? {
        val options = QnnDelegate.Options().apply {
            setSkelLibraryDir(nativeLibraryDir)
            setLogLevel(QnnDelegate.Options.LogLevel.LOG_LEVEL_WARN)
            setCacheDir(cacheDir)
            setModelToken(modelIdentifier)

            val hasFP16 = QnnDelegate.checkCapability(QnnDelegate.Capability.HTP_RUNTIME_FP16)
            val hasQuant = QnnDelegate.checkCapability(QnnDelegate.Capability.HTP_RUNTIME_QUANTIZED)

            Log.i(TAG, "🔍 QNN Delegate 활성화 여부 - HTP_FP16: $hasFP16, HTP_QUANTIZED: $hasQuant")

            setBackendType(QnnDelegate.Options.BackendType.HTP_BACKEND)
            setHtpUseConvHmx(QnnDelegate.Options.HtpUseConvHmx.HTP_CONV_HMX_ON)
            setHtpPerformanceMode(QnnDelegate.Options.HtpPerformanceMode.HTP_PERFORMANCE_BURST)

            if (useFP16) {
                if (!hasFP16) {
                    Log.e(TAG, "❌ FP16 delegate 사용 요청했지만 디바이스에서 지원하지 않음")
                    return null
                }
                setHtpPrecision(QnnDelegate.Options.HtpPrecision.HTP_PRECISION_FP16)
                Log.i(TAG, "⚙️ QNN Delegate 설정: FP16 precision 모드 사용")
            } else {
                if (!hasQuant) {
                    Log.e(TAG, "❌ Quantized delegate 사용 요청했지만 디바이스에서 지원하지 않음")
                    return null
                }
                // setHtpPrecision 생략 시 INT8이 기본 적용됨
                Log.i(TAG, "⚙️ QNN Delegate 설정: Quantized precision (INT8) 모드 사용")
            }

            Log.i(TAG, "✅ QNN HTP Delegate 옵션 설정 완료")
        }

        return try {
            QnnDelegate(options)
        } catch (e: Exception) {
            Log.e(TAG, "QNN delegate 생성 실패함: ${e.message}")
            null
        }
    }


    // GPUv2 delegate를 생성함
    private fun CreateGPUv2Delegate(cacheDir: String, modelIdentifier: String): Delegate? {
        val options = GpuDelegateFactory.Options().apply {
            inferencePreference = GpuDelegateFactory.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED
            isPrecisionLossAllowed = true
            setSerializationParams(cacheDir, modelIdentifier)
        }

        return try {
            GpuDelegate(options)
        } catch (e: Exception) {
            Log.e(TAG, "GPUv2 delegate 생성 실패함: ${e.message}")
            null
        }
    }

    fun getModelInputType(tfLiteModel: MappedByteBuffer): org.tensorflow.lite.DataType {
        return try {
            val interpreter = Interpreter(tfLiteModel)
            val inputTensor = interpreter.getInputTensor(0)
            val inputType = inputTensor.dataType()
            interpreter.close()
            inputType
        } catch (e: Exception) {
            Log.e(TAG, "❌ 입력 타입 확인 실패: ${e.message}")
            org.tensorflow.lite.DataType.FLOAT32 // 기본값 fallback
        }
    }

    fun getDelegatePriorityOrderFromInputType(inputType: org.tensorflow.lite.DataType): Array<Array<DelegateType>> {
        return when (inputType) {
            org.tensorflow.lite.DataType.FLOAT32 -> arrayOf(
                arrayOf(DelegateType.QNN_NPU_FP16, DelegateType.GPUv2),
                arrayOf(DelegateType.GPUv2),
                arrayOf()
            )
            org.tensorflow.lite.DataType.UINT8 -> arrayOf(
                arrayOf(DelegateType.QNN_NPU_QUANTIZED, DelegateType.GPUv2),
                arrayOf(DelegateType.GPUv2),
                arrayOf()
            )
            else -> {
                Log.w(TAG, "⚠️ 알 수 없는 입력 타입 $inputType, fallback to CPU")
                arrayOf()
            }
        }
    }

}