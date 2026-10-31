// qr.mjs —— 零依赖二维码生成（UTF-8 字节模式 / 版本 1-10 / 纠错等级 M）
// 用法: node qr.mjs <文本> [输出png路径]
// 作为模块: import { makeQR, writePNG, toConsole } from './qr.mjs'
import fs from 'node:fs';
import zlib from 'node:zlib';

// ---------- GF(256) ----------
const EXP = new Uint8Array(512);
const LOG = new Uint8Array(256);
(() => {
  let x = 1;
  for (let i = 0; i < 255; i++) {
    EXP[i] = x;
    LOG[x] = i;
    x <<= 1;
    if (x & 0x100) x ^= 0x11d;
  }
  for (let i = 255; i < 512; i++) EXP[i] = EXP[i - 255];
})();
const gmul = (a, b) => (a === 0 || b === 0 ? 0 : EXP[LOG[a] + LOG[b]]);

// ---------- 版本参数（纠错等级 M） ----------
// 每块纠错码字数 / 总码字数（QR 规范 Table 9，M 级）
const ECC_PER_BLOCK_M = {
  1: 10, 2: 16, 3: 26, 4: 18, 5: 24, 6: 16, 7: 18, 8: 22, 9: 22, 10: 26,
};
// 数据码字总数 / 块数（M 级）
const DATA_CW_M = {
  1: [16, 1], 2: [28, 1], 3: [44, 1], 4: [64, 2], 5: [86, 2],
  6: [108, 4], 7: [124, 4], 8: [154, 4], 9: [182, 5], 10: [216, 5],
};

function blockLayout(v) {
  const [totalData, numBlocks] = DATA_CW_M[v];
  const ecLen = ECC_PER_BLOCK_M[v];
  const base = Math.floor(totalData / numBlocks);
  const extra = totalData % numBlocks;
  const blocks = [];
  for (let i = 0; i < numBlocks; i++) {
    blocks.push({ dataLen: base + (i >= numBlocks - extra ? 1 : 0), ecLen });
  }
  return { blocks, totalData, ecLen };
}

// ---------- 数据编码 ----------
function encodeData(bytes, version) {
  const layout = blockLayout(version);
  const capBits = layout.totalData * 8;
  const bits = [];
  const push = (val, len) => {
    for (let i = len - 1; i >= 0; i--) bits.push((val >> i) & 1);
  };
  push(0b0100, 4); // 字节模式
  push(bytes.length, version <= 9 ? 8 : 16);
  for (const b of bytes) push(b, 8);

  const term = Math.min(4, capBits - bits.length);
  for (let i = 0; i < term; i++) bits.push(0);
  while (bits.length % 8 !== 0) bits.push(0);

  const cw = [];
  for (let i = 0; i < bits.length; i += 8) {
    let b = 0;
    for (let j = 0; j < 8; j++) b = (b << 1) | bits[i + j];
    cw.push(b);
  }
  const pad = [0xec, 0x11];
  let p = 0;
  while (cw.length < layout.totalData) cw.push(pad[p++ % 2]);
  return { codewords: cw, layout };
}

// ---------- RS 纠错 ----------
function rsGenerator(degree) {
  let poly = [1];
  for (let i = 0; i < degree; i++) {
    const next = new Array(poly.length + 1).fill(0);
    for (let j = 0; j < poly.length; j++) {
      next[j] ^= poly[j];
      next[j + 1] ^= gmul(poly[j], EXP[i]);
    }
    poly = next;
  }
  return poly;
}
function rsEncode(data, ecLen) {
  const gen = rsGenerator(ecLen);
  const res = new Array(ecLen).fill(0);
  for (const d of data) {
    const factor = d ^ res[0];
    res.shift();
    res.push(0);
    if (factor !== 0) {
      for (let i = 0; i < ecLen; i++) res[i] ^= gmul(gen[i + 1], factor);
    }
  }
  return res;
}

function interleave(codewords, layout) {
  const blocks = [];
  let off = 0;
  for (const b of layout.blocks) {
    const d = codewords.slice(off, off + b.dataLen);
    off += b.dataLen;
    blocks.push({ data: d, ecc: rsEncode(d, b.ecLen) });
  }
  const out = [];
  const maxData = Math.max(...blocks.map((b) => b.data.length));
  for (let i = 0; i < maxData; i++) {
    for (const b of blocks) if (i < b.data.length) out.push(b.data[i]);
  }
  for (let i = 0; i < layout.ecLen; i++) {
    for (const b of blocks) out.push(b.ecc[i]);
  }
  return out;
}

