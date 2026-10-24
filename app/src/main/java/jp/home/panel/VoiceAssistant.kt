package jp.home.panel

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

sealed interface VoiceState {
    data object Idle : VoiceState
    data object WaitingWake : VoiceState
    data object Listening : VoiceState
    data class Processing(val heard: String) : VoiceState
    data class Done(val heard: String, val reply: String) : VoiceState
    data class Failed(val message: String) : VoiceState
}

/**
 * Fire OS には Google の音声認識が無いため、マイク音声をそのまま Home Assistant の
 * Assist パイプライン（呼びかけ検知 → 音声認識 → 会話エージェント(AI) → 読み上げ）へ送る。
 * どの AI に答えさせるかは Home Assistant 側のパイプライン設定で決まる。
 */
class VoiceAssistant(private val baseUrl: String) {
    private val stopFlag = AtomicBoolean(false)
    private val deadline = AtomicLong(Long.MAX_VALUE)

    // AI との会話の続き（「それは何時まで？」など）を5分間保持する
    private var conversationId: String? = null
    private var conversationAt = 0L

    fun stopListening() = stopFlag.set(true)

    private fun basePayload(startStage: String, pipeline: String?, input: JSONObject): JSONObject {
        val p = JSONObject()
            .put("type", "assist_pipeline/run")
            .put("start_stage", startStage)
            .put("end_stage", "tts")
            .put("input", input)
        if (!pipeline.isNullOrBlank()) p.put("pipeline", pipeline)
        val cid = conversationId
        if (cid != null && SystemClock.elapsedRealtime() - conversationAt < CONVERSATION_KEEP_MS) {
            p.put("conversation_id", cid)
        }
        return p
    }

    /** 共通のイベント処理結果 */
    private class Outcome {
        var heard = ""
        var reply = ""
        var ttsUrl: String? = null
        var handled = false
    }

    private fun handleIntentEnd(data: JSONObject, out: Outcome) {
        val output = data.optJSONObject("intent_output") ?: return
        out.reply = output.optJSONObject("response")?.optJSONObject("speech")
            ?.optJSONObject("plain")?.optString("speech").orEmpty()
        output.optString("conversation_id").takeIf { it.isNotBlank() }?.let {
            conversationId = it
            conversationAt = SystemClock.elapsedRealtime()
        }
    }

    private fun errorFor(data: JSONObject): HaException = when (val code = data.optString("code")) {
        "wake-engine-missing", "wake-provider-missing" -> HaException(
            "呼びかけ検知が設定されていません（Home Assistant の音声アシスタントで「ウェイクワード」を設定してください）"
        )
        "stt-no-text-detected" -> HaException("聞き取れませんでした")
        else -> HaException(data.optString("message").ifBlank { "音声操作に失敗しました ($code)" })
    }

    /**
     * 音声で命令・質問する。wakeWord = true のときは呼びかけを待ってから聞き取る。
     * 戻り値：命令を処理したら true、呼びかけが無いまま時間切れなら false
     */
    suspend fun run(
        client: HaClient,
        wakeWord: Boolean,
        pipeline: String?,
        onState: (VoiceState) -> Unit,
    ): Boolean = coroutineScope {
        stopFlag.set(false)
        deadline.set(if (wakeWord) Long.MAX_VALUE else SystemClock.elapsedRealtime() + MAX_RECORD_MS)

        val input = JSONObject().put("sample_rate", SAMPLE_RATE)
        if (wakeWord) input.put("timeout", WAKE_TIMEOUT_S)
        val events = Channel<JSONObject>(Channel.UNLIMITED)
        val subId = client.subscribe(
            basePayload(if (wakeWord) "wake_word" else "stt", pipeline, input)
        ) { events.trySend(it) }

        val out = Outcome()
        try {
            var recJob: Job? = null
            withTimeout(if (wakeWord) (WAKE_TIMEOUT_S + 90) * 1000L else 90_000L) {
                loop@ for (ev in events) {
                    val data = ev.optJSONObject("data") ?: JSONObject()
                    when (ev.optString("type")) {
                        "run-start" -> {
                            val handler = data.optJSONObject("runner_data")
                                ?.optInt("stt_binary_handler_id", -1) ?: -1
                            if (handler < 0) throw HaException("音声入力を開始できませんでした")
                            onState(if (wakeWord) VoiceState.WaitingWake else VoiceState.Listening)
                            recJob = launch(Dispatchers.IO) { record(client, handler) }
                        }
                        "wake_word-end" -> {
                            val detected = data.optJSONObject("wake_word_output")
                                ?.optString("wake_word_id").orEmpty()
                            if (detected.isEmpty()) break@loop // 検知されないまま終了
                            deadline.set(SystemClock.elapsedRealtime() + MAX_RECORD_MS)
                            beep()
                            onState(VoiceState.Listening)
                        }
                        "stt-vad-end" -> stopFlag.set(true)
                        "stt-end" -> {
                            stopFlag.set(true)
                            out.handled = true
                            out.heard = data.optJSONObject("stt_output")?.optString("text").orEmpty()
                            onState(VoiceState.Processing(out.heard))
                        }
                        "intent-end" -> handleIntentEnd(data, out)
                        "tts-end" -> out.ttsUrl = data.optJSONObject("tts_output")?.optString("url")
                        "error" -> {
                            if (data.optString("code") == "wake-word-timeout") break@loop
                            throw errorFor(data)
                        }
                        "run-end" -> break@loop
                    }
                }
            }
            stopFlag.set(true)
            recJob?.join()
            if (out.handled) finish(out, onState)
        } finally {
            stopFlag.set(true)
            client.removeHandler(subId)
        }
        out.handled
    }

