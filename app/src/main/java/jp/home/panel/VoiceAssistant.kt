package jp.home.panel

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
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
import kotlin.coroutines.resume

sealed interface VoiceState {
    data object Idle : VoiceState
    data object Listening : VoiceState
    data class Processing(val heard: String) : VoiceState
    data class Done(val heard: String, val reply: String) : VoiceState
    data class Failed(val message: String) : VoiceState
}

/**
 * Fire OS には Google の音声認識が無いため、マイク音声をそのまま Home Assistant の
 * Assist パイプライン（Whisper → 会話 → Piper）へ送り、返ってきた音声を再生する。
 */
class VoiceAssistant(private val baseUrl: String) {
    private val stopFlag = AtomicBoolean(false)

    fun stopListening() = stopFlag.set(true)

    suspend fun run(client: HaClient, onState: (VoiceState) -> Unit) = coroutineScope {
        stopFlag.set(false)
        val events = Channel<JSONObject>(Channel.UNLIMITED)
        val subId = client.subscribe(
            JSONObject()
                .put("type", "assist_pipeline/run")
                .put("start_stage", "stt")
                .put("end_stage", "tts")
                .put("input", JSONObject().put("sample_rate", SAMPLE_RATE))
        ) { events.trySend(it) }

        try {
            var heard = ""
            var reply = ""
            var ttsUrl: String? = null
            var recJob: Job? = null

            withTimeout(45_000) {
                loop@ for (ev in events) {
                    val data = ev.optJSONObject("data") ?: JSONObject()
                    when (ev.optString("type")) {
                        "run-start" -> {
                            val handler = data.optJSONObject("runner_data")
                                ?.optInt("stt_binary_handler_id", -1) ?: -1
                            if (handler < 0) throw HaException("音声入力を開始できませんでした")
                            onState(VoiceState.Listening)
                            recJob = launch(Dispatchers.IO) { record(client, handler) }
                        }
                        "stt-vad-end" -> stopFlag.set(true)
                        "stt-end" -> {
                            stopFlag.set(true)
                            heard = data.optJSONObject("stt_output")?.optString("text").orEmpty()
                            onState(VoiceState.Processing(heard))
                        }
                        "intent-end" -> reply = data.optJSONObject("intent_output")
                            ?.optJSONObject("response")?.optJSONObject("speech")
                            ?.optJSONObject("plain")?.optString("speech").orEmpty()
                        "tts-end" -> ttsUrl = data.optJSONObject("tts_output")?.optString("url")
                        "error" -> throw HaException(
                            if (data.optString("code") == "stt-no-text-detected") "聞き取れませんでした"
                            else data.optString("message").ifBlank { "音声操作に失敗しました" }
                        )
                        "run-end" -> break@loop
                    }
                }
            }

            stopFlag.set(true)
            recJob?.join()
            onState(VoiceState.Done(heard, reply))
            ttsUrl?.takeIf { it.isNotBlank() }?.let { play(if (it.startsWith("http")) it else baseUrl + it) }
        } finally {
            stopFlag.set(true)
            client.removeHandler(subId)
        }
    }

    @SuppressLint("MissingPermission") // 権限は MainActivity で確認済み
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
            throw HaException("マイクを使えません")
        }
        val buf = ByteArray(2048) // 16kHz/16bit/モノラルで約64ms
        val deadline = SystemClock.elapsedRealtime() + MAX_RECORD_MS
        try {
            rec.startRecording()
            while (!stopFlag.get() && SystemClock.elapsedRealtime() < deadline) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) break
                if (n > 0) {
                    // 先頭1バイトに handler id を付けた PCM（リトルエンディアン）を送る
                    val frame = ByteArray(n + 1)
                    frame[0] = handler.toByte()
                    System.arraycopy(buf, 0, frame, 1, n)
                    client.sendBinary(frame)
                }
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
            client.sendBinary(byteArrayOf(handler.toByte())) // 音声の終わり
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
    }
}
