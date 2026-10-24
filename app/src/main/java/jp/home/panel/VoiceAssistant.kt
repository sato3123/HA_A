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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.math.sqrt

sealed interface VoiceState {
    data object Idle : VoiceState
    data object WaitingWake : VoiceState
    data object Listening : VoiceState
    data class Processing(val heard: String) : VoiceState
    data class Speaking(val heard: String, val reply: String) : VoiceState
    data class Done(val heard: String, val reply: String) : VoiceState
    data class Failed(val message: String) : VoiceState
}

/**
 * Fire OS には Google の音声認識が無いため、マイク音声をそのまま Home Assistant の
 * Assist パイプラインへ送る。
 *
 * 流れは2段階：
 *  1. listen()  … 呼びかけ検知 → 音声認識（文字にするところまで）
 *  2. respond() … 文字を会話エージェント（AI）に渡して、答えを読み上げる
 * 間でアプリ自身の命令（タイマーなど）を横取りできるようにしている。
 */
class VoiceAssistant(private val baseUrl: String, private val log: (String) -> Unit) {
    private val stopFlag = AtomicBoolean(false)
    private val deadline = AtomicLong(Long.MAX_VALUE)
    @Volatile private var player: MediaPlayer? = null
    @Volatile private var playDone: (() -> Unit)? = null

    // AI との会話の続き（「それは何時まで？」など）を5分間保持する
    private var conversationId: String? = null
    private var conversationAt = 0L

    /** マイク感度の補正（Home Assistant が対応していなければ自動で外す） */
    @Volatile var enhanceAudio = true

    fun stopListening() = stopFlag.set(true)

    /** 読み上げを途中で止める（マイクボタンで割り込んだとき） */
    fun stopSpeaking() {
        playDone?.invoke()
    }

    val isSpeaking: Boolean get() = player != null

    private fun basePayload(startStage: String, endStage: String, pipeline: String?, input: JSONObject): JSONObject {
        val p = JSONObject()
            .put("type", "assist_pipeline/run")
            .put("start_stage", startStage)
            .put("end_stage", endStage)
            .put("input", input)
        if (!pipeline.isNullOrBlank()) p.put("pipeline", pipeline)
        val cid = conversationId
        if (startStage == "intent" && cid != null && SystemClock.elapsedRealtime() - conversationAt < CONVERSATION_KEEP_MS) {
            p.put("conversation_id", cid)
        }
        return p
    }

    private fun errorFor(data: JSONObject): HaException = when (val code = data.optString("code")) {
        "wake-engine-missing", "wake-provider-missing", "wake-stream-failed" -> HaException(
            "呼びかけ検知が設定されていません。Home Assistant の音声アシスタントで「ウェイクワード」（openWakeWord）を設定してください"
        )
        "stt-no-text-detected" -> HaException("聞き取れませんでした")
        "stt-provider-missing", "stt-provider-unsupported-metadata" -> HaException("音声認識（Whisper など）が設定されていません")
        else -> HaException(data.optString("message").ifBlank { "音声操作に失敗しました ($code)" })
    }

    private suspend fun subscribeRun(client: HaClient, payload: JSONObject, onEvent: (JSONObject) -> Unit): Int =
        try {
            client.subscribe(payload, onEvent)
        } catch (e: HaException) {
            // 古い Home Assistant でマイク補正の指定が使えない場合は、外してやり直す
            val input = payload.optJSONObject("input")
            if (enhanceAudio && input != null && input.has("noise_suppression_level") &&
                (e.message ?: "").contains("extra keys", ignoreCase = true)
            ) {
                enhanceAudio = false
                log("マイク補正の指定に未対応のため外しました")
                input.remove("noise_suppression_level")
                input.remove("auto_gain_dbfs")
                client.subscribe(payload, onEvent)
            } else {
                throw e
            }
        }

