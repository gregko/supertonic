package com.hyperionics.supertonic.tts

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.hyperionics.supertonic.tts.ui.LicensesScreen
import com.hyperionics.supertonic.tts.ui.theme.SupertonicTheme

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
