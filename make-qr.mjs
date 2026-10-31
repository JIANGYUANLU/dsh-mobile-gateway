// make-qr.mjs —— 读取网关生成的 access.txt，把“地址/?k=口令”一键链接做成二维码
// 用法: node make-qr.mjs [输出png路径]
// 默认输出到 mobile\gateway\login-qr.png，并在控制台打印终端二维码
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { makeQR, writePNG, toConsole } from './qr.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ACCESS = path.join(HERE, 'gateway', 'access.txt');
const OUT_DEFAULT = path.join(HERE, 'gateway', 'login-qr.png');

function readAccess() {
  if (!fs.existsSync(ACCESS)) throw new Error('还没生成 access.txt，请先启动网关');
  const txt = fs.readFileSync(ACCESS, 'utf8');
  const url = (txt.match(/公网地址:\s*(\S+)/) || [])[1];
  const code = (txt.match(/访问口令:\s*(\S+)/) || [])[1];
  if (!url || !code) throw new Error('access.txt 里没有找到地址或口令');
  return { url, code };
}

const { url, code } = readAccess();
const link = `${url}/?k=${encodeURIComponent(code)}`;
const out = process.argv[2] || OUT_DEFAULT;

const { version, size, matrix, maskId } = makeQR(link);
const r = writePNG(matrix, out, { scale: 8, quiet: 4 });

console.log('\n' + '='.repeat(60));
console.log('  手机扫码直达（含口令，扫完即进入）');
console.log('='.repeat(60));
console.log(toConsole(matrix, { quiet: 2 }));
console.log('='.repeat(60));
console.log('  一键链接: ' + link);
console.log('  二维码已存: ' + out + `  (${r.dim}x${r.dim}px)`);
console.log('  版本 ' + version + ' / 掩码 ' + maskId + ' / 内容 ' + Buffer.byteLength(link, 'utf8') + ' 字节');
console.log('='.repeat(60) + '\n');