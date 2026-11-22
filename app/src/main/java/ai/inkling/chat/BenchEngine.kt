package ai.inkling.chat

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class BenchSample(
    val timestamp: Long,
    val model: String,
    val threads: Int,
    val contextLength: Int,
    val temperature: Float,
    val tokens: Int,
    val tokensPerSecond: Double,
    val prefillTokens: Int,
    val prefillMs: Long,
)

data class BenchReport(
    val readBandwidthGBps: Double,
    val modelBytes: Long,
    val ceilingTps: Double,
    val medianTps: Double,
    val bestTps: Double,
    val medianTtftMs: Long,
    val samples: List<BenchSample>,
) {
    val pctOfCeiling: Double get() = if (ceilingTps > 0) 100.0 * medianTps / ceilingTps else 0.0
}

class BenchEngine(context: Context) {

    private val ledgerFile = File(context.filesDir, "ledger.jsonl")

    fun appendLedger(sample: BenchSample) {
        val json = JSONObject().apply {
            put("ts", sample.timestamp)
            put("model", sample.model)
            put("threads", sample.threads)
            put("ctx", sample.contextLength)
            put("temp", sample.temperature.toDouble())
            put("tokens", sample.tokens)
            put("tps", sample.tokensPerSecond)
            put("prefill_tokens", sample.prefillTokens)
            put("prefill_ms", sample.prefillMs.toDouble())
        }
        ledgerFile.appendText(json.toString() + "\n")
    }

    fun readLedger(): List<BenchSample> =
        if (ledgerFile.exists()) {
            ledgerFile.readLines().filter { it.isNotBlank() }.mapNotNull { line ->
                runCatching {
                    val j = JSONObject(line)
                    BenchSample(
                        timestamp = j.getLong("ts"),
                        model = j.getString("model"),
                        threads = j.optInt("threads", 0),
                        contextLength = j.optInt("ctx", 0),
                        temperature = j.optDouble("temp", 0.0).toFloat(),
                        tokens = j.optInt("tokens", 0),
                        tokensPerSecond = j.optDouble("tps", 0.0),
                        prefillTokens = j.optInt("prefill_tokens", 0),
                        prefillMs = j.optLong("prefill_ms", 0),
                    )
                }.getOrNull()
            }
        } else {
            emptyList()
        }

    fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }

    fun standardError(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        val variance = values.sumOf { (it - mean) * (it - mean) } / (values.size - 1)
        return sqrt(variance) / sqrt(values.size.toDouble())
    }

    suspend fun measure(
        session: LlamaEngine.Session,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        onSample: (BenchSample) -> Unit,
    ): BenchSample = withContext(Dispatchers.Default) {
        val prefillTokens = session.tokenCount(prompt)
        val promptStart = System.nanoTime()
        val tokenTimes = mutableListOf<Long>()
        val output = session.complete(prompt, temperature, 1.0f, maxTokens) { _ ->
            tokenTimes.add(System.nanoTime())
        }
        val ttftMs = if (tokenTimes.isEmpty()) 0L else (tokenTimes.first() - promptStart) / 1_000_000
        val decodeSpanMs = if (tokenTimes.size < 2) 0L else (tokenTimes.last() - tokenTimes.first()) / 1_000_000
        val generatedTokens = if (output.isEmpty()) 0 else tokenTimes.size
        val tps = if (tokenTimes.size >= 2 && decodeSpanMs > 0) {
            (tokenTimes.size - 1) * 1000.0 / decodeSpanMs
        } else {
            0.0
        }
        val sample = BenchSample(
            timestamp = System.currentTimeMillis(),
            model = session.path.substringAfterLast('/'),
            threads = Runtime.getRuntime().availableProcessors(),
            contextLength = session.contextLength,
            temperature = temperature,
            tokens = generatedTokens,
            tokensPerSecond = (tps * 100).roundToInt() / 100.0,
            prefillTokens = prefillTokens,
            prefillMs = ttftMs,
        )
        appendLedger(sample)
        onSample(sample)
        sample
    }

    suspend fun run(
        session: LlamaEngine.Session,
        bandwidthGBps: Double,
        samples: Int = 7,
        maxTokens: Int = 64,
        prompt: String = "Conte uma história curta sobre um robô explorador.",
        onSample: (BenchSample) -> Unit,
    ): BenchReport = withContext(Dispatchers.Default) {
        val measured = mutableListOf<BenchSample>()
        repeat(samples) {
            val s = measure(session, prompt, maxTokens, 0.0f, onSample)
            measured.add(s)
        }
        val tpsValues = measured.map { it.tokensPerSecond }
        val ceiling = if (bandwidthGBps > 0 && session.modelSizeBytes > 0) {
            bandwidthGBps * 1e9 / session.modelSizeBytes
        } else {
            0.0
        }
        BenchReport(
            readBandwidthGBps = bandwidthGBps,
            modelBytes = session.modelSizeBytes,
            ceilingTps = ceiling,
            medianTps = median(tpsValues),
            bestTps = tpsValues.maxOrNull() ?: 0.0,
            medianTtftMs = measured.map { it.prefillMs }.sorted().let {
                if (it.isEmpty()) 0L else if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2
            },
            samples = measured,
        )
    }
}
