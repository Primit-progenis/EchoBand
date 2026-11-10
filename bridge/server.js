'use strict';

const http = require('http');
const fs = require('fs');
const os = require('os');
const path = require('path');
const crypto = require('crypto');
const { WebSocketServer, WebSocket } = require('ws');

const ROOT = path.resolve(__dirname, '..');
const PORT = Number(process.env.ECHOBAND_PORT || 8787);
const DEMO = process.argv.includes('--demo') || process.env.ECHOBAND_DEMO === '1';
const PAIRING_CODE = process.env.ECHOBAND_PAIRING_CODE || crypto.randomBytes(3).toString('hex').toUpperCase();
const STATIC_FILES = new Set(['index.html', 'style.css', 'app.js', 'realtime.js']);
const dashboards = new Set();
let watchClient = null;
let demoTimer = null;
let demoStarted = 0;

function isPrivateIPv4(address) {
  const p = address.split('.').map(Number);
  return p.length === 4 && (p[0] === 10 || (p[0] === 172 && p[1] >= 16 && p[1] <= 31) || (p[0] === 192 && p[1] === 168));
}

function findLanAddress() {
  for (const list of Object.values(os.networkInterfaces())) {
    for (const item of list || []) {
      if (item.family === 'IPv4' && !item.internal && isPrivateIPv4(item.address)) return item.address;
    }
  }
  return null;
}

function constantTimeMatch(value) {
  if (typeof value !== 'string' || value.length !== PAIRING_CODE.length) return false;
  return crypto.timingSafeEqual(Buffer.from(value), Buffer.from(PAIRING_CODE));
}

