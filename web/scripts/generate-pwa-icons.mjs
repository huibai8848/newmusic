#!/usr/bin/env node
/**
 * 生成 PWA 图标（零依赖）。
 *
 * 仓库里只有 `favicon.svg`，而 manifest 的 192/512 图标需要 PNG；
 * 本机没有 ImageMagick / Pillow，引 sharp 又太重，所以直接用 zlib 写 PNG。
 * 图形是 favicon 的简化版：蓝色圆底 + 白色音符线框，4×超采样做抗锯齿。
 *
 * 用法：node scripts/generate-pwa-icons.mjs
 */
import { deflateSync } from 'node:zlib';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const PUBLIC_DIR = join(dirname(fileURLToPath(import.meta.url)), '..', 'public');
const ICON_DIR = join(PUBLIC_DIR, 'icons');

const BRAND = [0x16, 0x77, 0xff];
const WHITE = [0xff, 0xff, 0xff];

const CRC_TABLE = (() => {
  const table = new Uint32Array(256);
  for (let n = 0; n < 256; n += 1) {
    let c = n;
    for (let k = 0; k < 8; k += 1) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c >>> 0;
  }
  return table;
})();

function crc32(buffer) {
  let crc = 0xffffffff;
  for (const byte of buffer) crc = CRC_TABLE[(crc ^ byte) & 0xff] ^ (crc >>> 8);
  return (crc ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const length = Buffer.alloc(4);
  length.writeUInt32BE(data.length >>> 0);
  const typeBuffer = Buffer.from(type, 'ascii');
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(Buffer.concat([typeBuffer, data])));
  return Buffer.concat([length, typeBuffer, data, crc]);
}

/** RGBA 像素缓冲 → PNG（filter 0，无隔行） */
function encodePng(width, height, rgba) {
  const header = Buffer.alloc(13);
  header.writeUInt32BE(width, 0);
  header.writeUInt32BE(height, 4);
  header[8] = 8; // bit depth
  header[9] = 6; // RGBA
  header[10] = 0;
  header[11] = 0;
  header[12] = 0;

  const stride = width * 4;
  const raw = Buffer.alloc((stride + 1) * height);
  for (let y = 0; y < height; y += 1) {
    raw[y * (stride + 1)] = 0;
    rgba.copy(raw, y * (stride + 1) + 1, y * stride, (y + 1) * stride);
  }

  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', header),
    chunk('IDAT', deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

function inCircle(x, y, cx, cy, r) {
  const dx = x - cx;
  const dy = y - cy;
  return dx * dx + dy * dy <= r * r;
}

/** 音符的白色部分（归一化坐标 0..1） */
function inNote(x, y) {
  const head1 = inCircle(x, y, 0.36, 0.665, 0.121) && !inCircle(x, y, 0.36, 0.665, 0.072);
  const head2 = inCircle(x, y, 0.675, 0.605, 0.121) && !inCircle(x, y, 0.675, 0.605, 0.072);
  const stem1 = x >= 0.435 && x <= 0.478 && y >= 0.28 && y <= 0.665;
  const stem2 = x >= 0.75 && x <= 0.793 && y >= 0.22 && y <= 0.605;
  const beam = x >= 0.435 && x <= 0.793 && y >= 0.22 && y <= 0.33;
  return head1 || head2 || stem1 || stem2 || beam;
}

/**
 * @param {number} size 输出边长
 * @param {{fullBleed?: boolean, artworkScale?: number, samples?: number}} options
 *   fullBleed=true 时整张铺蓝底（maskable / apple-touch-icon 不支持透明）
 */
function renderIcon(size, { fullBleed = false, artworkScale = 1, samples = 4 } = {}) {
  const rgba = Buffer.alloc(size * size * 4);
  const sampleCount = samples * samples;

  for (let py = 0; py < size; py += 1) {
    for (let px = 0; px < size; px += 1) {
      let r = 0;
      let g = 0;
      let b = 0;
      let a = 0;
      for (let sy = 0; sy < samples; sy += 1) {
        for (let sx = 0; sx < samples; sx += 1) {
          const x = (px + (sx + 0.5) / samples) / size;
          const y = (py + (sy + 0.5) / samples) / size;
          // maskable 图标缩到中间安全区
          const nx = 0.5 + (x - 0.5) / artworkScale;
          const ny = 0.5 + (y - 0.5) / artworkScale;

          let color = null;
          if (fullBleed || inCircle(x, y, 0.5, 0.5, 0.485)) {
            color = inNote(nx, ny) ? WHITE : BRAND;
          }
          if (color) {
            r += color[0];
            g += color[1];
            b += color[2];
            a += 255;
          }
        }
      }
      const offset = (py * size + px) * 4;
      if (a > 0) {
        // 预乘后平均，再还原：透明边缘不会发黑
        rgba[offset] = Math.round(r / (a / 255));
        rgba[offset + 1] = Math.round(g / (a / 255));
        rgba[offset + 2] = Math.round(b / (a / 255));
        rgba[offset + 3] = Math.round(a / sampleCount);
      }
    }
  }

  return encodePng(size, size, rgba);
}

mkdirSync(ICON_DIR, { recursive: true });

const outputs = [
  ['icons/icon-192.png', renderIcon(192, { samples: 4 })],
  ['icons/icon-512.png', renderIcon(512, { samples: 3 })],
  ['icons/icon-maskable-512.png', renderIcon(512, { fullBleed: true, artworkScale: 0.72, samples: 3 })],
  ['apple-touch-icon.png', renderIcon(180, { fullBleed: true, artworkScale: 0.78, samples: 4 })],
];

for (const [name, buffer] of outputs) {
  writeFileSync(join(PUBLIC_DIR, name), buffer);
  console.log(`${name}  ${buffer.length} bytes`);
}
