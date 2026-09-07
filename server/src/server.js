const { WebSocketServer } = require('ws');
const crypto = require('crypto');
const http = require('http');

const HTTP_PORT = process.env.CELLCAM_HTTP_PORT || 8080;
const WS_PORT = process.env.CELLCAM_WS_PORT || 8081;

function createRoomCode() {
  return crypto.randomInt(100000, 999999).toString();
}

class SignalingServer {
  constructor() {
    this.rooms = new Map(); // roomCode -> Map<role, ws>
    this.lastRoom = null;   // última sala criada (para auto-descoberta do app)
  }

  join(ws, { roomCode, role }) {
    if (!roomCode || (role !== 'sender' && role !== 'receiver')) {
      ws.send(JSON.stringify({ type: 'error', message: 'Invalid join payload' }));
      return;
    }

    if (!this.rooms.has(roomCode)) {
      this.rooms.set(roomCode, new Map());
    }
    const room = this.rooms.get(roomCode);
    room.set(role, ws);
    ws.roomCode = roomCode;
    ws.role = role;

    ws.send(JSON.stringify({ type: 'joined', roomCode, role }));

    // Avisa o peer que alguém entrou
    const other = room.get(role === 'sender' ? 'receiver' : 'sender');
    if (other && other.readyState === ws.OPEN) {
      other.send(JSON.stringify({ type: 'peer-joined', role }));
      ws.send(JSON.stringify({ type: 'peer-joined', role: other.role }));
    }

    console.log(`[join] room=${roomCode} role=${role} peers=${room.size}`);
  }

  relay(from, message) {
    const room = this.rooms.get(from.roomCode);
    if (!room) return;
    const payload = { from: from.role, data: message.data };
    for (const [role, peer] of room) {
      if (peer !== from && peer.readyState === peer.OPEN) {
        peer.send(JSON.stringify({ type: 'signal', from: from.role, data: message.data }));
      }
    }
  }

  leave(ws) {
    if (!ws.roomCode) return;
    const room = this.rooms.get(ws.roomCode);
    if (room) {
      room.delete(ws.role);
      const other = room.get(ws.role === 'sender' ? 'receiver' : 'sender');
      if (other && other.readyState === other.OPEN) {
        other.send(JSON.stringify({ type: 'peer-left' }));
      }
      if (room.size === 0) {
        this.rooms.delete(ws.roomCode);
      }
    }
    console.log(`[leave] room=${ws.roomCode} role=${ws.role}`);
    ws.roomCode = undefined;
  }

  handleMessage(ws, raw) {
    let msg;
    try {
      msg = JSON.parse(raw);
    } catch {
      ws.send(JSON.stringify({ type: 'error', message: 'Invalid JSON' }));
      return;
    }

    switch (msg.type) {
      case 'join':
        this.join(ws, msg.payload || {});
        break;
      case 'signal':
        if (ws.roomCode) this.relay(ws, msg);
        break;
      default:
        ws.send(JSON.stringify({ type: 'error', message: `Unknown message type: ${msg.type}` }));
    }
  }
}

// HTTP server simples para QR code / info (rota /health e /room)
const httpServer = http.createServer((req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  if (req.url === '/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ ok: true, rooms: server.rooms.size }));
    return;
  }
  if (req.url === '/room') {
    const code = createRoomCode();
    server.rooms.set(code, new Map());
    server.lastRoom = code;
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ roomCode: code }));
    return;
  }
  if (req.url === '/api/room') {
    // Sala que o app deve usar: preferindo uma sala com receiver ativo,
    // senão a última sala criada.
    let roomCode = null;
    for (const [code, room] of server.rooms) {
      if (room.has('receiver')) { roomCode = code; break; }
    }
    if (!roomCode) roomCode = server.lastRoom;
    if (!roomCode) {
      res.writeHead(404, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: 'no room yet' }));
      return;
    }
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ roomCode }));
    return;
  }
  res.writeHead(404);
  res.end();
});

const wss = new WebSocketServer({ port: WS_PORT });
const server = new SignalingServer();

wss.on('connection', (ws) => {
  console.log('[ws] connection opened');
  ws.on('message', (data) => server.handleMessage(ws, data.toString()));
  ws.on('close', () => server.leave(ws));
  ws.on('error', (err) => console.error('[ws] error:', err.message));
});

httpServer.listen(HTTP_PORT, () => {
  console.log(`CellCam signaling up`);
  console.log(`  HTTP  (info/QR): http://localhost:${HTTP_PORT}`);
  console.log(`  WS    (signaling): ws://localhost:${WS_PORT}`);
});