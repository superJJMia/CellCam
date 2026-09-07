package com.cellcam.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.cellcam.app.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton
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

    // Descoberta
    private var deviceCandidates: List<MdnsServer> = emptyList()
    private var advancedOpen = false
    private var devicesOpen = false

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            binding.statusText.text = "Basta tocar em CONECTAR."
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
binding.statusText.text = "Basta tocar em CONECTAR."
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

        binding.advancedHeader.setOnClickListener { toggleAdvanced() }
        binding.devicesHeader.setOnClickListener { toggleDevices() }
        binding.manualConnectButton.setOnClickListener {
            binding.advancedPanel.visibility = View.GONE
            advancedOpen = false
            binding.advancedHeader.text = "AVANÇADO ▸"
            startStreaming()
        }
    }

    private fun toggleAdvanced() {
        advancedOpen = !advancedOpen
        binding.advancedPanel.visibility = if (advancedOpen) View.VISIBLE else View.GONE
        binding.advancedHeader.text = if (advancedOpen) "AVANÇADO ▾" else "AVANÇADO ▸"
    }

    private fun toggleDevices() {
        if (deviceCandidates.isEmpty()) return
        devicesOpen = !devicesOpen
        binding.deviceList.visibility = if (devicesOpen) View.VISIBLE else View.GONE
        binding.devicesHeader.text =
            if (devicesOpen) "DISPOSITIVOS ENCONTRADOS ▾" else "DISPOSITIVOS ENCONTRADOS ▸"
    }

    private fun startStreaming() {
        val ip = binding.serverIpInput.text.toString().trim()
        val room = binding.roomCodeInput.text.toString().trim()

        if (room.isNotEmpty() && room.length != 6) {
            Toast.makeText(this, "Informe um código de sala de 6 dígitos (ou deixe vazio)", Toast.LENGTH_LONG).show()
            return
        }

        initiated = true
        retrying = false
        reconnectDelayMs = 1000L
        binding.connectButton.text = "PARAR"
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
            lifecycleScope.launch {
                var effectiveRoom = room
                if (effectiveRoom.isEmpty()) {
                    effectiveRoom = withContext(Dispatchers.IO) {
                        MdnsDiscovery.fetchRoomDirect(ip)
                    } ?: ""
                }
                if (!initiated) return@launch
                if (effectiveRoom.isEmpty()) {
                    binding.statusText.text =
                        "Não encontrei a sala automaticamente. Informe o código da sala."
                    stopStreaming()
                    return@launch
                }
                connectSignaling(ip, effectiveRoom)
            }
        }
    }

    /**
     * Sem IP digitado, descobre o desktop. Um único desktop conecta sozinho
     * (com a sala do TXT mDNS ou /api/room); com vários, mostra uma lista
     * expansível para o usuário escolher.
     */
    private fun discoverAndConnect(userRoom: String) {
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
                    if (!initiated) return@launch
                    val live = servers.filter { tcpReachable(it.ip, it.port) }
                    if (live.isEmpty()) {
                        binding.statusText.text =
                            "Nenhum desktop encontrado na rede. Toque em AVANÇADO para digitar IP."
                        return@launch
                    }
                    if (live.size == 1) {
                        connectTo(live.first(), userRoom)
                    } else {
                        showDeviceChooser(live, userRoom)
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

    private fun showDeviceChooser(live: List<MdnsServer>, userRoom: String) {
        deviceCandidates = live
        binding.deviceList.removeAllViews()
        for (server in live) {
            val meio = if (server.preferWifi) "WIFI" else "CABO"
            val label = server.name?.takeIf { it.isNotBlank() } ?: server.ip
            val btn = MaterialButton(this).apply {
                text = "$label  ·  $meio"
                setTextColor(getColor(com.cellcam.app.R.color.accent))
                textSize = 13f
                letterSpacing = 0.08f
                isAllCaps = true
                background = getDrawable(com.cellcam.app.R.drawable.bg_panel_dark)
                insetTop = 0
                insetBottom = 0
                isEnabled = true
                setOnClickListener {
                    pickDevice(server, userRoom)
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, dp(1), 0, dp(1))
            btn.layoutParams = lp
            binding.deviceList.addView(btn)
        }
        binding.devicesHeader.visibility = View.VISIBLE
        binding.devicesHeader.text = "DISPOSITIVOS ENCONTRADOS ▾"
        devicesOpen = true
        binding.deviceList.visibility = View.VISIBLE
        binding.statusText.text = "${live.size} desktops encontrados. Escolha um:"
        mdnsDiscovery?.stop()
    }

    private fun pickDevice(server: MdnsServer, userRoom: String) {
        lifecycleScope.launch { connectTo(server, userRoom) }
    }

    private suspend fun connectTo(server: MdnsServer, userRoom: String) {
        if (!initiated) return
        var effectiveRoom = userRoom.ifEmpty { server.room.orEmpty() }
        if (effectiveRoom.isEmpty()) {
            effectiveRoom = withContext(Dispatchers.IO) {
                MdnsDiscovery.fetchRoomDirect(server.ip)
            } ?: ""
        }
        if (!initiated) return
        if (effectiveRoom.isEmpty()) {
            binding.statusText.text =
                "Desktop encontrado, mas a sala não foi descoberta. Use AVANÇADO para informar."
            return
        }
        connectSignaling(server.ip, effectiveRoom, server.port)
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
                        startWebRtc()
                    }
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
            if (webRtc?.isStreaming == true) {
                return
            }
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
        binding.localPreview.visibility = View.VISIBLE
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun stopStreaming() {
        initiated = false
        retrying = false

        signaling?.close()
        signaling = null
        mdnsDiscovery?.stop()
        mdnsDiscovery = null
        webRtc?.localVideoTrack?.removeSink(binding.localPreview)
        webRtc?.stop()
        webRtc = null

        deviceCandidates = emptyList()
        binding.deviceList.removeAllViews()
        binding.devicesHeader.visibility = View.GONE
        binding.deviceList.visibility = View.GONE

        binding.serverIpInput.isEnabled = true
        binding.roomCodeInput.isEnabled = true
        binding.cameraButton.isEnabled = false
        binding.mirrorButton.isEnabled = false
        binding.rotateButton.isEnabled = false
        binding.connectButton.text = "CONECTAR E TRANSMITIR"
        binding.connectButton.isEnabled = true
        binding.localPreview.clearImage()
        binding.localPreview.visibility = View.GONE
        binding.statusText.text = "Tocando em CONECTAR, a descoberta é automática."
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