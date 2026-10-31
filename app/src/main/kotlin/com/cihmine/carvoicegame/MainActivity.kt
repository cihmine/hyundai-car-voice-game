package com.cihmine.carvoicegame

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cihmine.carvoicegame.core.GameKind
import com.cihmine.carvoicegame.core.word.WordDictionary

/** 뼈대 화면. 음성 흐름·게임 화면은 규칙 엔진 위에 이어서 붙인다. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val wordCount = WordDictionary.loadSample().size
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(getString(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
                        Text(GameKind.entries.joinToString(" · ") { it.label })
                        Text("샘플 사전 ${wordCount}단어 준비됨")
                    }
                }
            }
        }
    }
}
