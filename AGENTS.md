# AGENTS.md — CellCam

## O que é
App que transforma um celular Android em **webcam virtual** para Windows via rede local:
celular captura câmera → WebRTC/H.264 → desktop decodifica → injeta na **OBS Virtual Camera**.

## Regra de trabalho
- **Toda mudança feita deve ser commitada** (e, em geral, pushada) ao final do trabalho.
- Usuário git: `MeowA` / `meow@ahmiau.com`.
- Repositório remoto: `https://github.com/superJJMia/CellCam` (público, branch `main`).

## Contexto
- Antes de trabalhar, **ler `CONTEXT.md`** (estado atual + histórico). Após cada mudança,
  **atualizar `CONTEXT.md`** e commitá-lo junto com a mudança.

## Estrutura
- `mobile/` — app Android (Kotlin, gradle wrapper 8.7, AGP 8.5.2, io.github.webrtc-sdk:android:137.7151.05)
- `desktop/` — receptor Python (aiortc 1.15 + pyvirtualcam). `.venv` local, backend padrão `obs`
- `server/` — sinalização Node.js (`ws`), HTTP 8080 / WS 8081
- `ESCOPO.md` — escopo/fases/documentação

## Ambiente Windows (importante)
- Node.js em `C:\Program Files\nodejs` **não está no PATH**. Executar via:
  ```powershell
  $env:PATH = "C:\Program Files\nodejs;" + $env:PATH; & "C:\Program Files\nodejs\npm.cmd" <cmd>
  ```
- PowerShell bloqueia `.ps1` (execution policy) — usar `.cmd`/`node.exe` direto.
- Shell tool aborta com `Unknown: ChildProcess.kill` ao usar `Start-Process` com redirects — ignorar (cosmético).
- JDK: `C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot` (JAVA_HOME User scope).
- Android SDK: `C:\Users\MeowA\AppData\Local\Android\Sdk` (ANDROID_HOME User scope).
- `adb` em `C:\Users\MeowA\AppData\Local\Android\Sdk\platform-tools\adb.exe`.

## Como rodar
- **Desktop**: `desktop\.venv\Scripts\python.exe desktop\main.py` (backend padrão `obs`; `--backend dry` p/ debug sem webcam; `--no-server` se já houver signaling).
- **Server (isolado)**: `node src/server.js` dentro de `server/`.
- **Mobile**: build via gradlew no `mobile/` (APK em `mobile/app/build/outputs/apk/debug/app-debug.apk`).

## Convenções
- Config do desktop em `desktop/main.py` (argparse) e `desktop/receiver.py`.
- Não commitam artefatos: `.venv`, `build/`, `node_modules/`, logs, `qr.png` (ver `.gitignore`).
- Assertivas da API do aiortc 1.15: sem `icecandidate` event (candidatos vêm no SDP),
  estados ICE `completed`/`closed`; `RTCIceCandidate` via `aiortc.sdp.candidate_from_sdp`; `recv()` de track deve retornar PyAV `VideoFrame` com `pts`/`time_base`.