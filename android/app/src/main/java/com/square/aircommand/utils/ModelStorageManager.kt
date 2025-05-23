package com.square.aircommand.utils

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
import java.io.IOException

/**
 * ### 로컬 저장소에서 모델 정보 및 라벨 맵을 관리하는 유틸리티 객체
 *
 * - model_code.json : 현재 사용 중인 모델 식별자 저장
 * - gesture_labels.json : 제스처 라벨 맵핑 (index -> gesture name) 저장
 */
object ModelStorageManager {

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

                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, "✅ 새 제스처 모델 적용 완료!", Toast.LENGTH_SHORT).show()
                    }

                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, "✅ 새 제스처 모델 적용 완료!", Toast.LENGTH_SHORT).show()
                    }
                }
            })
        } catch (e: Exception) {
            Log.e("ModelDownload", "예외 발생: ${e.message}", e)
        }
    }

    /**
     * ### model_code.json 파일에 모델 식별 코드를 저장
     * - 이 정보는 서버에서 학습된 모델을 구분하는 데 사용
     *
     * @param context 앱의 내부 저장소 접근을 위한 컨텍스트
     * @param modelCode 저장할 모델 식별 코드 (예: "cnn_013abc")
     */
    fun saveModelCode(context: Context, modelCode: String) {
        val file = File(context.filesDir, "model_code.json")
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
        val file = File(context.filesDir, "model_code.json")
        return if (file.exists()) {
            try {
                val json = JSONObject(file.readText())
                json.getString("model_code")
            } catch (e: Exception) {
                e.printStackTrace()
                "basic" // 파싱 실패 시 기본값 반환
            }
        } else {
            val defaultCode = "basic"
            saveModelCode(context, defaultCode)
            defaultCode
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
