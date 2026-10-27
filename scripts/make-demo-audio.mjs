#!/usr/bin/env node
/**
 * 合成演示音频（模块 07）。
 *
 * 本机没有 ffmpeg，也不想为一个演示素材引入编码器依赖，所以直接用 Node 写 WAV：
 * RIFF 头是固定 44 字节，PCM 数据逐样本算出即可，零依赖、可重复执行。
 *
 * 输出与 `sql/data.sql` 里 `t_song.audio_standard` 的取值一一对应：
 *   /media/demo/demo-01.wav … demo-06.wav
 * 时长也和 `t_song.duration` 对齐（否则进度条总时长与音频实际长度会对不上）。
 *
 * 音频内容不是真实音乐，而是「和声进行 + 五声音阶旋律 + 低音」的合成音：
 * 目的是让播放/暂停/进度/歌词时间轴**可听可验**，音质本身不在验收范围内（系分 R-03）。
 *
 * 用法：
 *   node scripts/make-demo-audio.mjs                 # 生成 6 个文件到 media/demo
 *   node scripts/make-demo-audio.mjs --out media/demo
 *   node scripts/make-demo-audio.mjs --rate 44100    # 提高采样率（体积同比上升）
 *   node scripts/make-demo-audio.mjs --with-click    # 每秒加一个轻拍，便于用耳朵校验歌词同步
 *   node scripts/make-demo-audio.mjs --only 01       # 只生成 demo-01.wav
 */

import { mkdirSync, writeFileSync, statSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const PROJECT_ROOT = resolve(__dirname, '..');

// ---------------------------------------------------------------------------
// 参数
// ---------------------------------------------------------------------------

function parseArgs(argv) {
  const opts = { out: 'media/demo', rate: 22050, withClick: false, only: null };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--out') opts.out = argv[++i];
    else if (arg === '--rate') opts.rate = Number(argv[++i]);
    else if (arg === '--with-click') opts.withClick = true;
    else if (arg === '--only') opts.only = String(argv[++i]).padStart(2, '0');
    else if (arg === '--help' || arg === '-h') {
      console.log('用法: node scripts/make-demo-audio.mjs [--out 目录] [--rate 采样率] [--with-click] [--only 序号]');
      process.exit(0);
    } else {
      console.error(`未知参数: ${arg}（用 --help 查看用法）`);
      process.exit(2);
    }
  }
  if (!Number.isFinite(opts.rate) || opts.rate < 8000 || opts.rate > 192000) {
    console.error(`采样率非法: ${opts.rate}`);
    process.exit(2);
  }
  return opts;
}

const opts = parseArgs(process.argv.slice(2));
const OUT_DIR = resolve(PROJECT_ROOT, opts.out);

// ---------------------------------------------------------------------------
// 曲目表：时长 / BPM 必须与 sql/data.sql 的 t_song 一致
// ---------------------------------------------------------------------------

/** 每首给一个不同的调和速度，切歌时能听出区别 */
const TRACKS = [
  { no: '01', name: '晴天', artist: '周杰伦', seconds: 269, bpm: 78, root: 60 /* C4 */ },
  { no: '02', name: '稻香', artist: '周杰伦', seconds: 223, bpm: 84, root: 62 /* D4 */ },
  { no: '03', name: '倔强', artist: '五月天', seconds: 251, bpm: 132, root: 64 /* E4 */ },
  { no: '04', name: '突然好想你', artist: '五月天', seconds: 347, bpm: 96, root: 57 /* A3 */ },
  { no: '05', name: '泡沫', artist: '邓紫棋', seconds: 258, bpm: 72, root: 55 /* G3 */ },
  { no: '06', name: '光年之外', artist: '邓紫棋', seconds: 235, bpm: 68, root: 59 /* B3 */ },
];

// ---------------------------------------------------------------------------
// 乐理小工具
// ---------------------------------------------------------------------------

