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
        QNN_NPU
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

        return try {
            Interpreter(tfLiteModel, options).apply {
                allocateTensors()
                Log.i(TAG, "🎯 Interpreter 생성 성공함. 사용된 delegate: ${
                    delegates.map { it.first }.joinToString(", ")
                }")
            }
        } catch (e: Exception) {
            val enabledDelegates = delegates.mapNotNull { it.first.name }.toMutableList().apply { add("XNNPack") }
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
            DelegateType.QNN_NPU -> CreateQNN_NPUDelegate(nativeLibraryDir, cacheDir, modelIdentifier)
        }
    }

    // QNN NPU delegate를 생성함 (HTP fallback 포함)
    private fun CreateQNN_NPUDelegate(
        nativeLibraryDir: String,
        cacheDir: String,
        modelIdentifier: String
    ): Delegate? {
        val options = QnnDelegate.Options().apply {
            setSkelLibraryDir(nativeLibraryDir)
            setLogLevel(QnnDelegate.Options.LogLevel.LOG_LEVEL_WARN)
            setCacheDir(cacheDir)
            setModelToken(modelIdentifier)

            if (QnnDelegate.checkCapability(QnnDelegate.Capability.DSP_RUNTIME)) {
                // DSP backend 설정함
                setBackendType(QnnDelegate.Options.BackendType.DSP_BACKEND)
                setDspOptions(
                    QnnDelegate.Options.DspPerformanceMode.DSP_PERFORMANCE_BURST,
                    QnnDelegate.Options.DspPdSession.DSP_PD_SESSION_ADAPTIVE
                )
            } else {
                // HTP fallback 설정함
                val hasFP16 = QnnDelegate.checkCapability(QnnDelegate.Capability.HTP_RUNTIME_FP16)
                val hasQuant = QnnDelegate.checkCapability(QnnDelegate.Capability.HTP_RUNTIME_QUANTIZED)

                if (!hasFP16 && !hasQuant) {
                    Log.e(TAG, "QNN NPU backend를 지원하지 않음")
                    return null
                }

                setBackendType(QnnDelegate.Options.BackendType.HTP_BACKEND)
                setHtpUseConvHmx(QnnDelegate.Options.HtpUseConvHmx.HTP_CONV_HMX_ON)
                setHtpPerformanceMode(QnnDelegate.Options.HtpPerformanceMode.HTP_PERFORMANCE_BURST)
                if (hasFP16) {
                    setHtpPrecision(QnnDelegate.Options.HtpPrecision.HTP_PRECISION_FP16)
                }
            }
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
}