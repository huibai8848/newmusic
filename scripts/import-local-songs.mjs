#!/usr/bin/env node
/**
 * 本地歌曲批量导入（零依赖）。
 *
 * 解决的问题：后台的「JSON 导入」要求先把歌名、歌手 ID、音频 URL 手写成数组，
 * 手里的本地文件（mp3/flac/wav）反而要人工抄一遍。这个脚本直接读文件：
 *
 *   1) 解析音频元数据 —— mp3（ID3v2.2/2.3/2.4 + ID3v1 + MPEG 帧头估算时长）、
 *      flac（STREAMINFO 精确时长 + VORBIS_COMMENT）、wav（fmt/data 时长 + LIST/INFO 标签，
 *      以及部分 wav 里内嵌的 id3 块）；
 *   2) 打标签缺失时按文件名兜底（`01. 周杰伦 - 晴天.wav` → 曲序 1 / 周杰伦 / 晴天）；
 *   3) 找歌词 —— 同目录同名 `.lrc`（UTF-8，失败按 GB18030/GBK 再解一次），
 *      或音频内嵌歌词（ID3 USLT / TXXX:LYRICS、FLAC LYRICS、WAV ILYR），随歌曲行一起提交；
 *   4) 扫描整个文件夹（默认递归），逐个上传音频拿 URL，再按 ≤200 行一批提交
 *      现有的 `POST /api/v1/admin/songs/import`；
 *   5) 输出逐文件报告（成功/跳过/失败原因）与汇总。
 *
 * 音频文件本身**不携带平台歌词记录**，`t_lyric` 也没有自动来源：
 * 不带 `lyric` 字段导进去的歌曲，播放器里必然是「暂无歌词」。
 * 已经导入过、只是缺歌词的歌曲用 `--lyrics-only` 补：
 * 该模式不上传音频、不建歌曲，只按「歌名 + 歌手」找到已有歌曲后调
 * `PUT /api/v1/admin/songs/{id}/lyric`。
 *
 * 为什么不在后端做「按目录导入」：那等于给后台开一个「按路径读服务器文件系统」的入口，
 * 目录穿越、权限、审计都是新风险；而本脚本跑在开发机上，文件本来就在本地，
 * 上传 + 导入走的还是已经验收过的那两条接口（`admin/files/audio`、`admin/songs/import`）。
 *
 * 用法：
 *   node scripts/import-local-songs.mjs "D:/Music/周杰伦" --dry-run
 *   node scripts/import-local-songs.mjs "D:/Music/周杰伦" --artist 周杰伦 --publish
 *   node scripts/import-local-songs.mjs "D:/Music" --recursive --create-artist --limit 20
 *   node scripts/import-local-songs.mjs "D:/Music/周杰伦" --lyrics-only --dry-run
 *   node scripts/import-local-songs.mjs --self-test
 *
 * 前置：后端已启动，且 `NEWMUSIC_ADMIN_CAPTCHA_ECHO=true`（脚本要自动过图形验证码）。
 * 约束：单文件 ≤50MB（与 `AdminFileController.MAX_AUDIO` 一致），仅 mp3/flac/wav。
 *
 * 安全：脚本不写数据库、不建 Redis 键，所有写操作都走后台 HTTP 接口；
 * 口令只从 `--password` / `NEWMUSIC_ADMIN_PASSWORD` 读取，不落盘（报告里也不写）。
 */

import { mkdirSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { basename, dirname, extname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url));
const PROJECT_ROOT = resolve(SCRIPT_DIR, '..');

const AUDIO_EXTS = ['.mp3', '.flac', '.wav'];
const MAX_AUDIO_DEFAULT = 50 * 1024 * 1024; // 与 AdminFileController.MAX_AUDIO 对齐
const IMPORT_BATCH = 200; // 与 SongImportReq.@Size(max=200) 对齐

// ---------------------------------------------------------------------------
// 参数
// ---------------------------------------------------------------------------

function parseArgs(argv) {
  const opts = {
    dir: null,
    base: process.env.API_BASE || 'http://127.0.0.1:8081/api/v1',
    user: process.env.NEWMUSIC_ADMIN_USER || 'admin',
    password: process.env.NEWMUSIC_ADMIN_PASSWORD || 'admin123',
    recursive: true,
    publish: false,
    createArtist: false,
    skipExisting: true,
    album: true,
    attachLyrics: true,
    lyricsOnly: false,
    dryRun: false,
    selfTest: false,
    limit: 0,
    artist: null,
    language: null,
    maxSize: MAX_AUDIO_DEFAULT,
    timeout: 60000,
    report: null,
    help: false,
  };

  const take = (i, name) => {
    const v = argv[i];
    if (v === undefined) throw new Error(`${name} 缺少取值`);
    return v;
  };

  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    const [flag, inline] = arg.includes('=') ? [arg.slice(0, arg.indexOf('=')), arg.slice(arg.indexOf('=') + 1)] : [arg, null];
    const value = () => (inline !== null ? inline : take(++i, flag));
    switch (flag) {
      case '--dir': opts.dir = value(); break;
      case '--base': opts.base = value().replace(/\/+$/, ''); break;
      case '--user': opts.user = value(); break;
      case '--password': opts.password = value(); break;
      case '--recursive': opts.recursive = true; break;
      case '--no-recursive': opts.recursive = false; break;
      case '--publish': opts.publish = true; break;
      case '--create-artist': opts.createArtist = true; break;
      case '--skip-existing': opts.skipExisting = true; break;
      case '--no-skip-existing': opts.skipExisting = false; break;
      case '--album': opts.album = true; break;
      case '--no-album': opts.album = false; break;
      case '--lyrics': opts.attachLyrics = true; break;
      case '--no-lyrics': opts.attachLyrics = false; break;
      case '--lyrics-only': opts.lyricsOnly = true; break;
      case '--dry-run': opts.dryRun = true; break;
      case '--self-test': opts.selfTest = true; break;
      case '--limit': opts.limit = Number(value()) || 0; break;
      case '--artist': opts.artist = value(); break;
      case '--language': opts.language = value(); break;
      case '--max-size': opts.maxSize = Math.round(Number(value()) * 1024 * 1024) || MAX_AUDIO_DEFAULT; break;
      case '--timeout': opts.timeout = Number(value()) || 60000; break;
      case '--report': opts.report = value(); break;
      case '--help': case '-h': opts.help = true; break;
      default:
        if (arg.startsWith('-')) throw new Error(`未知参数：${arg}（--help 查看用法）`);
        if (!opts.dir) opts.dir = arg;
        else throw new Error(`多余的位置参数：${arg}`);
    }
  }
  return opts;
}

const HELP = `
本地歌曲批量导入（mp3 / flac / wav）

用法：
  node scripts/import-local-songs.mjs <文件夹> [选项]

选项：
  --base <url>         后端 API 根，默认 http://127.0.0.1:8081/api/v1
  --user <name>        后台账号，默认 admin
  --password <pwd>     后台密码，默认 admin123（也可用 NEWMUSIC_ADMIN_PASSWORD）
  --recursive          递归子目录（默认开启）；--no-recursive 只扫当前层
  --publish            导入即上架（同时写 auditStatus=1、copyrightStatus=1）
  --create-artist      歌手不存在时自动创建
  --no-skip-existing   库里已有「同名 + 同歌手」时不跳过（默认跳过）
  --artist <name>      强制歌手名（优先级低于文件标签；用于整批同一歌手的目录）
  --no-album           不按标签匹配专辑（默认按「专辑名 + 歌手」精确匹配）
  --no-lyrics          不读取 .lrc / 内嵌歌词（默认找到就随歌曲写入）
  --lyrics-only        只补歌词：按「歌名 + 歌手」找到库中已有歌曲，
                       调 PUT /admin/songs/{id}/lyric；不上传音频、不新建歌曲
  --language <lang>    统一语种，如 国语 / 粤语（不传则由后端默认）
  --limit <n>          只处理前 n 个文件（调试用）
  --max-size <MB>      本地文件体积上限，默认 50（与服务端一致）
  --dry-run            只解析并打印元数据，不登录、不上传、不写库
  --report <path>      报告路径，默认 .tmp/import-report-<时间戳>.json
  --timeout <ms>       单请求超时，默认 60000
  --self-test          用内置合成文件自测三种解析器（不联网）
  -h, --help           显示本帮助
`;

