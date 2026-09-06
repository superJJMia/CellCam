# CellCam — Contexto do Projeto

> Arquivo de contexto mantido pelo opencode. Atualizar a cada mudança e commitar junto.
> Última atualização: 2026-09-06 (commit `a0f1bf0`).

## Estado atual (o que funciona)
- Pipeline **completo e validado ponta-a-ponta**:
  celular Android → WebRTC/H.264 → signaling local → receptor Python (aiortc) → decodifica → **OBS Virtual Camera** → visível em apps (validado visualmente no OBS).
- Backend de webcam padrão: **`obs`** (`desktop/main.py`). Originalmente testou-se Unity Capture, mas o driver não recebia dados do pyvirtualcam (ficava verde/placeholder) e foi **removido do sistema e do código**.

## Decisões técnicas
- Transporte: WebRTC/H.264 (aiortc 1.15.0 + libwebrtc Android M137).
- Sinalização: Node.js `ws`, HTTP 8080 / WS 8081, rooms 6 dígitos; celular = sender, PC = receiver.
- Mobile: Kotlin, `io.github.webrtc-sdk:android:137.7151.05`, Câmera 1280x720@30fps (60fps é meta).
- Webcam virtual: **OBS Virtual Camera** (OBS 32.2.2 instalado). Unity Capture desistalado.
- Filtros estilo Snapchat: decisão adiada (biblioteca pública da Snap não é para terceiros).

## Ambiente
- Windows; JDK 17 Temurin; Android SDK (platform-tools, android-34); Node 24; Python 3.12.10 (venv `desktop/.venv`).
- IP local do servidor usado nos testes: `192.168.0.119` (dinâmico — o desktop imprime na hora).
- Celular de teste: **moto G7** (Android 10, API 29), conectado por adb.

## Bugs/fixes conhecidos (importantes)
- aiortc 1.15: sem evento `icecandidate` — candidatos vêm no SDP após `setLocalDescription`.
- Receiver: `RTCIceCandidate(...)` agora via `aiortc.sdp.candidate_from_sdp(candidate_str)` (trickle do Android).
- Receiver aceita nova sessão resetando o PeerConnection anterior (senão rejeita replay).
- `CameraBridge` recria a webcam quando o celular muda de resolução (ex.: 360x640 → 720x1280).
- Track sender deve retornar `av.VideoFrame` com `pts`/`time_base` (API do aiortc 1.15 não tem `to_frame`/`from_ndarray`).

## Riscos pendentes
- App Câmera do Windows e Gerenciador de Dispositivos **não** listam câmeras virtuais (limitação do Windows; OBS/Meet/Teams/Zoom/Discord funcionam).

## Próximos passos
1. Validar no app consumidor real (Meet/Teams/Zoom) selecionando "OBS Virtual Camera".
2. Fase 1 restante: melhorar resolução (720p+) e estabilidade, tratamento de reconexão.
3. Fase 2: filtros/efeitos (decisão de lib) e Fase 3: refinamentos/instaladores.

## Histórico (cronológico)
- 2026-09-06 — Fase 1 MVP concluída e commitada (`a0f1bf0`). Etapas:
  1. Toolchain Windows (JDK, SDK, Gradle) e projetos criados (`server`, `mobile`, `desktop`, `ESCOPO.md`).
  2. Sinalização Node.js + testes E2E do signaling (7/7).
  3. App Android compilado (APK ~50MB) e instalado no moto G7.
  4. Receptor Python: backend `dry`/`obs`/`unitycapture`, QR, auto-start do signaling.
  5. Ajustes E2E (aiortc 1.15): candidates no SDP do answer, frame `av.VideoFrame`, reset de sessão, recriação de webcam por resolução.
  6. Unity Capture instalado → entregava frames mas placeholder verde → **desinstalado** (exclusão da DLL agendada para boot).
  7. OBS 32.2.2 instalado; OBS Virtual Camera validada visualmente com a câmera do celular (~30fps).
  8. Código limpo: backend padrão `obs`, ESCOPO.md atualizado, README de contexto criado.
  9. Repositório GitHub público criado: https://github.com/superJJMia/CellCam