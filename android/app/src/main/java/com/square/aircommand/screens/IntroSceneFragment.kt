package com.square.aircommand.screens

import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.square.aircommand.R
import com.square.aircommand.databinding.FragmentIntroSceneBinding
import android.os.Handler
import android.view.animation.AnimationUtils
import androidx.navigation.fragment.findNavController


class IntroSceneFragment : Fragment() {

    private var _binding: FragmentIntroSceneBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentIntroSceneBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        startMotion()
        setTouchListener()
    }

    /** 3초짜리 모션 실행 */
    private fun startMotion() = with(binding.introMotion) {
        transitionToStart()
        post { transitionToEnd() }
    }

    /** 애니메이션 끝난 뒤 깜빡임 + 터치 처리 */
    private fun setTouchListener() = with(binding) {
        Handler(Looper.getMainLooper()).postDelayed({
            tvTouch.startAnimation(
                AnimationUtils.loadAnimation(requireContext(), R.anim.blink)
            )
            introMotion.setOnClickListener {
                findNavController().navigate(R.id.action_introSceneFragment_to_airCommandFragment)
            }

        }, 3000)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
