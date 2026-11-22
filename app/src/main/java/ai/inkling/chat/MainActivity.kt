package ai.inkling.chat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                App()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: AppViewModel = viewModel()) {
    var tab by remember { mutableIntStateOf(0) }
    val engineState by vm.engineState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Text(engineState.loadedModel ?: "Inkling Chat")
            })
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, label = { Text("Chat") }, icon = {})
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, label = { Text("Modelos") }, icon = {})
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, label = { Text("Bench") }, icon = {})
            }
        },
    ) { padding ->
        Surface(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> ChatScreen(vm)
                1 -> ModelsScreen(vm)
                2 -> BenchScreen(vm)
            }
        }
    }
}

@Composable
fun ChatScreen(vm: AppViewModel) {
    val chat by vm.chat.collectAsState()
    val input by vm.inputText.collectAsState()
    val state by vm.engineState.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(chat.size, chat.lastOrNull()?.content?.length) {
        if (chat.isNotEmpty()) listState.animateScrollToItem(chat.size - 1)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(chat) { msg ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            if (msg.role == "user") "Você" else "Modelo",
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(msg.content, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 12.dp)) }

        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { vm.inputText.value = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (state.loadedModel == null) "Carregue um modelo primeiro" else "Mensagem…") },
                enabled = state.loadedModel != null && !state.busy,
            )
            IconButton(
                onClick = { if (state.busy) vm.abort() else vm.send() },
                enabled = state.loadedModel != null,
            ) {
                Icon(if (state.busy) Icons.Filled.Stop else Icons.AutoMirrored.Filled.Send, contentDescription = null)
            }
        }
    }
}

@Composable
fun ModelsScreen(vm: AppViewModel) {
    val models by vm.models.collectAsState()
    val state by vm.engineState.collectAsState()
    val download by vm.downloadState.collectAsState()
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }

    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Parâmetros do motor", style = MaterialTheme.typography.titleMedium)
        SettingSlider("Threads: ${state.threads}", 1f, 8f, state.threads.toFloat(), steps = 6) { vm.setThreads(it.toInt()) }
        SettingSlider("Contexto: ${state.contextLength} tokens", 256f, 8192f, state.contextLength.toFloat()) { vm.setContextLength(it.toInt()) }
        SettingSlider("Temperatura: %.2f".format(state.temperature), 0f, 2f, state.temperature) { vm.setTemperature(it) }
        SettingSlider("Máx. tokens de resposta: ${state.maxTokens}", 64f, 4096f, state.maxTokens.toFloat()) { vm.setMaxTokens(it.toInt()) }

        Text("Modelos no aparelho", style = MaterialTheme.typography.titleMedium)
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(models) { m ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(m.name, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                        Text("%.2f GB".format(m.sizeBytes / 1e9), style = MaterialTheme.typography.labelSmall)
                        vm.warningsFor(m)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            androidx.compose.material3.TextButton(onClick = { vm.loadModel(m.name) }) {
                                Text(if (state.loadedModel == m.name) "Carregado" else if (state.loading) "…" else "Carregar")
                            }
                            androidx.compose.material3.TextButton(onClick = { vm.deleteModel(m.name) }) { Text("Apagar") }
                        }
                    }
                }
            }
        }

        Text("Baixar da Hugging Face", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(value = url, onValueChange = { url = it }, modifier = Modifier.fillMaxWidth(), label = { Text("URL (hf.co/…/resolve/main/….gguf)") })
        OutlinedTextField(value = name, onValueChange = { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Nome do arquivo local") })
        if (download.running) {
            Text("Baixando… %.1f%% (%.0f/%.0f MB)".format(download.progress * 100, download.bytesDownloaded / 1e6, download.totalBytes / 1e6))
        }
        download.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        androidx.compose.material3.Button(
            onClick = {
                val finalName = name.ifBlank { url.substringAfterLast('/') }
                if (url.isNotBlank()) vm.startDownload(url, finalName)
                url = ""
                name = ""
            },
            enabled = url.isNotBlank() && !download.running,
        ) { Text("Baixar") }
    }
}

@Composable
fun SettingSlider(label: String, from: Float, to: Float, value: Float, steps: Int = 0, onChange: (Float) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Slider(value = value, onValueChange = onChange, valueRange = from..to, steps = steps)
    }
}

@Composable
fun BenchScreen(vm: AppViewModel) {
    val bench by vm.benchState.collectAsState()
    val state by vm.engineState.collectAsState()

    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Benchmark de inferência", style = MaterialTheme.typography.titleMedium)
        state.loadedModel?.let {
            Text("Modelo: $it", style = MaterialTheme.typography.bodySmall)
        } ?: Text("Carregue um modelo na aba Modelos.", style = MaterialTheme.typography.bodySmall)

        androidx.compose.material3.Button(onClick = { vm.runBench() }, enabled = state.loadedModel != null && !bench.running) {
            Text(if (bench.running) "Medindo…" else "Rodar benchmark (7 amostras)")
        }
        bench.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        bench.report?.let { r ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Banda de RAM medida: %.1f GB/s".format(r.readBandwidthGBps), style = MaterialTheme.typography.bodySmall)
                    Text("Bytes do modelo: %.2f GB".format(r.modelBytes / 1e9), style = MaterialTheme.typography.bodySmall)
                    Text("Teto teórico decode: %.1f tok/s".format(r.ceilingTps), style = MaterialTheme.typography.bodySmall)
                    Text("Mediana medida: %.1f tok/s".format(r.medianTps), style = MaterialTheme.typography.bodySmall)
                    Text("%.1f%% do teto teórico".format(r.pctOfCeiling), style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Teto assume leitura de todos os pesos por token (pessimista para MoE: só experts ativos são lidos).",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        Text("Amostras desta rodada", style = MaterialTheme.typography.titleSmall)
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(bench.samples) { s ->
                Text(
                    "%.1f tok/s | %d tokens | TTFT ≈ prefill %d tok".format(s.tokensPerSecond, s.tokens, s.prefillTokens),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Text("Histórico completo em files/ledger.jsonl", style = MaterialTheme.typography.labelSmall)
    }
}
