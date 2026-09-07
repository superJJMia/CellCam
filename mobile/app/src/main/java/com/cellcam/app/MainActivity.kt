package com.cellcam.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.cellcam.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.VideoTrack

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var signaling: SignalingClient? = null
    private var webRtc: WebRtcSender? = null
    private var eglBase: EglBase? = null
    private var mdnsDiscovery: MdnsDiscovery? = null

    // Estado da sessão
    private var initiated = false
    private var retrying = false
    private var reconnectDelayMs = 1000L
    private var mirrorEnabled = true
    private var rotateDegrees = 0

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            binding.statusText.text = "Câmera liberada. Digite IP e código para conectar."
            binding.connectButton.isEnabled = true
        } else {
            binding.statusText.text = "Permissão de câmera negada."
            Toast.makeText(this, "CellCam precisa da câmera", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupPreview()
        setupButtons()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            binding.statusText.text = "Digite o IP do servidor e o código da sala."
            binding.connectButton.isEnabled = true
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun setupPreview() {
        if (eglBase != null) return
        eglBase = EglBase.create()
        binding.localPreview.init(eglBase!!.eglBaseContext, null)
        binding.localPreview.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        binding.localPreview.setMirror(mirrorEnabled)
        binding.localPreview.setZOrderMediaOverlay(true)
    }

    private fun setupButtons() {
        binding.connectButton.setOnClickListener {
            if (initiated) {
                stopStreaming()
            } else {
                startStreaming()
            }
        }

        binding.cameraButton.setOnClickListener {
            webRtc?.switchCamera()
        }

        binding.mirrorButton.setOnClickListener {
            mirrorEnabled = !mirrorEnabled
            binding.localPreview.setMirror(mirrorEnabled)
            webRtc?.setMirror(mirrorEnabled)
        }

        binding.rotateButton.setOnClickListener {
            rotateDegrees = (rotateDegrees + 90) % 360
            webRtc?.setRotate(rotateDegrees)
        }
    }

    private fun startStreaming() {
        val ip = binding.serverIpInput.text.toString().trim()
        val room = binding.roomCodeInput.text.toString().trim()

        if (room.length != 6) {
            Toast.makeText(this, "Informe um código de sala de 6 dígitos", Toast.LENGTH_LONG).show()
            return
        }

        initiated = true
        retrying = false
        reconnectDelayMs = 1000L
        binding.connectButton.text = "Parar"
        binding.serverIpInput.isEnabled = false
        binding.roomCodeInput.isEnabled = false
        binding.cameraButton.isEnabled = true
        binding.mirrorButton.isEnabled = true
        binding.rotateButton.isEnabled = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (ip.isEmpty()) {
            discoverAndConnect(room)
        } else {
            binding.statusText.text = "Conectando ao servidor $ip..."
            connectSignaling(ip, room)
        }
    }

    /**
     * Sem IP digitado, encontra o desktop via mDNS (WiFi ou cabo USB).
     * O cabo é tentado primeiro; se não responder, cai para a rede WiFi.
     */
    private fun discoverAndConnect(room: String) {
        binding.statusText.text = "Procurando desktop na rede..."
        mdnsDiscovery = MdnsDiscovery(this)
        mdnsDiscovery?.onStatusCb = { msg ->
            runOnUiThread {
                if (initiated) binding.statusText.text = msg
            }
        }
        mdnsDiscovery?.start(
            onDiscovered = { servers ->
                lifecycleScope.launch {
                    var connected = false
                    for (server in servers) {
                        if (!initiated) return@launch
                        val meio = if (server.preferWifi) "WiFi" else "cabo USB"
                        binding.statusText.text = "Verificando ${server.ip} ($meio)..."
                        if (tcpReachable(server.ip, server.port)) {
                            connected = true
                            connectSignaling(server.ip, room, server.port)
                            break
                        }
                    }
                    if (!connected && initiated) {
                        binding.statusText.text =
                            "Nenhum desktop encontrado na rede. Digite o IP manualmente acima."
                    }
                }
            },
            onError = { msg ->
                runOnUiThread {
                    if (initiated) binding.statusText.text = msg
                }
            }
        )
    }

    private suspend fun tcpReachable(ip: String, port: Int): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                java.net.Socket().use { it.connect(java.net.InetSocketAddress(ip, port), 700); true }
            }.getOrDefault(false)
        }

    private fun connectSignaling(ip: String, room: String, port: Int = 8081) {
        signaling?.close()
        signaling = null

        signaling = SignalingClient(
            serverIp = ip,
            roomCode = room,
            scope = lifecycleScope,
            port = port,
            listener = object : SignalingClient.Listener {
                override fun onConnected() {
                    retrying = false
                    runOnUiThread {
                        binding.statusText.text = "Conectado! Aguardando o PC (receiver)..."
                    }
                }

                override fun onPeerJoined() {
                    runOnUiThread {
                        binding.statusText.text = "PC conectado! Iniciando WebRTC..."
                    }
                    startWebRtc()
                }

                override fun onPeerLeft() {
                    runOnUiThread {
                        binding.statusText.text = "PC desconectou. Aguardando reconexão..."
                    }
                }

                override fun onSignal(from: String, data: org.json.JSONObject) {
                    webRtc?.handleSignal(data)
                }

                override fun onError(message: String) {
                    runOnUiThread {
                        binding.statusText.text = "Erro: $message"
                    }
                    scheduleReconnectIfNeeded()
                }

                override fun onClosed() {
                    runOnUiThread {
                        binding.statusText.text = "Conexão com servidor encerrada."
                    }
                    scheduleReconnectIfNeeded()
                }
            }
        )
        signaling?.connect()
    }

    /**
     * Tenta reconectar automaticamente com backoff (1s -> 2s -> ... -> 10s)
     * enquanto a transmissão estiver iniciada.
     */
    private fun scheduleReconnectIfNeeded() {
        if (!initiated) return
        if (retrying) return
        retrying = true

        runOnUiThread {
            binding.statusText.text = "Conexão perdida. Tentando reconectar em ${reconnectDelayMs / 1000}s..."
        }

        val ip = signaling?.serverIp ?: return
        val room = signaling?.roomCode ?: return
        val port = signaling?.port ?: 8081
        lifecycleScope.launch {
            delay(reconnectDelayMs)
            retrying = false
            reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(10_000L)
            if (!initiated) return@launch
            connectSignaling(ip, room, port)
        }
    }

    private fun startWebRtc() {
        if (initiated.not()) return
        setupPreview()

        if (webRtc != null) {
            // Sessão já existe: atualiza o signaling e renegocia (transmissão foi perdida)
            webRtc?.setSignaling(signaling!!)
            webRtc?.setMirror(mirrorEnabled)
            webRtc?.setRotate(rotateDegrees)
            webRtc?.createPeerConnection {
                webRtc?.createOffer()
            }
            return
        }

        webRtc = WebRtcSender(
            context = applicationContext,
            signaling = signaling!!,
            eventListener = object : WebRtcSender.Listener {
                override fun onIceConnected() {
                    runOnUiThread {
                        binding.statusText.text = "Transmitindo ao PC!"
                        showLocalPreview()
                    }
                }

                override fun onIceDisconnected() {
                    runOnUiThread {
                        binding.statusText.text = "Conexão perdida com o PC."
                    }
                }

                override fun onError(message: String) {
                    runOnUiThread {
                        binding.statusText.text = "WebRTC: $message"
                    }
                }
            }
        )

        webRtc?.start()
        // Envia o estado de espelho/rotação para a sessão que acabou de conectar
        webRtc?.setMirror(mirrorEnabled)
        webRtc?.setRotate(rotateDegrees)
        webRtc?.createPeerConnection {
            webRtc?.createOffer()
        }
    }

    private fun showLocalPreview() {
        val track = webRtc?.localVideoTrack ?: return
        track.addSink(binding.localPreview)
        binding.localPreview.visibility = android.view.View.VISIBLE
    }

    private fun stopStreaming() {
        initiated = false
        retrying = false

        signaling?.close()
        signaling = null
        mdnsDiscovery?.stop()
        mdnsDiscovery = null
        webRtc?.stop()
        webRtc = null

        runCatching { binding.localPreview.release() }
        eglBase?.release()
        eglBase = null

        binding.serverIpInput.isEnabled = true
        binding.roomCodeInput.isEnabled = true
        binding.cameraButton.isEnabled = false
        binding.mirrorButton.isEnabled = false
        binding.rotateButton.isEnabled = false
        binding.connectButton.text = "Conectar e transmitir"
        binding.connectButton.isEnabled = true
        binding.localPreview.visibility = android.view.View.GONE
        binding.statusText.text = "Transmissão parada. Digite IP e código para reconectar."
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onBackPressed() {
        if (initiated) {
            stopStreaming()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mdnsDiscovery?.stop()
        signaling?.close()
        webRtc?.stop()
        runCatching { binding.localPreview.release() }
        eglBase?.release()
    }
}