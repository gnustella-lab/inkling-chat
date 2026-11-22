package ai.inkling.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.withLock

object LlamaEngine {
    private var libLoaded = false
    private val listener = AtomicReference<((String) -> Unit)?>(null)
    private val logListener = AtomicReference<((String) -> Unit)?>(null)

    val logLines = MutableStateFlow<List<String>>(emptyList())

    init {
        synchronized(this) {
            if (!libLoaded) {
                System.loadLibrary("llama-android-jni")
                libLoaded = true
            }
        }
    }

    fun setTokenListener(cb: ((String) -> Unit)?) {
        listener.set(cb)
    }

    fun setLogListener(cb: ((String) -> Unit)?) {
        logListener.set(cb)
    }

    fun nativeOnToken(piece: String) {
        listener.get()?.invoke(piece)
    }

    fun nativeOnLog(line: String) {
        logListener.get()?.invoke(line)
        val current = logLines.value
        logLines.value = (current + line).takeLast(200)
    }

    class Session(
        val path: String,
        val contextLength: Int,
        private val ptr: Long,
    ) : AutoCloseable {
        private val lock = java.util.concurrent.locks.ReentrantLock()

        val modelSizeBytes: Long = nativeModelSize(ptr)
        val nParams: Float = nativeModelNParams(ptr)
        val chatTemplate: String = nativeChatTemplate(ptr)

        fun tokenCount(text: String): Int = nativeTokenCount(ptr, text)

        fun complete(
            prompt: String,
            temperature: Float,
            topP: Float,
            maxTokens: Int,
            onToken: (String) -> Unit,
        ): String = lock.withLock {
            setTokenListener(onToken)
            try {
                nativeComplete(ptr, prompt, temperature, topP, maxTokens) ?: ""
            } finally {
                setTokenListener(null)
            }
        }

        fun abort() {
            nativeAbort(ptr)
        }

        override fun close() {
            nativeCloseModel(ptr)
        }
    }

    fun openModel(path: String, contextLength: Int, threads: Int): Session {
        val ptr = nativeOpenModel(path, contextLength, threads)
        if (ptr == 0L) {
            throw IllegalStateException("Failed to open model: $path")
        }
        return Session(path, contextLength, ptr)
    }

    fun bandwidthGBps(probeMb: Int = 128): Float = nativeBandwidthGBps(probeMb)

    private external fun nativeOpenModel(path: String, contextLength: Int, threads: Int): Long
    private external fun nativeCloseModel(ptr: Long)
    private external fun nativeTokenCount(ptr: Long, text: String): Int
    private external fun nativeComplete(
        ptr: Long, prompt: String, temperature: Float, topP: Float, maxTokens: Int,
    ): String?
    private external fun nativeAbort(ptr: Long)
    private external fun nativeChatTemplate(ptr: Long): String
    private external fun nativeModelSize(ptr: Long): Long
    private external fun nativeModelNParams(ptr: Long): Float
    private external fun nativeBandwidthGBps(probeMb: Int): Float
}