// ---------- 对齐图形中心坐标 ----------
const ALIGN_POS = {
  1: [],
  2: [6, 18], 3: [6, 22], 4: [6, 26], 5: [6, 30],
  6: [6, 34], 7: [6, 22, 38], 8: [6, 24, 42], 9: [6, 26, 46], 10: [6, 28, 50],
};

// ---------- 功能图形占位表（严谨定义，供数据放置与掩码共用） ----------
function functionMask(version) {
  const size = version * 4 + 17;
  const f = Array.from({ length: size }, () => new Array(size).fill(false));
  const mark = (r, c) => {
    if (r >= 0 && r < size && c >= 0 && c < size) f[r][c] = true;
  };
  // 三个定位图形 + 分隔符（8x8 区域）
  for (let r = 0; r < 8; r++) for (let c = 0; c < 8; c++) {
    mark(r, c); mark(r, size - 1 - c); mark(size - 1 - r, c);
  }
  // 定时图形
  for (let i = 0; i < size; i++) { mark(6, i); mark(i, 6); }
  // 校正图形
  const al = ALIGN_POS[version];
  for (const ar of al) for (const ac of al) {
    if ((ar <= 8 && ac <= 8) || (ar <= 8 && ac >= size - 9) || (ar >= size - 9 && ac <= 8)) continue;
    for (let dr = -2; dr <= 2; dr++) for (let dc = -2; dc <= 2; dc++) mark(ar + dr, ac + dc);
  }
  // 格式信息区
  for (let i = 0; i <= 8; i++) { mark(8, i); mark(i, 8); }
  for (let i = 0; i < 8; i++) { mark(8, size - 1 - i); mark(size - 1 - i, 8); }
  // 暗模块
  mark(size - 8, 8);
  // 版本信息区（版本 7 起）
  if (version >= 7) {
    for (let i = 0; i < 18; i++) {
      const a = Math.floor(i / 3);
      const b = size - 11 + (i % 3);
      mark(b, a);
      mark(a, b);
    }
  }
  return f;
}

function BCH(data, poly, bits) {
  let v = data << bits;
  const polyDeg = (() => { let d = 0; let p = poly; while (p > 1) { p >>= 1; d++; } return d; })();
  while (v.toString(2).length > polyDeg) {
    v ^= poly << (v.toString(2).length - polyDeg - 1);
  }
  return (data << bits) | v;
}

