package com.square.aircommand.utils

import android.content.ContentValues.TAG
import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object ModelStorageManager {

    private const val MODEL_CODE_FILENAME = "model_code.json"
    private const val TAG = "ModelCodeManager"
    private const val MODEL_NAME = "update_gesture_model_cnns.tflite"


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

    fun updateLabelMap(context: Context, gestureName: String) {
        val file = File(context.filesDir, "gesture_labels.json")
        Log.d("updateLabelMap", "file: $file")
        // 기존 라벨 읽기
        val existingLabels = try {
            val jsonStr = file.readText()
            val jsonObj = JSONObject(jsonStr)
            jsonObj.keys().asSequence().associateWith { jsonObj.getString(it) }.toMutableMap()
        } catch (e: Exception) {
            mutableMapOf()
        }

        Log.d("updateLabelMap", "existingLabels: $existingLabels")
        // 값 정제: 공백 제거 및 소문자 변환
        val normalizedGestureName = gestureName.trim().lowercase()
        val normalizedExistingValues = existingLabels.values.map { it.trim().lowercase() }

        // 이미 있는 제스처는 추가하지 않음
        if (normalizedGestureName in normalizedExistingValues) {
            Log.d("updateLabelMap", "이미 존재하는 제스처: $gestureName")
            return
        }

        // 새로운 키 번호 결정
        val newKey = (existingLabels.keys.mapNotNull { it.toIntOrNull() }.maxOrNull() ?: -1) + 1
        existingLabels[newKey.toString()] = gestureName.trim()

        // JSON 저장
        val mergedJson = JSONObject(existingLabels as Map<*, *>)
        Log.d("updateLabelMap", "mergedJson: $mergedJson")
        file.writeText(mergedJson.toString(4))
    }
}