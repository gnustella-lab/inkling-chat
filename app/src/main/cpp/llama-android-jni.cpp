#include <jni.h>
#include <android/log.h>
#define DEPRECATED(func, hint) func
#include <llama.h>
#undef DEPRECATED

#include <atomic>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <numeric>
#include <string>
#include <vector>

#define LOG_TAG "llama-android-jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct LlamaHandle {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    std::vector<llama_token> last_prompt_tokens;
    std::atomic<bool> abort_requested{false};
};

JavaVM * g_vm = nullptr;
jclass g_engine_class = nullptr;
jmethodID g_on_token_method = nullptr;
jmethodID g_on_log_method = nullptr;

std::string to_string(JNIEnv * env, jstring value) {
    if (value == nullptr) {
        return {};
    }
    const char * chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

void throw_illegal_state(JNIEnv * env, const std::string & message) {
    jclass ex = env->FindClass("java/lang/IllegalStateException");
    if (ex != nullptr) {
        env->ThrowNew(ex, message.c_str());
    }
}

void call_void_string(jmethodID method, const std::string & value) {
    JNIEnv * env = nullptr;
    bool attached = false;
    if (g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        g_vm->AttachCurrentThread(&env, nullptr);
        attached = true;
    }
    if (env != nullptr && g_engine_class != nullptr && method != nullptr) {
        jstring jvalue = env->NewStringUTF(value.c_str());
        env->CallStaticVoidMethod(g_engine_class, method, jvalue);
        env->DeleteLocalRef(jvalue);
    }
    if (attached) {
        g_vm->DetachCurrentThread();
    }
}

void emit_token(const std::string & piece) {
    call_void_string(g_on_token_method, piece);
}

void emit_log(const std::string & line) {
    call_void_string(g_on_log_method, line);
    LOGI("%s", line.c_str());
}

LlamaHandle * get_handle(JNIEnv * env, jlong ptr) {
    auto * handle = reinterpret_cast<LlamaHandle *>(ptr);
    if (handle == nullptr || handle->model == nullptr || handle->ctx == nullptr) {
        throw_illegal_state(env, "Model not loaded");
        return nullptr;
    }
    return handle;
}

} // namespace

jint JNI_OnLoad(JavaVM * vm, void *) {
    g_vm = vm;
    JNIEnv * env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    g_engine_class = reinterpret_cast<jclass>(env->NewGlobalRef(
        env->FindClass("ai/inkling/chat/LlamaEngine")));
    if (g_engine_class == nullptr) {
        return JNI_ERR;
    }
    g_on_token_method = env->GetStaticMethodID(g_engine_class, "nativeOnToken", "(Ljava/lang/String;)V");
    g_on_log_method = env->GetStaticMethodID(g_engine_class, "nativeOnLog", "(Ljava/lang/String;)V");
    if (g_on_token_method == nullptr || g_on_log_method == nullptr) {
        return JNI_ERR;
    }
    llama_backend_init();
    LOGI("llama.cpp %s initialized", llama_version());
    return JNI_VERSION_1_6;
}

