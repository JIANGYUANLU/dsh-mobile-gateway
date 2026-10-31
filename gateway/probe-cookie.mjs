// 验证：用 credentials 里的 browser-session secret 直接给桌面端 19387 签 cookie
// 不打印 secret 本体，只输出验证结果
import fs from 'node:fs';
import crypto from 'node:crypto';

const CRED = 'C:\\Users\\1\\.dsh\\.credentials.yaml';
const raw = fs.readFileSync(CRED, 'utf8');

// 提取 client-connection / browser-session 那条记录里的 secret
// 结构（无注释）：
//   - kind: grant
//     payload:
//       version: 1
//       secret: <base64url>
// 通过定位含 client-connection 的上下文拿不到 ref 名，改为按出现顺序取第一个
// "version: 1" + "secret:" 组合且 secret 长度 43 的那条
const lines = raw.split(/\r?\n/);
let secretB64 = null;
for (let i = 0; i < lines.length; i++) {
  const m = lines[i].match(/^\s+secret:\s*([A-Za-z0-9_\-]+)\s*$/);
  if (m && m[1].length === 43) { secretB64 = m[1]; break; }
}
if (!secretB64) { console.error('未找到 43 字符 secret'); process.exit(1); }
const secret = Buffer.from(secretB64.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - secretB64.length % 4) % 4), 'base64');
console.log('secret 读取成功: ' + secret.length + ' bytes (base64url len=' + secretB64.length + ')');

const b64url = (buf) => Buffer.from(buf).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const sign = (sec, body) => crypto.createHmac('sha256', sec).update(body).digest();

function makeCookie(authority, days = 30) {
  const now = Date.now();
  const payload = { version: 1, authority, issuedAt: now, expiresAt: now + days * 86400000 };
  const body = b64url(Buffer.from(JSON.stringify(payload), 'utf8'));
  const sig = b64url(sign(secret, body));
  // 服务端：cookieName = "dsh-auth-" + base64url(SHA256(authority))
  const name = 'dsh-auth-' + b64url(crypto.createHash('sha256').update(authority).digest());
  return { value: `v1.${body}.${sig}`, name, exp: payload.expiresAt };
}

const authority = '127.0.0.1:19387';
const { value, name, exp } = makeCookie(authority);
console.log('cookie 名: ' + name);
console.log('cookie 值长度: ' + value.length + '，过期: ' + new Date(exp).toISOString());

const base = 'http://127.0.0.1:19387';
const names = [name];
async function probe(name) {
  const r = await fetch(base + '/', { headers: { cookie: `${name}=${value}` }, redirect: 'manual' });
  return { name, status: r.status, location: r.headers.get('location') };
}

console.log('\n=== 未带 cookie（基线） ===');
{
  const r = await fetch(base + '/', { redirect: 'manual' });
  console.log('  status=' + r.status);
}

console.log('\n=== 带伪造 cookie ===');
for (const n of names) {
  try {
    const res = await probe(n);
    console.log(`  ${res.name}: status=${res.status} location=${res.location || '-'}`);
  } catch (e) { console.log(`  ${res.name}: ERR ${e.message}`); }
}

// 也试 /api 端点（fence + 认证都过才算通）
console.log('\n=== /api 探针 ===');
for (const n of names) {
  try {
    const r = await fetch(base + '/api/pluginInventory/list', {
      method: 'POST',
      headers: { cookie: `${n}=${value}`, 'content-type': 'application/json' },
      body: '{}',
    });
    console.log(`  ${n}: status=${r.status}`);
  } catch (e) { console.log(`  ${n}: ERR ${e.message}`); }
}