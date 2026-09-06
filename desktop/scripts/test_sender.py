"""
Sender de teste (fake "celular") usando aiortc.

Gera frames de video sinteticos e transmite para o receiver pela sinalizacao,
sem precisar de um aparelho fisico. Usado para validar o pipeline E2E.

Uso:
    python scripts/test_sender.py --room 420896 [--server ws://localhost:8081]
"""
import argparse
import asyncio
import json
import logging

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(name)s %(message)s")
logging.getLogger("aiortc").setLevel(logging.DEBUG)

import numpy as np
import av
from aiortc import (
    RTCPeerConnection,
    RTCIceServer,
    RTCConfiguration,
    RTCSessionDescription,
    VideoStreamTrack,
)
from aiortc.mediastreams import VideoFrame
import websockets

W = 640
H = 480


class FakeStream(VideoStreamTrack):
    """VideoStreamTrack que gera um gradiente animado."""

    def __init__(self):
        super().__init__()
        self.counter = 0

    async def recv(self):
        pts, _time_base = await self.next_timestamp()
        self.counter += 1
        if self.counter % 30 == 1:
            print(f"[sender] recv() chamado #{self.counter}")
        phase = (self.counter % 60) / 60.0
        yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
        r = ((xx / W) * 255).astype(np.uint8)
        g = ((yy / H) * 255).astype(np.uint8)
        b = np.full((H, W), phase * 255, dtype=np.uint8)
        frame = np.dstack([r, g, b])
        vf = av.VideoFrame.from_ndarray(frame, format="rgb24")
        vf.pts = pts
        vf.time_base = _time_base
        return vf


async def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--room", required=True)
    parser.add_argument("--server", default="ws://localhost:8081")
    parser.add_argument("--seconds", type=int, default=8)
    args = parser.parse_args()

    pc = RTCPeerConnection(
        configuration=RTCConfiguration(
            iceServers=[RTCIceServer(urls="stun:stun.l.google.com:19302")]
        )
    )
    track = FakeStream()
    pc.addTrack(track)

    rtstate = {"ws": None}

    @pc.on("icecandidate")
    async def on_ice(candidate):
        if candidate is None or rtstate["ws"] is None:
            print("[sender] icecandidate event (None ou ws pendente)")
            return
        print(f"[sender] enviando ICE candidate: {candidate.candidate[:60]}")
        await rtstate["ws"].send(
            json.dumps(
                {
                    "type": "signal",
                    "data": {
                        "kind": "ice",
                        "candidate": candidate.candidate,
                        "sdpMid": candidate.sdpMid,
                        "sdpMLineIndex": candidate.sdpMLineIndex,
                    },
                }
            )
        )

    async with websockets.connect(args.server) as ws:
        rtstate["ws"] = ws
        await ws.send(
            json.dumps(
                {"type": "join", "payload": {"roomCode": args.room, "role": "sender"}}
            )
        )
        print(f"[sender] conectado. Sala {args.room}. Transmitindo por {args.seconds}s...")

        async def listener():
            async for raw in ws:
                msg = json.loads(raw)
                if msg.get("type") != "signal":
                    continue
                data = msg.get("data", {})
                kind = data.get("kind")
                if kind == "answer":
                    print("[sender] answer recebido; iniciando stream")
                    await pc.setRemoteDescription(
                        RTCSessionDescription(sdp=data["sdp"], type="answer")
                    )
                elif kind == "ice":
                    from aiortc import RTCIceCandidate

                    print(f"[sender] ICE candidato recebido: {data.get('candidate','')[:50]}")
                    await pc.addIceCandidate(
                        RTCIceCandidate(
                            sdpMid=data.get("sdpMid") or "0",
                            sdpMLineIndex=data.get("sdpMLineIndex") or 0,
                            candidate=data.get("candidate") or "",
                        )
                    )

        listen_task = asyncio.ensure_future(listener())

        offer = await pc.createOffer()
        await pc.setLocalDescription(offer)
        await ws.send(
            json.dumps(
                {
                    "type": "signal",
                    "data": {"kind": "offer", "type": offer.type, "sdp": pc.localDescription.sdp},
                }
            )
        )
        print("[sender] offer enviado")

        await asyncio.sleep(args.seconds)
        listen_task.cancel()
        await pc.close()

    print("[sender] teste finalizado")


if __name__ == "__main__":
    asyncio.run(main())