package com.square.aircommand.utils

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

object ModelStorageManager {

    fun saveModelCode(context: Context, modelCode: String) {
        val file = File(context.filesDir, "model_code.json")
        val json = JSONObject().put("model_code", modelCode)
        file.writeText(json.toString())
    }

    fun getSavedModelCode(context: Context): String {
        return try {
            val file = File(context.filesDir, "model_code.json")
            val jsonStr = file.readText()
            val jsonObj = JSONObject(jsonStr)
            jsonObj.optString("model_code", "basic")
        } catch (e: Exception) {
            e.printStackTrace()
            "basic"
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