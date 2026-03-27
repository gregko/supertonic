package com.brahmadeo.supertonic.tts

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.brahmadeo.supertonic.tts.ui.LicensesScreen
import com.brahmadeo.supertonic.tts.ui.theme.SupertonicTheme

class LicensesActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SupertonicTheme {
                LicensesScreen(
                    onBackClick = { finish() }
                )
            }
        }
    }
}
