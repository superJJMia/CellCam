"""
Receptor WebRTC do CellCam (desktop).

Recebe o vídeo H.264 do celular via WebRTC (aiortc) e injeta os frames
na webcam virtual do Windows (pyvirtualcam -> OBS Virtual Camera).
"""
import asyncio
import json
import logging

import numpy as np
import pyvirtualcam
from aiortc import RTCPeerConnection, RTCIceServer, RTCConfiguration, RTCSessionDescription

logger = logging.getLogger("cellcam.receiver")


class CameraBridge:
    """Escreve frames numpy (RGB) na webcam virtual."""

    def __init__(self, backend: str = "obs", fps: int = 30):
        self.backend = backend
        self.fps = fps
        self.cam: pyvirtualcam.Camera | None = None
        self.frame_count = 0
        self.mirror = False

    def send(self, frame: np.ndarray):
        if self.mirror:
            frame = frame[:, ::-1, :]
        if self.backend == "dry":
            self.frame_count += 1
            return
        h, w = frame.shape[:2]
        if self.cam is not None and (self.cam.width != w or self.cam.height != h):
            logger.info(
                "Resolucao mudou: recriando webcam virtual (%dx%d -> %dx%d)",
                self.cam.width, self.cam.height, w, h,
            )
            self.cam.close()
            self.cam = None
        if self.cam is None:
            try:
                self.cam = pyvirtualcam.Camera(
                    width=w, height=h, fps=self.fps, backend=self.backend
                )
                logger.info("Webcam virtual criada: %s (%dx%d)", self.cam.device, w, h)
            except RuntimeError as e:
                logger.error("Nao foi possivel abrir webcam virtual (%s): %s", self.backend, e)
                raise
        self.cam.send(frame)
        self.cam.sleep_until_next_frame()
        self.frame_count += 1

    def close(self):
        if self.cam is not None:
            self.cam.close()
            logger.info("Webcam virtual fechada (frames enviados: %d)", self.frame_count)


