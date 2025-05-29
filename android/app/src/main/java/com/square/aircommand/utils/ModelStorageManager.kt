package com.square.aircommand.utils

import android.content.ContentValues.TAG
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * ### 로컬 저장소에서 모델 정보 및 라벨 맵을 관리하는 유틸리티 객체
 *
 * - model_code.json : 현재 사용 중인 모델 식별자 저장
 * - gesture_labels.json : 제스처 라벨 맵핑 (index -> gesture name) 저장
 */
object ModelStorageManager {
    private const val MODEL_CODE_FILENAME = "model_code.json"
    private const val TAG = "ModelCodeManager"
    private const val MODEL_NAME = "update_gesture_model_cnns.tflite"

    /**
     * ✅ model_url.json에 있는 URL로부터 새 모델을 다운로드하고 기존 모델 파일을 덮어쓴다
     * - 다운로드 성공 시 `update_gesture_model_cnns.tflite` 파일로 저장됨
     * - UI 쓰레드에서 Toast 메시지로 사용자에게 성공/실패 여부를 알려줌
     */
    fun downloadAndReplaceModel(context: Context, urlJsonPath: String = "model_url.json") {
        try {
            val urlFile = File(context.filesDir, urlJsonPath)
            if (!urlFile.exists()) {
                Log.w("ModelDownload", "❌ model_url.json 파일 없음.")
                return
            }

            val modelUrl = JSONObject(urlFile.readText()).optString("model_url")
            if (modelUrl.isNullOrBlank()) {
                Log.w("ModelDownload", "❌ model_url이 비어있음.")
                return
            }

            val client = OkHttpClient()
            val request = Request.Builder().url(modelUrl).build()
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e("ModelDownload", "모델 다운로드 실패: ${e.message}", e)
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, "❌ 모델 다운로드 실패", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!response.isSuccessful) {
                        Log.e("ModelDownload", "모델 응답 오류: ${response.code}")
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(context, "❌ 모델 응답 오류 (${response.code})", Toast.LENGTH_SHORT).show()
                        }
                        return
                    }

                    val bytes = response.body?.bytes() ?: run {
                        Log.e("ModelDownload", "응답 바디 없음")
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(context, "❌ 모델 파일이 비어 있음", Toast.LENGTH_SHORT).show()
                        }
                        return
                    }

                    val modelFile = File(context.filesDir, "update_gesture_model_cnns.tflite")
                    modelFile.writeBytes(bytes)

                    Log.i("ModelDownload", "✅ 모델 다운로드 및 교체 완료 → ${modelFile.absolutePath}")

                    // 모델 파일이 실제로 저장되었는지, 파일 크기가 정상적인지, model_url.json OR gesture_labels.json 확인하는 로그
                    Log.d("ModelDebug", "📂 filesDir 목록:")
                    context.filesDir.listFiles()?.forEach {
                        Log.d("ModelDebug", "🗂️ ${it.name} (${it.length()} bytes)")
                    }

//                    Handler(Looper.getMainLooper()).post {
//                        Toast.makeText(context, "✅ 새 제스처 모델 적용 완료!", Toast.LENGTH_SHORT).show()
//                    }
                }
            })
        } catch (e: Exception) {
            Log.e("ModelDownload", "예외 발생: ${e.message}", e)
        }
    }


    fun initializeModelCodeFromAssetsIfNotExists(context: Context) {
        val targetFile = File(context.filesDir, MODEL_CODE_FILENAME)
        if (!targetFile.exists()) {
            try {
                context.assets.open(MODEL_CODE_FILENAME).use { inputStream ->
                    targetFile.outputStream().use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                Log.d(TAG, "📄 model_code.json 복사 완료 (from assets)")
            } catch (e: IOException) {
                Log.e(TAG, "❌ model_code.json 복사 실패: ${e.message}")
            }
        } else {
            Log.d(TAG, "✅ model_code.json 이미 존재함 → 복사 생략")
        }
    }

    fun initializeModelFromAssetsIfNotExists(context: Context) {
        val targetFile = File(context.filesDir, MODEL_NAME)
        if (targetFile.exists()) {
            return  // 이미 복사된 경우는 무시
        }
        try {
            context.assets.open(MODEL_NAME).use { inputStream ->
                FileOutputStream(targetFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            Log.d("ModelStorageManager", "✅ 모델 파일 복사 완료: ${targetFile.absolutePath}")
        } catch (e: IOException) {
            Log.e("ModelStorageManager", "❌ 모델 파일 복사 실패: ${e.message}", e)
        }
    }

    fun saveModelCode(context: Context, modelCode: String) {
        val file = File(context.filesDir, MODEL_CODE_FILENAME)
        val json = JSONObject().put("model_code", modelCode)
        file.writeText(json.toString())
    }

    /**
     * ### model_code.json에서 저장된 모델 코드 불러오기
     * - 없거나 파싱에 실패하면 기본값 "basic"을 반환
     *
     * @return 저장된 모델 코드 문자열 ("basic"이 기본값)
     */
    fun getSavedModelCode(context: Context): String {
        val file = File(context.filesDir, MODEL_CODE_FILENAME)
        return if (file.exists()) {
            try {
                val json = JSONObject(file.readText())
                val modelCode = json.getString("model_code")
                Log.d(TAG, "✅ 모델 코드 파싱 성공: $modelCode")
                modelCode
            } catch (e: Exception) {
                e.printStackTrace()
                "basic" // 파싱 실패 시 기본값 반환
            }
        } else {
            "basic" // 존재하지 않을 경우 기본값 (이론상 실행 안 됨)
        }
    }

    /**
     * ### gesture_labels.json 파일에 새로운 제스처 라벨을 추가
     * - 중복 제스처 이름은 무시하고 새 인덱스(key)를 부여하여 추가
     *
     * @param gestureName 추가할 사용자 정의 제스처 이름 (예: "thumb")
     */

    fun updateLabelMap(context: Context, gestureName: String) {
        val file = File(context.filesDir, "gesture_labels.json")
        Log.d("updateLabelMap", "file: $file")
        val existingLabels = try {
            val jsonStr = file.readText()
            val jsonObj = JSONObject(jsonStr)
            jsonObj.keys().asSequence().associateWith { jsonObj.getString(it) }.toMutableMap()
        } catch (e: Exception) {
            mutableMapOf()
        }

        Log.d("updateLabelMap", "existingLabels: $existingLabels")
        val normalizedGestureName = gestureName.trim().lowercase()
        val normalizedExistingValues = existingLabels.values.map { it.trim().lowercase() }

        if (normalizedGestureName in normalizedExistingValues) {
            Log.d("updateLabelMap", "이미 존재하는 제스처: $gestureName")
            return
        }

        val newKey = (existingLabels.keys.mapNotNull { it.toIntOrNull() }.maxOrNull() ?: -1) + 1
        existingLabels[newKey.toString()] = gestureName.trim()

        val mergedJson = JSONObject(existingLabels as Map<*, *>)
        Log.d("updateLabelMap", "mergedJson: $mergedJson")
        file.writeText(mergedJson.toString(4))
    }
}