/** MIDI 音高 → 频率 */
const freqOf = (midi) => 440 * 2 ** ((midi - 69) / 12);

/** 五声音阶（大调宫商角徵羽）在八度内的半音偏移 */
const PENTATONIC = [0, 2, 4, 7, 9];

/** 和声进行 I - V - vi - IV（相对主音的半音偏移） */
const PROGRESSION = [0, 7, 9, 5];

/** 确定性伪随机（xorshift32），保证每次生成的文件字节完全一致 */
function makeRandom(seed) {
  let s = seed >>> 0 || 1;
  return () => {
    s ^= s << 13;
    s >>>= 0;
    s ^= s >> 17;
    s ^= s << 5;
    s >>>= 0;
    return s / 0x100000000;
  };
}

// ---------------------------------------------------------------------------
// 合成
// ---------------------------------------------------------------------------

/**
 * 叠加一个音符：正弦基波 + 一点三次谐波（避免纯正弦过于单调），
 * 包络为「10ms 起音 → 指数衰减到尾」，不同音符叠加时不会出现爆音。
 */
function addNote(buffer, sampleRate, startSec, durSec, midi, amp) {
  const start = Math.max(0, Math.round(startSec * sampleRate));
  const end = Math.min(buffer.length, Math.round((startSec + durSec) * sampleRate));
  const total = end - start;
  if (total <= 0) return;

  const w = (2 * Math.PI * freqOf(midi)) / sampleRate;
  const attack = Math.min(Math.round(0.01 * sampleRate), Math.floor(total / 4));

  for (let i = 0; i < total; i += 1) {
    const t = i / total;
    // 起音线性爬升，随后指数衰减（自然衰减感）
    const env = (i < attack ? i / attack : 1) * Math.exp(-3.2 * t);
    const phase = w * i;
    const value = Math.sin(phase) + 0.22 * Math.sin(3 * phase);
    buffer[start + i] += value * env * amp;
  }
}

/** 轻拍：用于用耳朵校验歌词同步（--with-click） */
function addClick(buffer, sampleRate, atSec, amp) {
  const start = Math.round(atSec * sampleRate);
  const len = Math.round(0.02 * sampleRate);
  for (let i = 0; i < len && start + i < buffer.length; i += 1) {
    const env = Math.exp(-40 * (i / len));
    buffer[start + i] += Math.sin((2 * Math.PI * 1800 * i) / sampleRate) * env * amp;
  }
}

function synthesize(track, sampleRate) {
  const length = Math.round(track.seconds * sampleRate);
  const mix = new Float32Array(length);
  const beat = 60 / track.bpm; // 一拍秒数
  const bar = beat * 4;
  const rand = makeRandom(Number(track.no) * 2654435761);

  // 低音：每小节一个根音，低两个八度
  for (let t = 0, barIndex = 0; t < track.seconds; t += bar, barIndex += 1) {
    const root = track.root + PROGRESSION[barIndex % PROGRESSION.length];
    const dur = Math.min(bar, track.seconds - t);
    addNote(mix, sampleRate, t, dur * 0.98, root - 24, 0.30);
    addNote(mix, sampleRate, t, dur * 0.45, root - 12, 0.12);
  }

  // 旋律：每拍一个音，音高从当前和弦的五声音阶里取
  for (let t = 0, beatIndex = 0; t < track.seconds; t += beat, beatIndex += 1) {
    const chord = track.root + PROGRESSION[Math.floor(beatIndex / 4) % PROGRESSION.length];
    const degree = PENTATONIC[Math.floor(rand() * PENTATONIC.length)];
    const octave = rand() < 0.75 ? 12 : 0; // 大部分在主音上方一个八度
    const midi = chord + degree + octave;
    // 留一点空隙让旋律有呼吸感（每 4 拍的第 4 拍缩短）
    const dur = beat * (beatIndex % 4 === 3 ? 0.55 : 0.9);
    addNote(mix, sampleRate, t, Math.min(dur, track.seconds - t), midi, 0.22);
    // 长音：每 8 拍补一个持续音，避免听起来全是断奏
    if (beatIndex % 8 === 0) {
      addNote(mix, sampleRate, t, Math.min(beat * 3.5, track.seconds - t), midi - 12, 0.10);
    }
  }

  if (opts.withClick) {
    for (let t = 1; t < track.seconds; t += 1) {
      addClick(mix, sampleRate, t, 0.10);
    }
  }

  // 3 秒淡出，避免结尾"啪"一声
  const fade = Math.min(Math.round(3 * sampleRate), Math.floor(length / 4));
  for (let i = 0; i < fade; i += 1) {
    mix[length - 1 - i] *= i / fade;
  }
  return mix;
}

