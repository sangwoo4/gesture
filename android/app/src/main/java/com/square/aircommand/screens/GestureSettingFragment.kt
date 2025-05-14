package com.square.aircommand.screens

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import androidx.fragment.app.Fragment
import com.square.aircommand.databinding.FragmentGestureSettingBinding

class GestureSettingFragment : Fragment() {

    private var _binding: FragmentGestureSettingBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGestureSettingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val prefs = requireContext().getSharedPreferences("gesture_prefs", 0)

        // 저장된 인덱스 불러오기
        val gesture1Index = prefs.getInt("gesture1_index", 0)
        val gesture2Index = prefs.getInt("gesture2_index", 0)

        binding.spinner1.setSelection(gesture1Index)
        binding.spinner2.setSelection(gesture2Index)

        // 선택 리스너 설정
        binding.spinner1.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.edit().putInt("gesture1_index", position).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.spinner2.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.edit().putInt("gesture2_index", position).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.backButton.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
    }


    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