// ---------------------------------------------------------------------------
// ID3（mp3 / wav 内嵌 id3 共用）
// ---------------------------------------------------------------------------

function readSynchsafe(buf, off) {
  return ((buf[off] & 0x7f) << 21) | ((buf[off + 1] & 0x7f) << 14) | ((buf[off + 2] & 0x7f) << 7) | (buf[off + 3] & 0x7f);
}

const UTF8_STRICT = new TextDecoder('utf-8', { fatal: true });
const GB18030 = new TextDecoder('gb18030');

/**
 * ID3 编码字节 → 字符串（不做空白/分隔符清洗，歌词要原样保留换行）。
 *
 * enc=0 按规范是 ISO-8859-1，但中文工具链（千千静听、早期 foobar 汉化版）
 * 普遍把 GBK 字节直接标成 enc=0。这里对含高位字节的 enc=0 先试严格 UTF-8，
 * 再退 GB18030——都不是时至少 latin1 还能读出 ASCII 部分。
 */
function decodeId3Bytes(enc, data) {
  if (enc === 1) {
    if (data.length >= 2 && data[0] === 0xff && data[1] === 0xfe) return data.subarray(2).toString('utf16le');
    if (data.length >= 2 && data[0] === 0xfe && data[1] === 0xff) return swap16(data.subarray(2)).toString('utf16le');
    return data.toString('utf16le');
  }
  if (enc === 2) return swap16(data).toString('utf16le');
  if (enc === 3) return data.toString('utf8');
  const raw = data.toString('latin1');
  if (!/[\x80-\xff]/u.test(raw)) return raw;
  try {
    return UTF8_STRICT.decode(data);
  } catch {
    return GB18030.decode(data);
  }
}

function decodeId3Text(body) {
  if (!body || body.length === 0) return '';
  return decodeId3Bytes(body[0], body.subarray(1)).replace(/\0+$/u, '').replace(/\0/gu, ' / ').trim();
}

/**
 * USLT（非同步歌词）：编码(1) 语言(3) 内容描述(NUL 结尾) 歌词正文。
 * 描述按当前编码的结束符切分（UTF-16 是两个 0 字节）。
 */
function decodeUslt(body) {
  if (!body || body.length < 4) return '';
  const enc = body[0];
  const term = enc === 1 || enc === 2 ? 2 : 1;
  const rest = body.subarray(4);
  let cut = -1;
  if (term === 2) {
    for (let i = 0; i + 1 < rest.length; i += 2) {
      if (rest[i] === 0 && rest[i + 1] === 0) { cut = i + 2; break; }
    }
  } else {
    const idx = rest.indexOf(0);
    if (idx >= 0) cut = idx + 1;
  }
  return decodeId3Bytes(enc, rest.subarray(cut < 0 ? 0 : cut)).replace(/\0+$/u, '').trim();
}

/** TXXX（用户自定义文本）：编码(1) 描述(NUL 结尾) 值。 */
function decodeTxxx(body) {
  if (!body || body.length < 2) return { desc: '', value: '' };
  const enc = body[0];
  const rest = body.subarray(1);
  const term = enc === 1 || enc === 2 ? 2 : 1;
  let cut = -1;
  if (term === 2) {
    for (let i = 0; i + 1 < rest.length; i += 2) {
      if (rest[i] === 0 && rest[i + 1] === 0) { cut = i; break; }
    }
  } else {
    cut = rest.indexOf(0);
  }
  if (cut < 0) return { desc: '', value: '' };
  return {
    desc: decodeId3Bytes(enc, rest.subarray(0, cut)).trim(),
    value: decodeId3Bytes(enc, rest.subarray(cut + term)).replace(/\0+$/u, '').trim(),
  };
}

/** `.lrc` 常见编码：UTF-8（含 BOM）/ GBK。先严格 UTF-8，失败再按 GB18030 解。 */
function decodeLyricBuffer(buf) {
  if (!buf || buf.length === 0) return '';
  if (buf.length >= 3 && buf[0] === 0xef && buf[1] === 0xbb && buf[2] === 0xbf) {
    return buf.toString('utf8', 3);
  }
  if (buf.length >= 2 && buf[0] === 0xff && buf[1] === 0xfe) {
    return buf.toString('utf16le', 2);
  }
  try {
    return UTF8_STRICT.decode(buf);
  } catch {
    return GB18030.decode(buf);
  }
}

function swap16(buf) {
  const out = Buffer.from(buf);
  for (let i = 0; i + 1 < out.length; i += 2) {
    const t = out[i];
    out[i] = out[i + 1];
    out[i + 1] = t;
  }
  return out;
}

/** 解析文件头部的 ID3v2；返回 { tags, audioStart } 或 null。 */
function parseId3v2(buf, start = 0) {
  if (buf.length < start + 10 || buf.toString('latin1', start, start + 3) !== 'ID3') return null;
  const major = buf[start + 3];
  if (major < 2 || major > 4) return null;
  const flags = buf[start + 5];
  const size = readSynchsafe(buf, start + 6);
  const audioStart = start + 10 + size;
  const end = Math.min(audioStart, buf.length);
  const tags = {};

  let p = start + 10;
  if (flags & 0x40) {
    // 扩展头：v2.3 的长度字段不含自身 4 字节，v2.4 的含
    if (p + 4 <= end) {
      const extSize = major === 4 ? readSynchsafe(buf, p) : buf.readUInt32BE(p);
      p += major === 4 ? extSize : extSize + 4;
    }
  }

  while (p + 6 <= end) {
    let id;
    let frameSize;
    let headerSize;
    if (major === 2) {
      id = buf.toString('latin1', p, p + 3);
      frameSize = (buf[p + 3] << 16) | (buf[p + 4] << 8) | buf[p + 5];
      headerSize = 6;
    } else {
      id = buf.toString('latin1', p, p + 4);
      frameSize = major === 4 ? readSynchsafe(buf, p + 4) : buf.readUInt32BE(p + 4);
      headerSize = 10;
    }
    if (!/^[A-Z0-9]{3,4}$/.test(id) || frameSize <= 0) break;
    if (p + headerSize + frameSize > end) break;
    const body = buf.subarray(p + headerSize, p + headerSize + frameSize);
    if (id[0] === 'T' && id !== 'TXXX') {
      const text = decodeId3Text(body);
      if (text && !tags[id]) tags[id] = text;
    } else if (id === 'USLT') {
      const text = decodeUslt(body);
      if (text && !tags.USLT) tags.USLT = text;
    } else if (id === 'TXXX') {
      const { desc, value } = decodeTxxx(body);
      if (desc && value && !tags[`TXXX:${desc.toUpperCase()}`]) {
        tags[`TXXX:${desc.toUpperCase()}`] = value;
      }
    }
    p += headerSize + frameSize;
  }
  return { tags, audioStart };
}

/** ID3v1（文件末尾 128 字节）。只补缺失字段。 */
function parseId3v1(buf) {
  if (buf.length < 128) return null;
  const off = buf.length - 128;
  if (buf.toString('latin1', off, off + 3) !== 'TAG') return null;
  const read = (from, len) => buf.toString('latin1', off + from, off + from + len).replace(/\0.*$/u, '').trim();
  return {
    title: read(3, 30),
    artist: read(33, 30),
    album: read(63, 30),
    year: read(93, 4),
    genre: read(127, 1),
  };
}

// ---------------------------------------------------------------------------
// MPEG 帧头 → 时长
// ---------------------------------------------------------------------------

const MPEG_BITRATES = {
  '1-1': [0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448],
  '1-2': [0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384],
  '1-3': [0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320],
  '2-1': [0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256],
  '2-2': [0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160],
  '2-3': [0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160],
};
const MPEG_SAMPLE_RATES = { 1: [44100, 48000, 32000], 2: [22050, 24000, 16000], 2.5: [11025, 12000, 8000] };

