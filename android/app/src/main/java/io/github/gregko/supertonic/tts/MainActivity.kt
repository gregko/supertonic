package io.github.gregko.supertonic.tts

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import io.github.gregko.supertonic.tts.service.IPlaybackListener
import io.github.gregko.supertonic.tts.service.IPlaybackService
import io.github.gregko.supertonic.tts.service.PlaybackService
import io.github.gregko.supertonic.tts.ui.AboutDialog
import io.github.gregko.supertonic.tts.ui.MainScreen
import io.github.gregko.supertonic.tts.ui.theme.SupertonicTheme
import io.github.gregko.supertonic.tts.utils.AssetInstaller
import io.github.gregko.supertonic.tts.utils.HistoryManager
import io.github.gregko.supertonic.tts.utils.LexiconManager
import io.github.gregko.supertonic.tts.utils.QueueManager
import io.github.gregko.supertonic.tts.utils.SynthesisPreferences
import kotlinx.coroutines.*
import java.io.File

class MainActivity : ComponentActivity() {

    // UI State
    private var inputTextState = mutableStateOf("")
    private var isSynthesizingState = mutableStateOf(false) 
    private var isInitializingState = mutableStateOf(true) // Start true (initializing)

    // Settings State
    private var currentLangState = mutableStateOf("en")
    private var selectedVoiceFileState = mutableStateOf("M1.json")
    private var selectedVoiceFile2State = mutableStateOf("M2.json")
    private var isMixingEnabledState = mutableStateOf(false)
    private var mixAlphaState = mutableFloatStateOf(0.5f)
    private var currentSpeedState = mutableFloatStateOf(1.1f)
    private var currentStepsState = mutableIntStateOf(5)
    private var currentTemperatureState = mutableFloatStateOf(SynthesisPreferences.DEFAULT_TEMPERATURE)

    // Mini Player State
    private var showMiniPlayerState = mutableStateOf(false)
    private var miniPlayerTitleState = mutableStateOf("Now Playing")
    private var miniPlayerIsPlayingState = mutableStateOf(false)

    // Data
    private val voiceFiles = mutableStateMapOf<String, String>()
    private val languages = mapOf(
        "English" to "en",
        "French" to "fr",
        "Portuguese" to "pt",
        "Spanish" to "es",
        "Korean" to "ko"
    )

    private var currentModelVersion = AssetInstaller.preferredModelVersion("en")

    // Service
    private var playbackService: IPlaybackService? = null
    private var isBound = false

    // Dialog State
    private var showQueueDialog = mutableStateOf(false)
    private var queueDialogText = ""
    private var showAboutDialog = mutableStateOf(false)

    private val playbackListener = object : IPlaybackListener.Stub() {
        override fun onStateChanged(isPlaying: Boolean, hasContent: Boolean, isSynthesizing: Boolean) {
            runOnUiThread {
                miniPlayerIsPlayingState.value = isPlaying
                isSynthesizingState.value = isSynthesizing
                if (hasContent || isSynthesizing) {
                    showMiniPlayerState.value = true
                    val lastText = getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE).getString("last_text", "")
                    if (!lastText.isNullOrEmpty()) {
                        miniPlayerTitleState.value = lastText
                    }
                } else {
                    showMiniPlayerState.value = false
                }
            }
        }
        override fun onProgress(current: Int, total: Int) { }
        override fun onPlaybackStopped() {
            runOnUiThread {
                showMiniPlayerState.value = false
                miniPlayerIsPlayingState.value = false
            }
        }
        override fun onExportComplete(success: Boolean, path: String) { }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            playbackService = IPlaybackService.Stub.asInterface(service)
            isBound = true
            try {
                playbackService?.setListener(playbackListener)
            } catch (e: Exception) { e.printStackTrace() }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            playbackService = null
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val historyLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val selectedText = result.data?.getStringExtra("selected_text")
            if (!selectedText.isNullOrEmpty()) {
                inputTextState.value = selectedText
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        loadPreferences()
        checkNotificationPermission()

        val bindIntent = Intent(this, PlaybackService::class.java)
        bindService(bindIntent, connection, Context.BIND_AUTO_CREATE)

        LexiconManager.load(this)
        QueueManager.initialize(this)

        // Initial setup
        val savedLang = getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE).getString("selected_lang", "en") ?: "en"
        currentModelVersion = AssetInstaller.preferredModelVersion(savedLang)