// ---------------------------------------------------------------------------
// WAV 编码（RIFF / PCM 16-bit 单声道）
// ---------------------------------------------------------------------------

function encodeWav(mix, sampleRate) {
  const dataBytes = mix.length * 2;
  const buffer = Buffer.alloc(44 + dataBytes);

  buffer.write('RIFF', 0, 'ascii');
  buffer.writeUInt32LE(36 + dataBytes, 4);
  buffer.write('WAVE', 8, 'ascii');
  buffer.write('fmt ', 12, 'ascii');
  buffer.writeUInt32LE(16, 16); // fmt chunk 长度
  buffer.writeUInt16LE(1, 20); // PCM
  buffer.writeUInt16LE(1, 22); // 声道数：单声道（演示音频，体积优先）
  buffer.writeUInt32LE(sampleRate, 24);
  buffer.writeUInt32LE(sampleRate * 2, 28); // 字节率 = 采样率 × 声道 × 位深/8
  buffer.writeUInt16LE(2, 32); // 块对齐
  buffer.writeUInt16LE(16, 34); // 位深
  buffer.write('data', 36, 'ascii');
  buffer.writeUInt32LE(dataBytes, 40);

  for (let i = 0; i < mix.length; i += 1) {
    // 硬限幅后转 Int16：叠加多个音符时峰值可能超过 1，不夹紧会溢出成刺耳的爆音
    const v = Math.max(-1, Math.min(1, mix[i]));
    buffer.writeInt16LE(Math.round(v * 32767), 44 + i * 2);
  }
  return buffer;
}

// ---------------------------------------------------------------------------
// 主流程
// ---------------------------------------------------------------------------

mkdirSync(OUT_DIR, { recursive: true });

const targets = opts.only ? TRACKS.filter((t) => t.no === opts.only) : TRACKS;
if (targets.length === 0) {
  console.error(`--only ${opts.only} 没有匹配的曲目（可选 01~06）`);
  process.exit(2);
}

console.log(`输出目录: ${OUT_DIR}`);
console.log(`采样率  : ${opts.rate} Hz / 单声道 / 16-bit PCM${opts.withClick ? '（带每秒轻拍）' : ''}`);
console.log('');

let totalBytes = 0;
for (const track of targets) {
  const started = Date.now();
  const mix = synthesize(track, opts.rate);
  const wav = encodeWav(mix, opts.rate);
  const file = join(OUT_DIR, `demo-${track.no}.wav`);
  writeFileSync(file, wav);
  const size = statSync(file).size;
  totalBytes += size;
  const mmss = `${String(Math.floor(track.seconds / 60)).padStart(2, '0')}:${String(track.seconds % 60).padStart(2, '0')}`;
  console.log(
    `demo-${track.no}.wav  ${mmss}  ${String(Math.round(size / 1024)).padStart(6)} KB  ` +
      `${track.name} - ${track.artist}  (${Date.now() - started}ms)`,
  );
}

console.log('');
console.log(`共 ${targets.length} 个文件，合计 ${(totalBytes / 1024 / 1024).toFixed(1)} MB`);
console.log('对应 sql/data.sql 中 t_song.audio_standard 的取值，重启后端即可播放。');
