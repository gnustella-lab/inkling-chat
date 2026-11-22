package ai.inkling.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class ModelFile(val name: String, val sizeBytes: Long)

data class DownloadState(
    val url: String = "",
    val fileName: String = "",
    val bytesDownloaded: Long = 0,
    val totalBytes: Long = -1,
    val running: Boolean = false,
    val error: String? = null,
) {
    val progress: Float
        get() = if (totalBytes > 0) bytesDownloaded.toFloat() / totalBytes else 0f
}

class ModelManager(private val modelsDir: File) {

    private val _downloads = MutableStateFlow(DownloadState())
    val downloads: StateFlow<DownloadState> = _downloads

    init {
        modelsDir.mkdirs()
    }

    fun listModels(): List<ModelFile> =
        modelsDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".gguf") }
            ?.map { ModelFile(it.name, it.length()) }
            ?.sortedBy { it.name }
            ?: emptyList()

    fun fileFor(name: String): File = File(modelsDir, name)

    fun delete(name: String): Boolean = fileFor(name).delete()

    suspend fun download(url: String, fileName: String, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        withContext(Dispatchers.IO) {
            val target = File(modelsDir, fileName)
            _downloads.value = DownloadState(url = url, fileName = fileName, running = true)
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30000
                    readTimeout = 60000
                    setRequestProperty("User-Agent", "InklingChat/0.1")
                    instanceFollowRedirects = true
                }
                val code = connection.responseCode
                if (code !in 200..299) {
                    throw IOException("HTTP $code para $url")
                }
                val total = connection.contentLengthLong
                _downloads.value = _downloads.value.copy(totalBytes = total)

                connection.inputStream.use { input ->
                    val tmp = File(modelsDir, "$fileName.part")
                    tmp.outputStream().use { out ->
                        val buffer = ByteArray(1 shl 16)
                        var read: Int
                        var downloaded = 0L
                        while (true) {
                            read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            downloaded += read
                            _downloads.value = _downloads.value.copy(bytesDownloaded = downloaded)
                            onProgress(downloaded, total)
                        }
                    }
                    if (target.exists()) target.delete()
                    if (!tmp.renameTo(target)) {
                        tmp.copyTo(target, overwrite = true)
                        tmp.delete()
                    }
                }
                _downloads.value = DownloadState()
            } catch (t: Throwable) {
                _downloads.value = _downloads.value.copy(running = false, error = t.message)
                throw t
            } finally {
                connection?.disconnect()
            }
        }
    }
}