namespace {

LlamaHandle * open_handle(JNIEnv * env, jstring jpath, jint context_length, jint threads) {
    std::string path = to_string(env, jpath);

    auto * handle = new LlamaHandle();

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;

    emit_log("Loading model from " + path);
    handle->model = llama_model_load_from_file(path.c_str(), model_params);
    if (handle->model == nullptr) {
        delete handle;
        throw_illegal_state(env, "Failed to load model: " + path);
        return nullptr;
    }

    char desc[512];
    llama_model_desc(handle->model, desc, sizeof(desc));
    emit_log("Model: " + std::string(desc));

    handle->vocab = llama_model_get_vocab(handle->model);

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = static_cast<uint32_t>(context_length);
    ctx_params.n_batch = static_cast<uint32_t>(context_length);
    ctx_params.n_threads = threads;
    ctx_params.n_threads_batch = threads;
    ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;

    handle->ctx = llama_init_from_model(handle->model, ctx_params);
    if (handle->ctx == nullptr) {
        llama_model_free(handle->model);
        delete handle;
        throw_illegal_state(env, "Failed to create context (not enough RAM?)");
        return nullptr;
    }

    emit_log("Context ready: " + std::to_string(llama_n_ctx(handle->ctx)) + " tokens, " +
             std::to_string(threads) + " threads");
    return handle;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_ai_inkling_chat_LlamaEngine_nativeOpenModel(
        JNIEnv * env, jclass, jstring path, jint context_length, jint threads) {
    LlamaHandle * handle = open_handle(env, path, context_length, threads);
    return reinterpret_cast<jlong>(handle);
}

JNIEXPORT void JNICALL
Java_ai_inkling_chat_LlamaEngine_nativeCloseModel(JNIEnv * env, jclass, jlong ptr) {
    auto * handle = reinterpret_cast<LlamaHandle *>(ptr);
    if (handle == nullptr) {
        return;
    }
    if (handle->ctx != nullptr) {
        llama_free(handle->ctx);
    }
    if (handle->model != nullptr) {
        llama_model_free(handle->model);
    }
    delete handle;
}

JNIEXPORT jint JNICALL
Java_ai_inkling_chat_LlamaEngine_nativeTokenCount(JNIEnv * env, jclass, jlong ptr, jstring text) {
    LlamaHandle * handle = get_handle(env, ptr);
    if (handle == nullptr) {
        return -1;
    }
    std::string value = to_string(env, text);
    const int n_tokens = -llama_tokenize(
        handle->vocab, value.c_str(), value.size(), nullptr, 0, true, true);
    return static_cast<jint>(n_tokens);
}

JNIEXPORT void JNICALL
Java_ai_inkling_chat_LlamaEngine_nativeAbort(JNIEnv *, jclass, jlong ptr) {
    auto * handle = reinterpret_cast<LlamaHandle *>(ptr);
    if (handle != nullptr) {
        handle->abort_requested.store(true);
    }
}

JNIEXPORT jlong JNICALL
Java_ai_inkling_chat_LlamaEngine_nativeModelSize(JNIEnv * env, jclass, jlong ptr) {
    LlamaHandle * handle = get_handle(env, ptr);
    if (handle == nullptr) {
        return 0;
    }
    return static_cast<jlong>(llama_model_size(handle->model));
}

JNIEXPORT jfloat JNICALL
Java_ai_inkling_chat_LlamaEngine_nativeModelNParams(JNIEnv * env, jclass, jlong ptr) {
    LlamaHandle * handle = get_handle(env, ptr);
    if (handle == nullptr) {
        return 0.0f;
    }
    return static_cast<jfloat>(llama_model_n_params(handle->model));
}

JNIEXPORT jstring JNICALL
Java_ai_inkling_chat_LlamaEngine_nativeChatTemplate(JNIEnv * env, jclass, jlong ptr) {
    LlamaHandle * handle = get_handle(env, ptr);
    if (handle == nullptr || handle->model == nullptr) {
        return env->NewStringUTF("");
    }
    char buf[8192];
    const int32_t n = llama_model_meta_val_str(
        handle->model, "tokenizer.chat_template", buf, sizeof(buf));
    if (n < 0) {
        const char * builtin = llama_model_chat_template(handle->model, nullptr);
        return env->NewStringUTF(builtin != nullptr ? builtin : "");
    }
    const size_t len = std::min<size_t>(static_cast<size_t>(n), sizeof(buf) - 1);
    return env->NewStringUTF(std::string(buf, len).c_str());
}

JNIEXPORT jfloat JNICALL
Java_ai_inkling_chat_LlamaEngine_nativeBandwidthGBps(JNIEnv * env, jclass, jint probe_mb) {
    const size_t bytes = static_cast<size_t>(probe_mb) * 1024 * 1024;
    uint8_t * buffer = static_cast<uint8_t *>(malloc(bytes));
    if (buffer == nullptr) {
        throw_illegal_state(env, "Could not allocate bandwidth probe buffer");
        return 0.0f;
    }
    for (size_t i = 0; i < bytes; i++) {
        buffer[i] = static_cast<uint8_t>(i);
    }
    volatile uint32_t sink = 0;
    float best = 0.0f;
    for (int pass = 0; pass < 3; pass++) {
        const auto start = std::chrono::steady_clock::now();
        uint32_t acc = 0;
        for (size_t i = 0; i + 3 < bytes; i += 4) {
            acc += static_cast<uint32_t>(
                buffer[i] | (buffer[i + 1] << 8) | (buffer[i + 2] << 16) | (buffer[i + 3] << 24));
        }
        sink = acc;
        const auto end = std::chrono::steady_clock::now();
        const double seconds = std::chrono::duration<double>(end - start).count();
        if (seconds > 0) {
            const float gbps = static_cast<float>(bytes / seconds / 1e9);
            if (gbps > best) {
                best = gbps;
            }
        }
    }
    free(buffer);
    (void) sink;
    emit_log("Measured read bandwidth: " + std::to_string(best) + " GB/s");
    return best;
}

JNIEXPORT jstring JNICALL
Java_ai_inkling_chat_LlamaEngine_nativeComplete(
        JNIEnv * env, jclass, jlong ptr, jstring prompt,
        jfloat temperature, jfloat top_p, jint max_tokens) {
    LlamaHandle * handle = get_handle(env, ptr);
    if (handle == nullptr) {
        return nullptr;
    }

    std::string prompt_text = to_string(env, prompt);

    std::vector<llama_token> tokens(prompt_text.size() + 1);
    int n_tokens = llama_tokenize(
        handle->vocab, prompt_text.c_str(), prompt_text.size(),
        tokens.data(), tokens.size(), true, true);
    if (n_tokens < 0) {
        tokens.resize(-n_tokens);
        n_tokens = llama_tokenize(
            handle->vocab, prompt_text.c_str(), prompt_text.size(),
            tokens.data(), tokens.size(), true, true);
    }
    if (n_tokens <= 0) {
        throw_illegal_state(env, "Failed to tokenize prompt");
        return nullptr;
    }
    tokens.resize(static_cast<size_t>(n_tokens));

    llama_memory_t memory = llama_get_memory(handle->ctx);
    llama_memory_seq_rm(memory, 0, -1, -1);
    handle->abort_requested.store(false);

    constexpr size_t kMaxBatch = 256;
    llama_batch batch = llama_batch_init(kMaxBatch, 0, 1);
    std::string output;

    llama_sampler * chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(chain, llama_sampler_init_dist(12345));
        llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(chain, llama_sampler_init_top_p(top_p, 1));
    }

    int64_t start_us = llama_time_us();
    int generated = 0;
    bool aborted = false;
    llama_token last_token = LLAMA_TOKEN_NULL;

    size_t pos = 0;
    while (generated < max_tokens) {
        size_t n_batch = std::min<size_t>(tokens.size() - pos, kMaxBatch);
        batch.n_tokens = static_cast<int32_t>(n_batch);
        for (size_t i = 0; i < n_batch; i++) {
            batch.token[i] = tokens[pos + i];
            batch.pos[i] = static_cast<llama_pos>(pos + i);
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = 0;
            batch.logits[i] = (i == n_batch - 1);
        }
        pos += n_batch;

        if (llama_decode(handle->ctx, batch) != 0) {
            throw_illegal_state(env, "llama_decode failed (context too small?)");
            llama_batch_free(batch);
            llama_sampler_free(chain);
            return env->NewStringUTF(output.c_str());
        }

        last_token = llama_sampler_sample(chain, handle->ctx, -1);
        if (llama_vocab_is_eog(handle->vocab, last_token)) {
            break;
        }

        char piece_buf[256];
        const int piece_len = llama_token_to_piece(
            handle->vocab, last_token, piece_buf, sizeof(piece_buf), 0, true);
        if (piece_len > 0) {
            output.append(piece_buf, static_cast<size_t>(piece_len));
            emit_token(std::string(piece_buf, static_cast<size_t>(piece_len)));
        }
        generated++;

        if (handle->abort_requested.load()) {
            aborted = true;
            break;
        }

        tokens = {last_token};
        pos = 0;
    }

    llama_memory_t mem = llama_get_memory(handle->ctx);
    llama_memory_seq_rm(mem, 0, -1, -1);

    int64_t total_us = llama_time_us() - start_us;
    double tps = generated > 0 && total_us > 0
        ? (generated * 1000000.0 / total_us)
        : 0.0;
    emit_log(std::string(aborted ? "Aborted" : "Done") + ": " +
             std::to_string(generated) + " tokens, " +
             std::to_string(tps) + " tokens/s");

    llama_batch_free(batch);
    llama_sampler_free(chain);
    return env->NewStringUTF(output.c_str());
}

} // extern "C"
