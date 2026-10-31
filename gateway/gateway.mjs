/**
 * DSH Mobile Gateway
 * ------------------
 * 只在 127.0.0.1 上监听，把 DSH Web GUI 通过 Cloudflare 快速隧道暴露成一个
 * 带口令的 HTTPS 地址，手机浏览器打开即可使用（可"添加到主屏幕"）。
 *
 * 数据流：
 *   手机 --HTTPS--> Cloudflare 边缘 <--QUIC 出站-- cloudflared --> 127.0.0.1:19399 (本网关)
 *        --> 127.0.0.1:19388 (dsh web，只绑回环)
 *
 * 本网关只做三件事：
 *   1. 口令闸门（自己的 dsg cookie）
 *   2. 原样转发 HTTP / WebSocket（Host 保持公网域名，因此 dsh 的 Host/Origin 校验天然通过）
 *   3. 给 index.html 注入窄屏适配（布局宽度 520px 等比缩放）
 *
 * 不修改 DSH 本体、配置、profile；关掉本进程即彻底断开。
 */
import { spawn, execFileSync } from 'node:child_process';
import http from 'node:http';
import net from 'node:net';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));

const CFG = {
  gwPort: 19399,
  desktopPort: 19387,       // 桌面端首选端口；找不到会扫描其它 loopback 端口
  cloudflared: path.resolve(HERE, '..', 'bin', 'cloudflared.exe'),
  stateFile: path.join(HERE, 'state.json'),
  accessFile: path.join(HERE, 'access.txt'),
  cfLog: path.resolve(HERE, '..', 'logs', 'cloudflared.log'),
};

const MIN_LAYOUT_WIDTH = 520; // 实测：520 及更宽布局完整，460 起横向溢出

function log(...a) { console.log('[gw]', ...a); }

const REQ_LOG = path.resolve(HERE, '..', 'logs', 'requests.log');
function reqLog(method, url, status) {
  try {
    fs.mkdirSync(path.dirname(REQ_LOG), { recursive: true });
    fs.appendFileSync(REQ_LOG, `${new Date().toISOString()} ${method} ${url} -> ${status}\n`);
  } catch { /* ignore */ }
}

/* ---------------- 口令 ---------------- */
function loadAccessCode() {
  try {
    const s = JSON.parse(fs.readFileSync(CFG.stateFile, 'utf8'));
    if (typeof s.accessCode === 'string' && s.accessCode.length >= 8) return s.accessCode;
  } catch { /* 首次运行 */ }
  const alphabet = 'abcdefghjkmnpqrstuvwxyz23456789';
  const bytes = crypto.randomBytes(10);
  let out = '';
  for (const b of bytes) out += alphabet[b % alphabet.length];
  return out;
}
const ACCESS_CODE = loadAccessCode();
const GATE_COOKIE = 'dsg';
const gateValue = crypto.createHash('sha256').update('dsh-gw:' + ACCESS_CODE).digest('hex').slice(0, 32);

function cookieVal(req, name) {
  const raw = req.headers.cookie;
  if (!raw) return undefined;
  for (const part of raw.split(';')) {
    const i = part.indexOf('=');
    if (i < 0) continue;
    if (part.slice(0, i).trim() === name) return part.slice(i + 1).trim();
  }
  return undefined;
}
const gated = (req) => cookieVal(req, GATE_COOKIE) === gateValue;

/* ---------------- 登录页 ---------------- */
function loginPage(wrong) {
  return `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>DSH 手机网关</title>
<style>
*{box-sizing:border-box}
body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;
background:#0d0f14;color:#e6e8ee;font:16px/1.6 system-ui,-apple-system,"Segoe UI",sans-serif;padding:24px}
.card{width:100%;max-width:360px}
h1{font-size:20px;margin:0 0 6px;font-weight:600}
p.sub{margin:0 0 22px;color:#8b93a7;font-size:13px}
input{width:100%;padding:14px 16px;border-radius:12px;border:1px solid #2b3040;background:#151922;
color:#e6e8ee;font-size:16px;outline:none}
input:focus{border-color:#4d7cff}
button{width:100%;margin-top:12px;padding:14px;border:0;border-radius:12px;background:#4d7cff;color:#fff;
font-size:16px;font-weight:600}
button:active{opacity:.85}
.err{margin-top:12px;color:#ff6b6b;font-size:13px}
</style></head><body><div class="card">
<h1>DSH 手机网关</h1>
<p class="sub">请输入电脑上显示的访问口令</p>
<form method="POST" action="/gw/login">
  <input name="code" type="password" inputmode="latin" autocapitalize="off" autocomplete="off"
         placeholder="访问口令" autofocus required>
  <button type="submit">进入</button>
</form>
${wrong ? '<div class="err">口令不对，请重新输入</div>' : ''}
</div></body></html>`;
}