    /**
     * 音声を聞き取って文字にする。wakeWord = true のときは呼びかけを待ってから。
     * 戻り値：聞き取った文字。呼びかけが無いまま時間切れなら null
     */
    suspend fun listen(
        client: HaClient,
        wakeWord: Boolean,
        pipeline: String?,
        onState: (VoiceState) -> Unit,
    ): String? = coroutineScope {
        stopFlag.set(false)
        deadline.set(if (wakeWord) Long.MAX_VALUE else SystemClock.elapsedRealtime() + MAX_RECORD_MS)

        val input = JSONObject().put("sample_rate", SAMPLE_RATE)
        if (wakeWord) input.put("timeout", WAKE_TIMEOUT_S)
        if (enhanceAudio) input.put("noise_suppression_level", 2).put("auto_gain_dbfs", 15)
        val events = Channel<JSONObject>(Channel.UNLIMITED)
        val subId = subscribeRun(client, basePayload(if (wakeWord) "wake_word" else "stt", "stt", pipeline, input)) {
            events.trySend(it)
        }
        log(if (wakeWord) "呼びかけの待ち受けを開始" else "聞き取りを開始")

        var heard: String? = null
        try {
            var recJob: Job? = null
            withTimeout(if (wakeWord) (WAKE_TIMEOUT_S + 90) * 1000L else 60_000L) {
                loop@ for (ev in events) {
                    val data = ev.optJSONObject("data") ?: JSONObject()
                    val type = ev.optString("type")
                    if (type != "stt-vad-start") log("← $type")
                    when (type) {
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
                            if (detected.isEmpty()) break@loop
                            log("呼びかけを検知：$detected")
                            deadline.set(SystemClock.elapsedRealtime() + MAX_RECORD_MS)
                            beep()
                            onState(VoiceState.Listening)
                        }
                        "stt-vad-end" -> stopFlag.set(true)
                        "stt-end" -> {
                            stopFlag.set(true)
                            heard = data.optJSONObject("stt_output")?.optString("text").orEmpty()
                            log("聞き取り結果：「$heard」")
                        }
                        "error" -> {
                            val code = data.optString("code")
                            log("エラー：$code ${data.optString("message")}")
                            if (code == "wake-word-timeout") break@loop
                            throw errorFor(data)
                        }
                        "run-end" -> break@loop
                    }
                }
            }
            stopFlag.set(true)
            recJob?.join()
        } finally {
            stopFlag.set(true)
            client.removeHandler(subId)
        }
        heard?.let { if (it.isBlank()) throw HaException("聞き取れませんでした") }
        heard
    }

    /** 文字の命令・質問を会話エージェントに渡し、答えを読み上げる */
    suspend fun respond(
        client: HaClient,
        text: String,
        pipeline: String?,
        onState: (VoiceState) -> Unit,
    ) = runToTts(client, "intent", JSONObject().put("text", text), pipeline, text, onState)

    /** アプリ自身の返事を読み上げる（タイマーなど） */
    suspend fun speak(client: HaClient, heard: String, reply: String, pipeline: String?, onState: (VoiceState) -> Unit) =
        runToTts(client, "tts", JSONObject().put("text", reply), pipeline, heard, onState, fixedReply = reply)

    private suspend fun runToTts(
        client: HaClient,
        start: String,
        input: JSONObject,
        pipeline: String?,
        heard: String,
        onState: (VoiceState) -> Unit,
        fixedReply: String? = null,
    ) = coroutineScope {
        val events = Channel<JSONObject>(Channel.UNLIMITED)
        onState(VoiceState.Processing(heard))
        val subId = client.subscribe(basePayload(start, "tts", pipeline, input)) { events.trySend(it) }
        var reply = fixedReply.orEmpty()
        var ttsUrl: String? = null
        try {
            withTimeout(90_000L) {
                loop@ for (ev in events) {
                    val data = ev.optJSONObject("data") ?: JSONObject()
                    val type = ev.optString("type")
                    log("← $type")
                    when (type) {
                        "intent-end" -> {
                            val output = data.optJSONObject("intent_output") ?: JSONObject()
                            reply = output.optJSONObject("response")?.optJSONObject("speech")
                                ?.optJSONObject("plain")?.optString("speech").orEmpty()
                            output.optString("conversation_id").takeIf { it.isNotBlank() }?.let {
                                conversationId = it
                                conversationAt = SystemClock.elapsedRealtime()
                            }
                        }
                        "tts-end" -> ttsUrl = data.optJSONObject("tts_output")?.optString("url")
                        "error" -> {
                            log("エラー：${data.optString("code")} ${data.optString("message")}")
                            throw errorFor(data)
                        }
                        "run-end" -> break@loop
                    }
                }
            }
        } finally {
            client.removeHandler(subId)
        }
        val url = ttsUrl?.takeIf { it.isNotBlank() }
        if (url != null) {
            onState(VoiceState.Speaking(heard, reply))
            // 読み上げが終わらなくても、長くても2分で次へ進めるようにする
            withTimeoutOrNull(120_000L) { play(if (url.startsWith("http")) url else baseUrl + url) }
            stopPlayer()
        }
        onState(VoiceState.Done(heard, reply))
    }

    @SuppressLint("MissingPermission") // 権限は MainActivity / ViewModel で確認済み
    private fun record(client: HaClient, handler: Int) {
        val rec = openRecorder()
        if (rec == null) {
            client.sendBinary(byteArrayOf(handler.toByte()))
            throw HaException("マイクを使えません（Alexaのハンズフリーなど、他の機能が使用中の可能性があります）")
        }
        val buf = ByteArray(2048) // 16kHz/16bit/モノラルで約64ms
        var frames = 0L
        try {
            rec.startRecording()
            while (!stopFlag.get() && SystemClock.elapsedRealtime() < deadline.get()) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) {
                    log("マイクの読み取りエラー ($n)")
                    break
                }
                if (n > 0) {
                    // 先頭1バイトに handler id を付けた PCM（リトルエンディアン）を送る
                    val frame = ByteArray(n + 1)
                    frame[0] = handler.toByte()
                    System.arraycopy(buf, 0, frame, 1, n)
                    if (!client.sendBinary(frame)) {
                        log("音声を送れませんでした（接続が切れた可能性）")
                        break
                    }
                    frames++
                }
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
            client.sendBinary(byteArrayOf(handler.toByte())) // 音声の終わり
            log("音声送信を終了（約${frames * 64 / 1000}秒分）")
        }
    }

    @SuppressLint("MissingPermission")
    private fun openRecorder(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        for (src in listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
            val rec = AudioRecord(src, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 4096))
            if (rec.state == AudioRecord.STATE_INITIALIZED) return rec
            rec.release()
        }
        return null
    }

    /** マイクテスト：seconds 秒録音して、音の大きさ（0〜100）を返し続ける */
    suspend fun micTest(seconds: Int, onLevel: (Int) -> Unit): Int = withContext(Dispatchers.IO) {
        val rec = openRecorder() ?: throw HaException("マイクを使えません（Alexaのハンズフリーなど、他の機能が使用中の可能性があります）")
        val buf = ShortArray(1024)
        var peak = 0
        try {
            rec.startRecording()
            val end = SystemClock.elapsedRealtime() + seconds * 1000L
            while (SystemClock.elapsedRealtime() < end) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                var sum = 0.0
                for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
                val rms = sqrt(sum / n)
                val level = (rms / 3000.0 * 100).toInt().coerceIn(0, 100)
                peak = maxOf(peak, level)
                onLevel(level)
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
        peak
    }

    private fun beep() {
        runCatching {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            Thread { Thread.sleep(400); tg.release() }.start()
        }
    }

    private fun stopPlayer() {
        val p = player
        player = null
        playDone = null
        runCatching { p?.stop() }
        runCatching { p?.release() }
    }

    private suspend fun play(url: String) = suspendCancellableCoroutine<Unit> { cont ->
        stopPlayer()
        val mp = MediaPlayer()
        player = mp
        val finish = {
            if (cont.isActive) cont.resume(Unit)
        }
        playDone = finish
        mp.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        mp.setOnPreparedListener { it.start() }
        mp.setOnCompletionListener { finish() }
        mp.setOnErrorListener { _, what, extra ->
            log("読み上げの再生エラー ($what/$extra)")
            finish()
            true
        }
        cont.invokeOnCancellation { stopPlayer() }
        try {
            mp.setDataSource(url)
            mp.prepareAsync()
        } catch (e: Exception) {
            log("読み上げを再生できません：${e.message}")
            finish()
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