// ---------- 生成 ----------
export function makeQR(text) {
  const bytes = Array.from(Buffer.from(text, 'utf8'));
  let version = -1;
  for (let v = 1; v <= 10; v++) {
    const layout = blockLayout(v);
    const needBits = 4 + (v <= 9 ? 8 : 16) + bytes.length * 8;
    if (needBits <= layout.totalData * 8) { version = v; break; }
  }
  if (version < 0) throw new Error('数据太长，超出本实现支持的版本 10（约 200 字节）');

  const { codewords, layout } = encodeData(bytes, version);
  const finalCw = interleave(codewords, layout);

  const size = version * 4 + 17;
  const fn = functionMask(version);
  const m = Array.from({ length: size }, () => new Array(size).fill(0));

  // --- 定位图形 ---
  const drawFinder = (r0, c0) => {
    for (let r = 0; r < 7; r++) {
      for (let c = 0; c < 7; c++) {
        const onRing = r === 0 || r === 6 || c === 0 || c === 6;
        const inCore = r >= 2 && r <= 4 && c >= 2 && c <= 4;
        m[r0 + r][c0 + c] = onRing || inCore ? 1 : 0;
      }
    }
  };
  drawFinder(0, 0);
  drawFinder(0, size - 7);
  drawFinder(size - 7, 0);

  // --- 定时图形 ---
  for (let i = 8; i < size - 8; i++) {
    m[6][i] = i % 2 === 0 ? 1 : 0;
    m[i][6] = i % 2 === 0 ? 1 : 0;
  }

  // --- 校正图形 ---
  const al = ALIGN_POS[version];
  for (const ar of al) for (const ac of al) {
    if ((ar <= 8 && ac <= 8) || (ar <= 8 && ac >= size - 9) || (ar >= size - 9 && ac <= 8)) continue;
    for (let dr = -2; dr <= 2; dr++) for (let dc = -2; dc <= 2; dc++) {
      m[ar + dr][ac + dc] = Math.max(Math.abs(dr), Math.abs(dc)) !== 1 ? 1 : 0;
    }
  }

  // --- 暗模块 ---
  m[size - 8][8] = 1;

  // --- 数据放置 ---
  const dataCells = [];
  let upward = true;
  for (let col = size - 1; col > 0; col -= 2) {
    if (col === 6) col--;
    for (let k = 0; k < size; k++) {
      const row = upward ? size - 1 - k : k;
      for (let dcol = 0; dcol < 2; dcol++) {
        const c = col - dcol;
        if (!fn[row][c]) dataCells.push([row, c]);
      }
    }
    upward = !upward;
  }
  const totalBits = finalCw.length * 8;
  for (let i = 0; i < dataCells.length; i++) {
    const [r, c] = dataCells[i];
    const bit = i < totalBits ? (finalCw[i >> 3] >>> (7 - (i & 7))) & 1 : 0;
    m[r][c] = bit;
  }

  // --- 掩码选择 ---
  const MASKS = [
    (r, c) => (r + c) % 2 === 0,
    (r) => r % 2 === 0,
    (r, c) => c % 3 === 0,
    (r, c) => (r + c) % 3 === 0,
    (r, c) => (Math.floor(r / 2) + Math.floor(c / 3)) % 2 === 0,
    (r, c) => ((r * c) % 2) + ((r * c) % 3) === 0,
    (r, c) => (((r * c) % 2) + ((r * c) % 3)) % 2 === 0,
    (r, c) => (((r + c) % 2) + ((r * c) % 3)) % 2 === 0,
  ];

  const writeFormat = (mm, maskId) => {
    const data = (0b00 << 3) | maskId; // 00 = 纠错等级 M
    const bits = BCH(data, 0x537, 10) ^ 0x5412; // 15 位，bit14 为最高位
    const b = (i) => (bits >>> i) & 1;

    // --- 左上角副本 ---
    // 横向 (8,0..8)，跳过定时列 c=6
    for (let c = 0; c <= 5; c++) mm[8][c] = b(14 - c);
    mm[8][7] = b(8);
    mm[8][8] = b(7);
    // 纵向 (7,8) 向下到 (0,8)，跳过定时行 r=6
    // (7,8)=b6, (5,8)=b5, (4,8)=b4, (3,8)=b3, (2,8)=b2, (1,8)=b1, (0,8)=b0
    mm[7][8] = b(6);
    for (let i = 0; i <= 5; i++) mm[5 - i][8] = b(5 - i);

    // --- 右上角副本（第 8 行右端）---
    // (8,size-1)=b0, (8,size-2)=b1 …… (8,size-8)=b7
    for (let i = 0; i <= 7; i++) mm[8][size - 1 - i] = b(i);

    // --- 左下角副本（第 8 列下端，仅 7 位：b14→b8）---
    // (size-1,8)=b14, (size-2,8)=b13, ... (size-7,8)=b8
    // 注意：b0..b7 只存在于右上角那一份，左下角不重复，再往上就是数据区
    for (let i = 0; i <= 6; i++) mm[size - 1 - i][8] = b(14 - i);

    // 暗模块
    mm[size - 8][8] = 1;
  };

  const writeVersion = (mm) => {
    if (version < 7) return;
    const bits = BCH(version, 0x1f25, 12);
    for (let i = 0; i < 18; i++) {
      const b = (bits >>> i) & 1;
      const a = Math.floor(i / 3);
      const c = size - 11 + (i % 3);
      mm[c][a] = b;
      mm[a][c] = b;
    }
  };

  const penalty = (mm) => {
    let s = 0;
    for (let r = 0; r < size; r++) {
      let run = 1;
      for (let c = 1; c < size; c++) {
        if (mm[r][c] === mm[r][c - 1]) run++;
        else { if (run >= 5) s += 3 + (run - 5); run = 1; }
      }
      if (run >= 5) s += 3 + (run - 5);
    }
    for (let c = 0; c < size; c++) {
      let run = 1;
      for (let r = 1; r < size; r++) {
        if (mm[r][c] === mm[r - 1][c]) run++;
        else { if (run >= 5) s += 3 + (run - 5); run = 1; }
      }
      if (run >= 5) s += 3 + (run - 5);
    }
    for (let r = 0; r < size - 1; r++) for (let c = 0; c < size - 1; c++) {
      const v = mm[r][c];
      if (v === mm[r][c + 1] && v === mm[r + 1][c] && v === mm[r + 1][c + 1]) s += 3;
    }
    let dark = 0;
    for (let r = 0; r < size; r++) for (let c = 0; c < size; c++) if (mm[r][c]) dark++;
    s += Math.floor(Math.abs((dark * 100) / (size * size) - 50) / 5) * 10;
    return s;
  };

  let best = null;
  for (let maskId = 0; maskId < 8; maskId++) {
    const mm = m.map((row) => row.slice());
    const fnc = MASKS[maskId];
    for (let r = 0; r < size; r++) for (let c = 0; c < size; c++) {
      if (fn[r][c]) continue;
      if (fnc(r, c)) mm[r][c] ^= 1;
    }
    writeFormat(mm, maskId);
    writeVersion(mm);
    const p = penalty(mm);
    if (!best || p < best.p) best = { p, mm, maskId };
  }

  return { version, size, matrix: best.mm, maskId: best.maskId };
}

