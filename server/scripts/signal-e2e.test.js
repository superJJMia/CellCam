// Teste E2E da sinalização: simula sender (celular) e receiver (desktop)
// Roda independente do servidor (precisa estar up).
// Verifica: join, relay de signal (SDP fake) nos dois sentidos, peer-left.
const WebSocket = require('ws');
const BASE = process.argv[2] || 'ws://localhost:8081';

function client() {
  return new WebSocket(BASE);
}

let failures = 0;
function assert(cond, label) {
  if (cond) console.log(`  PASS: ${label}`);
  else { console.error(`  FAIL: ${label}`); failures++; }
}

async function waitFor(ws, type, timeout = 3000) {
  return new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error(`timeout esperando ${type}`)), timeout);
    ws.on('message', function onMsg(data) {
      const msg = JSON.parse(data.toString());
      if (msg.type === type) {
        clearTimeout(t);
        ws.off('message', onMsg);
        resolve(msg);
      }
    });
  });
}

async function main() {
  console.log('Teste E2E sinalização CellCam');

  const sender = client();
  const receiver = client();

  await Promise.all([new Promise(r => sender.once('open', r)), new Promise(r => receiver.once('open', r))]);
  console.log('  ambos conectados no WS');

  const roomCode = '424242';
  sender.send(JSON.stringify({ type: 'join', payload: { roomCode, role: 'sender' } }));
  receiver.send(JSON.stringify({ type: 'join', payload: { roomCode, role: 'receiver' } }));

  const joinedSender = await waitFor(sender, 'joined');
  const joinedReceiver = await waitFor(receiver, 'joined');
  assert(joinedSender.role === 'sender' && joinedSender.roomCode === roomCode, 'sender joined');
  assert(joinedReceiver.role === 'receiver' && joinedReceiver.roomCode === roomCode, 'receiver joined');

  // sender -> receiver (SDP offer)
  receiver.emit('_capture', null);
  const gotOffer = waitFor(receiver, 'signal');
  sender.send(JSON.stringify({ type: 'signal', data: { kind: 'offer', sdp: 'fake-sdp-offer' } }));
  const offer = await gotOffer;
  assert(offer.data && offer.data.kind === 'offer', 'offer chega no receiver via relay');

  // receiver -> sender (SDP answer)
  const gotAnswer = waitFor(sender, 'signal');
  receiver.send(JSON.stringify({ type: 'signal', data: { kind: 'answer', sdp: 'fake-sdp-answer' } }));
  const answer = await gotAnswer;
  assert(answer.data && answer.data.kind === 'answer', 'answer chega no sender via relay');

  // ICE bidirecional
  const ice1 = waitFor(receiver, 'signal');
  sender.send(JSON.stringify({ type: 'signal', data: { kind: 'ice', candidate: 'cand-1' } }));
  const r1 = await ice1;
  assert(r1.data.candidate === 'cand-1', 'ICE sender->receiver');

  const ice2 = waitFor(sender, 'signal');
  receiver.send(JSON.stringify({ type: 'signal', data: { kind: 'ice', candidate: 'cand-2' } }));
  const r2 = await ice2;
  assert(r2.data.candidate === 'cand-2', 'ICE receiver->sender');

  // peer-left
  const gotPeerLeft = waitFor(sender, 'peer-left');
  receiver.close();
  await gotPeerLeft;
  assert(true, 'receiver saiu, sender recebeu peer-left');

  sender.close();
  console.log(failures === 0 ? '\nTODOS OS TESTES PASSARAM' : `\n${failures} FALHAS`);
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error('Erro crítico:', e.message);
  process.exit(1);
});