// QR 코드 부호기(ISO/IEC 18004). 스마트출석 QR token처럼 짧은 ASCII 문자열을 바이트 모드, 오류 정정 M 수준,
// 버전 1~10으로 그린다. 외부 서비스나 라이브러리를 쓰지 않고, 그림은 SVG 요소와 path 하나로 만든다(innerHTML 없음).

const SVG = "http://www.w3.org/2000/svg";

// 오류 정정 M 수준의 블록 구성: [블록 수, 블록당 전체 코드워드, 블록당 데이터 코드워드] 묶음.
const BLOCKS_M = [
  null,
  [[1, 26, 16]],
  [[1, 44, 28]],
  [[1, 70, 44]],
  [[2, 50, 32]],
  [[2, 67, 43]],
  [[4, 43, 27]],
  [[4, 49, 31]],
  [[2, 60, 38], [2, 61, 39]],
  [[3, 58, 36], [2, 59, 37]],
  [[4, 69, 43], [1, 70, 44]],
];

const ALIGN = [null, [], [6, 18], [6, 22], [6, 26], [6, 30], [6, 34], [6, 22, 38], [6, 24, 42], [6, 26, 46], [6, 28, 50]];

const MAX_VERSION = 10;

// GF(256), 원시 다항식 x^8 + x^4 + x^3 + x^2 + 1.
const EXP = new Uint8Array(512);
const LOG = new Uint8Array(256);
{
  let x = 1;
  for (let i = 0; i < 255; i += 1) {
    EXP[i] = x;
    LOG[x] = i;
    x <<= 1;
    if (x & 0x100) x ^= 0x11d;
  }
  for (let i = 255; i < 512; i += 1) EXP[i] = EXP[i - 255];
}

const mul = (a, b) => (a && b ? EXP[LOG[a] + LOG[b]] : 0);

function generator(degree) {
  let poly = [1];
  for (let i = 0; i < degree; i += 1) {
    const next = new Array(poly.length + 1).fill(0);
    for (let j = 0; j < poly.length; j += 1) {
      next[j] ^= poly[j];
      next[j + 1] ^= mul(poly[j], EXP[i]);
    }
    poly = next;
  }
  return poly;
}

function remainder(data, degree) {
  const gen = generator(degree);
  const rest = [...data, ...new Array(degree).fill(0)];
  for (let i = 0; i < data.length; i += 1) {
    const factor = rest[i];
    if (!factor) continue;
    for (let j = 0; j < gen.length; j += 1) rest[i + j] ^= mul(gen[j], factor);
  }
  return rest.slice(data.length);
}

function bytesOf(text) {
  return [...new TextEncoder().encode(String(text))];
}

function dataCapacity(version) {
  return BLOCKS_M[version].reduce((sum, [count, , data]) => sum + count * data, 0);
}

function chooseVersion(length) {
  for (let version = 1; version <= MAX_VERSION; version += 1) {
    const countBits = version < 10 ? 8 : 16;
    if (4 + countBits + length * 8 <= dataCapacity(version) * 8) return version;
  }
  return 0;
}

/** 모드·길이·데이터·종료 비트·채움 바이트까지 넣은 데이터 코드워드. */
function dataCodewords(bytes, version) {
  const bits = [];
  const put = (value, length) => {
    for (let i = length - 1; i >= 0; i -= 1) bits.push((value >>> i) & 1);
  };
  const capacity = dataCapacity(version) * 8;
  put(0b0100, 4);
  put(bytes.length, version < 10 ? 8 : 16);
  for (const byte of bytes) put(byte, 8);
  put(0, Math.min(4, capacity - bits.length));
  while (bits.length % 8) bits.push(0);
  const words = [];
  for (let i = 0; i < bits.length; i += 8) words.push(bits.slice(i, i + 8).reduce((acc, bit) => (acc << 1) | bit, 0));
  for (let pad = 0; words.length < capacity / 8; pad += 1) words.push(pad % 2 ? 0x11 : 0xec);
  return words;
}

/** 블록으로 나누고 오류 정정 코드워드를 붙여 교차 배치한 최종 코드워드. */
function interleave(words, version) {
  const blocks = [];
  let offset = 0;
  for (const [count, total, data] of BLOCKS_M[version]) {
    for (let i = 0; i < count; i += 1) {
      const part = words.slice(offset, offset + data);
      offset += data;
      blocks.push({ data: part, ecc: remainder(part, total - data) });
    }
  }
  const out = [];
  const longest = Math.max(...blocks.map((block) => block.data.length));
  for (let i = 0; i < longest; i += 1) for (const block of blocks) if (i < block.data.length) out.push(block.data[i]);
  const eccLength = blocks[0].ecc.length;
  for (let i = 0; i < eccLength; i += 1) for (const block of blocks) out.push(block.ecc[i]);
  return out;
}

