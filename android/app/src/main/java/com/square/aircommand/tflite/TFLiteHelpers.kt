package com.square.aircommand.tflite

import android.content.res.AssetManager
import android.util.Log
import com.qualcomm.qti.QnnDelegate
import org.tensorflow.lite.Delegate
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.gpu.GpuDelegateFactory
import java.io.File
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
        val cacheFile = File(modelCachePath)
        if (cacheFile.exists()) {
            cacheFile.delete()
            Log.i(TAG, "🧼 기존 QNN 캐시 삭제: $modelCachePath")
        }

        val attemptedDelegates = mutableSetOf<DelegateType>()

        for (delegatesToRegister in delegatePriorityOrder) {
            delegatesToRegister.filterNot { attemptedDelegates.contains(it) }.forEach { type ->
                CreateDelegate(type, nativeLibraryDir, cacheDir, modelIdentifier)?.let {
                    delegates[type] = it
                }
                attemptedDelegates.add(type)
            }

            if (delegatesToRegister.any { !delegates.containsKey(it) }) continue

            val pairs = delegatesToRegister.map { Pair(it, delegates[it]) }.toTypedArray()
            val interpreter = CreateInterpreterFromDelegates(pairs, numCPUThreads, tfLiteModel) ?: continue

            val used = delegatesToRegister.toSet()
            delegates.keys.filterNot { it in used }.forEach {
                delegates.remove(it)?.close()
            }

            return Pair(interpreter, delegates)
        }

        throw RuntimeException("인터프리터가 그 어떤 delegate와 연결할 수 없습니다")
    }

    fun CreateInterpreterFromDelegates(
        delegates: Array<Pair<DelegateType, Delegate?>>, numCPUThreads: Int, tfLiteModel: MappedByteBuffer
    ): Interpreter? {
        val options = Interpreter.Options().apply {
            setAllowBufferHandleOutput(true)
            setUseNNAPI(false)
            setUseXNNPACK(false) // CPU fallback 방지용 기본 비활성화
            setNumThreads(numCPUThreads)
            delegates.forEach { (_, delegate) ->
                delegate?.let { addDelegate(it) }
            }
        }

        return try {
            Interpreter(tfLiteModel, options).apply {
                allocateTensors()
                Log.i(TAG, "🎯 Interpreter 생성 성공: delegate = ${delegates.map { it.first }.joinToString(", ")}")
            }
        } catch (e: Exception) {
            val fallbackList = delegates.map { it.first.name } + "XNNPack"
            Log.e(TAG, "❌ Interpreter 생성 실패. delegates=${fallbackList.joinToString()} | 오류: ${e.message}")
            throw RuntimeException("Interpreter 생성 실패: ${e.message}")
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
            DelegateType.GPUv2 -> CreateGPUv2Delegate(cacheDir, modelIdentifier)
            DelegateType.QNN_NPU_FP16 -> CreateQNN_NPUDelegate(nativeLibraryDir, cacheDir, modelIdentifier, true)
            DelegateType.QNN_NPU_QUANTIZED -> CreateQNN_NPUDelegate(nativeLibraryDir, cacheDir, modelIdentifier, false)
        }
    }

    private fun CreateQNN_NPUDelegate(
        nativeLibraryDir: String,
        cacheDir: String,
        modelIdentifier: String,
        useFP16: Boolean
    ): Delegate? {
        val actualIdentifier = "$modelIdentifier-${if (useFP16) "fp16" else "quant"}"
        val options = QnnDelegate.Options().apply {
            setSkelLibraryDir(nativeLibraryDir)
            Log.i("QNN", "📂 SkelLibraryDir: $nativeLibraryDir")
            setCacheDir(cacheDir)
            setModelToken(actualIdentifier) // ✅ 고유 캐시 명칭 사용

            val hasFP16 = QnnDelegate.checkCapability(QnnDelegate.Capability.HTP_RUNTIME_FP16)
            val hasQuant = QnnDelegate.checkCapability(QnnDelegate.Capability.HTP_RUNTIME_QUANTIZED)

            if (useFP16 && !hasFP16) return null
            if (!useFP16 && !hasQuant) return null

            setBackendType(QnnDelegate.Options.BackendType.HTP_BACKEND)
            setHtpUseConvHmx(QnnDelegate.Options.HtpUseConvHmx.HTP_CONV_HMX_ON)
            setHtpPerformanceMode(QnnDelegate.Options.HtpPerformanceMode.HTP_PERFORMANCE_BURST)

            if (useFP16) setHtpPrecision(QnnDelegate.Options.HtpPrecision.HTP_PRECISION_FP16)
            else setHtpPrecision(QnnDelegate.Options.HtpPrecision.HTP_PRECISION_QUANTIZED)
        }

        return try {
            QnnDelegate(options)
        } catch (e: Exception) {
            Log.e(TAG, "QNN delegate 생성 실패: ${e.message}")
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
                arrayOf()
            }
        }
    }

}