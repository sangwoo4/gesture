package com.square.aircommand.utils

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * 내부 저장소에서 모델 파일 및 JSON 파일을 읽고 쓰는 데 사용하는 파일 유틸리티 클래스
 */
object FileUtils {

    /**
     * 제스처 모델 파일의 File 객체를 반환
     * 경로: filesDir/models/update_gesture_model_cnns.tflite
     * 폴더가 없으면 자동으로 생성
     */
    fun getModelFile(context: Context): File {
        val dir = File(context.filesDir, "models")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "update_gesture_model_cnns.tflite")
    }

    /**
     * 제스처 라벨 JSON 파일의 File 객체를 반환
     * 경로: filesDir/labels/gesture_labels.json
     * 폴더가 없으면 자동으로 생성
     */
    fun getLabelFile(context: Context): File {
        val dir = File(context.filesDir, "labels")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "gesture_labels.json")
    }

    /**
     * JSON 문자열을 지정된 파일에 저장
     * 기존 파일이 있다면 덮어씀
     */
    fun saveJsonToFile(file: File, json: String) {
        file.writeText(json)
    }

    fun downloadModel(
        context: Context,
        url: String,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        val request = okhttp3.Request.Builder().url(url).build()
        val client = okhttp3.OkHttpClient()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                onFailure(e.message ?: "다운로드 오류")
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (!response.isSuccessful) {
                    onFailure("HTTP ${response.code}")
                    return
                }

                val modelFile = getModelFile(context)
                try {
                    response.body?.byteStream()?.use { input ->
                        modelFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                        onSuccess()
                    } ?: onFailure("응답이 비어 있음")
                } catch (e: Exception) {
                    onFailure("파일 저장 실패: ${e.message}")
                }
            }
        })
    }
}