package com.square.aircommand.screens

import android.R.id.progress
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle

import android.text.Editable
import android.text.TextWatcher

import android.util.Log
import android.view.Gravity

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.square.aircommand.R
import com.square.aircommand.databinding.DialogGestureGuideBinding
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

        val editText = binding.gestureNameEditText.editText!!

        defaultGestures = resources.getStringArray(R.array.gesture_name_list).toList()
        customGestureList = loadCustomGestureList(requireContext(), defaultGestures)

        showCustomGestures() // ✅ 사용자 제스처만 UI에 표시

        binding.btnStartGestureShooting.isEnabled = false

        binding.backButton.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        // 텍스트 변경 시 체크 GIF, 결과 초기화 및 버튼 비활성화
        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                binding.checkPassedGif.visibility = View.GONE
                binding.duplicateCheckResultText.text = ""
                binding.duplicateCheckResultText.visibility = View.GONE
                binding.btnStartGestureShooting.isEnabled = false
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnCheckDuplicate.setOnClickListener {
            // 키보드 내리고 포커스 해제
            editText.clearFocus()
            hideKeyboard()

            val gestureName = editText.text.toString().trim()

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

                    // 제스처 촬영 버튼 클릭 시 다이얼로그 표시
                    binding.btnStartGestureShooting.setOnClickListener {
                        val dialogBinding = DialogGestureGuideBinding.inflate(LayoutInflater.from(requireContext()))

                        val dialog = AlertDialog.Builder(requireContext())
                            .setView(dialogBinding.root)
                            .create()

                        val window = dialog.window
                        window?.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        window?.setGravity(Gravity.CENTER)
                        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

                        dialogBinding.btnOpenSettings.setOnClickListener {
                            dialog.dismiss()
                            val bundle = Bundle().apply {
                                putString("gesture_name", gestureName)
                            }
                            findNavController().navigate(
                                R.id.action_userGestureFragment_to_gestureShootingFragment,
                                bundle
                            )
                        }

                        dialog.show()
                    }
                }
            }

            binding.duplicateCheckResultText.visibility = View.VISIBLE
        }

        binding.btnInitGestureShooting.setOnClickListener {
            val guideView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_reset_guide, null)
            val guideDialog = AlertDialog.Builder(requireContext())
                .setView(guideView)
                .setCancelable(false)
                .create()

            guideDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

            val cancelButton = guideView.findViewById<Button>(R.id.btn_gesture_cancel)
            val removeButton = guideView.findViewById<Button>(R.id.btn_gesture_remove)

            cancelButton.setOnClickListener {
                guideDialog.dismiss()
            }

            removeButton.setOnClickListener {
                guideDialog.dismiss()

                modelReset {
                    Log.d(tag, "✅ 모델 리셋 완료 콜백 호출됨")

                    // 초기화 완료 다이얼로그 표시
                    val completeView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_reset_complete, null)
                    val completeDialog = AlertDialog.Builder(requireContext())
                        .setView(completeView)
                        .setCancelable(false)
                        .create()

                    completeDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

                    val goMainButton = completeView.findViewById<Button>(R.id.btn_go_main)
                    goMainButton.setOnClickListener {
                        completeDialog.dismiss()

                        // 네비게이션 그래프에 정의된 action ID와 번들 사용 가능
                        findNavController().navigate(R.id.action_userGestureFragment_to_airCommandFragment)
                    }

                    completeDialog.show()

                    // 토스트 메시지도 원한다면 여기에
                    Toast.makeText(requireContext(), "모델이 초기화되었습니다.", Toast.LENGTH_SHORT).show()
                }
            }

            guideDialog.show()
        }


//        binding.btnInitGestureShooting.setOnClickListener {
//            modelReset {
//                Log.d(tag, "✅ 모델 리셋 완료 콜백 호출됨")
//                Toast.makeText(requireContext(), "모델이 초기화되었습니다.", Toast.LENGTH_SHORT).show()
//            }
//        }

        binding.btnStartGestureShooting.setOnClickListener {
            val gestureName = editText.text.toString().trim()
            val bundle = Bundle().apply {
                putString("gesture_name", gestureName)
            }
            findNavController().navigate(
                R.id.action_userGestureFragment_to_gestureShootingFragment,
                bundle
            )
        }

        // 1. 엔터키 눌렀을 때 키보드 숨기고 포커스 해제
        editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard()
                editText.clearFocus()
                true
            } else {
                false
            }
        }

        // 2. 빈 공간 터치 시 키보드 숨기고 포커스 해제
        binding.root.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                if (editText.isFocused) {
                    editText.clearFocus()
                    hideKeyboard()
                    true  // 이벤트 소비
                } else {
                    false
                }
            } else {
                false
            }
        }
    }

    // 키보드 숨기는 함수
    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(requireView().windowToken, 0)
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

            // 카드 형태로 감싸기
            val cardView = MaterialCardView(requireContext()).apply {
                radius = 16f
                setCardBackgroundColor(ContextCompat.getColor(context, R.color.cardview_light_background))
                strokeColor = ContextCompat.getColor(context, R.color.black)
                strokeWidth = 1
                cardElevation = 4f
                useCompatPadding = true
                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 8, 0, 0)
                }
            }

            // 텍스트뷰 추가
            val textView = TextView(requireContext()).apply {
                text = displayName
                textSize = 14f
                setTextColor(ContextCompat.getColor(context, android.R.color.black))
                setPadding(24, 16, 24, 16)
            }

            cardView.addView(textView)
            container.addView(cardView)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}