class Receiver:
    """Lado 'receiver' de uma sessao WebRTC (PC)."""

    def __init__(self, signaling_send, backend: str = "obs", fps: int = 30):
        self.pc: RTCPeerConnection | None = None
        self._send = signaling_send  # async func(kind, payload)
        self._video_task: asyncio.Task | None = None
        self.bridge = CameraBridge(backend=backend, fps=fps)
        self.on_state = lambda state: None
        self.on_error = lambda message: None
        # Estado de transformacao (persistente entre sessoes do receptor)
        self._mirror = False
        self._rotate_steps = 0  # 0=0°, 1=90° (anti-horario), 2=180°, 3=270°
        # Tamanho da webcam virtual: fixado no primeiro frame do processo.
        # A partir daí a imagem e sempre enquadrada (letterbox) nesse tamanho,
        # evitando que a webcam mude de resolucao "do nada".
        self._target_size: tuple[int, int] | None = None

    async def handle_offer(self, data: dict):
        if self.pc is not None:
            logger.warning("Nova offer recebida; encerrando sessao anterior")
            await self._reset()

        # NOTA: _target_size NAO e resetado aqui (mantem a resolucao da webcam).

        config = RTCConfiguration(
            iceServers=[
                RTCIceServer(urls="stun:stun.l.google.com:19302"),
                RTCIceServer(urls="stun:stun1.l.google.com:19302"),
            ]
        )
        self.pc = RTCPeerConnection(configuration=config)
        self.pc.on("iceconnectionstatechange", lambda: self._ice_state_changed())

        @self.pc.on("connectionstatechange")
        async def on_connection_state_change():
            logger.info("connectionState: %s", self.pc.connectionState)
            try:
                logger.info("dtls state: %s", self.pc.dtlsTransport.state)
            except Exception:
                pass

        async def _poll_dtls():
            try:
                logger.info("dtls poll: %s", self.pc.dtlsTransport.state)
            except Exception:
                pass

        @self.pc.on("icecandidate")
        async def on_ice_candidate(candidate):
            if candidate is None:
                return
            logger.info("Enviando ICE candidato do receiver: %s", candidate.candidate[:80])
            await self._send(
                "ice",
                {
                    "kind": "ice",
                    "candidate": candidate.candidate,
                    "sdpMid": candidate.sdpMid,
                    "sdpMLineIndex": candidate.sdpMLineIndex,
                },
            )

        @self.pc.on("track")
        async def on_track(track):
            logger.info("Track recebida (kind=%s)", track.kind)
            if track.kind != "video":
                return
            self._video_task = asyncio.ensure_future(self._consume_video(track))

        offer = RTCSessionDescription(sdp=data["sdp"], type="offer")
        await self.pc.setRemoteDescription(offer)

        answer = await self.pc.createAnswer()
        await self.pc.setLocalDescription(answer)

        # O SDP final (com ICE candidates gather) e o que deve ir ao sender
        final_sdp = self.pc.localDescription.sdp
        await self._send(
            "answer",
            {"kind": "answer", "type": "answer", "sdp": final_sdp},
        )
        logger.info("Answer enviado (candidates inclusos)")

    async def _consume_video(self, track):
        count = 0
        while True:
            try:
                frame = await track.recv()
            except Exception as e:
                logger.info("Fim da track de video: %s", e)
                break
            try:
                img = frame.to_ndarray(format="rgb24")
                img = self._apply_transform(img)
                img = self._fit_letterbox(img)
                self.bridge.send(img)
                count += 1
                if count % 30 == 0:
                    logger.info("Frames recebidos: %d", count)
                    if self.bridge.backend == "dry":
                        self.bridge.frame_count = count
            except Exception as e:
                logger.warning("Erro ao processar frame: %s", e)

    async def handle_ice(self, data: dict):
        if self.pc is None:
            logger.warning("ICE antes da offer; ignorando")
            return
        from aiortc.sdp import candidate_from_sdp

        cand = data.get("candidate") or ""
        logger.info("ICE candidato recebido (mline=%s): %s", data.get("sdpMLineIndex"), cand[:80])
        sdp = cand.split(":", 1)[1] if cand.startswith("candidate:") else cand
        candidate = candidate_from_sdp(sdp)
        candidate.sdpMid = data.get("sdpMid") or "0"
        candidate.sdpMLineIndex = data.get("sdpMLineIndex") or 0
        await self.pc.addIceCandidate(candidate)

    def _ice_state_changed(self):
        if self.pc is None:
            return
        state = self.pc.iceConnectionState
        logger.info("ICE state: %s", state)
        self.on_state(state)

    async def set_mirror(self, enabled: bool):
        """Espelha/desespelha horizontalmente o fluxo injetado na webcam."""
        enabled = bool(enabled)
        if self._mirror == enabled:
            return
        self._mirror = enabled
        self.bridge.mirror = enabled
        logger.info("Espelho vertical (mirror) aplicado no fluxo" if enabled else "Espelho desativado")

    async def set_rotate(self, degrees: int):
        """Gira o fluxo em 90°, 180° ou 270° (antes do espelho)."""
        steps = (int(degrees) % 360) // 90
        if self._rotate_steps == steps:
            return
        self._rotate_steps = steps
        logger.info("Rotacao aplicada: %d graus", steps * 90)

    def _apply_transform(self, img: np.ndarray) -> np.ndarray:
        # Espelho ANTES do giro: o PC replica o espelho visto no preview do app,
        # e a rotacao e aplicada por cima (como o usuario gira apenas no PC).
        if self._mirror:
            img = img[:, ::-1, :]
        if self._rotate_steps:
            img = np.rot90(img, k=self._rotate_steps)
        return img

    def _fit_letterbox(self, img: np.ndarray) -> np.ndarray:
        """Enquadra img no tamanho fixo da webcam preservando proporcao.

        Se a resolucao da transmissao variar (encoder/giro), a imagem e
        redimensionada mantendo o aspect ratio e centralizada num fundo preto,
        de modo que a webcam virtual nunca mude de tamanho.
        """
        h, w = img.shape[:2]
        if self._target_size is None:
            self._target_size = (w, h)
            logger.info("Resolucao da webcam fixada: %dx%d", w, h)
            return img
        tw, th = self._target_size
        if w == tw and h == th:
            return img

        scale = min(tw / w, th / h)
        nw = max(1, int(round(w * scale)))
        nh = max(1, int(round(h * scale)))

        from PIL import Image

        pil = Image.fromarray(img)
        if (nw, nh) != (w, h):
            pil = pil.resize((nw, nh), Image.LANCZOS)

        canvas = np.zeros((th, tw, 3), dtype=np.uint8)
        x0, y0 = (tw - nw) // 2, (th - nh) // 2
        canvas[y0:y0 + nh, x0:x0 + nw] = np.asarray(pil)
        return canvas

    async def _reset(self):
        if self._video_task:
            self._video_task.cancel()
            self._video_task = None
        if self.pc is not None:
            try:
                await self.pc.close()
            except Exception:
                pass
            self.pc = None
        if self.bridge.frame_count:
            self.bridge.close()
            # recria para a proxima sessao, preservando o estado de espelho
            self.bridge = CameraBridge(backend=self.bridge.backend, fps=self.bridge.fps)
            self.bridge.mirror = self._mirror

    async def close(self):
        await self._reset()


class Client:
    """Conecta ao servidor de sinalizacao como receiver e orquestra o Receiver."""

    def __init__(self, ws_url, room_code, backend: str = "obs", fps: int = 30):
        self.ws_url = ws_url
        self.room_code = room_code
        self.backend = backend
        self.fps = fps
        self.receiver: Receiver | None = None
        self.on_state = lambda state: None
        self.on_error = lambda message: None

    async def _send(self, ws, kind, payload):
        await ws.send(json.dumps({"type": "signal", "data": payload}))

    async def run(self):
        import websockets

        async with websockets.connect(self.ws_url) as ws:
            await ws.send(
                json.dumps(
                    {
                        "type": "join",
                        "payload": {"roomCode": self.room_code, "role": "receiver"},
                    }
                )
            )
            logger.info("Conectado ao signaling como receiver (sala %s)", self.room_code)

            self.receiver = Receiver(
                signaling_send=lambda kind, payload: self._send(ws, kind, payload),
                backend=self.backend,
                fps=self.fps,
            )
            self.receiver.on_state = self.on_state
            self.receiver.on_error = self.on_error

            async for raw in ws:
                msg = json.loads(raw)
                mtype = msg.get("type")
                if mtype == "signal":
                    data = msg.get("data", {})
                    kind = data.get("kind")
                    if kind == "offer":
                        await self.receiver.handle_offer({"sdp": data.get("sdp"), "type": "offer"})
                    elif kind == "ice":
                        await self.receiver.handle_ice(data)
                    elif kind == "mirror":
                        await self.receiver.set_mirror(data.get("mirror", False) is True)
                    elif kind == "rotate":
                        await self.receiver.set_rotate(data.get("degrees", 0))
                elif mtype == "error":
                    self.on_error(msg.get("message", "erro"))