package com.itantra.app.core

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/** Stores independent offline language packs in app-private storage. */
class ModelPackManager(private val context: Context) {
    companion object {
        private const val PACK_DIR = "model-packs"
        const val ROOT_NAME = "models"
    }

    private val root = File(context.filesDir, PACK_DIR)

    fun rootDir(): File = root

    fun languageDir(language: SupportedLanguage): File = File(root, language.bcp47)

    fun isInstalled(language: SupportedLanguage): Boolean {
        val dir = languageDir(language)
        val stt = File(dir, "vosk/${language.bcp47}/am").exists()
        val tts = File(dir, "tts/${language.bcp47}/model.onnx").exists() &&
            File(dir, "tts/${language.bcp47}/tokens.txt").exists()
        return stt || tts
    }

    fun hasStt(language: SupportedLanguage): Boolean =
        File(languageDir(language), "vosk/${language.bcp47}/am").exists()

    fun hasTts(language: SupportedLanguage): Boolean =
        File(languageDir(language), "tts/${language.bcp47}/model.onnx").exists() &&
            File(languageDir(language), "tts/${language.bcp47}/tokens.txt").exists()

    fun sharedVadFile(): File = File(root, "shared/vad/silero_vad.onnx")

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

            val manifest = File(staging, "pack.json")
            require(manifest.exists()) { "Invalid iTantra pack: pack.json missing" }
            val text = manifest.readText()
            val language = Regex("\"language\"\\s*:\\s*\"([a-z-]+)\"").find(text)?.groupValues?.get(1)
                ?: error("Invalid iTantra pack: language missing")
            val stagedLanguage = File(staging, "models")
            require(stagedLanguage.isDirectory) { "Invalid iTantra pack: models directory missing" }

            val destination = languageDir(SupportedLanguage.values().firstOrNull { it.bcp47 == language }
                ?: error("Unsupported language pack: $language"))
            destination.deleteRecursively()
            destination.parentFile?.mkdirs()
            stagedLanguage.copyRecursively(destination, overwrite = true)

            val stagedShared = File(staging, "shared")
            if (stagedShared.isDirectory) {
                stagedShared.copyRecursively(File(root, "shared"), overwrite = true)
            }
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun safeTarget(base: File, entryName: String): File {
        val target = File(base, entryName)
        val basePath = base.canonicalPath + File.separator
        require(target.canonicalPath.startsWith(basePath)) { "Unsafe path in model pack: $entryName" }
        return target
    }
}