function readMpegInfo(buf, start) {
  const limit = Math.min(buf.length - 4, start + 64 * 1024); // 同步字最多往后找 64KB
  for (let p = start; p < limit; p += 1) {
    if (buf[p] !== 0xff || (buf[p + 1] & 0xe0) !== 0xe0) continue;
    const versionBits = (buf[p + 1] >> 3) & 0x03;
    const layerBits = (buf[p + 1] >> 1) & 0x03;
    if (versionBits === 1 || layerBits === 0) continue; // 保留值
    const version = versionBits === 3 ? 1 : versionBits === 2 ? 2 : 2.5;
    const layer = 4 - layerBits; // 3 → Layer I，2 → Layer II，1 → Layer III
    const bitrateIndex = (buf[p + 2] >> 4) & 0x0f;
    const sampleRateIndex = (buf[p + 2] >> 2) & 0x03;
    if (bitrateIndex === 0 || bitrateIndex === 15 || sampleRateIndex === 3) continue;
    const rateKey = version === 1 ? '1' : '2';
    const bitrate = MPEG_BITRATES[`${rateKey}-${layer}`]?.[bitrateIndex];
    const sampleRate = MPEG_SAMPLE_RATES[version]?.[sampleRateIndex];
    if (!bitrate || !sampleRate) continue;

    const channelMode = (buf[p + 3] >> 6) & 0x03;
    const mono = channelMode === 3;
    const samplesPerFrame = layer === 1 ? 384 : layer === 2 ? 1152 : version === 1 ? 1152 : 576;
    const sideInfo = version === 1 ? (mono ? 17 : 32) : (mono ? 9 : 17);
    const xingOffset = p + 4 + sideInfo;
    let frames = 0;
    if (xingOffset + 12 <= buf.length) {
      const mark = buf.toString('latin1', xingOffset, xingOffset + 4);
      if (mark === 'Xing' || mark === 'Info') {
        const flags = buf.readUInt32BE(xingOffset + 4);
        if (flags & 0x01) frames = buf.readUInt32BE(xingOffset + 8);
      }
    }

    const audioBytes = Math.max(0, buf.length - p - 128 * (buf.toString('latin1', buf.length - 128, buf.length - 125) === 'TAG' ? 1 : 0));
    const duration = frames > 0
      ? (frames * samplesPerFrame) / sampleRate
      : (audioBytes * 8) / (bitrate * 1000);
    return { bitrate, sampleRate, frames, duration: Math.round(duration) };
  }
  return null;
}

// ---------------------------------------------------------------------------
// 三种格式的解析器
// ---------------------------------------------------------------------------

function normalizeYear(value) {
  if (!value) return null;
  const m = String(value).match(/(19|20)\d{2}/);
  return m ? Number(m[0]) : null;
}

function parseBpm(value) {
  if (!value) return null;
  const n = Number(String(value).trim());
  return Number.isFinite(n) && n > 0 && n < 1000 ? Math.round(n) : null;
}

function cleanGenre(value) {
  if (!value) return null;
  const m = String(value).match(/^\((\d+)\)(.*)$/u);
  if (m) return m[2].trim() || null; // 只有数字编号的那部分无法还原，宁可不填
  return String(value).trim() || null;
}

function pick(tags, ...keys) {
  for (const k of keys) {
    const v = tags[k];
    if (v !== undefined && v !== null && String(v).trim() !== '') return String(v).trim();
  }
  return null;
}

function parseMp3(buf) {
  const id3 = parseId3v2(buf, 0);
  const tags = id3 ? { ...id3.tags } : {};
  const v1 = parseId3v1(buf);
  if (v1) {
    if (!pick(tags, 'TIT2', 'TT2') && v1.title) tags.v1Title = v1.title;
    if (!pick(tags, 'TPE1', 'TP1', 'TPE2', 'TP2') && v1.artist) tags.v1Artist = v1.artist;
    if (!pick(tags, 'TALB', 'TAL') && v1.album) tags.v1Album = v1.album;
    if (!pick(tags, 'TYER', 'TYE', 'TDRC') && v1.year) tags.v1Year = v1.year;
    if (!pick(tags, 'TCON', 'TCO') && v1.genre) tags.v1Genre = v1.genre;
  }
  const info = readMpegInfo(buf, id3 ? id3.audioStart : 0);
  let duration = 0;
  const tlen = Number(pick(tags, 'TLEN'));
  if (Number.isFinite(tlen) && tlen > 0) duration = Math.round(tlen / 1000);
  if (!duration && info) duration = info.duration;

  return {
    format: 'mp3',
    title: pick(tags, 'TIT2', 'TT2', 'v1Title'),
    artist: pick(tags, 'TPE1', 'TP1', 'TPE2', 'TP2', 'v1Artist'),
    album: pick(tags, 'TALB', 'TAL', 'v1Album'),
    track: Number.parseInt(pick(tags, 'TRCK', 'TRK') || '', 10) || null,
    genre: cleanGenre(pick(tags, 'TCON', 'TCO', 'v1Genre')),
    year: normalizeYear(pick(tags, 'TDRC', 'TYER', 'TYE', 'v1Year')),
    isrc: pick(tags, 'TSRC'),
    bpm: parseBpm(pick(tags, 'TBPM')),
    language: pick(tags, 'TLAN'),
    duration,
    sampleRate: info ? info.sampleRate : null,
    lyric: pick(tags, 'USLT', 'TXXX:LYRICS', 'TXXX:UNSYNCEDLYRICS', 'TXXX:UNSYNCED LYRICS', 'TXXX:LYRIC'),
    lyricSource: pick(tags, 'USLT') ? 'id3-USLT'
      : pick(tags, 'TXXX:LYRICS', 'TXXX:UNSYNCEDLYRICS', 'TXXX:UNSYNCED LYRICS', 'TXXX:LYRIC') ? 'id3-TXXX' : null,
  };
}

function parseFlac(buf) {
  if (buf.length < 8 || buf.toString('latin1', 0, 4) !== 'fLaC') return null;
  const tags = {};
  let stream = null;
  let p = 4;
  while (p + 4 <= buf.length) {
    const header = buf[p];
    const last = (header & 0x80) !== 0;
    const type = header & 0x7f;
    const len = (buf[p + 1] << 16) | (buf[p + 2] << 8) | buf[p + 3];
    const body = buf.subarray(p + 4, Math.min(p + 4 + len, buf.length));

    if (type === 0 && body.length >= 18) {
      const sampleRate = (body[10] << 12) | (body[11] << 4) | (body[12] >> 4);
      const channels = ((body[12] >> 1) & 0x07) + 1;
      const totalSamples = (body[13] & 0x0f) * 2 ** 32 + body.readUInt32BE(14);
      stream = { sampleRate, channels, totalSamples };
    } else if (type === 4) {
      let q = 0;
      if (body.length >= 8) {
        const vendorLen = body.readUInt32LE(q);
        q += 4 + vendorLen;
        if (q + 4 <= body.length) {
          const count = body.readUInt32LE(q);
          q += 4;
          for (let i = 0; i < count && q + 4 <= body.length; i += 1) {
            const l = body.readUInt32LE(q);
            q += 4;
            if (q + l > body.length) break;
            const kv = body.toString('utf8', q, q + l);
            q += l;
            const eq = kv.indexOf('=');
            if (eq > 0) {
              const key = kv.slice(0, eq).toUpperCase();
              if (!tags[key]) tags[key] = kv.slice(eq + 1);
            }
          }
        }
      }
    }
    p += 4 + len;
    if (last) break;
  }
  const duration = stream && stream.sampleRate > 0
    ? Math.round(stream.totalSamples / stream.sampleRate)
    : 0;

  return {
    format: 'flac',
    title: pick(tags, 'TITLE'),
    artist: pick(tags, 'ARTIST', 'ALBUMARTIST'),
    album: pick(tags, 'ALBUM'),
    track: Number.parseInt(pick(tags, 'TRACKNUMBER') || '', 10) || null,
    genre: cleanGenre(pick(tags, 'GENRE')),
    year: normalizeYear(pick(tags, 'DATE', 'YEAR')),
    isrc: pick(tags, 'ISRC'),
    bpm: parseBpm(pick(tags, 'BPM')),
    language: pick(tags, 'LANGUAGE'),
    duration,
    sampleRate: stream ? stream.sampleRate : null,
    lyric: pick(tags, 'LYRICS', 'UNSYNCEDLYRICS', 'UNSYNCED LYRICS', 'LYRIC'),
    lyricSource: pick(tags, 'LYRICS', 'UNSYNCEDLYRICS', 'UNSYNCED LYRICS', 'LYRIC') ? 'flac-LYRICS' : null,
  };
}

