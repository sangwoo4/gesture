package com.square.aircommand.screens

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.google.android.material.card.MaterialCardView
import com.square.aircommand.R
import com.square.aircommand.databinding.FragmentUserGestureBinding
import org.json.JSONObject
import java.io.File

class UserGestureFragment : Fragment() {

    private var _binding: FragmentUserGestureBinding? = null
    private val binding get() = _binding!!

    private lateinit var defaultGestures: List<String>  // 기본 제스처 (중복 검사용)
    private lateinit var customGestureList: List<String>  // json 기반 사용자 제스처

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
            val gestureName = binding.gestureNameEditText.editText?.text.toString().trim()


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

        binding.btnStartGestureShooting.setOnClickListener {
            val gestureName = binding.gestureNameEditText.editText?.text.toString().trim()


            val bundle = Bundle().apply {
                putString("gesture_name", gestureName)
            }

            findNavController().navigate(
                R.id.action_userGestureFragment_to_gestureShootingFragment,
                bundle
            )
        }

        // EditText 외의 영역 터치 시 키보드 및 포커스 제거
        binding.root.setOnTouchListener { v, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                val currentFocusView = requireActivity().currentFocus
                if (currentFocusView != null) {
                    currentFocusView.clearFocus()
                    imm.hideSoftInputFromWindow(currentFocusView.windowToken, 0)

                    // 포커스를 root로 옮겨서 EditText에서 포커스 완전히 제거
                    binding.root.requestFocus()
                }
            }
            false // ← 중요! false로 변경해서 하위 뷰가 터치 이벤트 받을 수 있게
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


//    private fun showCustomGestures() {
//        val container = binding.customGestureContainer
//        container.removeAllViews()
//
//        for (gesture in customGestureList) {
//            val displayName = getDisplayLabel(gesture)
//            if (displayName.isBlank()) continue
//
//            val textView = TextView(requireContext()).apply {
//                text = displayName
//                textSize = 12f
//                setTextColor(ContextCompat.getColor(context, android.R.color.black))
//                setPadding(16, 12, 16, 12)
//                background = ContextCompat.getDrawable(context, R.drawable.rounded_box)
//            }
//
//            val layoutParams = ViewGroup.MarginLayoutParams(
//                ViewGroup.LayoutParams.MATCH_PARENT,
//                ViewGroup.LayoutParams.WRAP_CONTENT
//            ).apply {
//                setMargins(0, 8, 0, 0)
//            }
//
//            container.addView(textView, layoutParams)
//        }
//    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}