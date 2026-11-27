package com.geniex.demo

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.geniex.sdk.GenieXSdk
import com.geniex.sdk.LlmWrapper
import com.geniex.sdk.ModelManagerWrapper
import com.geniex.sdk.bean.ChatMessage
import com.geniex.sdk.bean.LlmCreateInput
import com.geniex.sdk.bean.LlmStreamResult
import com.geniex.sdk.bean.ModelConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

// Same UID, separate process: model files remain in the existing app sandbox.
// Messenger messages contain only strings/numbers, never native handles.
internal object InferenceProtocol {
    const val LOAD = 1
    const val GENERATE = 2
    const val RESET = 3
    const val UNLOAD = 4
    const val STOP = 5
}

class TextInferenceService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val serial = Mutex()
    private val ready = CompletableDeferred<Unit>()
    @Volatile private var engine: LlmWrapper? = null
    @Volatile private var generating = false
    private val history = arrayListOf<ChatMessage>()
    private fun memoryReport(): String {
        val info = android.os.Debug.MemoryInfo()
        android.os.Debug.getMemoryInfo(info)
        val values = runCatching {
            java.io.File("/proc/self/status").readLines().filter {
                it.startsWith("VmRSS:") || it.startsWith("VmHWM:")
            }.associate { it.substringBefore(':') to it.substringAfter(':').trim().substringBefore(' ').toLong() }
        }.getOrDefault(emptyMap())
        return "Worker PSS ${info.totalPss / 1024} MiB | RSS ${values["VmRSS"]?.div(1024) ?: "?"} MiB | RSS lifetime peak ${values["VmHWM"]?.div(1024) ?: "?"} MiB"
    }
    private val receiver = Messenger(Handler(Looper.getMainLooper()) { msg ->
        val op = msg.what
        val id = msg.arg1
        val args = Bundle(msg.data)
        val reply = msg.replyTo
        if (reply != null) scope.launch {
            fun send(kind: String, text: String = "") {
                val response = Message.obtain(null, 0)
                response.arg1 = id
                response.data = Bundle().apply {
                    putString("kind", kind)
                    putString("text", text)
                }
                reply.send(response)
            }
            try {
                if (op == InferenceProtocol.STOP) {
                    if (generating) engine?.stopStream()
                    send("done")
                } else serial.withLock {
                    withTimeout(30_000) { ready.await() }
                    when (op) {
                        InferenceProtocol.LOAD -> {
                            check(engine == null) { "Unload the current model first" }
                            val paths = ModelManagerWrapper.getPaths(requireNotNull(args.getString("model")))
                                ?: error("Model paths unavailable in inference process")
                            val runtime = paths.runtime_id.ifEmpty { requireNotNull(args.getString("runtime")) }
                            val qairt = runtime == "qairt"
                            val conf = ModelConfig(
                                nCtx = if (qairt) 0 else args.getInt("context", 1024),
                                nGpuLayers = if (qairt) 0 else args.getInt("layers"),
                                enable_thinking = args.getBoolean("thinking"))
                            engine = LlmWrapper.builder().llmCreateInput(LlmCreateInput(
                                model_name = paths.model_name, model_path = paths.model_path,
                                tokenizer_path = paths.tokenizer_path, config = conf, runtime_id = runtime,
                                compute_unit = requireNotNull(args.getString("device")))).build().getOrThrow()
                            history.clear()
                            send("loaded", runtime + " | worker PID " + android.os.Process.myPid())
                            send("memory", memoryReport())
                        }
                        InferenceProtocol.GENERATE -> {
                            val current = checkNotNull(engine) { "No model loaded" }
                            val user = requireNotNull(args.getString("prompt"))
                            check(user.length <= 32768) { "Prompt too long" }
                            val previousSize = history.size
                            history.add(ChatMessage("user", user))
                            val output = StringBuilder()
                            var completed = false
                            generating = true
                            try {
                                val formatted = current.applyChatTemplate(history.toTypedArray(), null,
                                    args.getBoolean("thinking")).getOrThrow()
                                current.generateStreamFlow(formatted.formattedText,
                                    GenerationConfigSample(maxTokens = args.getInt("maxTokens", 2048).coerceIn(1, 2048)).toGenerationConfig()).collect { event ->
                                    when (event) {
                                        is LlmStreamResult.Token -> {
                                            output.append(event.text)
                                            send("token", event.text)
                                        }
                                        is LlmStreamResult.Completed -> {
                                            history.add(ChatMessage("assistant", output.toString()))
                                            completed = true
                                            val p = event.profile
                                            send("profile", String.format(Locale.US,
                                                "TTFT: %.2f ms; Prompt Tokens: %s; Prefilling Speed: %.2f tok/s\nGenerated Tokens: %s; Decoding Speed: %.2f tok/s",
                                                p.ttftMs, p.promptTokens, p.prefillSpeed, p.generatedTokens, p.decodingSpeed))
                                        }
                                        is LlmStreamResult.Error -> throw event.throwable
                                    }
                                }
                                check(completed) { "Generation stopped before completion" }
                                send("memory", memoryReport())
                            } finally {
                                generating = false
                                if (!completed) {
                                    while (history.size > previousSize) history.removeAt(history.lastIndex)
                                    // Clear native conversation state before any retry.
                                    current.reset()
                                }
                            }
                        }
                        InferenceProtocol.RESET -> {
                            checkNotNull(engine).reset()
                            history.clear()
                        }
                        InferenceProtocol.UNLOAD -> {
                            engine?.destroy()
                            engine = null
                            history.clear()
                        }
                        else -> error("Unknown inference operation")
                    }
                    send("done")
                }
            } catch (e: Exception) {
                runCatching { send("error", e.message ?: e.javaClass.simpleName) }
            }
        }
        true
    })

    override fun onCreate() {
        super.onCreate()
        GenieXSdk.getInstance().init(this, object : GenieXSdk.InitCallback {
            override fun onSuccess() { ready.complete(Unit) }
            override fun onFailure(reason: String) { ready.completeExceptionally(IllegalStateException(reason)) }
        })
    }
    override fun onBind(intent: Intent?): IBinder = receiver.binder
    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
        // A blocked native operation cannot safely be destroyed on another thread.
        // Terminate only the dedicated worker when its final binding is removed.
        if (android.app.Application.getProcessName() == packageName + ":inference")
            android.os.Process.killProcess(android.os.Process.myPid())
    }
}