const WAV_INFO_KEYS = {
  INAM: 'title',
  IART: 'artist',
  IPRD: 'album',
  IGNR: 'genre',
  ICRD: 'date',
  ITRK: 'track',
  ISRC: 'isrc',
  IBPM: 'bpm',
  ILNG: 'language',
  IALB: 'album',
  ILYR: 'lyric',
};

function parseWav(buf) {
  if (buf.length < 12 || buf.toString('latin1', 0, 4) !== 'RIFF' || buf.toString('latin1', 8, 12) !== 'WAVE') return null;
  const info = {};
  let fmt = null;
  let dataSize = 0;
  let embedded = null;
  let p = 12;
  while (p + 8 <= buf.length) {
    const id = buf.toString('latin1', p, p + 4);
    const size = buf.readUInt32LE(p + 4);
    const bodyStart = p + 8;
    const body = buf.subarray(bodyStart, Math.min(bodyStart + size, buf.length));

    if (id === 'fmt ' && body.length >= 16) {
      fmt = {
        channels: body.readUInt16LE(2),
        sampleRate: body.readUInt32LE(4),
        byteRate: body.readUInt32LE(8),
        bits: body.readUInt16LE(14),
      };
    } else if (id === 'data') {
      dataSize = Math.min(size, buf.length - bodyStart);
    } else if (id === 'LIST' && body.length >= 4 && body.toString('latin1', 0, 4) === 'INFO') {
      let q = 4;
      while (q + 8 <= body.length) {
        const subId = body.toString('latin1', q, q + 4);
        const subLen = body.readUInt32LE(q + 4);
        const value = body.toString('utf8', q + 8, Math.min(q + 8 + subLen, body.length)).replace(/\0+$/u, '').trim();
        if (value && subId.trim()) info[subId.trim()] = value;
        q += 8 + subLen + (subLen % 2);
      }
    } else if (id.toLowerCase() === 'id3 ') {
      const id3 = parseId3v2(body, 0);
      if (id3) embedded = id3.tags;
    }
    p = bodyStart + size + (size % 2);
  }
  const duration = fmt && fmt.byteRate > 0 ? Math.round(dataSize / fmt.byteRate) : 0;

  const tags = {};
  for (const [id, key] of Object.entries(WAV_INFO_KEYS)) {
    if (info[id]) tags[key] = info[id];
  }
  const id3Title = embedded ? pick(embedded, 'TIT2', 'TT2') : null;

  return {
    format: 'wav',
    title: id3Title || tags.title || null,
    artist: (embedded ? pick(embedded, 'TPE1', 'TP1') : null) || tags.artist || null,
    album: (embedded ? pick(embedded, 'TALB', 'TAL') : null) || tags.album || null,
    track: Number.parseInt(tags.track || (embedded ? pick(embedded, 'TRCK') : '') || '', 10) || null,
    genre: cleanGenre(tags.genre || (embedded ? pick(embedded, 'TCON') : null)),
    year: normalizeYear(tags.date || (embedded ? pick(embedded, 'TDRC', 'TYER') : null)),
    isrc: tags.isrc || (embedded ? pick(embedded, 'TSRC') : null),
    bpm: parseBpm(tags.bpm || (embedded ? pick(embedded, 'TBPM') : null)),
    language: tags.language || null,
    duration,
    sampleRate: fmt ? fmt.sampleRate : null,
    lyric: tags.lyric || (embedded
      ? pick(embedded, 'USLT', 'TXXX:LYRICS', 'TXXX:UNSYNCEDLYRICS') : null),
    lyricSource: tags.lyric ? 'wav-ILYR' : (embedded
      ? (pick(embedded, 'USLT', 'TXXX:LYRICS', 'TXXX:UNSYNCEDLYRICS') ? 'wav-id3' : null) : null),
  };
}

function parseAudioFile(buf, ext) {
  if (ext === '.mp3') return parseMp3(buf);
  if (ext === '.flac') return parseFlac(buf);
  if (ext === '.wav') return parseWav(buf);
  return null;
}

/** 同目录同名 `.lrc`。Windows 文件名大小写不敏感，`.LRC` 也能按 `.lrc` 读到。 */
function readSidecarLyric(audioPath) {
  const candidate = join(dirname(audioPath), `${basename(audioPath, extname(audioPath))}.lrc`);
  try {
    const text = decodeLyricBuffer(readFileSync(candidate)).replace(/^\uFEFF/u, '').trim();
    return text ? { lyric: text, source: 'lrc-file', file: basename(candidate) } : null;
  } catch {
    return null;
  }
}

/** 歌词优先级：同名 `.lrc`（通常带时间轴、最完整）> 音频内嵌歌词。 */
function loadLyric(audioPath, meta, options) {
  if (!options.attachLyrics) return null;
  const side = readSidecarLyric(audioPath);
  if (side) return side;
  const embedded = meta && meta.lyric ? String(meta.lyric).trim() : '';
  return embedded ? { lyric: embedded, source: meta.lyricSource || 'embedded' } : null;
}

/** 与后端 LyricService 的自动判断保持一致：含 `[mm:ss]` 时间轴按标准 LRC，否则纯文本。 */
function lyricContentTypeOf(text) {
  return /\[\d{1,2}:\d{2}(?:[.:]\d{1,3})?\]/u.test(String(text || '')) ? 1 : 3;
}

// ---------------------------------------------------------------------------
// 文件名兜底
// ---------------------------------------------------------------------------

/** `01. 周杰伦 - 晴天.wav` → { track: 1, artist: '周杰伦', title: '晴天' } */
function fromFilename(filePath) {
  let base = basename(filePath, extname(filePath)).normalize('NFC').trim();
  let track = null;
  const trackMatch = base.match(/^(\d{1,3})\s*[-.、_)\s]\s*/u);
  if (trackMatch) {
    track = Number(trackMatch[1]);
    base = base.slice(trackMatch[0].length).replace(/^[\])\s]+/u, '');
  }
  const parts = base.split(/\s+[-–—]\s+/u);
  if (parts.length >= 2 && parts[0].trim() && parts.slice(1).join(' - ').trim()) {
    return { track, artist: parts[0].trim(), title: parts.slice(1).join(' - ').trim() };
  }
  return { track, artist: null, title: base.trim() };
}

function eraOf(year) {
  if (!year) return null;
  if (year >= 2020) return '20s';
  if (year >= 2010) return '10s';
  if (year >= 2000) return '00s';
  if (year >= 1990) return '90s';
  if (year >= 1980) return '80s';
  return null;
}

// ---------------------------------------------------------------------------
// HTTP 客户端（Cookie 会话）
// ---------------------------------------------------------------------------

class Api {
  constructor(base, timeout) {
    this.base = base;
    this.timeout = timeout;
    this.cookies = new Map();
  }

  cookieHeader() {
    return [...this.cookies.entries()].map(([k, v]) => `${k}=${v}`).join('; ');
  }

  async request(method, path, { json, body, headers = {}, timeout } = {}) {
    const finalHeaders = { ...headers };
    const cookie = this.cookieHeader();
    if (cookie) finalHeaders.Cookie = cookie;
    let payload = body;
    if (json !== undefined) {
      finalHeaders['Content-Type'] = 'application/json; charset=utf-8';
      payload = JSON.stringify(json);
    }
    const res = await fetch(this.base + path, {
      method,
      headers: finalHeaders,
      body: payload,
      signal: AbortSignal.timeout(timeout || this.timeout),
    });
    const setCookies = typeof res.headers.getSetCookie === 'function'
      ? res.headers.getSetCookie()
      : (res.headers.get('set-cookie') ? [res.headers.get('set-cookie')] : []);
    for (const raw of setCookies) {
      const pair = raw.split(';')[0];
      const eq = pair.indexOf('=');
      if (eq > 0) this.cookies.set(pair.slice(0, eq).trim(), pair.slice(eq + 1));
    }
    const text = await res.text();
    let parsed = null;
    try {
      parsed = text ? JSON.parse(text) : null;
    } catch {
      parsed = null;
    }
    return { status: res.status, json: parsed, text };
  }

