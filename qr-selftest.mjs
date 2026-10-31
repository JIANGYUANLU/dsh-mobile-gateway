// Structural self-test for the QR generator.
//
// This verifies the parts that can be checked without reimplementing a full
// decoder (format-info parsing, unmasking, de-interleaving). Full payload
// round-trip verification was done against independent tooling:
//   - pixel-identical output vs the Python `qrcode` reference library
//   - successful decode by pyzbar (an independent decoder)
//
// Run: node qr-selftest.mjs

import { makeQR } from './qr.mjs';

let failed = 0;

function check(name, cond, detail = '') {
  if (cond) {
    console.log(`  ✅ ${name}`);
  } else {
    console.log(`  ❌ ${name}${detail ? '  ' + detail : ''}`);
    failed++;
  }
}

// Expected symbol size for a given version: 21 + (v-1)*4
const sizeForVersion = (v) => 21 + (v - 1) * 4;

// A finder pattern is a 7x7 with a dark border, light ring, dark 3x3 core.
function hasFinderAt(matrix, size, r0, c0) {
  for (let r = 0; r < 7; r++) {
    for (let c = 0; c < 7; c++) {
      const ring = r === 0 || r === 6 || c === 0 || c === 6;
      const core = r >= 2 && r <= 4 && c >= 2 && c <= 4;
      const expected = ring || core ? 1 : 0;
      if (matrix[r0 + r][c0 + c] !== expected) return false;
    }
  }
  return true;
}

const samples = [
  'HELLO WORLD',
  'https://example.com/?k=abcdefghij',
  'x',
  'https://example.com/a/rather/long/path/that/forces/a/bigger/symbol?token=0123456789abcdef',
];

console.log('QR generator structural self-test\n');

for (const text of samples) {
  const { version, size, matrix } = makeQR(text);
  console.log(`"${text.length > 40 ? text.slice(0, 40) + '…' : text}"  (${Buffer.byteLength(text, 'utf8')}B, v${version}, ${size}x${size})`);

  check('size matches version', size === sizeForVersion(version), `expected ${sizeForVersion(version)}, got ${size}`);
  check('version in supported range', version >= 1 && version <= 10);
  check('matrix is square and fully populated',
    matrix.length === size && matrix.every((row) => row.length === size) &&
    matrix.every((row) => row.every((v) => v === 0 || v === 1)));

  // Three finder patterns: top-left, top-right, bottom-left
  check('finder pattern top-left', hasFinderAt(matrix, size, 0, 0));
  check('finder pattern top-right', hasFinderAt(matrix, size, 0, size - 7));
  check('finder pattern bottom-left', hasFinderAt(matrix, size, size - 7, 0));

  // Timing patterns: alternating modules on row 6 and column 6
  let timingOK = true;
  for (let i = 8; i < size - 8; i++) {
    const expect = i % 2 === 0 ? 1 : 0;
    if (matrix[6][i] !== expect) timingOK = false;
    if (matrix[i][6] !== expect) timingOK = false;
  }
  check('timing patterns alternate', timingOK);

  // Dark module just above the bottom-left finder pattern
  check('dark module present', matrix[size - 8][8] === 1);

  console.log('');
}

console.log(failed === 0 ? 'All checks passed.' : `${failed} check(s) failed.`);
process.exit(failed === 0 ? 0 : 1);