internal class TextInferenceClient(private val context: Context, private val onLost: () -> Unit) {
    private var remote: Messenger? = null
    private var binding = false
    private var connected = CompletableDeferred<Messenger>()
    private var nextId = 0
    private val pending = mutableMapOf<Int, Channel<Bundle>>()
    private val receiver = Messenger(Handler(Looper.getMainLooper()) { msg ->
        pending[msg.arg1]?.trySend(Bundle(msg.data))
        true
    })
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (!binding) return
            val messenger = Messenger(binder)
            remote = messenger
            connected.complete(messenger)
        }
        override fun onServiceDisconnected(name: ComponentName) = lost()
        override fun onBindingDied(name: ComponentName) = lost()
        override fun onNullBinding(name: ComponentName) = lost()
    }
    private fun lost() {
        val wasBound = binding
        close()
        if (wasBound) onLost()
    }
    // All state accessed only on the main dispatcher, including Binder callbacks.
    fun close() {
        val failure = IllegalStateException("Inference process disconnected")
        remote = null
        connected.completeExceptionally(failure)
        pending.values.forEach { it.close(failure) }
        pending.clear()
        if (binding) {
            binding = false
            runCatching { context.unbindService(connection) }
        }
    }
    private suspend fun connect(): Messenger {
        remote?.let { return it }
        if (!binding) {
            connected = CompletableDeferred()
            binding = context.bindService(Intent(context, TextInferenceService::class.java),
                connection, Context.BIND_AUTO_CREATE)
            check(binding) { "Cannot bind inference process" }
        }
        return withTimeout(15_000) { connected.await() }
    }
    suspend fun call(op: Int, args: Bundle = Bundle(), event: (Bundle) -> Unit = {}) =
        withContext(Dispatchers.Main.immediate) {
            val target = try {
                if (op == InferenceProtocol.LOAD) connect()
                else checkNotNull(remote) { "Inference process not connected" }
            } catch (e: Exception) {
                lost()
                throw e
            }
            val id = ++nextId
            val responses = Channel<Bundle>(Channel.UNLIMITED)
            pending[id] = responses
            try {
                val request = Message.obtain(null, op)
                request.arg1 = id
                request.replyTo = receiver
                request.data = args
                target.send(request)
                withTimeout(if (op == InferenceProtocol.GENERATE) 300_000 else 120_000) {
                    while (true) {
                        val result = responses.receive()
                        when (result.getString("kind")) {
                            "done" -> break
                            "error" -> error(result.getString("text").orEmpty())
                            else -> event(result)
                        }
                    }
                }
            } catch (e: Exception) {
                // Do not reuse an uncertain native session after timeouts or Binder failures.
                if (e is TimeoutCancellationException || e is android.os.RemoteException) lost()
                throw e
            } finally {
                pending.remove(id)
                responses.close()
            }
        }
}