  async login(user, password) {
    const cap = await this.request('GET', '/admin/auth/captcha');
    const data = cap.json && cap.json.code === 0 ? cap.json.data : null;
    if (!data || !data.captchaId) {
      throw new Error(`获取验证码失败：HTTP ${cap.status} ${(cap.text || '').slice(0, 160)}`);
    }
    if (!data.code) {
      throw new Error('后端未回显验证码（需要以 NEWMUSIC_ADMIN_CAPTCHA_ECHO=true 启动）');
    }
    const res = await this.request('POST', '/admin/auth/login', {
      json: { username: user, password, captchaId: data.captchaId, captchaCode: data.code },
    });
    if (!res.json || res.json.code !== 0) {
      throw new Error(`登录失败：${res.json ? `${res.json.code} ${res.json.message}` : `HTTP ${res.status}`}`);
    }
    const admin = res.json.data && res.json.data.admin;
    if (!admin) throw new Error('登录响应异常：缺少 admin 字段');
    return { perms: admin.perms || [], roles: admin.roles || [] };
  }
}

// ---------------------------------------------------------------------------
// 导入流水线
// ---------------------------------------------------------------------------

function collectFiles(root, recursive) {
  const out = [];
  const walk = (dir) => {
    let entries;
    try {
      entries = readdirSync(dir, { withFileTypes: true });
    } catch (err) {
      console.warn(`  跳过不可读目录 ${dir}：${err.message}`);
      return;
    }
    for (const entry of entries) {
      const abs = join(dir, entry.name);
      if (entry.isDirectory()) {
        if (recursive && !entry.name.startsWith('.')) walk(abs);
      } else if (entry.isFile()) {
        const ext = extname(entry.name).toLowerCase();
        if (AUDIO_EXTS.includes(ext)) {
          let size = 0;
          try {
            size = statSync(abs).size;
          } catch {
            size = 0;
          }
          out.push({ abs, ext, size });
        }
      }
    }
  };
  walk(root);
  return out.sort((a, b) => a.abs.localeCompare(b.abs, 'zh-CN', { numeric: true }));
}

function mimeOf(ext) {
  if (ext === '.mp3') return 'audio/mpeg';
  if (ext === '.flac') return 'audio/flac';
  return 'audio/wav';
}

function normalizeName(value) {
  return String(value || '').normalize('NFC').replace(/\s+/gu, ' ').trim().toLowerCase();
}

class Resolver {
  constructor(api, options) {
    this.api = api;
    this.options = options;
    this.artists = new Map(); // name → { id, name } | null
    this.albums = new Map(); // `${artistId}|${album}` → id | null
    this.songs = new Map(); // `${artistId}|${text}` → boolean（同一目录里重复文件名只查一次）
    this.songIds = new Map(); // `${artistId}|${text}` → { id, name } | null
  }

  async artist(name) {
    const key = normalizeName(name);
    if (this.artists.has(key)) return this.artists.get(key);

    const res = await this.api.request('GET', `/admin/artists?keyword=${encodeURIComponent(name)}&pageSize=50`);
    const list = (res.json && res.json.data && res.json.data.list) || [];
    let hit = list.find((a) => normalizeName(a.name) === key);
    if (hit) {
      const found = { id: String(hit.id), name: hit.name };
      this.artists.set(key, found);
      return found;
    }
    if (!this.options.createArtist) {
      this.artists.set(key, null);
      return null;
    }
    const created = await this.api.request('POST', '/admin/artists', { json: { name: String(name).trim(), status: 1 } });
    if (created.json && created.json.code === 0) {
      const found = { id: String(created.json.data), name: String(name).trim(), created: true };
      this.artists.set(key, found);
      return found;
    }
    // 并发或已存在（DUPLICATE_DATA）时再查一次
    const retry = await this.api.request('GET', `/admin/artists?keyword=${encodeURIComponent(name)}&pageSize=50`);
    const retryList = (retry.json && retry.json.data && retry.json.data.list) || [];
    hit = retryList.find((a) => normalizeName(a.name) === key);
    if (hit) {
      const found = { id: String(hit.id), name: hit.name };
      this.artists.set(key, found);
      return found;
    }
    this.artists.set(key, null);
    return null;
  }

  async album(name, artistId) {
    if (!this.options.album || !name || !artistId) return null;
    const key = `${artistId}|${normalizeName(name)}`;
    if (this.albums.has(key)) return this.albums.get(key);
    const res = await this.api.request(
      'GET',
      `/admin/albums?keyword=${encodeURIComponent(name)}&artistId=${encodeURIComponent(artistId)}&pageSize=50`,
    );
    const list = (res.json && res.json.data && res.json.data.list) || [];
    const hit = list.find((a) => normalizeName(a.name) === normalizeName(name));
    const id = hit ? String(hit.id) : null;
    this.albums.set(key, id);
    return id;
  }

  async songExists(title, artistId) {
    if (!this.options.skipExisting) return false;
    const key = `${artistId}|${normalizeName(title)}`;
    if (this.songs.has(key)) return this.songs.get(key);
    const res = await this.api.request('GET', `/admin/songs?keyword=${encodeURIComponent(title)}&pageSize=50`);
    const list = (res.json && res.json.data && res.json.data.list) || [];
    const exists = list.some((s) => normalizeName(s.name) === normalizeName(title)
      && String(s.artistId) === String(artistId));
    this.songs.set(key, exists);
    return exists;
  }

  /** 按「歌名 + 歌手」找已有歌曲；找不到返回 null。给 --lyrics-only 用。 */
  async findSong(title, artistId) {
    const key = `${artistId}|${normalizeName(title)}`;
    if (this.songIds.has(key)) return this.songIds.get(key);
    const res = await this.api.request('GET', `/admin/songs?keyword=${encodeURIComponent(title)}&pageSize=50`);
    const list = (res.json && res.json.data && res.json.data.list) || [];
    const hit = list.find((s) => normalizeName(s.name) === normalizeName(title)
      && String(s.artistId) === String(artistId));
    const value = hit ? { id: hit.id, name: hit.name } : null;
    this.songIds.set(key, value);
    return value;
  }

  async saveLyric(songId, lyric) {
    const res = await this.api.request('PUT', `/admin/songs/${encodeURIComponent(songId)}/lyric`, {
      json: { content: lyric },
    });
    if (!res.json || res.json.code !== 0) {
      throw new Error(`歌词保存失败：${res.json ? `${res.json.code} ${res.json.message}` : `HTTP ${res.status}`}`);
    }
    return res.json.data;
  }

  async upload(abs, ext, buf) {
    const form = new FormData();
    form.append('file', new Blob([buf], { type: mimeOf(ext) }), basename(abs));
    const res = await this.api.request('POST', '/admin/files/audio', { body: form });
    if (!res.json || res.json.code !== 0) {
      throw new Error(`上传失败：${res.json ? `${res.json.code} ${res.json.message}` : `HTTP ${res.status}`}`);
    }
    return res.json.data.url;
  }

  async importBatch(rows) {
    const res = await this.api.request('POST', '/admin/songs/import', { json: { rows } });
    if (!res.json || res.json.code !== 0) {
      throw new Error(`导入接口失败：${res.json ? `${res.json.code} ${res.json.message}` : `HTTP ${res.status}`}`);
    }
    return res.json.data;
  }
}

function buildRow(meta, artistId, albumId, audioUrl, options) {
  const row = {
    name: meta.title,
    artistId: Number.isSafeInteger(Number(artistId)) ? Number(artistId) : artistId,
    audioStandard: audioUrl,
  };
  if (albumId) row.albumId = Number.isSafeInteger(Number(albumId)) ? Number(albumId) : albumId;
  if (meta.duration) row.duration = meta.duration;
  if (meta.genre) row.genre = meta.genre;
  if (meta.isrc) row.isrc = meta.isrc;
  if (meta.bpm) row.bpm = meta.bpm;
  if (meta.language || options.language) row.language = options.language || meta.language;
  const era = eraOf(meta.year);
  if (era) row.era = era;
  if (meta.lyric) {
    row.lyric = meta.lyric;
    row.lyricContentType = lyricContentTypeOf(meta.lyric);
  }
  if (options.publish) {
    row.status = 1;
    row.auditStatus = 1;
    row.copyrightStatus = 1;
  }
  return row;
}