    /** 文字で命令・質問する（回答は読み上げる） */
    suspend fun runText(
        client: HaClient,
        text: String,
        pipeline: String?,
        onState: (VoiceState) -> Unit,
    ) = coroutineScope {
        val events = Channel<JSONObject>(Channel.UNLIMITED)
        onState(VoiceState.Processing(text))
        val subId = client.subscribe(
            basePayload("intent", pipeline, JSONObject().put("text", text))
        ) { events.trySend(it) }
        val out = Outcome().apply { heard = text; handled = true }
        try {
            withTimeout(90_000L) {
                loop@ for (ev in events) {
                    val data = ev.optJSONObject("data") ?: JSONObject()
                    when (ev.optString("type")) {
                        "intent-end" -> handleIntentEnd(data, out)
                        "tts-end" -> out.ttsUrl = data.optJSONObject("tts_output")?.optString("url")
                        "error" -> throw errorFor(data)
                        "run-end" -> break@loop
                    }
                }
            }
            finish(out, onState)
        } finally {
            client.removeHandler(subId)
        }
    }

    private suspend fun finish(out: Outcome, onState: (VoiceState) -> Unit) {
        onState(VoiceState.Done(out.heard, out.reply))
        out.ttsUrl?.takeIf { it.isNotBlank() }
            ?.let { play(if (it.startsWith("http")) it else baseUrl + it) }
    }

    @SuppressLint("MissingPermission") // 権限は MainActivity / ViewModel で確認済み
    private fun record(client: HaClient, handler: Int) {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, 4096),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            client.sendBinary(byteArrayOf(handler.toByte()))
            throw HaException("マイクを使えません（Alexaのハンズフリーなど、他の機能が使用中の可能性があります）")
        }
        val buf = ByteArray(2048) // 16kHz/16bit/モノラルで約64ms
        try {
            rec.startRecording()
            while (!stopFlag.get() && SystemClock.elapsedRealtime() < deadline.get()) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) break
                if (n > 0) {
                    // 先頭1バイトに handler id を付けた PCM（リトルエンディアン）を送る
                    val frame = ByteArray(n + 1)
                    frame[0] = handler.toByte()
                    System.arraycopy(buf, 0, frame, 1, n)
                    if (!client.sendBinary(frame)) break
                }
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
            client.sendBinary(byteArrayOf(handler.toByte())) // 音声の終わり
        }
    }

    private fun beep() {
        runCatching {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            Thread { Thread.sleep(400); tg.release() }.start()
        }
    }

    private suspend fun play(url: String) = suspendCancellableCoroutine<Unit> { cont ->
        val mp = MediaPlayer()
        mp.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        mp.setOnPreparedListener { it.start() }
        mp.setOnCompletionListener {
            runCatching { it.release() }
            if (cont.isActive) cont.resume(Unit)
        }
        mp.setOnErrorListener { p, _, _ ->
            runCatching { p.release() }
            if (cont.isActive) cont.resume(Unit)
            true
        }
        cont.invokeOnCancellation { runCatching { mp.release() } }
        try {
            mp.setDataSource(url)
            mp.prepareAsync()
        } catch (e: Exception) {
            runCatching { mp.release() }
            if (cont.isActive) cont.resume(Unit)
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val MAX_RECORD_MS = 10_000L
        /** 呼びかけ待ち1回あたりの秒数。時間切れになったら自動でやり直す */
        const val WAKE_TIMEOUT_S = 600
        const val CONVERSATION_KEEP_MS = 5 * 60 * 1000L
    }
}
