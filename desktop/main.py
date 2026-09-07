"""
CellCam Desktop - Receptor + Webcam Virtual (Windows)

Uso:
    python main.py                              # usa OBS Virtual Camera (padrao)
    python main.py --backend dry                # teste sem abrir webcam (debug)
    python main.py --no-server                   # nao sobe o signaling (já tem um rodando)
"""
import argparse
import asyncio
import json
import logging
import os
import shutil
import subprocess
import sys
import time
import urllib.request

import qrcode

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("cellcam.main")

if os.environ.get("CELLCAM_DEBUG"):
    logging.getLogger("aiortc").setLevel(logging.DEBUG)

HTTP_PORT = 8080
WS_PORT = 8081
SERVER_DIR = r"C:\Users\MeowA\Documents\CellCam\server"
NODE_CANDIDATES = [
    r"C:\Program Files\nodejs\node.exe",
    shutil.which("node"),
]


def find_node():
    for p in NODE_CANDIDATES:
        if p and p.lower().endswith("node.exe") and os.path.isfile(p):
            return p
    fallback = shutil.which("node")
    if fallback:
        return fallback
    return NODE_CANDIDATES[0] if NODE_CANDIDATES else None


def start_signaling():
    node = find_node()
    if not node:
        logger.error("Node nao encontrado. Inicie o servidor de sinalizacao manualmente.")
        return None
    proc = subprocess.Popen(
        [node, "src/server.js"],
        cwd=SERVER_DIR,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    logger.info("Servidor de sinalizacao iniciado (pid=%d)", proc.pid)
    # espera ficar pronto
    for _ in range(20):
        try:
            with urllib.request.urlopen(f"http://localhost:{HTTP_PORT}/health", timeout=1) as r:
                if r.status == 200:
                    logger.info("Signaling pronto em localhost:%d", HTTP_PORT)
                    return proc
        except Exception:
            time.sleep(0.5)
    logger.warning("Signaling nao respondeu /health; seguindo mesmo assim")
    return proc


def create_room():
    with urllib.request.urlopen(f"http://localhost:{HTTP_PORT}/room", timeout=3) as r:
        data = json.loads(r.read().decode())
    return data["roomCode"]


def print_qr(text: str, filename: str | None = None):
    qr = qrcode.QRCode(border=1)
    qr.add_data(text)
    qr.make(fit=True)
    # Imprime com caracteres ASCII seguros (evita problemas de encoding em consoles Windows)
    matrix = qr.get_matrix()
    for row in matrix:
        print("".join("##" if cell else "  " for cell in row))
    if filename:
        img = qr.make_image(fill_color="black", back_color="white")
        img.save(filename)
        logger.info("QR salvo em: %s", filename)


def get_lan_ip():
    try:
        import socket

        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "localhost"


async def run(args):
    proc = None
    if not args.no_server:
        proc = start_signaling()

    from mdns_publisher import MdnsPublisher

    mdns = MdnsPublisher(WS_PORT)
    mdns.start()
    try:
        room = create_room() if args.room is None else args.room
    except Exception as e:
        logger.error("Nao consegui criar sala: %s", e)
        return 1

    ip = get_lan_ip()
    logger.info("=" * 56)
    logger.info("CELLCAM DESKTOP")
    logger.info("  IP do servidor (para o celular): ws://%s:%d", ip, WS_PORT)
    logger.info("  Codigo da sala:                   %s", room)
    logger.info("  Backend de webcam:                %s", args.backend)
    logger.info("=" * 56)
    print()
    print_qr(f"cellcam:{ip}:{room}", filename=os.path.join(os.path.dirname(__file__), "qr.png"))
    print()
    print("No celular (app CellCam), informe:")
    print(f"   IP: {ip}")
    print(f"   Codigo: {room}")
    print("Depois toque em 'Conectar e transmitir'.")
    print()

    # sinais do receiver (callbacks sincronos)
    def on_state(state):
        # aiortc usa "completed"; libwebrtc usa "connected"
        if state in ("connected", "completed"):
            logger.info(">> CONECTADO! O celular esta transmitindo para a webcam virtual.")
        elif state in ("disconnected", "failed", "closed"):
            logger.info(">> Conexao encerrada (%s).", state)

    def on_error(msg):
        logger.error("Erro: %s", msg)

    from receiver import Client

    # Reconnect automatico do signaling (o app tambem reconecta do lado dele)
    delay = 1.0
    try:
        while True:
            client = Client(f"ws://localhost:{WS_PORT}", room, backend=args.backend, fps=args.fps)
            client.on_state = on_state
            client.on_error = on_error
            try:
                await client.run()
            except (KeyboardInterrupt, asyncio.CancelledError):
                raise
            except Exception as e:
                logger.warning("Conexao com signaling perdida (%s)", e)
            finally:
                if client.receiver is not None:
                    await client.receiver.close()
            logger.info("Tentando reconectar ao signaling em %.0fs...", delay)
            await asyncio.sleep(delay)
            delay = min(delay * 2, 5)
    except KeyboardInterrupt:
        logger.info("Encerrando...")
    finally:
        mdns.stop()
        if proc:
            proc.terminate()
    return 0


def main():
    parser = argparse.ArgumentParser(description="CellCam Desktop")
    parser.add_argument(
        "--backend",
        choices=["obs", "dry"],
        default="obs",
        help="driver de webcam virtual (dry = apenas conta frames, sem abrir camera)",
    )
    parser.add_argument("--no-server", action="store_true", help="nao iniciar signaling local")
    parser.add_argument("--room", default=None, help="usar um codigo de sala especifico")
    parser.add_argument("--fps", type=int, default=30, help="fps da webcam virtual (padrao: 30)")
    args = parser.parse_args()
    try:
        code = asyncio.run(run(args))
    except KeyboardInterrupt:
        code = 0
    sys.exit(code)


if __name__ == "__main__":
    main()