/* ---------------- 窄屏适配注入 ---------------- */
const MOBILE_CSS = `
html{-webkit-text-size-adjust:100%;text-size-adjust:100%}
body{overscroll-behavior-y:none}
`;

const MOBILE_JS = `
(function(){
  var MIN=${MIN_LAYOUT_WIDTH};
  function apply(){
    var m=document.querySelector('meta[name="viewport"]');
    if(!m){m=document.createElement('meta');m.setAttribute('name','viewport');document.head.appendChild(m);}
    // Use screen dimensions (unaffected by our own meta change) to avoid resize feedback loops.
    var sw=window.screen&&window.screen.width?window.screen.width:window.innerWidth;
    var sh=window.screen&&window.screen.height?window.screen.height:window.innerHeight;
    var portrait=sh>=sw;
    if(portrait&&sw<MIN){
      // Desktop-only layout has no responsive breakpoints: pin layout width and let the browser scale to fit.
      m.setAttribute('content','width='+MIN+',initial-scale='+(sw/MIN).toFixed(4)+',user-scalable=yes');
    }else{
      m.setAttribute('content','width=device-width,initial-scale=1,user-scalable=yes');
    }
  }
  apply();
  window.addEventListener('orientationchange',function(){setTimeout(apply,250);});

  // On phones the session drawer starts collapsed and its entry is easy to miss.
  // Open it once per page load so the shared session list is visible immediately.
  // NOTE: this injected block must stay pure ASCII (CJK text is expressed as \\u escapes).
  var DRAWER_OPEN = /\u6253\u5f00\u4fa7\u8fb9\u680f|opensidebar/i;   // open sidebar
  var MODAL_BTN = /^(\u7ee7\u7eed|\u77e5\u9053\u4e86|\u5f00\u59cb\u4f7f\u7528)$/;  // modal buttons
  var triedOpen=false;

  function modalVisible(){
    var els=document.querySelectorAll('button,[role="button"]');
    for(var i=0;i<els.length;i++){
      if(MODAL_BTN.test((els[i].innerText||'').trim())) return true;
    }
    return false;
  }

  function maybeOpenDrawer(){
    if(triedOpen) return;
    // Never touch the drawer while an onboarding dialog is up: clicking behind it
    // desyncs the drawer state (the button flips to "collapse" without rendering).
    if(modalVisible()) return;
    var btns=document.querySelectorAll('button,[role="button"]');
    for(var i=0;i<btns.length;i++){
      var label=(btns[i].getAttribute('aria-label')||btns[i].title||'').trim();
      if(DRAWER_OPEN.test(label)){
        triedOpen=true;
        try{btns[i].click();}catch(e){}
        return;
      }
    }
  }

  var ticks=0;
  var timer=setInterval(function(){
    ticks++;
    if(triedOpen||ticks>120){clearInterval(timer);return;}
    var t=document.body&&document.body.innerText?document.body.innerText:'';
    if(t.indexOf('Loading plugins')>=0) return;
    maybeOpenDrawer();
  },500);
})();
`;

