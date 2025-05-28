package com.square.aircommand.screens

import android.R.id.progress
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.square.aircommand.R
import com.square.aircommand.databinding.FragmentUserGestureBinding
import com.square.aircommand.utils.ModelStorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class UserGestureFragment : Fragment() {

    private var _binding: FragmentUserGestureBinding? = null
    private val binding get() = _binding!!

    private lateinit var defaultGestures: List<String>  // 기본 제스처 (중복 검사용)
    private lateinit var customGestureList: List<String>  // json 기반 사용자 제스처

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentUserGestureBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        defaultGestures = resources.getStringArray(R.array.gesture_name_list).toList()
        customGestureList = loadCustomGestureList(requireContext(), defaultGestures)

        showCustomGestures() // ✅ 사용자 제스처만 UI에 표시

        binding.btnStartGestureShooting.isEnabled = false

        binding.backButton.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        binding.btnCheckDuplicate.setOnClickListener {
            val gestureName = binding.gestureNameEditText.text.toString().trim()

            when {
                gestureName.isEmpty() -> {
                    binding.duplicateCheckResultText.text = "이름을 작성해주세요."
                    binding.btnStartGestureShooting.isEnabled = false
                }
                isGestureNameDuplicate(gestureName) -> {
                    binding.duplicateCheckResultText.text = "중복된 이름입니다."
                    binding.btnStartGestureShooting.isEnabled = false
                }
                else -> {
                    binding.duplicateCheckResultText.text =
                        "등록할 수 있는 이름입니다. [제스처 촬영]을 눌러 촬영을 시작해주세요"
                    binding.btnStartGestureShooting.isEnabled = true

                    Glide.with(this)
                        .asGif()
                        .load(R.raw.checkgif)
                        .into(binding.checkPassedGif)
                    binding.checkPassedGif.visibility = View.VISIBLE
                }
            }

            binding.duplicateCheckResultText.visibility = View.VISIBLE
        }

        binding.btnInitGestureShooting.setOnClickListener {
            modelReset {
                Log.d(tag, "✅ 모델 리셋 완료 콜백 호출됨")
                Toast.makeText(requireContext(), "모델이 초기화되었습니다.", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnStartGestureShooting.setOnClickListener {
            val gestureName = binding.gestureNameEditText.text.toString().trim()

            val bundle = Bundle().apply {
                putString("gesture_name", gestureName)
            }

            findNavController().navigate(
                R.id.action_userGestureFragment_to_gestureShootingFragment,
                bundle
            )
        }

    }

    // GestureShootingFragment.kt 파일 상단 또는 하단에 선언
    fun interface OnModelResetCallback {
        fun onResetComplete()
    }

    private fun modelReset(callback: OnModelResetCallback? = null) {
        serviceScope.launch {
            val context = requireContext()
            val filesDir = context.filesDir
            val files = filesDir.listFiles()

            if (files != null && files.isNotEmpty()) {
                for (file in files) {
                    val deleted = file.delete()
                    Log.d(tag, if (deleted) "🗑️ ${file.name} 삭제 성공" else "⚠️ ${file.name} 삭제 실패")
                }
            } else {
                Log.d(tag, "ℹ️ 삭제할 파일 없음 (filesDir 비어 있음)")
            }

            // 모델 파일이 없을 경우 Assets 에서 복사
            ModelStorageManager.initializeModelCodeFromAssetsIfNotExists(context)
            ModelStorageManager.initializeModelFromAssetsIfNotExists(context)

            // 메인 스레드에서 콜백 실행
            withContext(Dispatchers.Main) {
                callback?.onResetComplete()
            }
        }
    }

    private fun isGestureNameDuplicate(name: String): Boolean {
        return defaultGestures.contains(name) || customGestureList.contains(name)
    }

    private fun loadCustomGestureList(context: Context, excludeList: List<String>): List<String> {
        val file = File(context.filesDir, "gesture_labels.json")
        if (!file.exists()) return emptyList()

        return try {
            val jsonObject = JSONObject(file.readText())
            jsonObject.keys().asSequence()
                .mapNotNull { key -> jsonObject.optString(key, null) }
                .filter { it !in excludeList && it.lowercase() != "none" && it.lowercase() != "unknown" }
                .toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun getDisplayLabel(label: String): String {
        return when (label.lowercase()) {
            "paper" -> "보 제스처"
            "rock" -> "주먹 제스처"
            "scissors" -> "가위 제스처"
            "none", "unknown" -> ""
            else -> if (label.lowercase().contains("제스처")) label else "$label 제스처"
        }
    }

    private fun showCustomGestures() {
        val container = binding.customGestureContainer
        container.removeAllViews()

        for (gesture in customGestureList) {
            val displayName = getDisplayLabel(gesture)
            if (displayName.isBlank()) continue

            val textView = TextView(requireContext()).apply {
                text = displayName
                textSize = 12f
                setTextColor(ContextCompat.getColor(context, android.R.color.black))
                setPadding(16, 12, 16, 12)
                background = ContextCompat.getDrawable(context, R.drawable.rounded_box)
            }

            val layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 8, 0, 0)
            }

            container.addView(textView, layoutParams)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}