const http = require('http');

// Teste manual sem dependência de ws client: apenas verifica se o servidor HTTP está servindo /room e /health
// Para rodar: node scripts/smoke-test.js (com o servidor já em outra janela)

const BASE = 'http://localhost:8080';

function get(path) {
  return new Promise((resolve, reject) => {
    http.get(BASE + path, (res) => {
      let body = '';
      res.on('data', (c) => (body += c));
      res.on('end', () => resolve({ status: res.statusCode, body }));
    }).on('error', reject);
  });
}

(async () => {
  const health = await get('/health');
  console.log('/health =>', health.status, health.body);

  const room = await get('/room');
  console.log('/room =>', room.status, room.body);

  const parsed = JSON.parse(room.body);
  console.log('roomCode gerado:', parsed.roomCode, 'len=', parsed.roomCode.length);
  if (!/^\d{6}$/.test(parsed.roomCode)) {
    console.error('FALHA: roomCode não tem 6 dígitos');
    process.exit(1);
  }

  const apiRoom = await get('/api/room');
  console.log('/api/room =>', apiRoom.status, apiRoom.body);
  const apiParsed = JSON.parse(apiRoom.body);
  if (apiParsed.roomCode !== parsed.roomCode) {
    console.error('FALHA: /api/room não retornou a última sala criada');
    process.exit(1);
  }
  console.log('OK');
})().catch((e) => {
  console.error('Erro:', e.message);
  process.exit(1);
});