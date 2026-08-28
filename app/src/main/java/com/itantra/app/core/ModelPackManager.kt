package com.itantra.app.core

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/** Stores model packs in app-private storage so the APK can remain small. */
class ModelPackManager(private val context: Context) {
    companion object {
        private const val PACK_DIR = "model-packs/demo"
        const val ROOT_NAME = "models"
    }

    private val root = File(context.filesDir, PACK_DIR)

    fun rootDir(): File = File(root, ROOT_NAME)

    fun isInstalled(language: SupportedLanguage): Boolean {
        val models = rootDir()
        return File(models, "vosk/${language.bcp47}/am").exists() &&
            File(models, "tts/${language.bcp47}/model.onnx").exists() &&
            File(models, "tts/${language.bcp47}/tokens.txt").exists() &&
            File(models, "vad/silero_vad.onnx").exists()
    }

    fun hasAnyPack(): Boolean = File(rootDir(), "vad/silero_vad.onnx").exists()

    @Synchronized
    fun installPack(uri: Uri) {
        val staging = File(context.cacheDir, "model-pack-staging")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            context.contentResolver.openInputStream(uri).use { raw ->
                requireNotNull(raw) { "Unable to open selected model pack" }
                ZipInputStream(raw.buffered()).use { zip ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val entry: ZipEntry = zip.nextEntry ?: break
                        if (entry.isDirectory) continue
                        val target = safeTarget(staging, entry.name)
                        target.parentFile?.mkdirs()
                        target.outputStream().buffered().use { out ->
                            while (true) {
                                val read = zip.read(buffer)
                                if (read <= 0) break
                                out.write(buffer, 0, read)
                            }
                        }
                    }
                }
            }

            val stagedModels = File(staging, ROOT_NAME)
            require(File(stagedModels, "vad/silero_vad.onnx").exists()) {
                "Invalid iTantra model pack: VAD model missing"
            }
            require(File(stagedModels, "vosk").isDirectory) {
                "Invalid iTantra model pack: Vosk models missing"
            }
            require(File(stagedModels, "tts").isDirectory) {
                "Invalid iTantra model pack: TTS models missing"
            }

            val destination = root
            destination.deleteRecursively()
            destination.parentFile?.mkdirs()
            stagedModels.copyRecursively(File(destination, ROOT_NAME), overwrite = true)
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun safeTarget(base: File, entryName: String): File {
        val target = File(base, entryName)
        val basePath = base.canonicalPath + File.separator
        require(target.canonicalPath.startsWith(basePath)) {
            "Unsafe path in model pack: $entryName"
        }
        return target
    }
}