function stamp() {
  const d = new Date();
  const pad = (n, w = 2) => String(n).padStart(w, '0');
  return `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}-${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}`;
}

function printHelpAndExit(code) {
  process.stdout.write(HELP);
  process.exit(code);
}

async function run(options) {
  const root = resolve(options.dir);
  let rootStat;
  try {
    rootStat = statSync(root);
  } catch {
    throw new Error(`目录不存在：${root}`);
  }
  if (!rootStat.isDirectory()) throw new Error(`不是目录：${root}`);

  const files = collectFiles(root, options.recursive);
  const picked = options.limit > 0 ? files.slice(0, options.limit) : files;
  console.log(`扫描 ${root}`);
  console.log(`  发现音频 ${files.length} 个${options.limit ? `，本次处理前 ${picked.length} 个` : ''}`
    + `${options.recursive ? '（含子目录）' : '（仅当前目录）'}`);

  const api = new Api(options.base, options.timeout);
  let resolver = null;
  if (options.dryRun) {
    console.log('  --dry-run：只解析元数据，不登录、不上传、不写库');
  } else {
    const info = await api.login(options.user, options.password);
    console.log(`  已登录 ${options.user}（角色 ${info.roles.join(',') || '—'}，权限 ${info.perms.length} 项）`);
    resolver = new Resolver(api, options);
    if (options.publish && !info.perms.includes('admin:song:online')) {
      throw new Error('--publish 需要 admin:song:online 权限');
    }
    if (options.lyricsOnly && !info.perms.includes('admin:song:edit')) {
      throw new Error('--lyrics-only 需要 admin:song:edit 权限');
    }
  }

  const results = [];
  const rows = [];
  const rowFiles = [];
  let skipped = 0;
  let failed = 0;
  let lyricUpdated = 0;

  for (let i = 0; i < picked.length; i += 1) {
    const entry = picked[i];
    const rel = relative(root, entry.abs) || basename(entry.abs);
    const label = `[${i + 1}/${picked.length}] ${rel}`;
    const record = { file: rel, size: entry.size, format: entry.ext.slice(1) };
    const fail = (reason) => {
      record.status = 'failed';
      record.reason = reason;
      failed += 1;
      results.push(record);
      console.log(`  ✗ ${label}：${reason}`);
    };

    if (entry.size <= 0) {
      fail('空文件');
      continue;
    }
    // --lyrics-only 不上传音频，体积上限是上传接口的约束，这里不适用
    if (!options.lyricsOnly && entry.size > options.maxSize) {
      fail(`超过 ${Math.round(options.maxSize / 1024 / 1024)}MB（当前 ${(entry.size / 1024 / 1024).toFixed(1)}MB），请先转码或调大 --max-size`);
      continue;
    }

    let buf;
    try {
      buf = readFileSync(entry.abs);
    } catch (err) {
      fail(`读取失败：${err.message}`);
      continue;
    }

    let meta;
    try {
      meta = parseAudioFile(buf, entry.ext);
    } catch (err) {
      fail(`解析失败：${err.message}`);
      continue;
    }
    if (!meta) {
      fail('无法识别的音频文件');
      continue;
    }

    const fromName = fromFilename(entry.abs);
    const title = meta.title || fromName.title;
    const artistName = options.artist || meta.artist || fromName.artist;
    const albumName = meta.album || null;
    record.title = title;
    record.artistName = artistName;
    record.albumName = albumName;
    record.duration = meta.duration || null;
    record.track = meta.track || fromName.track || null;
    record.genre = meta.genre || null;
    record.year = meta.year || null;
    record.sampleRate = meta.sampleRate || null;
    record.tags = Boolean(meta.title || meta.artist || meta.album);
    const lyricInfo = loadLyric(entry.abs, meta, options);
    if (lyricInfo) {
      record.lyricSource = lyricInfo.source;
      record.lyricChars = lyricInfo.lyric.length;
    }

    if (!title) {
      fail('无法确定歌曲名（无标签且文件名为空）');
      continue;
    }
    if (!artistName) {
      fail('无法确定歌手（无标签且文件名不含「歌手 - 歌名」，可用 --artist 指定）');
      continue;
    }

    if (options.dryRun) {
      record.status = 'dry-run';
      results.push(record);
      console.log(`  · ${label} → ${title} / ${artistName} / ${meta.duration || '?'}s`
        + `${record.tags ? '' : '（标签缺失，用文件名兜底）'}`
        + `${lyricInfo ? ` / 歌词 ${lyricInfo.lyric.length} 字（${lyricInfo.source}）` : ' / 无歌词'}`);
      continue;
    }

    const artist = await resolver.artist(artistName);
    if (!artist) {
      fail(`歌手不存在：${artistName}（加 --create-artist 可自动创建）`);
      continue;
    }
    record.artistId = artist.id;
    if (artist.created) console.log(`  + 新建歌手 ${artist.name}（${artist.id}）`);

    if (options.lyricsOnly) {
      const song = await resolver.findSong(title, artist.id);
      if (!song) {
        fail('库中没有同名同歌手的歌曲（--lyrics-only 只给已有歌曲补歌词）');
        continue;
      }
      record.songId = song.id;
      if (!lyricInfo) {
        record.status = 'skipped';
        record.reason = '没有歌词（同目录无 .lrc，音频也没有内嵌歌词）';
        skipped += 1;
        results.push(record);
        console.log(`  ⊘ ${label}：歌曲 ${song.id} 存在，但没有歌词`);
        continue;
      }
      try {
        await resolver.saveLyric(song.id, lyricInfo.lyric);
      } catch (err) {
        fail(err.message);
        continue;
      }
      record.status = 'lyric-updated';
      lyricUpdated += 1;
      results.push(record);
      console.log(`  ♪ ${label}：歌词 ${lyricInfo.lyric.length} 字（${lyricInfo.source}）→ 歌曲 ${song.id}`);
      continue;
    }

    const albumId = await resolver.album(albumName, artist.id);
    if (albumId) record.albumId = albumId;

    if (await resolver.songExists(title, artist.id)) {
      record.status = 'skipped';
      record.reason = lyricInfo
        ? '库中已有同名同歌手的歌曲（加 --lyrics-only 可只补歌词）'
        : '库中已有同名同歌手的歌曲（--no-skip-existing 可强制导入）';
      skipped += 1;
      results.push(record);
      console.log(`  ⊘ ${label}：已存在，跳过${lyricInfo ? '（可用 --lyrics-only 补歌词）' : ''}`);
      continue;
    }

    let url;
    try {
      url = await resolver.upload(entry.abs, entry.ext, buf);
    } catch (err) {
      fail(err.message);
      continue;
    }
    record.audioUrl = url;

    let rowLyric = lyricInfo ? lyricInfo.lyric : null;
    if (rowLyric && rowLyric.length > 200000) {
      record.lyricSkipped = '歌词超过 200000 字，未写入';
      console.warn(`  ! ${label}：歌词 ${rowLyric.length} 字，超过后端上限 200000，跳过歌词`);
      rowLyric = null;
    }
    rows.push(buildRow({ ...meta, title, lyric: rowLyric }, artist.id, albumId, url, options));
    rowFiles.push({ record, label });
    record.status = 'pending';
    results.push(record);
    console.log(`  ↑ ${label}：上传完成 ${url}`
      + `${lyricInfo ? `，带歌词 ${lyricInfo.lyric.length} 字（${lyricInfo.source}）` : ''}`);
  }

  // 导入：每 200 行一批，失败行按序号回填
  let imported = 0;
  for (let offset = 0; offset < rows.length; offset += IMPORT_BATCH) {
    const chunk = rows.slice(offset, offset + IMPORT_BATCH);
    const chunkFiles = rowFiles.slice(offset, offset + IMPORT_BATCH);
    try {
      const result = await resolver.importBatch(chunk);
      const errors = new Map();
      for (const e of result.errors || []) errors.set(e.row, e.message);
      for (let j = 0; j < chunkFiles.length; j += 1) {
        const { record } = chunkFiles[j];
        const message = errors.get(j + 1);
        if (message) {
          record.status = 'failed';
          record.reason = message;
          failed += 1;
        } else {
          record.status = 'imported';
          delete record.reason;
          imported += 1;
        }
      }
      console.log(`  导入批次 ${offset / IMPORT_BATCH + 1}：成功 ${result.success} / 失败 ${result.failed}`);
    } catch (err) {
      for (const { record } of chunkFiles) {
        record.status = 'failed';
        record.reason = err.message;
        failed += 1;
      }
      console.log(`  导入批次 ${offset / IMPORT_BATCH + 1}：整批失败 ${err.message}`);
    }
  }

  const reportPath = options.report
    ? resolve(options.report)
    : join(PROJECT_ROOT, '.tmp', `import-report-${stamp()}.json`);
  const report = {
    startedAt: new Date().toISOString(),
    root,
    recursive: options.recursive,
    publish: options.publish,
    lyricsOnly: options.lyricsOnly,
    dryRun: options.dryRun,
    total: picked.length,
    imported,
    lyricUpdated,
    skipped,
    failed,
    files: results,
  };
  try {
    mkdirSync(dirname(reportPath), { recursive: true });
    writeFileSync(reportPath, JSON.stringify(report, null, 2), 'utf8');
  } catch (err) {
    console.warn(`  报告写入失败：${err.message}`);
  }

  const byReason = new Map();
  for (const r of results) {
    if (r.status !== 'failed') continue;
    byReason.set(r.reason, (byReason.get(r.reason) || 0) + 1);
  }

  console.log('');
  console.log('================ 汇总 ================');
  if (options.dryRun) {
    console.log(`  解析成功 ${results.filter((r) => r.status === 'dry-run').length} / 失败 ${failed}`);
    console.log(`  其中带歌词 ${results.filter((r) => r.lyricChars > 0).length} 个`);
  } else if (options.lyricsOnly) {
    console.log(`  歌词更新 ${lyricUpdated} / 跳过 ${skipped} / 失败 ${failed}（共 ${picked.length} 个）`);
  } else {
    console.log(`  导入成功 ${imported} / 跳过 ${skipped} / 失败 ${failed}（共 ${picked.length} 个）`);
    if (options.publish && imported > 0) {
      console.log(`  已按 --publish 上架，落库即进 ES 索引（如有）`);
    }
  }
  if (byReason.size > 0) {
    console.log('  失败原因：');
    for (const [reason, count] of byReason) console.log(`    ×${count} ${reason}`);
  }
  console.log(`  报告：${reportPath}`);

  return options.dryRun ? (failed > 0 ? 1 : 0) : (failed > 0 ? 1 : 0);
}

