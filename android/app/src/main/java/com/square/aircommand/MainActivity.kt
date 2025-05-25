package com.square.aircommand

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment

import com.square.aircommand.utils.ModelStorageManager

// TODO: 상단 바에서 사용중 키고 끌 수 잇게 추가하자

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ModelStorageManager.initializeModelCodeFromAssetsIfNotExists(this)
        ModelStorageManager.initializeModelFromAssetsIfNotExists(this)

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
    }
}