// ---------- 输出 ----------
function toGrayscale(matrix, scale, quiet) {
  const n = matrix.length;
  const dim = (n + quiet * 2) * scale;
  const buf = Buffer.alloc(dim * dim, 255);
  for (let r = 0; r < n; r++) for (let c = 0; c < n; c++) {
    if (!matrix[r][c]) continue;
    const y0 = (r + quiet) * scale;
    const x0 = (c + quiet) * scale;
    for (let dy = 0; dy < scale; dy++) {
      const off = (y0 + dy) * dim;
      buf.fill(0, off + x0, off + x0 + scale);
    }
  }
  return { buf, dim };
}

function crc32(buf) {
  let c = ~0;
  for (const b of buf) {
    c ^= b;
    for (let k = 0; k < 8; k++) c = (c >>> 1) ^ (0xedb88320 & -(c & 1));
  }
  return ~c >>> 0;
}
function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const td = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(td));
  return Buffer.concat([len, td, crc]);
}

export function writePNG(matrix, file, { scale = 8, quiet = 4 } = {}) {
  const { buf, dim } = toGrayscale(matrix, scale, quiet);
  const raw = Buffer.alloc((dim + 1) * dim);
  for (let y = 0; y < dim; y++) {
    raw[y * (dim + 1)] = 0;
    buf.copy(raw, y * (dim + 1) + 1, y * dim, y * dim + dim);
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(dim, 0);
  ihdr.writeUInt32BE(dim, 4);
  ihdr[8] = 8; ihdr[9] = 0; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
  const png = Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
  fs.writeFileSync(file, png);
  return { file, dim, bytes: png.length };
}

export function toConsole(matrix, { quiet = 2 } = {}) {
  const n = matrix.length;
  const get = (r, c) => (r < 0 || r >= n || c < 0 || c >= n ? 0 : matrix[r][c]);
  const lines = [];
  for (let r = -quiet; r < n + quiet; r += 2) {
    let line = '';
    for (let c = -quiet; c < n + quiet; c++) {
      const top = get(r, c);
      const bot = get(r + 1, c);
      line += top && bot ? '█' : top ? '▀' : bot ? '▄' : ' ';
    }
    lines.push(line);
  }
  return lines.join('\n');
}

// CLI：仅当本文件被直接执行时才跑（被 import 时 filename 不同）
import { fileURLToPath } from 'node:url';
import path from 'node:path';
const isMain = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isMain) {
  const text = process.argv[2];
  if (!text) {
    console.error('用法: node qr.mjs <文本> [输出png路径]');
    process.exit(2);
  }
  const { version, size, matrix, maskId } = makeQR(text);
  if (process.argv[3]) {
    const r = writePNG(matrix, process.argv[3], { scale: 8, quiet: 4 });
    console.log(`PNG: ${r.file}  ${r.dim}x${r.dim}px  ${r.bytes} bytes`);
  } else {
    console.log(toConsole(matrix));
  }
  console.log(`version=${version} size=${size} mask=${maskId} len=${Buffer.byteLength(text, 'utf8')}B`);
}