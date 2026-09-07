# CellCam

Transforme seu celular Android em uma **webcam virtual** para o PC via rede local.

O celular captura a câmera, transmite por **WebRTC/H.264** e o desktop injeta o vídeo na
**OBS Virtual Camera** — sem cabos, sem apps pagos.

## Como funciona

```
┌──────────────┐    WebRTC (H.264)    ┌──────────────────────────────────────┐
│  Android     │  ◄────────────────►  │  Desktop (Windows)                   │
│  (sender)    │   signaling local    │  aiortc → decodifica → OBS Virtual    │
│  app CellCam │   HTTP 8080/WS 8081  │  Camera → Qualquer app de vídeo       │
└──────────────┘                      └──────────────────────────────────────┘
```

- **Android (sender):** captura a câmera (Camera2), negocia WebRTC e envia vídeo H.264.
- **Desktop (receiver):** aiortc decodifica o stream e injeta frames na webcam virtual via
  [pyvirtualcam](https://github.com/jremmons/pyvirtualcam) (backend OBS).
- **Sinalização:** servidor Node.js local (sala de 6 dígitos + QR code).

## Requisitos

- **Desktop:** Windows 10/11, Python 3.12+, Node.js e **OBS Studio** (para a OBS Virtual Camera).
- **Android:** Android 8+ (testado em Android 10).
- **Rede:** celular e PC na mesma rede local.

## Instalação — Desktop

```bat
python -m venv desktop\.venv
desktop\.venv\Scripts\pip install -r desktop\requirements.txt
```

Instale o [OBS Studio](https://obsproject.com/) (a OBS Virtual Camera vem junto).

## Instalação — Android

Compile o APK e instale no celular:

```bat
cd mobile
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17...
gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## Como usar

1. No PC, rode o server/receptor: `start.cmd` (ou `desktop\.venv\Scripts\python.exe desktop\main.py`).
   Ele imprime o **IP do servidor**, o **código da sala** e um **QR code**.
2. No celular, abra o app e informe IP + código (ou toque no QR).
3. Toque em **Conectar e transmitir**.
4. Em qualquer app (Meet, Teams, Zoom, OBS...), selecione **"OBS Virtual Camera"** como câmera.

Opções do desktop:

| Flag          | Descrição                                        |
| ------------- | ------------------------------------------------ |
| `--backend`   | `obs` (padrão) ou `dry` (debug, sem abrir câmera) |
| `--room`      | usar um código de sala específico                |
| `--fps`       | fps da webcam virtual (padrão: 30)               |
| `--no-server` | não subir o signaling local (já tem um rodando)  |

## O app Android

- Botão **Conectar / Parar** e status em tempo real.
- **Trocar câmera** (frontal/traseira) e **Espelho**.
- Reconexão automática com backoff caso o PC caia ou o servidor reinicie.
- Mantém a tela ativa enquanto transmite.

## Limitações conhecidas

- Conta apenas com a OBS Virtual Camera (o driver UnityCapture não recebia dados do
  pyvirtualcam e foi removido).
- Windows não lista câmeras virtuais no app "Câmera"/Gerenciador de Dispositivos
  (limitação do Windows — apps como OBS/Meet/Teams/Zoom/Discord funcionam normalmente).
- Filtros estilo Snapchat são uma fase futura (a biblioteca pública da Snap não é para terceiros).

## Estrutura

```
├── mobile/      App Android (Kotlin, libwebrtc-sdk)
├── desktop/     Receptor Python (aiortc + pyvirtualcam)
├── server/      Sinalização Node.js (ws)
├── start.cmd    Início com um clique no desktop
└── ESCOPO.md    Escopo e fases do projeto
```

## Roadmap

- [x] Fase 1 — MVP: celular → webcam virtual (WebRTC/H.264 → OBS Virtual Camera)
- [ ] Fase 1.5 — melhorar resolução/estabilidade e validar em Meet/Teams/Zoom
- [ ] Fase 2 — Filtros e efeitos em tempo real (decisão de biblioteca)
- [ ] Fase 3 — Refinamentos: off-line de câmera no Android, instalador do desktop