// ---------------------------------------------------------------------------
// 自测：用合成文件验证三种解析器（不联网）
// ---------------------------------------------------------------------------

function buildWavFixture() {
  const sampleRate = 8000;
  const channels = 1;
  const bits = 16;
  const byteRate = sampleRate * channels * (bits / 8);
  const dataSize = byteRate * 3; // 3 秒
  const infoItems = [
    ['INAM', 'WAV 标题'], ['IART', 'WAV 歌手'], ['IPRD', 'WAV 专辑'], ['IGNR', 'Rock'],
    ['ILYR', 'WAV 歌词'],
  ];
  const infoBufs = [];
  for (const [id, value] of infoItems) {
    const text = Buffer.from(`${value}\0`, 'utf8');
    const head = Buffer.alloc(8);
    head.write(id, 0, 'latin1');
    head.writeUInt32LE(text.length, 4);
    infoBufs.push(head, text, text.length % 2 ? Buffer.alloc(1) : Buffer.alloc(0));
  }
  const infoBody = Buffer.concat([Buffer.from('INFO', 'latin1'), ...infoBufs]);
  const listHead = Buffer.alloc(8);
  listHead.write('LIST', 0, 'latin1');
  listHead.writeUInt32LE(infoBody.length, 4);

  const fmt = Buffer.alloc(24);
  fmt.write('fmt ', 0, 'latin1');
  fmt.writeUInt32LE(16, 4);
  fmt.writeUInt16LE(1, 8);
  fmt.writeUInt16LE(channels, 10);
  fmt.writeUInt32LE(sampleRate, 12);
  fmt.writeUInt32LE(byteRate, 16);
  fmt.writeUInt16LE(channels * (bits / 8), 20);
  fmt.writeUInt16LE(bits, 22);

  const dataHead = Buffer.alloc(8);
  dataHead.write('data', 0, 'latin1');
  dataHead.writeUInt32LE(dataSize, 4);

  const body = Buffer.concat([Buffer.from('WAVE', 'latin1'), fmt, listHead, infoBody, dataHead, Buffer.alloc(dataSize)]);
  const riff = Buffer.alloc(8);
  riff.write('RIFF', 0, 'latin1');
  riff.writeUInt32LE(body.length, 4);
  return Buffer.concat([riff, body]);
}

function buildFlacFixture() {
  const sampleRate = 44100;
  const totalSamples = 44100 * 7; // 7 秒
  const streamInfo = Buffer.alloc(34);
  streamInfo.writeUInt16BE(4096, 0);
  streamInfo.writeUInt16BE(4096, 2);
  streamInfo.write('000000', 4, 'latin1');
  streamInfo.write('000000', 7, 'latin1');
  streamInfo
    .fill(0, 10, 18)
    ;
  // sampleRate(20) | channels-1(3) | bits-1(5) | totalSamples(36)
  const b10 = (sampleRate >> 12) & 0xff;
  const b11 = (sampleRate >> 4) & 0xff;
  const b12 = ((sampleRate & 0x0f) << 4) | ((1 - 1) << 1) | (((16 - 1) >> 4) & 0x01);
  const b13 = (((16 - 1) & 0x0f) << 4) | Math.floor(totalSamples / 2 ** 32);
  streamInfo[10] = b10;
  streamInfo[11] = b11;
  streamInfo[12] = b12;
  streamInfo[13] = b13;
  streamInfo.writeUInt32BE(totalSamples >>> 0, 14);

  const vendor = Buffer.from('newmusic-test', 'utf8');
  const comments = [
    'TITLE=FLAC 标题',
    'ARTIST=FLAC 歌手',
    'ALBUM=FLAC 专辑',
    'TRACKNUMBER=3',
    'GENRE=Jazz',
    'DATE=2004-06-04',
    'LYRICS=[00:02.00]FLAC 歌词',
  ].map((s) => {
    const value = Buffer.from(s, 'utf8');
    const head = Buffer.alloc(4);
    head.writeUInt32LE(value.length, 0);
    return Buffer.concat([head, value]);
  });
  const vendorHead = Buffer.alloc(4);
  vendorHead.writeUInt32LE(vendor.length, 0);
  const count = Buffer.alloc(4);
  count.writeUInt32LE(comments.length, 0);
  const vorbis = Buffer.concat([vendorHead, vendor, count, ...comments]);

  const header = (type, last, length) => {
    const head = Buffer.alloc(4);
    head[0] = (last ? 0x80 : 0) | type;
    head[1] = (length >> 16) & 0xff;
    head[2] = (length >> 8) & 0xff;
    head[3] = length & 0xff;
    return head;
  };

  return Buffer.concat([
    Buffer.from('fLaC', 'latin1'),
    header(0, false, streamInfo.length), streamInfo,
    header(4, true, vorbis.length), vorbis,
    Buffer.alloc(256), // 假的音频帧：解析器不读帧
  ]);
}

