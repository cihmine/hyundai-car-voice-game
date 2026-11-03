package com.cihmine.carvoicegame

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.cihmine.carvoicegame.game.GameController
import com.cihmine.carvoicegame.ui.GameApp

class MainActivity : ComponentActivity() {
    private val controller: GameController by viewModels()

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        controller.setMicGranted(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        controller.setMicGranted(granted)
        if (!granted) micPermission.launch(Manifest.permission.RECORD_AUDIO)
        // 처음 화면에서 "꺼", "종료"라고 하면 앱을 닫는다
        lifecycleScope.launch { controller.closeRequests.collect { finish() } }
        setContent {
            // 차량 화면(가로 1200dp 이상, 저밀도)은 같은 dp 로는 글씨가 작아 전체를 키운다.
            val base = LocalDensity.current
            val scale = if (LocalConfiguration.current.screenWidthDp >= 1200) 1.5f else 1f
            CompositionLocalProvider(LocalDensity provides Density(base.density * scale, base.fontScale)) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    GameApp(controller)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        controller.onAppStarted()
    }

    override fun onStop() {
        super.onStop()
        // 화면 회전 같은 구성 변경은 제외하고, 실제로 앱이 내려갈 때만 판을 멈추고 저장한다.
        if (!isChangingConfigurations) controller.onAppStopped()
    }
}
