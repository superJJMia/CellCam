# CellCam — Contexto do Projeto

> Arquivo de contexto mantido pelo opencode. Atualizar a cada mudança e commitar junto.
> Última atualização: 2026-09-06 (sessão de lapidação + auto-descoberta).

## Estado atual (o que funciona)
- Pipeline **completo e validado ponta-a-ponta**:
  celular Android → WebRTC/H.264 → signaling local → receptor Python (aiortc) → decodifica → **OBS Virtual Camera** → visível em apps (validado visualmente no OBS).
- Backend de webcam padrão: **`obs`** (`desktop/main.py`). Unity Capture foi testado, **removido** (placeholder verde) e desinstalado.
- **Controles no app**: Parar/Conectar, trocar câmera, **Espelho**, **Girar 90°** (aplicado no PC; preview do celular só espelha, por decisão do usuário).
- **Reconexão automática** dos dois lados (app e receiver) com backoff.
- **Qualidade de vídeo estável**: webcam virtual com tamanho fixo (letterbox); não muda mais de resolução sem aviso.
- **Auto-descoberta**: o desktop publica `cellcam._cellcam._tcp` (mDNS/zeroconf); o app encontra o PC por WiFi ou cabo USB sem digitar IP (com fallback de varredura de subrede — o NsdManager do moto G7 está com bug).

## Decisões técnicas
- Transporte: WebRTC/H.264 (aiortc 1.15.0 + libwebrtc Android M137).
- Sinalização: Node.js `ws`, HTTP 8080 / WS 8081, rooms 6 dígitos; celular = sender, PC = receiver. Escuta em todas as interfaces (0.0.0.0) — funciona WiFi e USB.
- Mobile: Kotlin, `io.github.webrtc-sdk:android:137.7151.05`, Câmera 1280x720@30fps (60fps é meta).
- Webcam virtual: **OBS Virtual Camera** (OBS 32.2.2 instalado).
- Controles via signaling: mensagens `kind: "mirror"` (bool) e `kind: "rotate"` (graus, múltiplo de 90). Receiver aplica **espelho antes do giro** (`_apply_transform`) — espelho=reflexão, giro=rotação (não são equivalentes; mantido ambos por escolha do usuário).
- Estabilização de dimensão no receiver: `_target_size` fixado no primeiro frame e **mantido entre sessões do mesmo processo**; `_fit_letterbox` (Pillow LANCZOS + canvas) redimensiona sem distorcer — a resolução da webcam virtual nunca muda durante a operação.
- Estado de espelho persistente no receiver (`self._mirror`), preservado ao recriar a `CameraBridge` (evita dessincronização com o app).
- mDNS desktop: `zeroconf` + `psutil` em **thread própria** (o `register_service` síncrono do zeroconf 0.151 conflita com o event loop principal → `EventLoopBlocked`; por isso `asyncio.run` em thread dedicada com APIs async).
- Descoberta Android: preferência **cabo USB primeiro** (TRANSPORT_USB/interface rndis-usb-eth), depois WiFi. Campo de IP manual continua funcionando (ex.: `192.168.0.119`).

## Ambiente
- Windows; JDK 17 Temurin; Android SDK (platform-tools, android-34); Node 24; Python 3.12.10 (venv `desktop/.venv`).
- IP local do servidor usado nos testes: `192.168.0.119` (dinâmico — o desktop imprime na hora).
- Celular de teste: **moto G7** (Android 10, API 29), conectado por adb.

## Bugs/fixes conhecidos (importantes)
- aiortc 1.15: sem evento `icecandidate` — candidatos vêm no SDP após `setLocalDescription`; trickle via `candidate_from_sdp`.
- **mDNS no Android 10 (moto G7)**: `NsdManager.onStartDiscoveryFailed(0)` persistente (falha interna do aparelho, comum em API 29). Contorno: app faz retry, e se falhar ou demorar, **varre a subrede local** (TCP porta 8081, ~40 paralelos, cabo USB primeiro) até achar o desktop.
- Receiver: `CameraBridge` NÃO recria a webcam por mudança de resolução (letterbox resolve); só recria no `_reset` preservando `mirror`.
- Track sender deve retornar `av.VideoFrame` com `pts`/`time_base` (API do aiortc 1.15).
- Build Android com Gradle é lento/teimoso com caching: usar `clean` se necessário (`assembleDebug` com `*>` redirect para log).
- venv Python em Windows consome 2 processos (shim + interpretador) — não é bug.

## Riscos pendentes
- App Câmera do Windows e Gerenciador de Dispositivos **não** listam câmeras virtuais (limitações do Windows; OBS/Meet/Teams/Zoom/Discord funcionam).
- mDNS do moto G7 depende do fallback de varredura (funciona, mas é mais lento ~2-6s; o mDNS continua primeiro em aparelhos sem o bug).
- Com cabo USB + WiFi ativos, ICE pode escolher rota WiFi para a mídia mesmo com signaling por cabo (prioridade do dispositivo; aceitável).

## Próximos passos
1. Validar no app consumidor real (Meet/Teams/Zoom) selecionando "OBS Virtual Camera".
2. Fase 1 restante: melhorar resolução (720p+) e estabilidade; revisar prioridade de rota ICE por cabo.
3. Fase 2: filtros/efeitos (decisão de lib) e Fase 3: refinamentos/instaladores.

## Histórico (cronológico)
- 2026-09-06 — Fase 1 MVP concluída e commitada (`a0f1bf0`): toolchain, signaling Node.js (7/7 testes), app Android (~50MB), receptor Python (dry/obs/unitycapture + QR), ajustes E2E aiortc 1.15, OBS Virtual Camera validada, GitHub público https://github.com/superJJMia/CellCam. Docs `AGENTS.md`/`CONTEXT.md` commitados (`727c9fa`).
- 2026-09-06 — **Lapidação Fase 1** (a commitar): UX do app (Parar, trocar câmera, espelho, girar, manter tela ativa), reconexão automática com backoff (1s→10s) + renegociação WebRTC, espelho e giro E2E via signaling (espelho antes do giro), resolução da webcam fixa (letterbox), tamanho nunca muda, start.cmd e README.
- 2026-09-06 — **Auto-descoberta do desktop** (a commitar): mDNS (zeroconf `cellcam._cellcam._tcp`, thread própria) + app com descoberta (mDNS com retry → fallback varredura de subrede, cabo USB primeiro), porta personalizável no `SignalingClient`, `CHANGE_WIFI_MULTICAST_STATE`.