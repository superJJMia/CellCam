# CellCam — Webcam Virtual pelo Celular

App que transforma o celular em **webcam virtual real** (reconhecida pelo Windows) via rede local, com suporte futuro a filtros estilo Snapchat.

---

## Visão Geral

| Componente | Papel |
|---|---|
| **Mobile** (Android / Kotlin) | Captura a câmera, aplica filtros (futuro), envia vídeo |
| **Desktop** (Windows) | Recebe o vídeo e expõe como **webcam virtual no sistema** |
| **Sinalização** | Servidor local mínimo que conecta celular↔PC |

**Fluxo:**

```
📱 Android (Kotlin)
  Camera2 → MediaCodec (H.264 HW encode) → WebRTC
        │  (filtros AR aqui no futuro)
        ▼
🖥️ Windows Desktop
  WebRTC → decodifica H.264 (FFmpeg) → frame raw
        ▼
  Driver OBS Virtual Camera → webcam virtual
        ▼
  Zoom / Discord / OBS / Teams / qualquer app
```

---

## Decisões Técnicas Confirmadas

| Item | Escolha | Motivo |
|---|---|---|
| Transporte de vídeo | **WebRTC / H.264** | Única via realista p/ 60fps + qualidade |
| Desktop | **Windows** | Ecossistema nativo de driver DirectShow |
| Mobile | **Android nativo (Kotlin)** | Melhor acesso à câmera/performance |
| Webcam virtual | **OBS Virtual Camera** (via OBS) | Padrão de mercado, reconhecido pela maioria dos apps |
| Sinalização | Servidor local Node.js (WS) | Celular e PC na mesma rede via QR code |

---

## Arquitetura Mobile (Android)

- **Captura**: Camera2 API (ou CameraX) — melhor controle de fps/resolução
- **Encode**: MediaCodec (H.264 **hardware**) — 60fps sem queimar CPU
- **Filtros (fase 2)**: pipeline GPU (OpenGL ES / RenderScript / ML Kit face filters) **antes** do encode
- **Rede**: WebRTC via `org.webrtc:google-webrtc` (libwebrtc) — PeerConnection direto
- **Sinalização**: WebSocket para o servidor local (pega URL via QR code / configuração)

### Requisitos de 60fps
- Câmera que suporte 60fps (`CameraCharacteristics`) — senão, fallback 30fps
- `MediaCodec` com frame rate 60 e bitrate alto (ex: 10-15 Mbps p/ 1080p)
- OBS Virtual Camera suporta 60fps? → **verificação na Fase 1** (possível limite a 30 em alguns apps)

---

## Arquitetura Desktop (Windows)

- **Runtime**: Node.js (com `ffmpeg`/`GStreamer` para decodificar H.264)
- **WebRTC**: recebe stream do celular
- **Decodifica**: H.264 → frames raw (YUV/RGB)
- **Envia à webcam virtual**: escreve frames na OBS Virtual Camera (via `pyvirtualcam`)
- **UI**: painel local com QR code p/ parear, status da conexão, botão iniciar/parar

---

## Fases de Desenvolvimento

### Fase 1 — MVP (sem filtros)
- [ ] Servidor de sinalização local (Node.js + WS)
- [ ] App Android: captura câmera → WebRTC → envia
- [ ] Desktop: WebRTC → decodifica → OBS Virtual Camera → webcam virtual
- [ ] Pareamento via QR code (celular escaneia QR do PC)
- [ ] **Meta**: câmera do celular aparece como webcam no Windows, ~60fps, baixa latência

### Fase 2 — Filtros / Efeitos
- [ ] Pipeline de filtros no app (GPU) antes do encode
- [ ] Controles de imagem: espelhamento, rotação, brilho, saturação
- [ ] Lib de filtros de rosto estilo Snapchat (decisão de tecnologia aqui)
- [ ] Opções de resolução/fps configuráveis

### Fase 3 — Refinamentos
- [ ] Multi-câmera (vários celulares)
- [ ] Áudio do celular (opcional)
- [ ] Auto-reconexão, indicadores de perda de pacote
- [ ] Empacotamento (APK + instalador Windows)

---

## Stack / Ferramentas

| Camada | Tech |
|---|---|
| Mobile | Kotlin, Android SDK, CameraX/Camera2, WebRTC (`org.webrtc`), MediaCodec |
| Desktop | Node.js + Electron (UI), FFmpeg, OBS Virtual Camera |
| Sinalização | Node.js + `ws` (WebSocket) |
| Webcam virtual | OBS Virtual Camera |

---

## Riscos / Pontos de Atenção

1. **60fps real**: The most apps (Zoom/Discord) limitam a 30fps na entrada. 60fps funciona melhor via OBS/ferramentas de captura. Testar na Fase 1.
2. **Limite do driver**: OBS Virtual Camera pode ter limite de fps/resolução — validar no início.
3. **Filtros Snapchat**: A biblioteca pública do Snapchat **não** está disponível p/ terceiros. Filtros genuínos exigem Lens Studio + Camera Kit (com aprovação da Snap). Alternativa: filtros AR próprios (ML Kit/ARCore). **Decisão adiada para Fase 2.**
4. **Latência em LAN**: WebRTC P2P local ≈ 50-150ms — suficiente para webcam.

---

## Próximos Passos

1. Configurar projetos: Android (Kotlin) + Desktop (Node/Electron) + servidor de sinalização
2. Validar OBS Virtual Camera (instalar + testar com frame estático)
3. Construir o MVP da Fase 1
