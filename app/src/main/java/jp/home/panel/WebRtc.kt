package jp.home.panel

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Home Assistant のカメラを WebRTC で受け取る（いちばん遅れが少ない。1秒未満）。
 * やりとりは Home Assistant の camera/webrtc/offer（2024.11 以降）。
 */
class WebRtcSession(
    private val context: Context,
    private val egl: EglBase,
    private val vm: MainViewModel,
    private val entityId: String,
    private val sink: VideoSink,
    private val scope: CoroutineScope,
    private val onConnected: () -> Unit,
    private val onError: (String) -> Unit,
) {
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var sub: Int? = null
    @Volatile private var sessionId: String? = null
    @Volatile private var closed = false
    @Volatile private var connected = false
    private val pending = mutableListOf<IceCandidate>()

    fun start() = scope.launch {
        try {
            ensureInit(context)
            val f = PeerConnectionFactory.builder()
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
                .createPeerConnectionFactory()
            factory = f
            val servers = vm.cameraIceServers(entityId).map { s ->
                PeerConnection.IceServer.builder(s.urls).apply {
                    s.user?.let { setUsername(it) }
                    s.credential?.let { setPassword(it) }
                }.createIceServer()
            }
            val cfg = PeerConnection.RTCConfiguration(servers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }
            val p = f.createPeerConnection(cfg, observer) ?: throw HaException("映像の準備ができませんでした")
            pc = p
            p.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
            p.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
            val offer = createOffer(p)
            setLocal(p, offer)
            if (closed) return@launch
            sub = vm.cameraWebRtcOffer(entityId, offer.description) { ev -> onSignal(ev) }
            // しばらくつながらなければ、ほかの方式に切り替える
            delay(12_000)
            if (!connected && !closed) fail("つながるまでに時間がかかっています")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(e.message ?: "つなげませんでした")
        }
    }

    private fun onSignal(ev: JSONObject) {
        if (closed) return
        val p = pc ?: return
        when (ev.optString("type")) {
            "session" -> {
                val id = ev.optString("session_id")
                val queued = synchronized(pending) { sessionId = id; pending.toList().also { pending.clear() } }
                queued.forEach { sendCandidate(id, it) }
            }
            "answer" -> p.setRemoteDescription(NoopSdp, SessionDescription(SessionDescription.Type.ANSWER, ev.optString("answer")))
            "candidate" -> {
                val c = ev.optJSONObject("candidate")
                val cand = if (c != null) IceCandidate(c.optString("sdpMid", "0"), c.optInt("sdpMLineIndex", 0), c.optString("candidate"))
                else IceCandidate("0", 0, ev.optString("candidate"))
                if (cand.sdp.isNotBlank()) p.addIceCandidate(cand)
            }
            "error" -> fail(ev.optString("message").ifBlank { ev.optString("code") })
        }
    }

    private fun sendCandidate(session: String, c: IceCandidate) {
        vm.cameraWebRtcCandidate(
            entityId, session,
            JSONObject().put("candidate", c.sdp).put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex),
        )
    }

    private fun fail(msg: String) {
        if (closed) return
        scope.launch(Dispatchers.Main) { onError(msg) }
    }

    fun close() {
        if (closed) return
        closed = true
        sub?.let { vm.cameraWebRtcClose(it) }
        try {
            pc?.dispose()
            factory?.dispose()
        } catch (_: Exception) {
        }
        pc = null
        factory = null
    }

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
            when (s) {
                PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> {
                    if (!connected) {
                        connected = true
                        scope.launch(Dispatchers.Main) { onConnected() }
                    }
                }
                PeerConnection.IceConnectionState.FAILED -> fail("映像の通信がつながりませんでした")
                else -> {}
            }
        }
        override fun onIceConnectionReceivingChange(b: Boolean) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidate(c: IceCandidate) {
            val id = synchronized(pending) { sessionId ?: run { pending.add(c); null } } ?: return
            sendCandidate(id, c)
        }
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
        override fun onAddStream(s: MediaStream?) {}
        override fun onRemoveStream(s: MediaStream?) {}
        override fun onDataChannel(d: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(r: RtpReceiver?, s: Array<out MediaStream>?) {}
        override fun onTrack(t: RtpTransceiver?) {
            (t?.receiver?.track() as? VideoTrack)?.addSink(sink)
        }
    }

    private suspend fun createOffer(p: PeerConnection): SessionDescription = suspendCancellableCoroutine { cont ->
        p.createOffer(object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription) = cont.resume(d)
            override fun onCreateFailure(e: String?) = cont.resumeWithException(HaException(e ?: "offer"))
            override fun onSetSuccess() {}
            override fun onSetFailure(e: String?) {}
        }, MediaConstraints())
    }

    private suspend fun setLocal(p: PeerConnection, d: SessionDescription): Unit = suspendCancellableCoroutine { cont ->
        p.setLocalDescription(object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription?) {}
            override fun onCreateFailure(e: String?) {}
            override fun onSetSuccess() = cont.resume(Unit)
            override fun onSetFailure(e: String?) = cont.resumeWithException(HaException(e ?: "local"))
        }, d)
    }

    private object NoopSdp : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(e: String?) {}
        override fun onSetFailure(e: String?) {}
    }

    companion object {
        @Volatile private var inited = false

        @Synchronized
        fun ensureInit(context: Context) {
            if (inited) return
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions(),
            )
            inited = true
        }
    }
}

data class IceServerInfo(val urls: List<String>, val user: String?, val credential: String?)