function injectHtml(html) {
  let out = html;
  // 去掉原 viewport meta（由注入脚本按屏宽自行决定），其余原样保留
  out = out.replace(/<meta[^>]+name=["']viewport["'][^>]*>/i, '');
  // 换成带 PNG 图标的 manifest，并补 iOS 主屏图标
  if (/<link[^>]+rel=["']manifest["'][^>]*>/i.test(out)) {
    out = out.replace(/<link[^>]+rel=["']manifest["'][^>]*>/i, '<link rel="manifest" href="/gw/manifest.webmanifest">');
  }
  const block =
    `<link rel="apple-touch-icon" href="/gw/apple-touch-icon.png">` +
    `<style id="dsh-mobile-gw">${MOBILE_CSS}</style>` +
    `<script id="dsh-mobile-gw-script">${MOBILE_JS}</script>` +
    `<script id="dsh-mobile-gw-sw">if('serviceWorker' in navigator){navigator.serviceWorker.register('/sw.js',{scope:'/'}).catch(function(){});}</script>`;
  // 插在 </head> 之前：保证原有 <meta charset> 仍位于文档最前面，避免编码嗅探错乱
  if (/<\/head>/i.test(out)) return out.replace(/<\/head>/i, block + '</head>');
  return out.replace(/<head([^>]*)>/i, `<head$1>${block}`);
}

/* ---------------- 进程管理 ---------------- */
const children = [];

function killPort(port) {
  try {
    const out = execFileSync('netstat', ['-ano', '-p', 'TCP'], { encoding: 'utf8', windowsHide: true });
    const pids = new Set();
    for (const line of out.split('\n')) {
      const m = line.trim().split(/\s+/);
      if (m.length >= 5 && /^TCP$/i.test(m[0]) && m[1].endsWith(':' + port) && /LISTENING/i.test(m[3])) pids.add(m[4]);
    }
    for (const pid of pids) {
      try { execFileSync('taskkill', ['/PID', pid, '/F', '/T'], { windowsHide: true, stdio: 'ignore' }); } catch { /* ignore */ }
    }
    return [...pids];
  } catch { return []; }
}

function cleanup(code = 0) {
  log('正在清理…');
  for (const c of children) { try { c.kill(); } catch { /* ignore */ } }
  // 只关网关自己。桌面端是用户正在用的实例，网关绝不触碰它。
  killPort(CFG.gwPort);
  try { fs.unlinkSync(CFG.stateFile); } catch { /* ignore */ }
  log('已停止，所有外部入口关闭。');
  process.exit(code);
}
for (const sig of ['SIGINT', 'SIGTERM', 'SIGHUP']) process.on(sig, () => cleanup(0));

/* ---------------- 隧道 ---------------- */
function startTunnel() {
  return new Promise((resolve, reject) => {
    fs.mkdirSync(path.dirname(CFG.cfLog), { recursive: true });
    const cf = spawn(CFG.cloudflared, [
      'tunnel', '--url', `http://127.0.0.1:${CFG.gwPort}`,
      '--no-autoupdate', '--loglevel', 'info',
    ], { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
    children.push(cf);
    let buf = '';
    const timer = setTimeout(() => reject(new Error('隧道 90 秒内未建立')), 90000);
    const onData = (d) => {
      buf += d.toString();
      const m = buf.match(/https:\/\/([a-z0-9-]+\.trycloudflare\.com)/i);
      if (m) {
        clearTimeout(timer);
        resolve({ url: m[0], host: m[1] });
      }
    };
    cf.stdout.on('data', onData);
    cf.stderr.on('data', (d) => {
      try { fs.appendFileSync(CFG.cfLog, d); } catch { /* ignore */ }
      onData(d);
    });
    cf.on('exit', (c) => { clearTimeout(timer); reject(new Error('cloudflared 退出，code=' + c)); });
  });
}

/* ---------------- 桌面端实例（不再另起 dsh web） ---------------- */
/**
 * 手机与电脑共用同一个 DSH 实例，才能真正"一起用"同一个会话与同一个任务。
 * 桌面端只绑 127.0.0.1，并带 token→cookie 鉴权；这里的做法是：
 *   1. 找到桌面端监听端口
 *   2. 从本地 credentials 读 browser-session secret（持久，重启不变）
 *   3. 按 DSH 自己的算法签一个 authority=127.0.0.1:<port> 的 cookie
 *   4. 代理时把 Host / Origin 改写回 loopback，并注入该 cookie
 * 全程不碰桌面端进程，也不改它的任何配置。
 */
function resolveDesktopPort() {
  const ports = [];
  try {
    const out = execFileSync('netstat', ['-ano', '-p', 'TCP'], { encoding: 'utf8', windowsHide: true });
    for (const line of out.split('\n')) {
      const m = line.trim().split(/\s+/);
      if (m.length >= 5 && /^TCP$/i.test(m[0]) && /LISTENING/i.test(m[3])) {
        const la = m[1];
        if (!la.startsWith('127.0.0.1:')) continue;
        const p = Number(la.slice(la.lastIndexOf(':') + 1));
        if (Number.isInteger(p) && p > 1024) ports.push(p);
      }
    }
  } catch { /* ignore */ }
  // 必须是 DSH 自己的服务：用 /api 路由特征确认
  return ports;
}

function findDesktopPort(secret) {
  const candidates = resolveDesktopPort();
  const ordered = [CFG.desktopPort];
  for (const p of candidates.sort((a, b) => a - b)) if (!ordered.includes(p)) ordered.push(p);

  const probe = (port) => new Promise((resolve) => {
    let cookie;
    try { cookie = makeSessionCookie(secret, `127.0.0.1:${port}`); } catch { return resolve(false); }
    const req = http.request({
      host: '127.0.0.1', port, method: 'GET', path: '/', timeout: 1200,
      headers: { host: `127.0.0.1:${port}`, cookie: `${cookie.name}=${cookie.value}` },
    }, (r) => {
      let body = '';
      r.on('data', (c) => { body += c; if (body.length > 6000) r.destroy(); });
      r.on('end', () => resolve(r.statusCode === 200 && /DeepSeek Harness/.test(body)));
      r.on('error', () => resolve(false));
    });
    req.on('timeout', () => { req.destroy(); resolve(false); });
    req.on('error', () => resolve(false));
    req.end();
  });

  return new Promise(async (resolve, reject) => {
    // 首选端口优先单独试，避免无谓的并发扫描
    if (await probe(CFG.desktopPort)) return resolve(CFG.desktopPort);
    const rest = ordered.filter((p) => p !== CFG.desktopPort);
    const results = await Promise.all(rest.map((p) => probe(p).then((ok) => (ok ? p : 0))));
    const hit = results.find((p) => p > 0);
    if (hit) return resolve(hit);
    reject(new Error('没找到运行中的 DSH 桌面端（本机 loopback 上没有可验证的 DSH 服务）。请先打开 DSH 桌面端再启动网关。'));
  });
}

/** 从 ~/.dsh/.credentials.yaml 读 browser-session secret（32 字节） */
function loadSessionSecret() {
  const credPath = path.join(process.env.DSH_HOME || path.join(process.env.USERPROFILE || '', '.dsh'), '.credentials.yaml');
  const raw = fs.readFileSync(credPath, 'utf8');
  // 结构：- kind: grant / payload: / version: 1 / secret: <43 chars base64url>
  for (const line of raw.split(/\r?\n/)) {
    const m = line.match(/^\s+secret:\s*([A-Za-z0-9_-]{43})\s*$/);
    if (m) {
      const b = m[1].replace(/-/g, '+').replace(/_/g, '/');
      const buf = Buffer.from(b + '='.repeat((4 - (b.length % 4)) % 4), 'base64');
      if (buf.length === 32) return buf;
    }
  }
  throw new Error('没在 credentials 里找到 browser-session 的 32 字节 secret');
}

const b64url = (buf) => Buffer.from(buf).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

/** 按 DSH 的算法生成 cookie 名与值 */
function makeSessionCookie(secret, authority, days = 30) {
  const now = Date.now();
  const payload = { version: 1, authority, issuedAt: now, expiresAt: now + days * 86400000 };
  const body = b64url(Buffer.from(JSON.stringify(payload), 'utf8'));
  const sig = b64url(crypto.createHmac('sha256', secret).update(body).digest());
  const name = 'dsh-auth-' + b64url(crypto.createHash('sha256').update(authority).digest());
  return { name, value: `v1.${body}.${sig}` };
}

/* ---------------- PWA 资源 ---------------- */
const MANIFEST = {
  name: 'DSH 手机',
  short_name: 'DSH',
  description: 'DeepSeek Harness 手机端',
  start_url: '/',
  scope: '/',
  display: 'fullscreen',
  orientation: 'any',
  background_color: '#0d0f14',
  theme_color: '#0d0f14',
  icons: [
    { src: '/gw/icon-192.png', sizes: '192x192', type: 'image/png', purpose: 'any' },
    { src: '/gw/icon-512.png', sizes: '512x512', type: 'image/png', purpose: 'any' },
    { src: '/gw/icon-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
  ],
};

// 最小 Service Worker：只为让"安装应用"可用，不缓存、不拦截任何请求
const SW_JS = `self.addEventListener('install',e=>self.skipWaiting());
self.addEventListener('activate',e=>self.clients.claim());
self.addEventListener('fetch',()=>{});`;

function serveFile(res, file, type) {
  try {
    const buf = fs.readFileSync(path.join(HERE, file));
    res.writeHead(200, { 'content-type': type, 'content-length': buf.length, 'cache-control': 'public, max-age=86400' });
    res.end(buf);
  } catch {
    res.writeHead(404, { 'content-type': 'text/plain' });
    res.end('not found');
  }
}

/* ---------------- 代理 ---------------- */
let desktopPort = CFG.desktopPort;
let sessionCookie = null;

function upstreamHeaders(req) {
  const h = { ...req.headers };
  const authority = `127.0.0.1:${desktopPort}`;
  // Host / Origin 归一化到 loopback：这样既过 DSH 的 Host/Origin 校验，
  // 又让 cookie 名与签发 audience 对得上。
  h.host = authority;
  if (h.origin !== undefined) h.origin = `http://${authority}`;
  if (h.referer !== undefined) {
    try { const u = new URL(h.referer); h.referer = `http://${authority}${u.pathname}${u.search}`; } catch { delete h.referer; }
  }
  // 用我们自己签的 cookie 替换浏览器带来的（浏览器那份对 loopback 无效）
  const kept = String(h.cookie || '')
    .split(';')
    .map((s) => s.trim())
    .filter((s) => s.length > 0 && !s.startsWith('dsh-auth-'));
  if (sessionCookie) kept.push(`${sessionCookie.name}=${sessionCookie.value}`);
  if (kept.length > 0) h.cookie = kept.join('; '); else delete h.cookie;
  // 让上游返回未压缩内容，便于注入 HTML
  delete h['accept-encoding'];
  return h;
}

function proxyHttp(req, res) {
  const opts = {
    host: '127.0.0.1',
    port: desktopPort,
    method: req.method,
    path: req.url,
    headers: upstreamHeaders(req),
  };
  const up = http.request(opts, (ur) => {
    reqLog(req.method, req.url, ur.statusCode);
    const ct = String(ur.headers['content-type'] || '');
    const isHtml = ct.includes('text/html');
    const headers = { ...ur.headers };
    // 丢弃上游针对 loopback 签发的 dsh-auth cookie（我们每次自行注入）
    if (headers['set-cookie']) {
      const list = Array.isArray(headers['set-cookie']) ? headers['set-cookie'] : [headers['set-cookie']];
      const filtered = list.filter((c) => !String(c).trim().toLowerCase().startsWith('dsh-auth-'));
      if (filtered.length > 0) headers['set-cookie'] = filtered; else delete headers['set-cookie'];
    }
    if (isHtml) {
      delete headers['content-length'];
      const chunks = [];
      ur.on('data', (c) => chunks.push(c));
      ur.on('end', () => {
        let body = Buffer.concat(chunks).toString('utf8');
        body = injectHtml(body);
        headers['content-length'] = Buffer.byteLength(body);
        res.writeHead(ur.statusCode || 200, headers);
        res.end(body);
      });
    } else {
      res.writeHead(ur.statusCode || 200, headers);
      ur.pipe(res);
    }
  });
  up.on('error', (e) => {
    reqLog(req.method, req.url, 'ERR:' + e.message);
    if (!res.headersSent) { res.writeHead(502, { 'content-type': 'text/plain; charset=utf-8' }); }
    res.end('gateway upstream error');
  });
  req.pipe(up);
}

function proxyUpgrade(req, socket, head) {
  const up = net.connect(desktopPort, '127.0.0.1', () => {
    const authority = `127.0.0.1:${desktopPort}`;
    const lines = [`${req.method} ${req.url} HTTP/1.1`];
    let cookieSeen = false;
    for (let i = 0; i < req.rawHeaders.length; i += 2) {
      const name = req.rawHeaders[i];
      const value = req.rawHeaders[i + 1];
      const lower = name.toLowerCase();
      if (lower === 'host') { lines.push(`${name}: ${authority}`); continue; }
      if (lower === 'origin') { lines.push(`${name}: http://${authority}`); continue; }
      if (lower === 'referer') {
        try { const u = new URL(value); lines.push(`${name}: http://${authority}${u.pathname}${u.search}`); } catch { /* drop */ }
        continue;
      }
      if (lower === 'cookie') {
        const kept = String(value).split(';').map((s) => s.trim())
          .filter((s) => s.length > 0 && !s.startsWith('dsh-auth-'));
        if (sessionCookie) kept.push(`${sessionCookie.name}=${sessionCookie.value}`);
        if (kept.length > 0) lines.push(`${name}: ${kept.join('; ')}`);
        cookieSeen = true;
        continue;
      }
      lines.push(`${name}: ${value}`);
    }
    if (!cookieSeen && sessionCookie) {
      lines.push(`cookie: ${sessionCookie.name}=${sessionCookie.value}`);
    }
    up.write(lines.join('\r\n') + '\r\n\r\n');
    if (head && head.length) up.write(head);
    up.pipe(socket);
    socket.pipe(up);
  });
  up.on('error', () => { try { socket.destroy(); } catch { /* ignore */ } });
  socket.on('error', () => { try { up.destroy(); } catch { /* ignore */ } });
}

/* ---------------- 服务器 ---------------- */
function startServer() {
  const server = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://gw.invalid');

    if (url.pathname === '/gw/login') {
      if (req.method !== 'POST') { res.writeHead(405); return res.end(); }
      const chunks = [];
      req.on('data', (c) => chunks.push(c));
      req.on('end', () => {
        const body = Buffer.concat(chunks).toString('utf8');
        const code = new URLSearchParams(body).get('code') || '';
        if (code.trim() === ACCESS_CODE) {
          const secure = String(req.headers['x-forwarded-proto'] || '').includes('https');
          res.writeHead(303, {
            location: '/',
            'set-cookie': `${GATE_COOKIE}=${gateValue}; Path=/; HttpOnly; SameSite=Lax; Max-Age=2592000${secure ? '; Secure' : ''}`,
            'cache-control': 'no-store',
          });
          return res.end();
        }
        res.writeHead(200, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' });
        res.end(loginPage(true));
      });
      return;
    }

    // 公开静态资源：Chrome 拉 manifest / 图标 / SW 时不带 cookie，必须免口令
    if (url.pathname === '/gw/manifest.webmanifest') {
      const body = JSON.stringify(MANIFEST);
      res.writeHead(200, { 'content-type': 'application/manifest+json; charset=utf-8', 'cache-control': 'no-store' });
      return res.end(body);
    }
    if (url.pathname === '/sw.js') {
      res.writeHead(200, { 'content-type': 'text/javascript; charset=utf-8', 'cache-control': 'no-store' });
      return res.end(SW_JS);
    }
    if (url.pathname === '/gw/icon-192.png') return serveFile(res, 'icon-192.png', 'image/png');
    if (url.pathname === '/gw/icon-512.png') return serveFile(res, 'icon-512.png', 'image/png');
    if (url.pathname === '/gw/apple-touch-icon.png') return serveFile(res, 'apple-touch-icon.png', 'image/png');

    // 一键链接：/?k=<口令> 直接种 cookie，省去手输
    if (!gated(req) && url.searchParams.get('k') === ACCESS_CODE) {
      const secure = String(req.headers['x-forwarded-proto'] || '').includes('https');
      res.writeHead(303, {
        location: '/',
        'set-cookie': `${GATE_COOKIE}=${gateValue}; Path=/; HttpOnly; SameSite=Lax; Max-Age=2592000${secure ? '; Secure' : ''}`,
        'cache-control': 'no-store',
      });
      return res.end();
    }

    if (!gated(req)) {
      res.writeHead(200, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' });
      return res.end(loginPage(false));
    }

    proxyHttp(req, res);
  });

  server.on('upgrade', (req, socket, head) => {
    reqLog('UPGRADE', req.url, gated(req) ? 'open' : 'denied');
    if (!gated(req)) { socket.destroy(); return; }
    proxyUpgrade(req, socket, head);
  });

  return new Promise((resolve, reject) => {
    server.on('error', reject);
    server.listen(CFG.gwPort, '127.0.0.1', () => resolve(server));
  });
}

/* ---------------- 主流程 ---------------- */
async function main() {
  log('清理可能残留的旧网关…');
  killPort(CFG.gwPort);

  log('读取 browser-session secret…');
  const secret = loadSessionSecret();

  log('查找运行中的 DSH 桌面端…');
  desktopPort = await findDesktopPort(secret);
  log('桌面端实例: 127.0.0.1:' + desktopPort + '（手机将与电脑共用这一个实例）');

  sessionCookie = makeSessionCookie(secret, `127.0.0.1:${desktopPort}`);
  log('会话凭据已就绪（有效期 30 天）');

  log('启动本地网关 127.0.0.1:' + CFG.gwPort);
  await startServer();

  log('建立 Cloudflare 隧道…（约 10-20 秒）');
  const tun = await startTunnel();
  log('隧道就绪:', tun.url);

  const state = {
    startedAt: new Date().toISOString(),
    accessCode: ACCESS_CODE,
    publicUrl: tun.url,
    host: tun.host,
    gwPort: CFG.gwPort,
    desktopPort,
  };
  fs.writeFileSync(CFG.stateFile, JSON.stringify(state, null, 2), 'utf8');
  fs.writeFileSync(CFG.accessFile,
    'DSH 手机网关\n' +
    '=============\n' +
    '公网地址: ' + tun.url + '\n' +
    '访问口令: ' + ACCESS_CODE + '\n' +
    '桌面端实例: 127.0.0.1:' + desktopPort + '\n' +
    '启动时间: ' + state.startedAt + '\n\n' +
    '手机与电脑共用同一个 DSH 实例：会话、任务、工作区都是同一份。\n' +
    '用完后关闭本窗口（或按 Ctrl+C）即彻底断开。\n', 'utf8');

  console.log('\n' + '='.repeat(60));
  console.log('  DSH 手机网关已就绪');
  console.log('='.repeat(60));
  console.log('  手机上打开: ' + tun.url);
  console.log('  访问口令  : ' + ACCESS_CODE);
  console.log('='.repeat(60));
  console.log('  已接入电脑上正在运行的 DSH（127.0.0.1:' + desktopPort + '）');
  console.log('  手机与电脑是同一个实例：看得到相同的会话，可以接着同一段聊。');
  console.log('  关闭本窗口或按 Ctrl+C 即停止并关闭所有入口。\n');

  // 生成扫码直达的二维码（失败不影响网关本体）
  try {
    const { makeQR, writePNG, toConsole } = await import('../qr.mjs');
    const link = `${tun.url}/?k=${encodeURIComponent(ACCESS_CODE)}`;
    const q = makeQR(link);

    // 生成两版：屏幕扫描用的高清版（大静区），终端预览用的普通版
    const qrFile = path.join(HERE, 'login-qr.png');
    const r = writePNG(q.matrix, qrFile, { scale: 14, quiet: 6 });

    console.log('='.repeat(60));
    console.log('  手机扫码直达（已含口令，扫完即进入）');
    console.log('='.repeat(60));
    console.log(toConsole(q.matrix, { quiet: 2 }));
    console.log('='.repeat(60));
    console.log('  二维码图片: ' + qrFile + `  (${r.dim}x${r.dim}px)`);
    console.log('  一键链接  : ' + link);
    console.log('');
    console.log('  ▸ 已自动打开二维码图片窗口，用手机扫那张最清楚。');
    console.log('  ▸ 或者用手机自带相机/扫码功能扫，扫到链接点开即可。');
    console.log('');

    // 弹窗显示高清二维码图片（比终端字符块清晰得多）
    try {
      const { spawn } = await import('node:child_process');
      spawn('cmd', ['/c', 'start', '', qrFile], { detached: true, stdio: 'ignore', windowsHide: true }).unref();
    } catch (e) {
      console.log('  （自动打开图片失败，请手动打开上面那个文件）');
    }
  } catch (e) {
    console.log('  （二维码生成失败，不影响使用：' + e.message + '）\n');
  }
}

main().catch((e) => {
  console.error('\n启动失败:', e.message);
  cleanup(1);
});
