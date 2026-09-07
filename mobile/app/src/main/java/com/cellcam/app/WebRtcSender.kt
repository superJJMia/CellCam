package com.cellcam.app

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import org.json.JSONObject
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerationAndroid
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * Lado "sender" do WebRTC no celular.
 * Captura a câmera (Camera2), cria um VideoTrack e negocia com o receiver (PC).
 */
class WebRtcSender(
    private val context: Context,
    private var signaling: SignalingClient,
    private val eventListener: Listener
) {

    fun setSignaling(client: SignalingClient) {
        signaling = client
    }
    interface Listener {
        fun onIceConnected()
        fun onIceDisconnected()
        fun onError(message: String)
    }

    companion object {
        private const val TAG = "CellCamWebRtc"
        private val ICE_SERVERS = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
    }

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoSource: VideoSource? = null
    private var cameraCapturer: CameraVideoCapturer? = null
    private var videoTrack: VideoTrack? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private val eglBase: EglBase = EglBase.create()
    private val handlerThread = HandlerThread("CellCamVideo").apply { start() }
    private val videoHandler = Handler(handlerThread.looper)

    @Volatile
    private var streaming = false

    val isStreaming: Boolean
        get() = streaming

    val localVideoTrack: VideoTrack?
        get() = videoTrack

    fun eglContext(): EglBase.Context = eglBase.eglBaseContext

    fun start() {
        initializeFactory()
    }

    fun switchCamera() {
        val capturer = cameraCapturer ?: run {
            eventListener.onError("Câmera não iniciada")
            return
        }
        capturer.switchCamera(null)
    }

    /**
     * Avisa o receiver (PC) para espelhar/desespelhar o fluxo de vídeo.
     * O preview local continua sendo controlado pela view (SurfaceViewRenderer).
     */
    fun setMirror(enabled: Boolean) {
        Log.i(TAG, "Enviando mirror=$enabled ao receiver")
        signaling.sendSignal("mirror", JSONObject().put("mirror", enabled))
    }

    /**
     * Pede ao receiver (PC) para girar o fluxo em multiplos de 90°.
     */
    fun setRotate(degrees: Int) {
        Log.i(TAG, "Enviando rotate=${degrees}° ao receiver")
        signaling.sendSignal("rotate", JSONObject().put("degrees", degrees))
    }

    private fun initializeFactory() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions()
        )
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(
                org.webrtc.DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
            )
            .setVideoDecoderFactory(
                org.webrtc.DefaultVideoDecoderFactory(eglBase.eglBaseContext)
            )
            .createPeerConnectionFactory()

        startCamera()
    }

    private fun startCamera() {
        val enumerator = Camera2Enumerator(context)
        val deviceNames = enumerator.deviceNames

        // Prefere a câmera frontal
        var deviceName = deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: deviceNames.firstOrNull()
            ?: run {
                eventListener.onError("Nenhuma câmera encontrada")
                return
            }

        try {
            val source = factory!!.createVideoSource(false)
            val capturer = enumerator.createCapturer(deviceName, null) as CameraVideoCapturer

            textureHelper = SurfaceTextureHelper.create("CellCamCapturer", eglBase.eglBaseContext)
            capturer.initialize(textureHelper!!, context, source.capturerObserver)
            capturer.startCapture(1280, 720, 30)

            cameraCapturer = capturer
            videoSource = source
            videoTrack = factory!!.createVideoTrack("camera_track", source)
            videoTrack!!.setEnabled(true)

            Log.i(TAG, "Câmera iniciada: $deviceName @ 720p/30fps")
        } catch (e: Exception) {
            eventListener.onError("Falha ao abrir câmera: ${e.message}")
        }
    }

    fun createPeerConnection(onReady: () -> Unit) {
        // Fecha uma sessão anterior antes de criar uma nova (reconexão)
        runCatching {
            peerConnection?.close()
            peerConnection?.dispose()
        }
        peerConnection = null

        val config = PeerConnection.RTCConfiguration(ICE_SERVERS)
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN

        peerConnection = factory!!.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.i(TAG, "ICE state: $state")
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED -> {
                        streaming = true
                        eventListener.onIceConnected()
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.FAILED,
                    PeerConnection.IceConnectionState.CLOSED -> {
                        streaming = false
                        eventListener.onIceDisconnected()
                    }
                    else -> {}
                }
            }

            override fun onStandardizedIceConnectionChange(state: PeerConnection.IceConnectionState?) {}

            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let {
                    val payload = JSONObject()
                        .put("kind", "ice")
                        .put("candidate", it.sdp)
                        .put("sdpMid", it.sdpMid)
                        .put("sdpMLineIndex", it.sdpMLineIndex)
                    signaling.sendSignal("ice", payload)
                }
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
        })

        videoTrack?.let { pc ->
            peerConnection?.addTrack(pc, listOf())
        }

        onReady()
    }

    fun createOffer() {
        val pc = peerConnection ?: return
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }
        pc.createOffer(object : org.webrtc.SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                pc.setLocalDescription(object : org.webrtc.SdpObserver {
                    override fun onCreateSuccess(desc: SessionDescription?) {}
                    override fun onSetSuccess() {
                        val payload = JSONObject()
                            .put("kind", "offer")
                            .put("sdp", desc.description)
                        signaling.sendSignal("offer", payload)
                    }
                    override fun onSetFailure(reason: String?) {
                        eventListener.onError("Falha ao definir SDP local: $reason")
                    }
                    override fun onCreateFailure(reason: String?) {}
                }, desc)
            }

            override fun onCreateFailure(reason: String?) {
                eventListener.onError("Falha ao criar offer: $reason")
            }

            override fun onSetSuccess() {}
            override fun onSetFailure(reason: String?) {}
        }, constraints)
    }

    fun handleSignal(data: JSONObject) {
        when (data.getString("kind")) {
            "answer" -> setRemoteDescription(data.getString("sdp"))
            "ice" -> addIceCandidate(data)
        }
    }

    private fun setRemoteDescription(sdp: String) {
        val pc = peerConnection ?: return
        val desc = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        pc.setRemoteDescription(object : org.webrtc.SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {}
            override fun onSetSuccess() {
                Log.i(TAG, "Remote description set")
            }
            override fun onCreateFailure(reason: String?) {}
            override fun onSetFailure(reason: String?) {
                eventListener.onError("Falha ao definir SDP remoto: $reason")
            }
        }, desc)
    }

    private fun addIceCandidate(data: JSONObject) {
        val pc = peerConnection ?: return
        val candidate = IceCandidate(
            data.getString("sdpMid"),
            data.getInt("sdpMLineIndex"),
            data.getString("candidate")
        )
        pc.addIceCandidate(candidate)
    }

fun stop() {
        streaming = false
        runCatching {
            runCatching { peerConnection?.close() }
            runCatching { peerConnection?.dispose() }
            cameraCapturer?.stopCapture()
            cameraCapturer?.dispose()
            videoSource?.dispose()
            videoTrack?.dispose()
            textureHelper?.dispose()
            factory?.dispose()
            eglBase.release()
            handlerThread.quitSafely()
        }
    }
}