function bch(value, poly, shift) {
  let rest = value << shift;
  const top = Math.floor(Math.log2(poly));
  while (rest && Math.floor(Math.log2(rest)) >= top) rest ^= poly << (Math.floor(Math.log2(rest)) - top);
  return (value << shift) | rest;
}

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

function baseMatrix(version) {
  const size = version * 4 + 17;
  const dark = Array.from({ length: size }, () => new Array(size).fill(false));
  const fixed = Array.from({ length: size }, () => new Array(size).fill(false));
  const set = (r, c, on) => {
    if (r < 0 || c < 0 || r >= size || c >= size) return;
    dark[r][c] = on;
    fixed[r][c] = true;
  };
  for (const [r0, c0] of [[0, 0], [0, size - 7], [size - 7, 0]]) {
    for (let r = -1; r <= 7; r += 1) {
      for (let c = -1; c <= 7; c += 1) {
        const ring = Math.max(Math.abs(r - 3), Math.abs(c - 3));
        set(r0 + r, c0 + c, ring !== 2 && ring !== 4);
      }
    }
  }
  for (let i = 8; i < size - 8; i += 1) {
    set(6, i, i % 2 === 0);
    set(i, 6, i % 2 === 0);
  }
  const centers = ALIGN[version];
  const last = centers.length - 1;
  centers.forEach((r, i) => centers.forEach((c, j) => {
    // 세 찾기 패턴과 겹치는 모서리만 뺀다. 타이밍 줄 위의 정렬 패턴은 그린다.
    if ((i === 0 && j === 0) || (i === 0 && j === last) || (i === last && j === 0)) return;
    for (let dr = -2; dr <= 2; dr += 1) {
      for (let dc = -2; dc <= 2; dc += 1) set(r + dr, c + dc, Math.max(Math.abs(dr), Math.abs(dc)) !== 1);
    }
  }));
  // 형식 정보와 버전 정보 자리를 먼저 잡아 데이터가 들어가지 않게 한다.
  for (let i = 0; i < 9; i += 1) {
    if (!fixed[8][i]) set(8, i, false);
    if (!fixed[i][8]) set(i, 8, false);
  }
  for (let i = 0; i < 8; i += 1) {
    set(8, size - 1 - i, false);
    set(size - 1 - i, 8, false);
  }
  set(size - 8, 8, true);
  if (version >= 7) {
    for (let i = 0; i < 18; i += 1) {
      set(Math.floor(i / 3), size - 11 + (i % 3), false);
      set(size - 11 + (i % 3), Math.floor(i / 3), false);
    }
  }
  return { size, dark, fixed };
}

function placeData(matrix, codewords) {
  const { size, dark, fixed } = matrix;
  const bits = [];
  for (const word of codewords) for (let i = 7; i >= 0; i -= 1) bits.push((word >>> i) & 1);
  let index = 0;
  let upward = true;
  for (let right = size - 1; right >= 1; right -= 2) {
    if (right === 6) right = 5;
    for (let step = 0; step < size; step += 1) {
      const r = upward ? size - 1 - step : step;
      for (const c of [right, right - 1]) {
        if (fixed[r][c]) continue;
        dark[r][c] = index < bits.length ? bits[index] === 1 : false;
        index += 1;
      }
    }
    upward = !upward;
  }
}

function drawFormat(matrix, mask, version) {
  const { size, dark } = matrix;
  // M 수준의 오류 정정 비트는 00이다.
  const format = bch(mask, 0x537, 10) ^ 0x5412;
  const bit = (i) => ((format >>> i) & 1) === 1;
  for (let i = 0; i < 15; i += 1) {
    // 왼쪽 위 형식 정보(세로줄 8과 가로줄 8).
    if (i < 6) dark[i][8] = bit(i);
    else if (i < 8) dark[i + 1][8] = bit(i);
    else dark[size - 15 + i][8] = bit(i);
    // 오른쪽 위와 왼쪽 아래 사본.
    if (i < 8) dark[8][size - 1 - i] = bit(i);
    else if (i < 9) dark[8][15 - i - 1 + 1] = bit(i);
    else dark[8][15 - i - 1] = bit(i);
  }
  dark[size - 8][8] = true;
  if (version >= 7) {
    const info = bch(version, 0x1f25, 12);
    for (let i = 0; i < 18; i += 1) {
      const on = ((info >>> i) & 1) === 1;
      dark[Math.floor(i / 3)][size - 11 + (i % 3)] = on;
      dark[size - 11 + (i % 3)][Math.floor(i / 3)] = on;
    }
  }
}

