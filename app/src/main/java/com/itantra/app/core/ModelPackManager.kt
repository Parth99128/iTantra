package com.itantra.app.core

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import org.json.JSONObject

/** Stores independently installed offline language packs in app-private storage. */
class ModelPackManager(private val context: Context) {
    companion object { private const val PACK_DIR = "model-packs" }
    private val root = File(context.filesDir, PACK_DIR)

    fun rootDir(): File = root
    fun languageDir(language: SupportedLanguage): File = root

    fun isInstalled(language: SupportedLanguage): Boolean = hasStt(language) || hasTts(language)
    fun hasStt(language: SupportedLanguage): Boolean = File(root, "vosk/${language.bcp47}/am").exists()
    fun hasTts(language: SupportedLanguage): Boolean =
        File(root, "tts/${language.bcp47}/model.onnx").exists() &&
            File(root, "tts/${language.bcp47}/tokens.txt").exists()
    fun sharedVadFile(): File = File(root, "vad/silero_vad.onnx")

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
            val metadata = JSONObject(manifest.readText())
            val language = metadata.optString("language")
            require(SupportedLanguage.values().any { it.bcp47 == language }) {
                "Unsupported language pack: $language"
            }
            val stagedModels = File(staging, "models")
            require(stagedModels.isDirectory) { "Invalid iTantra pack: models directory missing" }

            // Merge this pack into the shared local store so Hindi/English/Marathi
            // can be installed independently without deleting previously installed packs.
            mergeRecursively(stagedModels, root)
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun mergeRecursively(source: File, destination: File) {
        if (source.isDirectory) {
            destination.mkdirs()
            source.listFiles()?.forEach { mergeRecursively(it, File(destination, it.name)) }
        } else {
            source.inputStream().use { input ->
                destination.parentFile?.mkdirs()
                destination.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    private fun safeTarget(base: File, entryName: String): File {
        val target = File(base, entryName)
        val basePath = base.canonicalPath + File.separator
        require(target.canonicalPath.startsWith(basePath)) { "Unsafe path in model pack: $entryName" }
        return target
    }
}