function serveStatic(req, res) {
  let pathname;
  try { pathname = decodeURIComponent(new URL(req.url, 'http://localhost').pathname); }
  catch { res.writeHead(400).end('Bad request'); return; }
  if (pathname === '/') pathname = '/index.html';
  const name = pathname.replace(/^\//, '');
  if (!STATIC_FILES.has(name)) { res.writeHead(404).end('Not found'); return; }
  const file = path.join(ROOT, name);
  fs.readFile(file, (error, body) => {
    if (error) { res.writeHead(404).end('Not found'); return; }
    const type = name.endsWith('.html') ? 'text/html; charset=utf-8' : name.endsWith('.css') ? 'text/css; charset=utf-8' : 'text/javascript; charset=utf-8';
    res.writeHead(200, { 'Content-Type': type, 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'no-referrer' });
    res.end(body);
  });
}

const wsServer = new WebSocketServer({ noServer: true, maxPayload: 600000, perMessageDeflate: false });
function sendDemo(client) {
  if (client.readyState === WebSocket.OPEN) client.send(JSON.stringify({ type: 'mode', demo: true }));
}

function handleUpgrade(req, socket, head) {
  let url;
  try { url = new URL(req.url, 'http://localhost'); }
  catch { socket.destroy(); return; }
  const role = url.searchParams.get('role');
  if (url.pathname !== '/ws' || !constantTimeMatch(url.searchParams.get('code')) || !['watch', 'dashboard'].includes(role)) {
    socket.write('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n');
    socket.destroy();
    return;
  }
  if (role === 'watch' && (DEMO || (watchClient && watchClient.readyState === WebSocket.OPEN))) {
    socket.write('HTTP/1.1 409 Conflict\r\nConnection: close\r\n\r\n');
    socket.destroy();
    return;
  }
  wsServer.handleUpgrade(req, socket, head, client => wsServer.emit('connection', client, req, role));
}

wsServer.on('connection', (client, req, role) => {
  client.role = role;
  if (role === 'watch') watchClient = client;
  else { dashboards.add(client); if (DEMO) sendDemo(client); }
  client.on('message', (data, isBinary) => {
    if (client.role !== 'watch') return;
    // Watch payloads are forwarded byte-for-byte and never written to disk or logged.
    for (const dashboard of dashboards) if (dashboard.readyState === WebSocket.OPEN) dashboard.send(data, { binary: isBinary });
  });
  client.on('close', () => {
    if (client.role === 'watch' && watchClient === client) watchClient = null;
    if (client.role === 'dashboard') dashboards.delete(client);
  });
});

const demoRows = [
  { hr: 70, ibi: [858, 861, 857], acc: [0.01, 0.02, 0.01] }, { hr: 71, ibi: [845, 849, 846], acc: [0.02, 0.01, 0.02] },
  { hr: 72, ibi: [833, 836, 831], acc: [0.01, 0.03, 0.02] }, { hr: 72, ibi: [834, 832, 836], acc: [0.02, 0.01, 0.02] },
  { hr: 73, ibi: [821, 823, 820], acc: [0.01, 0.02, 0.03] }, { hr: 71, ibi: [845, 844, 847], acc: [0.02, 0.02, 0.01] },
  { hr: 79, ibi: [760, 762, 761], acc: [0.08, 0.01, 0.01] }, { hr: 80, ibi: [758, 762, 759], acc: [0.20, 0.02, 0.01] },
  { hr: 79, ibi: [759, 761, 760], acc: [0.32, 0.01, 0.02] }, { hr: 104, ibi: [560, 750, 518], acc: [0.60, 0.08, 0.04] },
  { hr: 111, ibi: [510, 815, 492], acc: [0.96, 0.10, 0.08] }, { hr: 108, ibi: [542, 798, 505], acc: [1.22, 0.16, 0.10] }
];

function broadcast(message) {
  const payload = JSON.stringify(message);
  for (const client of dashboards) if (client.readyState === WebSocket.OPEN) client.send(payload);
}

function emitDemoEcg() {
  const fsHz = 500;
  const samples = [];
  for (let i = 0; i < 15000; i++) {
    const t = i / fsHz;
    const beat = (t * 1.25) % 1;
    const qrs = Math.exp(-Math.pow((beat - 0.26) / 0.018, 2)) * 1.1 - Math.exp(-Math.pow((beat - 0.235) / 0.012, 2)) * 0.18;
    const p = Math.exp(-Math.pow((beat - 0.12) / 0.045, 2)) * 0.12;
    const tw = Math.exp(-Math.pow((beat - 0.48) / 0.07, 2)) * 0.28;
    samples.push(Number((p + qrs + tw + Math.sin(t * 2 * Math.PI * 50) * 0.008).toFixed(4)));
  }
  broadcast({ type: 'ecg', fs: fsHz, samples, leadOff: false, done: true });
}

function startDemo() {
  demoStarted = Date.now();
  let sentEcg = false;
  demoTimer = setInterval(() => {
    const seconds = Math.floor((Date.now() - demoStarted) / 1000);
    const phase = seconds < 60 ? 0 : seconds < 95 ? 1 : 2;
    const list = phase === 0 ? demoRows.slice(0, 6) : phase === 1 ? demoRows.slice(6, 9) : demoRows.slice(9);
    const row = list[(seconds % list.length)];
    broadcast({ type: 'vitals', t: Date.now(), hr: row.hr, hrStatus: 1, ibi: row.ibi, ibiStatus: row.ibi.map(() => 0), acc: { x: row.acc[0], y: row.acc[1], z: row.acc[2] } });
    if (seconds >= 65 && !sentEcg) { sentEcg = true; emitDemoEcg(); }
  }, 1000);
}

function listenOn(host) {
  return new Promise((resolve, reject) => {
    const server = http.createServer(serveStatic);
    server.on('upgrade', handleUpgrade);
    server.once('error', reject);
    server.listen(PORT, host, () => { server.removeListener('error', reject); resolve(server); });
  });
}

async function main() {
  const configuredHost = process.env.ECHOBAND_HOST;
  if (configuredHost && !isPrivateIPv4(configuredHost)) throw new Error('ECHOBAND_HOST must be a private LAN IPv4 address.');
  const lanAddress = configuredHost || findLanAddress();
  const hosts = [...new Set(['127.0.0.1', lanAddress].filter(Boolean))];
  const servers = [];
  for (const host of hosts) {
    try { servers.push(await listenOn(host)); }
    catch (error) { if (host === '127.0.0.1' || !servers.length) throw error; }
  }
  console.log(`EchoBand relay listening on port ${PORT}`);
  if (lanAddress) console.log(`Dashboard: http://${lanAddress}:${PORT}/`);
  else console.log('No private LAN IPv4 address found; only localhost is available.');
  console.log(`Pairing code: ${PAIRING_CODE}`);
  console.log(`Watch endpoint: ws://${lanAddress || '127.0.0.1'}:${PORT}/ws`);
  console.log(`Mode: ${DEMO ? 'demo sample replay' : 'watch relay'}`);
  console.log('Health messages are forwarded in memory only; none are logged or saved.');
  if (DEMO) startDemo();
  const close = () => { clearInterval(demoTimer); for (const client of wsServer.clients) client.close(); for (const server of servers) server.close(); process.exit(0); };
  process.on('SIGINT', close);
  process.on('SIGTERM', close);
}

main().catch(error => { console.error(`Relay startup failed: ${error.message}`); process.exitCode = 1; });
