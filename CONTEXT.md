# CellCam — Contexto do Projeto

> Arquivo de contexto mantido pelo opencode. Atualizar a cada mudança e commitar junto.
> Última atualização: 2026-09-06 (sessão de lapidação + auto-descoberta + sala automática + nova UI).

## Estado atual (o que funciona)
- Pipeline **completo e validado ponta-a-ponta**:
  celular Android → WebRTC/H.264 → signaling local → receptor Python (aiortc) → decodifica → **OBS Virtual Camera** → visível em apps (validado visualmente no OBS).
- Backend de webcam padrão: **`obs`** (`desktop/main.py`). Unity Capture foi testado, **removido** (placeholder verde) e desinstalado.
- **Controles no app**: Parar/Conectar, trocar câmera, **Espelho**, **Girar 90°** (aplicado no PC; preview do celular só espelha, por decisão do usuário).
- **UI reestilizada** (paleta ciano-tech, sem temática sith): referência `super-mia-chi.vercel.app` mas com accent `#22E0FF` (ciano) sobre fundo quase preto; painéis com borda fina `#1E2A38`. Tela enxuta: **sem título/subtítulo** e sem campos na frente — só o botão principal + lista expansível. Controles de câmera agora são **botões de ícone** (câmera, girar 90°, espelho — `ImageButton` + vector + `contentDescription`), sem texto para não quebrar em telas estreitas.
- **Reconexão automática** dos dois lados (app e receiver) com backoff.
- **Qualidade de vídeo estável**: webcam virtual com tamanho fixo (letterbox); não muda mais de resolução sem aviso.
- **Auto-descoberta**: o desktop publica `cellcam._cellcam._tcp` (mDNS/zeroconf) com o **código da sala no TXT**; o app encontra o PC por WiFi (transporte principal da mídia) sem digitar IP **nem código** (fallback de varredura de subrede + `GET /api/room` — o NsdManager do moto G7 está com bug).

## Decisões técnicas
- Transporte: WebRTC/H.264 (aiortc 1.15.0 + libwebrtc Android M137).
- Sinalização: Node.js `ws`, HTTP 8080 / WS 8081, rooms 6 dígitos; celular = sender, PC = receiver. Escuta em todas as interfaces (0.0.0.0) — funciona WiFi e USB.
- Mobile: Kotlin, `io.github.webrtc-sdk:android:137.7151.05`, Câmera 1280x720@30fps (60fps é meta).
- Webcam virtual: **OBS Virtual Camera** (OBS 32.2.2 instalado).
- Controles via signaling: mensagens `kind: "mirror"` (bool) e `kind: "rotate"` (graus, múltiplo de 90). Receiver aplica **espelho antes do giro** (`_apply_transform`) — espelho=reflexão, giro=rotação (não são equivalentes; mantido ambos por escolha do usuário).
- Estabilização de dimensão no receiver: `_target_size` fixado no primeiro frame e **mantido entre sessões do mesmo processo**; `_fit_letterbox` (Pillow LANCZOS + canvas) redimensiona sem distorcer — a resolução da webcam virtual nunca muda durante a operação.
- Estado de espelho persistente no receiver (`self._mirror`), preservado ao recriar a `CameraBridge` (evita dessincronização com o app).
- **Sala automática (sem digitar código)**: `server.js` ganhou `GET /api/room` (retorna a sala de um receiver ativo, senão a última criada); `mdns_publisher` publica `room` no TXT do mDNS. No app, IP e sala vazios = descoberta completa: a sala vem do TXT (mDNS) ou do `/api/room` (varredura). Sala digitada pelo usuário prevalece.
- mDNS desktop: `zeroconf` + `psutil` em **thread própria** (o `register_service` síncrono do zeroconf 0.151 conflita com o event loop principal → `EventLoopBlocked`; por isso `asyncio.run` em thread dedicada com APIs async).
- Descoberta Android: preferência **WiFi primeiro** (transporte definitivo da mídia), depois cabo USB/rede móvel. Detecção com **bind dos sockets à `Network`** coletada via `ConnectivityManager.allNetworks` (necessário quando a rede não é a "default"). Campo de IP manual continua funcionando (ex.: `192.168.0.119`).
- **Cabo USB arquivado** (decisão do usuário): o Android 10 (moto G7) só registra a rede `rndis0` no `ConnectivityManager` quando o tethering tem um **upstream** (WiFi ou dados móveis); sem ele, o app vê "nenhuma rede ativa". Com WiFi ativo, o libwebrtc não expõe a interface `rndis0` como candidato ICE → a mídia obrigatoriamente vai por WiFi. Conclusão: cabo demandaria WiFi off + dados móveis on; o usuário optou por manter WiFi como transporte e arquivar o cabo.

## Ambiente
- Windows; JDK 17 Temurin; Android SDK (platform-tools, android-34); Node 24; Python 3.12.10 (venv `desktop/.venv`).
- IP local do servidor usado nos testes: `192.168.0.119` (dinâmico — o desktop imprime na hora).
- Celular de teste: **moto G7** (Android 10, API 29), conectado por adb.