        CoroutineScope(Dispatchers.IO).launch {
            val preparedModel = AssetInstaller.prepareModel(this@MainActivity, savedLang)
            withContext(Dispatchers.Main) {
                if (preparedModel == null) {
                    isInitializingState.value = false
                    Toast.makeText(
                        this@MainActivity,
                        "Model assets are missing from the APK.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@withContext
                }

                currentModelVersion = preparedModel.version
                setupVoicesMap()
            }

            if (preparedModel == null) {
                Log.e("MainActivity", "No compatible model assets available for language=$savedLang")
                return@launch
            }

            if (SupertonicTTS.initialize(preparedModel.modelPath, preparedModel.libPath)) {
                withContext(Dispatchers.Main) {
                    isInitializingState.value = false
                }
            } else {
                withContext(Dispatchers.Main) {
                    isInitializingState.value = false
                    Toast.makeText(
                        this@MainActivity,
                        "Failed to initialize the bundled TTS model.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        handleIntent(intent)
        checkResumeState()

        setContent {
            SupertonicTheme {
                if (showQueueDialog.value) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { showQueueDialog.value = false },
                        title = { Text(getString(R.string.playback_active_title)) },
                        text = { Text(getString(R.string.playback_active_message)) },
                        confirmButton = {
                            TextButton(onClick = {
                                addToQueue(queueDialogText)
                                showQueueDialog.value = false
                            }) { Text(getString(R.string.add_to_queue)) }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                playNow(queueDialogText)
                                showQueueDialog.value = false
                            }) { Text(getString(R.string.play_now)) }
                        }
                    )
                }

                if (showAboutDialog.value) {
                    AboutDialog(
                        appName = getString(R.string.app_name),
                        versionName = BuildConfig.VERSION_NAME,
                        repoUrl = BuildConfig.GITHUB_REPO_URL,
                        upstreamRepoLabel = BuildConfig.UPSTREAM_REPO_LABEL,
                        upstreamRepoUrl = BuildConfig.UPSTREAM_REPO_URL,
                        onDismiss = { showAboutDialog.value = false },
                        onOpenLicenses = { startActivity(Intent(this, LicensesActivity::class.java)) }
                    )
                }

                // Get localized placeholder
                val placeholder = getLocalizedResource(this, currentLangState.value, R.string.default_input_text)

                MainScreen(
                    inputText = inputTextState.value,
                    onInputTextChange = { inputTextState.value = it },
                    placeholderText = placeholder,
                    isSynthesizing = isSynthesizingState.value,
                    isInitializing = isInitializingState.value,
                    onSynthesizeClick = {
                        val textToPlay = inputTextState.value.ifEmpty { placeholder }
                        generateAndPlay(textToPlay)
                    },

                    languages = languages,
                    currentLangCode = currentLangState.value,
                    onLangChange = {
                        currentLangState.value = it
                        saveStringPref("selected_lang", it)
                        
                        currentModelVersion = AssetInstaller.preferredModelVersion(it)
                    },

                    voices = voiceFiles,
                    selectedVoiceFile = selectedVoiceFileState.value,
                    onVoiceChange = {
                        if (selectedVoiceFileState.value != it) {
                            selectedVoiceFileState.value = it
                            saveStringPref("selected_voice", it)
                            val resetIntent = Intent(this, PlaybackService::class.java).apply { action = "RESET_ENGINE" }
                            startService(resetIntent)
                        }
                    },

                    isMixingEnabled = isMixingEnabledState.value,
                    onMixingEnabledChange = { isMixingEnabledState.value = it },
                    selectedVoiceFile2 = selectedVoiceFile2State.value,
                    onVoice2Change = {
                        selectedVoiceFile2State.value = it
                        saveStringPref("selected_voice_2", it)
                    },
                    mixAlpha = mixAlphaState.value,
                    onMixAlphaChange = { mixAlphaState.value = it },

                    speed = currentSpeedState.value,
                    onSpeedChange = {
                        currentSpeedState.value = it
                        getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE).edit()
                            .putFloat("speed", it)
                            .apply()
                    },
                    temperature = currentTemperatureState.floatValue,
                    onTemperatureChange = {
                        val normalizedTemperature = SynthesisPreferences.normalizeTemperature(it)
                        currentTemperatureState.floatValue = normalizedTemperature
                        getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE).edit()
                            .putFloat(SynthesisPreferences.KEY_TEMPERATURE, normalizedTemperature)
                            .apply()
                    },
                    steps = currentStepsState.value,
                    onStepsChange = {
                        currentStepsState.value = it
                        getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE).edit().putInt("diffusion_steps", it).apply()
                    },

                    onResetClick = {
                        inputTextState.value = ""
                        val stopIntent = Intent(this, PlaybackService::class.java).apply { action = "STOP_PLAYBACK" }
                        startService(stopIntent)
                    },
                    onSavedAudioClick = { startActivity(Intent(this, SavedAudioActivity::class.java)) },
                    onHistoryClick = { historyLauncher.launch(Intent(this, HistoryActivity::class.java)) },
                    onQueueClick = { startActivity(Intent(this, QueueActivity::class.java)) },
                    onAboutClick = { showAboutDialog.value = true },
                    onLexiconClick = { startActivity(Intent(this, LexiconActivity::class.java)) },

                    showMiniPlayer = showMiniPlayerState.value,
                    miniPlayerTitle = miniPlayerTitleState.value,
                    miniPlayerIsPlaying = miniPlayerIsPlayingState.value,
                    onMiniPlayerClick = {
                        val intent = Intent(this, PlaybackActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                            
                            // Always pass current state to ensure PlaybackActivity can render/update
                            val lastText = getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE).getString("last_text", "") ?: ""
                            if (lastText.isNotEmpty()) {
                                putExtra(PlaybackActivity.EXTRA_TEXT, lastText)
                                
                                // Determine current voice path (might need to reconstruct based on selection)
                                var stylePath = buildStylePath(selectedVoiceFileState.value)
                                if (isMixingEnabledState.value) {
                                    val stylePath2 = buildStylePath(selectedVoiceFile2State.value)
                                    stylePath = "$stylePath;$stylePath2;${mixAlphaState.value}"
                                }
                                putExtra(PlaybackActivity.EXTRA_VOICE_PATH, stylePath)
                                
                                putExtra(PlaybackActivity.EXTRA_SPEED, currentSpeedState.value)
                                putExtra(PlaybackActivity.EXTRA_TEMPERATURE, currentTemperatureState.floatValue)
                                putExtra(PlaybackActivity.EXTRA_STEPS, currentStepsState.intValue)
                                putExtra(PlaybackActivity.EXTRA_LANG, currentLangState.value)
                                putExtra("is_resume", true)
                            }
                        }
                        startActivity(intent)
                    },
                    onMiniPlayerPlayPauseClick = {
                         if (playbackService?.isServiceActive == true) {
                            try {
                                if (miniPlayerIsPlayingState.value) playbackService?.pause() else playbackService?.play()
                            } catch (e: Exception) { e.printStackTrace() }
                        }
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (isBound && playbackService != null) {
            try {
                playbackService?.setListener(playbackListener)
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun getLocalizedResource(context: Context, localeCode: String, resId: Int): String {
        val locale = java.util.Locale.forLanguageTag(localeCode)
        val config = android.content.res.Configuration(context.resources.configuration)
        config.setLocale(locale)
        val localizedContext = context.createConfigurationContext(config)
        return localizedContext.getString(resId)
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE)
        currentStepsState.intValue = prefs.getInt("diffusion_steps", 5)
        currentTemperatureState.floatValue = SynthesisPreferences.getTemperature(prefs)
        currentLangState.value = prefs.getString("selected_lang", "en") ?: "en"
        selectedVoiceFileState.value = prefs.getString("selected_voice", "M1.json") ?: "M1.json"
        selectedVoiceFile2State.value = prefs.getString("selected_voice_2", "M2.json") ?: "M2.json"
        currentSpeedState.value = prefs.getFloat("speed", 1.1f)
    }

    private fun saveStringPref(key: String, value: String) {
        getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE).edit().putString(key, value).apply()
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun setupVoicesMap() {
        val voiceResources = mapOf(
            "M1.json" to R.string.voice_m1,
            "M2.json" to R.string.voice_m2,
            "M3.json" to R.string.voice_m3,
            "M4.json" to R.string.voice_m4,
            "M5.json" to R.string.voice_m5,
            "F1.json" to R.string.voice_f1,
            "F2.json" to R.string.voice_f2,
            "F3.json" to R.string.voice_f3,
            "F4.json" to R.string.voice_f4,
            "F5.json" to R.string.voice_f5
        )

        voiceResources.forEach { (filename, resId) ->
            voiceFiles[getString(resId)] = filename
        }

        val voiceDir = File(filesDir, "$currentModelVersion/voice_styles")
        if (voiceDir.exists()) {
            val files = voiceDir.listFiles { _, name -> name.endsWith(".json") }
            files?.forEach { file ->
                if (!voiceResources.containsKey(file.name)) {
                    val friendlyName = file.name.removeSuffix(".json")
                    voiceFiles[friendlyName] = file.name
                }
            }
        }
    }

    private fun buildStylePath(voiceFile: String): String {
        return AssetInstaller.resolveStyleFile(this, voiceFile, currentLangState.value).absolutePath
    }

    private fun buildSelectedStylePath(): String {
        var stylePath = buildStylePath(selectedVoiceFileState.value)
        if (isMixingEnabledState.value) {
            val stylePath2 = buildStylePath(selectedVoiceFile2State.value)
            if (File(stylePath2).exists()) {
                stylePath = "$stylePath;$stylePath2;${mixAlphaState.value}"
            }
        }
        return stylePath
    }

    private fun generateAndPlay(text: String) {
        var stylePath = buildStylePath(selectedVoiceFileState.value)
        if (!File(stylePath).exists()) {
             Toast.makeText(this, "Voice style not found", Toast.LENGTH_SHORT).show()
             return
        }

        if (isMixingEnabledState.value) {
            val stylePath2 = buildStylePath(selectedVoiceFile2State.value)
            if (File(stylePath2).exists()) {
                stylePath = "$stylePath;$stylePath2;${mixAlphaState.value}"
            }
        }
        
        // Generate friendly voice name
        val primaryVoiceName = voiceFiles.entries.find { it.value == selectedVoiceFileState.value }?.key ?: "Voice 1"
        val secondaryVoiceName = voiceFiles.entries.find { it.value == selectedVoiceFile2State.value }?.key ?: "Voice 2"
        val voiceName = if (isMixingEnabledState.value) "Mixed: $primaryVoiceName + $secondaryVoiceName" else primaryVoiceName

        HistoryManager.saveItem(this, text, voiceName)

        try {
            if (playbackService?.isServiceActive == true) {
                // Check if we are trying to play the exact same text that is currently active
                val lastText = getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE).getString("last_text", "")
                if (text == lastText) {
                    val intent = Intent(this, PlaybackActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        putExtra(PlaybackActivity.EXTRA_TEXT, text)
                        putExtra(PlaybackActivity.EXTRA_VOICE_PATH, stylePath)
                        putExtra(PlaybackActivity.EXTRA_SPEED, currentSpeedState.value)
                        putExtra(PlaybackActivity.EXTRA_TEMPERATURE, currentTemperatureState.floatValue)
                        putExtra(PlaybackActivity.EXTRA_STEPS, currentStepsState.intValue)
                        putExtra(PlaybackActivity.EXTRA_LANG, currentLangState.value)
                        putExtra("is_resume", true) // Hint to PlaybackActivity that we are resuming
                    }
                    startActivity(intent)
                    return
                }

                queueDialogText = text
                showQueueDialog.value = true
            } else {
                launchPlaybackActivity(text, stylePath)
            }
        } catch (e: Exception) {
            launchPlaybackActivity(text, stylePath)
        }
    }

    private fun addToQueue(text: String) {
        val stylePath = buildSelectedStylePath()

        try {
            playbackService?.addToQueue(
                text,
                currentLangState.value,
                stylePath,
                currentSpeedState.value,
                currentTemperatureState.floatValue,
                currentStepsState.intValue,
                0
            )
            Toast.makeText(this, getString(R.string.added_to_queue), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun playNow(text: String) {
        val stylePath = buildSelectedStylePath()
        launchPlaybackActivity(text, stylePath)
    }

    private fun launchPlaybackActivity(text: String, stylePath: String) {
        val intent = Intent(this, PlaybackActivity::class.java).apply {
            putExtra(PlaybackActivity.EXTRA_TEXT, text)
            putExtra(PlaybackActivity.EXTRA_VOICE_PATH, stylePath)
            putExtra(PlaybackActivity.EXTRA_SPEED, currentSpeedState.value)
            putExtra(PlaybackActivity.EXTRA_TEMPERATURE, currentTemperatureState.floatValue)
            putExtra(PlaybackActivity.EXTRA_STEPS, currentStepsState.intValue)
            putExtra(PlaybackActivity.EXTRA_LANG, currentLangState.value)
        }
        startActivity(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrEmpty()) {
                inputTextState.value = sharedText
            }
        } else {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.data?.getQueryParameter("text")
            if (!text.isNullOrEmpty()) {
                inputTextState.value = text
            }
        }
    }

    private fun checkResumeState() {
        val prefs = getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE)
        val lastText = prefs.getString("last_text", null)
        val isPlaying = prefs.getBoolean("is_playing", false)

        if (!lastText.isNullOrEmpty() && isPlaying) {
             AlertDialog.Builder(this)
                .setTitle(getString(R.string.resume_title))
                .setMessage(getString(R.string.resume_message))
                .setPositiveButton(getString(R.string.yes)) { _, _ ->
                    val intent = Intent(this, PlaybackActivity::class.java)
                    intent.putExtra("is_resume", true)
                    startActivity(intent)
                }
                .setNegativeButton(getString(R.string.no)) { _, _ ->
                    getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE).edit()
                        .putBoolean("is_playing", false)
                        .apply()
                    val stopIntent = Intent(this, PlaybackService::class.java)
                    stopIntent.action = "STOP_PLAYBACK"
                    startService(stopIntent)
                }
                .show()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        // scope.cancel() // Don't cancel IO scope to allow asset copy to finish if backgrounded
    }
}