function buildId3Fixture() {
  const textFrame = (id, text) => {
    const payload = Buffer.concat([Buffer.from([3]), Buffer.from(text, 'utf8')]);
    const head = Buffer.alloc(10);
    head.write(id, 0, 'latin1');
    head.writeUInt32BE(payload.length, 4);
    return Buffer.concat([head, payload]);
  };
  const usltFrame = (text) => {
    // 编码(1) 语言(3) 描述(NUL) 歌词正文
    const payload = Buffer.concat([
      Buffer.from([3]),
      Buffer.from('chi', 'latin1'),
      Buffer.from([0]),
      Buffer.from(text, 'utf8'),
    ]);
    const head = Buffer.alloc(10);
    head.write('USLT', 0, 'latin1');
    head.writeUInt32BE(payload.length, 4);
    return Buffer.concat([head, payload]);
  };
  const txxxFrame = (desc, text) => {
    const payload = Buffer.concat([
      Buffer.from([3]),
      Buffer.from(`${desc}\0`, 'utf8'),
      Buffer.from(text, 'utf8'),
    ]);
    const head = Buffer.alloc(10);
    head.write('TXXX', 0, 'latin1');
    head.writeUInt32BE(payload.length, 4);
    return Buffer.concat([head, payload]);
  };
  const frames = Buffer.concat([
    textFrame('TIT2', 'MP3 标题'),
    textFrame('TPE1', 'MP3 歌手'),
    textFrame('TALB', 'MP3 专辑'),
    textFrame('TRCK', '7'),
    textFrame('TCON', 'Rock'),
    textFrame('TDRC', '2013-03-15'),
    usltFrame('[00:01.00]MP3 歌词\n[00:03.00]第二行'),
    txxxFrame('LYRICS', 'TXXX 歌词'),
  ]);
  const size = frames.length;
  const head = Buffer.alloc(10);
  head.write('ID3', 0, 'latin1');
  head[3] = 3;
  head[4] = 0;
  head[5] = 0;
  head[6] = (size >> 21) & 0x7f;
  head[7] = (size >> 14) & 0x7f;
  head[8] = (size >> 7) & 0x7f;
  head[9] = size & 0x7f;

  // 一帧 MPEG1 Layer III / 128kbps / 44100Hz / 立体声 + Xing 帧数
  const frameLen = Math.floor((144 * 128000) / 44100); // 417
  const frame = Buffer.alloc(frameLen);
  frame[0] = 0xff;
  frame[1] = 0xfb;
  frame[2] = 0x90;
  frame[3] = 0x00;
  const xing = 4 + 32; // MPEG1 立体声边信息 32 字节
  frame.write('Xing', xing, 'latin1');
  frame.writeUInt32BE(0x01, xing + 4);
  frame.writeUInt32BE(100, xing + 8); // 100 帧 ≈ 2.61 秒

  return Buffer.concat([head, frames, frame, Buffer.alloc(frameLen * 9)]);
}

function selfTest() {
  const checks = [];
  const check = (name, cond, extra) => {
    checks.push({ name, ok: Boolean(cond), extra });
    console.log(`  ${cond ? '✓' : '✗'} ${name}${cond || extra === undefined ? '' : ` → ${JSON.stringify(extra)}`}`);
  };

  console.log('WAV');
  const wav = parseWav(buildWavFixture());
  check('标题（LIST/INFO INAM）', wav && wav.title === 'WAV 标题', wav && wav.title);
  check('歌手（IART）', wav && wav.artist === 'WAV 歌手', wav && wav.artist);
  check('专辑（IPRD）', wav && wav.album === 'WAV 专辑', wav && wav.album);
  check('时长 3 秒（data/byteRate）', wav && wav.duration === 3, wav && wav.duration);
  check('采样率', wav && wav.sampleRate === 8000, wav && wav.sampleRate);
  check('内嵌歌词（LIST/INFO ILYR）', wav && wav.lyric === 'WAV 歌词', wav && wav.lyric);

  console.log('FLAC');
  const flac = parseFlac(buildFlacFixture());
  check('标题（VORBIS TITLE）', flac && flac.title === 'FLAC 标题', flac && flac.title);
  check('歌手（ARTIST）', flac && flac.artist === 'FLAC 歌手', flac && flac.artist);
  check('专辑（ALBUM）', flac && flac.album === 'FLAC 专辑', flac && flac.album);
  check('曲序（TRACKNUMBER）', flac && flac.track === 3, flac && flac.track);
  check('流派（GENRE）', flac && flac.genre === 'Jazz', flac && flac.genre);
  check('年份（DATE）', flac && flac.year === 2004, flac && flac.year);
  check('时长 7 秒（STREAMINFO）', flac && flac.duration === 7, flac && flac.duration);
  check('歌词（VORBIS LYRICS）', flac && flac.lyric === '[00:02.00]FLAC 歌词', flac && flac.lyric);

  console.log('MP3');
  const mp3 = parseMp3(buildId3Fixture());
  check('标题（ID3v2 TIT2）', mp3 && mp3.title === 'MP3 标题', mp3 && mp3.title);
  check('歌手（TPE1）', mp3 && mp3.artist === 'MP3 歌手', mp3 && mp3.artist);
  check('专辑（TALB）', mp3 && mp3.album === 'MP3 专辑', mp3 && mp3.album);
  check('曲序（TRCK）', mp3 && mp3.track === 7, mp3 && mp3.track);
  check('流派（TCON）', mp3 && mp3.genre === 'Rock', mp3 && mp3.genre);
  check('年份（TDRC）', mp3 && mp3.year === 2013, mp3 && mp3.year);
  check('时长约 2.6 秒（Xing 帧数）', mp3 && mp3.duration >= 2 && mp3.duration <= 3, mp3 && mp3.duration);
  check('歌词（USLT，保留换行）', mp3 && mp3.lyric === '[00:01.00]MP3 歌词\n[00:03.00]第二行', mp3 && mp3.lyric);
  check('歌词来源标记', mp3 && mp3.lyricSource === 'id3-USLT', mp3 && mp3.lyricSource);

  console.log('歌词文件解码');
  check('UTF-8 .lrc', decodeLyricBuffer(Buffer.from('[00:01.00]晴天', 'utf8')) === '[00:01.00]晴天');
  check('UTF-8 BOM .lrc',
    decodeLyricBuffer(Buffer.concat([Buffer.from([0xef, 0xbb, 0xbf]), Buffer.from('晴天', 'utf8')])) === '晴天');
  check('GBK .lrc（0xC7E7 0xCCEC → 晴天）',
    decodeLyricBuffer(Buffer.from([0xc7, 0xe7, 0xcc, 0xec])) === '晴天',
    decodeLyricBuffer(Buffer.from([0xc7, 0xe7, 0xcc, 0xec])));
  check('LRC 时间轴 → 类型 1', lyricContentTypeOf('[00:01.00]晴天') === 1);
  check('纯文本 → 类型 3', lyricContentTypeOf('晴天\n多云') === 3);

  console.log('文件名兜底');
  const named = fromFilename('/x/01. 周杰伦 - 晴天.wav');
  check('曲序前缀', named.track === 1, named.track);
  check('歌手拆分', named.artist === '周杰伦', named.artist);
  check('歌名拆分', named.title === '晴天', named.title);
  const plain = fromFilename('/x/夜曲.mp3');
  check('无分隔符时整体作为歌名', plain.title === '夜曲' && plain.artist === null, plain);

  console.log('年代推导');
  check('2004 → 00s', eraOf(2004) === '00s', eraOf(2004));
  check('1996 → 90s', eraOf(1996) === '90s', eraOf(1996));
  check('1975 → null', eraOf(1975) === null, eraOf(1975));

  const failedChecks = checks.filter((c) => !c.ok);
  console.log('');
  console.log(`自测：通过 ${checks.length - failedChecks.length} / 失败 ${failedChecks.length}`);
  return failedChecks.length === 0 ? 0 : 1;
}

// ---------------------------------------------------------------------------
// 入口
// ---------------------------------------------------------------------------

async function main() {
  let options;
  try {
    options = parseArgs(process.argv.slice(2));
  } catch (err) {
    console.error(err.message);
    printHelpAndExit(2);
  }
  if (options.help) printHelpAndExit(0);
  if (options.selfTest) process.exit(selfTest());

  if (!options.dir) {
    console.error('缺少目录参数。用法：node scripts/import-local-songs.mjs <文件夹> [选项]');
    printHelpAndExit(2);
  }

  try {
    const code = await run(options);
    process.exitCode = code;
  } catch (err) {
    console.error(`失败：${err.message}`);
    process.exitCode = 1;
  }
}

main();
