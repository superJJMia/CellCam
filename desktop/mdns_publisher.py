"""
CellCam mDNS publisher - publica o signaling na rede via DNS-SD (_cellcam._tcp).

Permite que o app encontre o desktop automaticamente, tanto por WiFi quanto
por cabo USB (tethering), sem digitar IP.

Roda numa thread dedicada com seu proprio event loop: o zeroconf (0.151)
aplica um timeout no `register_service` sincrono que conflita com o loop
principal do receiver, por isso usamos as APIs async em loop proprio.
"""
import asyncio
import logging
import socket
import threading

import psutil
from zeroconf import IPVersion, InterfaceChoice, ServiceInfo, Zeroconf

logger = logging.getLogger("cellcam.mdns")

SERVICE_TYPE = "_cellcam._tcp.local."
INSTANCE = "cellcam"
SERVICE = f"{INSTANCE}.{SERVICE_TYPE}"


def get_ipv4_addresses() -> list[str]:
    """Todos os enderecos IPv4 das interfaces ativas (exceto loopback e link-local)."""
    ips: list[str] = []
    for _name, addrs in psutil.net_if_addrs().items():
        for addr in addrs:
            if addr.family == socket.AF_INET:
                ip = addr.address
                if ip.startswith("127.") or ip.startswith("169.254."):
                    continue
                if ip not in ips:
                    ips.append(ip)
    return ips


class MdnsPublisher:
    def __init__(self, port: int):
        self.port = port
        self._thread: threading.Thread | None = None
        self._stop = threading.Event()

    def start(self) -> None:
        if self._thread is not None and self._thread.is_alive():
            return
        self._stop.clear()
        self._thread = threading.Thread(target=self._run, name="cellcam-mdns", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=2)
            self._thread = None

    def _run(self) -> None:
        try:
            asyncio.run(self._serve())
        except Exception as e:  # mDNS nunca deve derrubar o receiver
            logger.warning("Falha ao publicar via mDNS: %s", e)

    async def _serve(self) -> None:
        zc: Zeroconf | None = None
        try:
            zc = Zeroconf(ip_version=IPVersion.V4Only, interfaces=InterfaceChoice.All)
            info = ServiceInfo(
                SERVICE_TYPE,
                SERVICE,
                port=self.port,
                addresses=[socket.inet_aton(ip) for ip in get_ipv4_addresses()],
                properties={"name": "CellCam Desktop"},
            )
            await zc.async_register_service(info)
            ips = ", ".join(get_ipv4_addresses())
            logger.info(
                "mDNS ativo: %s na porta %d (interfaces: %s)",
                SERVICE,
                self.port,
                ips or "(nenhuma)",
            )
            await asyncio.get_running_loop().run_in_executor(None, self._stop.wait)
        finally:
            if zc is not None:
                try:
                    await zc.async_close()
                except Exception:
                    pass