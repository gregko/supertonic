package io.github.gregko.supertonic.tts.utils

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import java.io.File
import java.io.IOException

data class PreparedModel(
    val version: String,
    val modelPath: String,
    val libPath: String
)

object AssetInstaller {
    private const val TAG = "AssetInstaller"
    private const val MODEL_VERSION = "v2"
    private val installLock = Any()

    fun preferredModelVersion(language: String): String = MODEL_VERSION

    fun prepareModel(context: Context, language: String): PreparedModel? {
        synchronized(installLock) {
            ensureInstalled(context, MODEL_VERSION)
            if (hasInstalledModel(context, MODEL_VERSION)) {
                return buildPreparedModel(context, MODEL_VERSION)
            }
        }

        Log.e(TAG, "No compatible model assets are available for language=$language")
        return null
    }

    fun installBundledAssets(context: Context) {
        synchronized(installLock) {
            ensureInstalled(context, MODEL_VERSION)
        }
    }

    fun resolveStyleFile(context: Context, voiceFile: String, language: String): File {
        val targetFile = File(context.filesDir, "${preferredModelVersion(language)}/voice_styles/${File(voiceFile).name}")
        synchronized(installLock) {
            if (!targetFile.exists()) {
                ensureInstalled(context, MODEL_VERSION)
            }
        }
        return targetFile
    }

    fun normalizeStylePath(context: Context, stylePath: String, language: String): String {
        if (stylePath.isBlank()) {
            return stylePath
        }

        val parts = stylePath.split(';').toMutableList()
        val voicePartCount = minOf(2, parts.size)
        for (i in 0 until voicePartCount) {
            val candidate = parts[i].trim()
            if (candidate.endsWith(".json", ignoreCase = true)) {
                parts[i] = resolveStyleFile(context, candidate, language).absolutePath
            }
        }
        return parts.joinToString(";")
    }

    private fun buildPreparedModel(context: Context, version: String): PreparedModel {
        return PreparedModel(
            version = version,
            modelPath = File(context.filesDir, "$version/onnx").absolutePath,
            libPath = context.applicationInfo.nativeLibraryDir + "/libonnxruntime.so"
        )
    }

    private fun ensureInstalled(context: Context, version: String) {
        if (hasInstalledModel(context, version)) {
            return
        }

        val assetRoot = if (hasBundledModel(context.assets, version)) version else return

        Log.i(TAG, "Installing bundled model assets from $assetRoot into $version")
        copyModelRoot(context.assets, assetRoot, File(context.filesDir, version))
    }

    private fun hasBundledModel(assetManager: AssetManager, assetRoot: String): Boolean {
        return hasBundledFiles(assetManager, "$assetRoot/onnx") &&
            hasBundledFiles(assetManager, "$assetRoot/voice_styles")
    }

    private fun hasBundledFiles(assetManager: AssetManager, assetPath: String): Boolean {
        return try {
            val entries = assetManager.list(assetPath)
            entries != null && entries.isNotEmpty()
        } catch (_: IOException) {
            false
        }
    }

    private fun hasInstalledModel(context: Context, version: String): Boolean {
        val modelDir = File(context.filesDir, "$version/onnx")
        val voiceDir = File(context.filesDir, "$version/voice_styles")
        return hasInstalledFiles(modelDir) && hasInstalledFiles(voiceDir)
    }

    private fun hasInstalledFiles(dir: File): Boolean {
        return dir.isDirectory && (dir.listFiles()?.any { it.isFile } == true)
    }

    private fun copyModelRoot(assetManager: AssetManager, assetRoot: String, targetRoot: File) {
        ensureDirectory(targetRoot)
        copyDir(assetManager, "$assetRoot/onnx", File(targetRoot, "onnx"))
        copyDir(assetManager, "$assetRoot/voice_styles", File(targetRoot, "voice_styles"))
    }

    private fun copyDir(assetManager: AssetManager, assetPath: String, targetDir: File) {
        ensureDirectory(targetDir)

        val entries = assetManager.list(assetPath) ?: return
        for (entry in entries) {
            val sourcePath = "$assetPath/$entry"
            val childEntries = assetManager.list(sourcePath)
            if (childEntries != null && childEntries.isNotEmpty()) {
                copyDir(assetManager, sourcePath, File(targetDir, entry))
            } else {
                val targetFile = File(targetDir, entry)
                if (targetFile.isFile) {
                    continue
                }

                if (targetFile.exists()) {
                    targetFile.deleteRecursively()
                }

                ensureDirectory(targetFile.parentFile)

                assetManager.open(sourcePath).use { input ->
                    targetFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    private fun ensureDirectory(dir: File?) {
        if (dir == null) {
            return
        }

        if (dir.exists() && !dir.isDirectory) {
            Log.w(TAG, "Replacing legacy file with directory at ${dir.absolutePath}")
            dir.deleteRecursively()
        }

        if (!dir.exists()) {
            dir.mkdirs()
        }
    }
}