## Bugs/fixes conhecidos (importantes)
- aiortc 1.15: sem evento `icecandidate` — candidatos vêm no SDP após `setLocalDescription`; trickle via `candidate_from_sdp`.
- **mDNS no Android 10 (moto G7)**: `NsdManager.onStartDiscoveryFailed(0)` persistente (falha interna do aparelho, comum em API 29). Contorno: app faz retry, e se falhar ou demorar, **varre a subrede local** (TCP porta 8081, ~40 paralelos, **WiFi primeiro**) até achar o desktop.
- Receiver: `CameraBridge` NÃO recria a webcam por mudança de resolução (letterbox resolve); só recria no `_reset` preservando `mirror`.
- Track sender deve retornar `av.VideoFrame` com `pts`/`time_base` (API do aiortc 1.15).
- Build Android com Gradle é lento/teimoso com caching: usar `clean` se necessário (`assembleDebug` com `*>` redirect para log).
- venv Python em Windows consome 2 processos (shim + interpretador) — não é bug.

## Riscos pendentes
- App Câmera do Windows e Gerenciador de Dispositivos **não** listam câmeras virtuais (limitações do Windows; OBS/Meet/Teams/Zoom/Discord funcionam).
- mDNS do moto G7 depende do fallback de varredura (funciona, mas é mais lento ~2-6s; o mDNS continua primeiro em aparelhos sem o bug).
- Com WiFi com sinal fraco, a qualidade do vídeo pode sofrer (não há mais o cabo como alternativa de mídia — arquivado).

## Próximos passos
1. Validar no app consumidor real (Meet/Teams/Zoom) selecionando "OBS Virtual Camera".
2. Fase 1 restante: melhorar resolução (720p+) e estabilidade da mídia via WiFi.
3. Fase 2: filtros/efeitos (decisão de lib) e Fase 3: refinamentos/instaladores.

## Histórico (cronológico)
- 2026-09-06 — Fase 1 MVP concluída e commitada (`a0f1bf0`): toolchain, signaling Node.js (7/7 testes), app Android (~50MB), receptor Python (dry/obs/unitycapture + QR), ajustes E2E aiortc 1.15, OBS Virtual Camera validada, GitHub público https://github.com/superJJMia/CellCam. Docs `AGENTS.md`/`CONTEXT.md` commitados (`727c9fa`).
- 2026-09-06 — **Lapidação Fase 1** (a commitar): UX do app (Parar, trocar câmera, espelho, girar, manter tela ativa), reconexão automática com backoff (1s→10s) + renegociação WebRTC, espelho e giro E2E via signaling (espelho antes do giro), resolução da webcam fixa (letterbox), tamanho nunca muda, start.cmd e README.
- 2026-09-06 — **Auto-descoberta do desktop** (commitado junto): mDNS (zeroconf `cellcam._cellcam._tcp`, thread própria) + app com descoberta (mDNS com retry → fallback varredura de subrede), porta personalizável no `SignalingClient`, `CHANGE_WIFI_MULTICAST_STATE`, bind de sockets por `Network` (`ConnectivityManager`).
- 2026-09-06 — **Cabo USB arquivado / WiFi definitivo**: diagnóstico provou que no Android 10 a rede `rndis0` só existe para apps com upstream (WiFi/dados móveis) e o libwebrtc não gera candidato ICE na interface USB com WiFi ativo. Digitação: mídia por cabo só com WiFi off + dados móveis on. Usuário decidiu manter WiFi como transporte da mídia; descoberta prioriza WiFi (rank 0).
- 2026-09-06 — **Sala automática (sem digitar código)**: `GET /api/room` no signaling (sala de receiver ativo ou última criada), `room` no TXT do mDNS, e app conectando com IP e sala vazios (TXT → varredura → `/api/room`). Validado: `/api/room`=sala atual, TXT mDNS com `room=722385`.
- 2026-09-06 — **Nova UI (ciano-tech)**: layout com layout com painéis "Dispositivos encontrados" (expansível, para >1 desktop) e "Avançado" (expansível, IP/sala manuais). Campos removidos da tela principal.
- 2026-09-06 — **Refinamento da UI**: título/subtítulo removidos; controles de câmera viram botões de ícone (vetores + `contentDescription`); textos e alturas dos botões ajustados para não quebrar.
- 2026-09-06 — **Fix: loop de conectar/desconectar ao reconectar**: `startWebRtc()` agora roda na UI thread (antes rodava na thread do WebSocket do OkHttp, causando corrida com `stopStreaming`); `onPeerJoined` duplicado com sessão já transmitindo (`webRtc.isStreaming == true`) é ignorado em vez de recriar o PeerConnection — a câmera não era reiniciada, só o PC/sessão oscilava.
- 2026-09-06 — **Fix: crash ao parar (GLException 1282 no EglThread)**: o `stopStreaming()` liberava `localPreview` + `eglBase` enquanto o renderer ainda desenhava frames → contexto EGL destruído com render thread vivo. Agora o renderer e o `eglBase` ficam vivos por toda a vida da Activity (release só no `onDestroy`); no stop apenas remove o sink do track (`track.removeSink`) e esconde/limpa o preview.
- 2026-09-06 — **Modo horizontal (landscape)**: `screenOrientation="sensor"` + `configChanges` (não recria a Activity ao girar, preservando a transmissão WebRTC). `res/layout-land/activity_main.xml`: a câmera fica **intocada** (preview sem rotação, o giro/espelho seguem via botões enviados ao receiver) e o menu inferior do retrato vira **menu lateral (barra à direita)** , mantendo a mesma sequência de controles reorganizados para caber na tela horizontal. `onConfigurationChanged` re-infla o layout preservando status, sessão, painéis e listas; o re-nexo do preview usa `post` para aguardar o novo surface.