function applyMask(matrix, mask) {
  const { size, dark, fixed } = matrix;
  const test = MASKS[mask];
  for (let r = 0; r < size; r += 1) {
    for (let c = 0; c < size; c += 1) if (!fixed[r][c] && test(r, c)) dark[r][c] = !dark[r][c];
  }
}

function penalty(dark) {
  const size = dark.length;
  let score = 0;
  const line = (get) => {
    let total = 0;
    let run = 1;
    for (let i = 1; i < size; i += 1) {
      if (get(i) === get(i - 1)) run += 1;
      else {
        if (run >= 5) total += run - 2;
        run = 1;
      }
    }
    if (run >= 5) total += run - 2;
    const pattern = [true, false, true, true, true, false, true];
    for (let i = 0; i + 6 < size; i += 1) {
      if (!pattern.every((on, k) => get(i + k) === on)) continue;
      const before = [1, 2, 3, 4].every((k) => i - k < 0 || !get(i - k));
      const after = [7, 8, 9, 10].every((k) => i + k >= size || !get(i + k));
      if (before || after) total += 40;
    }
    return total;
  };
  for (let r = 0; r < size; r += 1) score += line((i) => dark[r][i]);
  for (let c = 0; c < size; c += 1) score += line((i) => dark[i][c]);
  for (let r = 0; r + 1 < size; r += 1) {
    for (let c = 0; c + 1 < size; c += 1) {
      const v = dark[r][c];
      if (dark[r][c + 1] === v && dark[r + 1][c] === v && dark[r + 1][c + 1] === v) score += 3;
    }
  }
  let darkCount = 0;
  for (const row of dark) for (const on of row) if (on) darkCount += 1;
  score += Math.floor(Math.abs((darkCount * 100) / (size * size) - 50) / 5) * 10;
  return score;
}

/**
 * `text`의 QR 모듈 행렬(`true`가 어두운 칸). 담을 수 없으면(버전 10의 M 수준, 213바이트 초과) null이다.
 * 마스크는 8개를 모두 그려 보고 벌점이 가장 낮은 것을 고른다.
 */
export function qrMatrix(text) {
  const bytes = bytesOf(text);
  const version = chooseVersion(bytes.length);
  if (!version) return null;
  const codewords = interleave(dataCodewords(bytes, version), version);
  let best = null;
  for (let mask = 0; mask < MASKS.length; mask += 1) {
    const matrix = baseMatrix(version);
    placeData(matrix, codewords);
    applyMask(matrix, mask);
    drawFormat(matrix, mask, version);
    const score = penalty(matrix.dark);
    if (!best || score < best.score) best = { score, dark: matrix.dark };
  }
  return best.dark;
}

/**
 * `text`를 담은 QR 코드 SVG 요소. 사방 4칸의 여백을 두고, 색은 CSS(`.qr-code`)가 테마와 관계없이 흰 바탕과 진한 칸으로 정한다.
 * `label`은 화면 읽기 프로그램에 읽히는 이름이다. 담을 수 없는 문자열이면 null이다.
 */
export function qrSvg(text, { label = "QR 코드" } = {}) {
  const dark = qrMatrix(text);
  if (!dark) return null;
  const quiet = 4;
  const size = dark.length + quiet * 2;
  const svg = document.createElementNS(SVG, "svg");
  svg.setAttribute("viewBox", `0 0 ${size} ${size}`);
  svg.setAttribute("class", "qr-code");
  svg.setAttribute("role", "img");
  svg.setAttribute("aria-label", label);
  svg.setAttribute("shape-rendering", "crispEdges");
  const paper = document.createElementNS(SVG, "rect");
  paper.setAttribute("class", "qr-code__paper");
  paper.setAttribute("width", String(size));
  paper.setAttribute("height", String(size));
  const parts = [];
  dark.forEach((row, r) => row.forEach((on, c) => {
    if (on) parts.push(`M${c + quiet} ${r + quiet}h1v1h-1z`);
  }));
  const ink = document.createElementNS(SVG, "path");
  ink.setAttribute("class", "qr-code__ink");
  ink.setAttribute("d", parts.join(""));
  svg.append(paper, ink);
  return svg;
}
