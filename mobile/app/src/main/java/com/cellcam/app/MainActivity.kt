package com.cellcam.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.cellcam.app.databinding.ActivityMainBinding
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.VideoTrack

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var signaling: SignalingClient? = null
    private var webRtc: WebRtcSender? = null
    private var eglBase: EglBase? = null

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
        eglBase = EglBase.create()
        binding.localPreview.init(eglBase!!.eglBaseContext, null)
        binding.localPreview.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        binding.localPreview.setMirror(true)
        binding.localPreview.setZOrderMediaOverlay(true)
    }

    private fun setupButtons() {
        binding.connectButton.setOnClickListener {
            val ip = binding.serverIpInput.text.toString().trim()
            val room = binding.roomCodeInput.text.toString().trim()

            if (ip.isEmpty() || room.length != 6) {
                Toast.makeText(this, "Informe o IP do servidor e um código de 6 dígitos", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            startStreaming(ip, room)
        }
    }

    private fun startStreaming(ip: String, room: String) {
        binding.connectButton.isEnabled = false
        binding.statusText.text = "Conectando ao servidor $ip..."

        signaling = SignalingClient(
            serverIp = ip,
            roomCode = room,
            scope = lifecycleScope,
            listener = object : SignalingClient.Listener {
                override fun onConnected() {
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
                        binding.connectButton.isEnabled = true
                    }
                }

                override fun onClosed() {
                    runOnUiThread {
                        binding.statusText.text = "Conexão com servidor encerrada."
                        binding.connectButton.isEnabled = true
                    }
                }
            }
        )
        signaling?.connect()
    }

    private fun startWebRtc() {
        if (webRtc != null) return

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
        webRtc?.createPeerConnection {
            webRtc?.createOffer()
        }
    }

    private fun showLocalPreview() {
        val track = webRtc?.localVideoTrack ?: return
        track.addSink(binding.localPreview)
        binding.localPreview.visibility = android.view.View.VISIBLE
    }

    override fun onDestroy() {
        super.onDestroy()
        signaling?.close()
        webRtc?.stop()
        binding.localPreview.release()
        eglBase?.release()
    }
}