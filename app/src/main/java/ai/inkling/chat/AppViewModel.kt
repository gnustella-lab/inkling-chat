package ai.inkling.chat

import android.app.ActivityManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class ChatMessage(val role: String, val content: String, val streaming: Boolean = false)

data class EngineState(
    val loadedModel: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val busy: Boolean = false,
    val threads: Int = 4,
    val contextLength: Int = 2048,
    val temperature: Float = 0.7f,
    val maxTokens: Int = 512,
)

data class BenchState(
    val running: Boolean = false,
    val bandwidthGBps: Double = 0.0,
    val report: BenchReport? = null,
    val samples: List<BenchSample> = emptyList(),
    val error: String? = null,
)

class AppViewModel(context: Context) : ViewModel() {

    private val appContext = context.applicationContext

    val engine = LlamaEngine
    val modelManager = ModelManager(File(appContext.filesDir, "models"))
    val benchEngine = BenchEngine(appContext)

    private var session: LlamaEngine.Session? = null
    private var generationJob: Job? = null
    private var benchJob: Job? = null

    val models = MutableStateFlow<List<ModelFile>>(emptyList())
    val engineState = MutableStateFlow(EngineState())
    val chat = MutableStateFlow<List<ChatMessage>>(emptyList())
    val inputText = MutableStateFlow("")

    val benchState = MutableStateFlow(BenchState())

    val downloadState: StateFlow<DownloadState> = modelManager.downloads

    val totalRamBytes: Long = run {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        info.totalMem
    }

    init {
        refreshModels()
    }

    fun refreshModels() {
        models.value = modelManager.listModels()
    }

    fun setThreads(value: Int) = engineState.update { it.copy(threads = value.coerceIn(1, 8)) }

    fun setContextLength(value: Int) = engineState.update { it.copy(contextLength = value.coerceIn(256, 16384)) }

    fun setTemperature(value: Float) = engineState.update { it.copy(temperature = value.coerceIn(0.0f, 2.0f)) }

    fun setMaxTokens(value: Int) = engineState.update { it.copy(maxTokens = value.coerceIn(32, 4096)) }

    fun warningsFor(model: ModelFile): String? {
        val limit = totalRamBytes * 0.8
        return if (model.sizeBytes > limit) {
            "%.1f GB de pesos > %.1f GB (80%% de %.1f GB de RAM). Este aparelho quase certamente não conseguirá carregar este modelo.".format(
                model.sizeBytes / 1e9, limit / 1e9, totalRamBytes / 1e9)
        } else {
            null
        }
    }

    fun loadModel(name: String) {
        if (engineState.value.busy || engineState.value.loading) return
        engineState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                session?.close()
                session = null
                val file = modelManager.fileFor(name)
                session = LlamaEngine.openModel(file.absolutePath, engineState.value.contextLength, engineState.value.threads)
                engineState.update { it.copy(loading = false, loadedModel = name, error = null) }
                chat.value = emptyList()
                benchState.update { it.copy(report = null, samples = emptyList()) }
            } catch (t: Throwable) {
                engineState.update { it.copy(loading = false, error = t.message) }
            }
        }
    }

    fun closeModel() {
        session?.close()
        session = null
        engineState.update { EngineState(threads = engineState.value.threads, contextLength = engineState.value.contextLength, temperature = engineState.value.temperature, maxTokens = engineState.value.maxTokens) }
    }

    fun send() {
        val text = inputText.value.trim()
        val s = session ?: run {
            engineState.update { it.copy(error = "Nenhum modelo carregado") }
            return
        }
        if (text.isEmpty() || engineState.value.busy) return

        inputText.value = ""
        val current = chat.value.filter { !it.streaming }
        val history = (current + ChatMessage("user", text))
        val formatted = ChatFormatter.format(
            s.chatTemplate,
            history.map { ChatFormatter.Message(it.role, it.content) },
        )
        chat.value = history + ChatMessage("assistant", "", streaming = true)
        engineState.update { it.copy(busy = true, error = null) }

        generationJob = viewModelScope.launch {
            try {
                val sb = StringBuilder()
                s.complete(
                    formatted,
                    engineState.value.temperature,
                    0.95f,
                    engineState.value.maxTokens,
                ) { piece ->
                    sb.append(piece)
                    val msgs = chat.value
                    val last = msgs.lastOrNull()
                    if (last?.streaming == true) {
                        chat.value = msgs.dropLast(1) + last.copy(content = sb.toString())
                    }
                }
            } catch (t: Throwable) {
                engineState.update { it.copy(error = t.message) }
            } finally {
                chat.value = chat.value.map { it.copy(streaming = false) }
                engineState.update { it.copy(busy = false) }
            }
        }
    }

    fun abort() {
        session?.abort()
        generationJob?.cancel()
        chat.value = chat.value.map { it.copy(streaming = false) }
        engineState.update { it.copy(busy = false) }
    }

    fun deleteModel(name: String) {
        if (engineState.value.loadedModel == name) {
            closeModel()
        }
        modelManager.delete(name)
        refreshModels()
    }

    fun startDownload(url: String, fileName: String) {
        viewModelScope.launch {
            try {
                modelManager.download(url, fileName)
                refreshModels()
            } catch (_: Throwable) {
            }
        }
    }

    fun runBench() {
        val s = session ?: run {
            benchState.update { it.copy(error = "Carregue um modelo primeiro") }
            return
        }
        if (benchState.value.running) return
        benchState.update { it.copy(running = true, error = null, samples = emptyList()) }
        benchJob = viewModelScope.launch {
            try {
                val bw = LlamaEngine.bandwidthGBps(128).toDouble()
                benchState.update { it.copy(bandwidthGBps = bw) }
                val report = benchEngine.run(s, bw) { sample ->
                    benchState.update { it.copy(samples = it.samples + sample) }
                }
                benchState.update { it.copy(running = false, report = report) }
            } catch (t: Throwable) {
                benchState.update { it.copy(running = false, error = t.message) }
            }
        }
    }

    override fun onCleared() {
        session?.close()
        session = null
    }
}
