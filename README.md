# Inkling Chat

App Android (Kotlin + Jetpack Compose) que roda **modelos de IA 100% locais** no aparelho, via [llama.cpp](https://github.com/ggml-org/llama.cpp) compilado direto no build NDK (ABI `arm64-v8a` / `x86_64`). Sem nuvem, sem API: o modelo inteiro roda no CPU do seu celular.

Foi pensado para o **Inkling-2-Mini** ([thinkingmachines/Inkling-2-Mini](https://huggingface.co/thinkingmachines/Inkling-2-Mini)): o runtime já embute um llama.cpp recente, que inclui a arquitetura `inkling2_loop`. **Mas leia o aviso abaixo antes de tentar.**

## ⚠️ Inkling-2-Mini não cabe em celular nenhum (hoje)

| Tamanho da menor quantização GGUF | Especificação do modelo |
| --- | --- |
| **~17,6 GB** (IQ4_NL, `ggml-org/Inkling-2-Mini-GGUF`) | 26,4B parâmetros, MoE (5,4B ativos/token), multimodal |

Flagships atuais têm 12–16 GB de RAM **total**, compartilhada com o sistema. O modelo nem carrega (o app mostra um aviso quando o arquivo excede 80% da RAM). O uso realista do Inkling-2-Mini é em desktops com 32 GB+. Neste app, ele funcionará em tablets/Chromebooks futuros com 24 GB+ de RAM — e em aparelhos com pouca RAM, use as sugestões abaixo.

## Modelos que funcionam bem no celular

Baixe na aba **Modelos** colando a URL `resolve/main` do Hugging Face (ou copie um `.gguf` para a pasta do app). Sugestões (Q4_K_M / Q4_0):

| Modelo | Aprox. | RAM mínima |
| --- | --- | --- |
| Qwen3 1.7B | ~1 GB | 4 GB |
| Llama 3.2 3B | ~2 GB | 6 GB |
| Gemma 3 4B | ~2,5 GB | 8 GB |
| Qwen3 4B / Phi-4-mini | ~2,5 GB | 8 GB |

## O que o app tem

- **Chat** com streaming token a token, temperatura/top-p/máx. tokens ajustáveis, botão de abortar.
- **Modelos**: download direto da Hugging Face com progresso, listagem, aviso de RAM insuficiente, parâmetros do motor (threads, contexto).
- **Bench** (metodologia de hill-climbing aplicada ao device):
  - sonda nativa de banda de RAM → **teto teórico de tok/s** (decode é limitado por banda: bytes do modelo ÷ GB/s medidos);
  - 7 amostras decodificadas com **mediana** e registro por amostra;
  - histórico completo em `files/ledger.jsonl` (uma linha JSON por amostra) para comparação A/B de configurações no mesmo aparelho.
  - Como otimizar no seu aparelho (uma mudança por vez): threads (2→4→6), contexto menor, quantizações menores. Se a mediana não sair de ±10%, é ruído.

Nota: o teto teórico assume leitura de **todos** os pesos por token — correto para modelos densos, pessimista para MoE (só os experts ativos são lidos).

## Build

Requisitos: Android SDK 35, NDK 27, CMake 3.22.1, JDK 17+.

```bash
gradle :app:assembleDebug
```

O llama.cpp é baixado pelo CMake (`LLAMA_CPP_TAG`, default `master` — ref com suporte a `inkling2_loop`; fixe um tag/commit para builds reprodutíveis).

Ou sem instalar nada: o workflow **Build APK** (`.github/workflows/build-apk.yml`) roda a cada push e publica os APKs como artifacts do GitHub Actions.

## Limitações conhecidas

- Apenas modo texto (o Inkling é multimodal; a torre de visão não é carregada).
- Formatação de chat por heurística (ChatML, Llama-3, Gemma, Phi-3; demais modelos usam formato simples).
- A conversa é re-tokenizada a cada turno (sem KV cache entre turnos) — troca de simplicidade por velocidade de prefill.

## Licença

Código do app: MIT. O llama.cpp e o Inkling-2-Mini têm suas próprias licenças (MIT e Apache-2.0, respectivamente).
