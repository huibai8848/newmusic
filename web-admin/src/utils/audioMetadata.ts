/**
 * 浏览器端音频标签解析（零依赖）。
 *
 * 支持 mp3（ID3v2.2/2.3/2.4 + ID3v1）、flac（STREAMINFO + VORBIS_COMMENT）、
 * wav（fmt/data + LIST/INFO，以及内嵌 id3）。解析口径与
 * `scripts/import-local-songs.mjs` 保持一致，方便「后台新增歌曲」和「本地批量导入」
 * 得到同样的歌名/歌手/专辑/时长/流派/日期/歌词。
 *
 * 只读取 File 内容，不上传；解析失败返回 null，不影响音频上传本身。
 */

export interface AudioMetadata {
  format: 'mp3' | 'flac' | 'wav';
  title?: string;
  artist?: string;
  album?: string;
  genre?: string;
  language?: string;
  /** 仅当标签里是完整年月日时返回 yyyy-MM-dd，避免编造月日 */
  releaseDate?: string;
  /** 秒 */
  duration?: number;
  /** 内嵌歌词（ID3 USLT / TXXX、FLAC LYRICS、WAV ILYR） */
  lyric?: string;
}

type TagMap = Record<string, string>;

const UTF8_STRICT = new TextDecoder('utf-8', { fatal: true });
const UTF8 = new TextDecoder('utf-8');
const UTF16LE = new TextDecoder('utf-16le');
const UTF16BE = new TextDecoder('utf-16be');
let gb18030: TextDecoder | null = null;

function decodeGb18030(bytes: Uint8Array): string {
  try {
    gb18030 ??= new TextDecoder('gb18030');
    return gb18030.decode(bytes);
  } catch {
    return latin1(bytes, 0, bytes.length);
  }
}

function latin1(bytes: Uint8Array, start: number, end: number): string {
  let out = '';
  const stop = Math.min(end, bytes.length);
  for (let i = start; i < stop; i += 1) {
    out += String.fromCharCode(bytes[i]);
  }
  return out;
}

function u32le(bytes: Uint8Array, offset: number): number {
  return (
    (bytes[offset] |
      (bytes[offset + 1] << 8) |
      (bytes[offset + 2] << 16) |
      (bytes[offset + 3] << 24)) >>>
    0
  );
}

function u32be(bytes: Uint8Array, offset: number): number {
  return (
    ((bytes[offset] << 24) |
      (bytes[offset + 1] << 16) |
      (bytes[offset + 2] << 8) |
      bytes[offset + 3]) >>>
    0
  );
}

function readSynchsafe(bytes: Uint8Array, offset: number): number {
  return (
    ((bytes[offset] & 0x7f) << 21) |
    ((bytes[offset + 1] & 0x7f) << 14) |
    ((bytes[offset + 2] & 0x7f) << 7) |
    (bytes[offset + 3] & 0x7f)
  );
}

/** ID3 编码字节 → 字符串（enc=0 的中文常见 GBK，先严格 UTF-8 再 GB18030）。 */
function decodeId3Bytes(encoding: number, data: Uint8Array): string {
  if (encoding === 1) {
    if (data.length >= 2 && data[0] === 0xff && data[1] === 0xfe) {
      return UTF16LE.decode(data.subarray(2));
    }
    if (data.length >= 2 && data[0] === 0xfe && data[1] === 0xff) {
      return UTF16BE.decode(data.subarray(2));
    }
    return UTF16LE.decode(data);
  }
  if (encoding === 2) {
    return UTF16BE.decode(data);
  }
  if (encoding === 3) {
    return UTF8.decode(data);
  }
  const raw = latin1(data, 0, data.length);
  if (!/[\u0080-ÿ]/.test(raw)) {
    return raw;
  }
  try {
    return UTF8_STRICT.decode(data);
  } catch {
    return decodeGb18030(data);
  }
}

/** 找 UTF-16 的双字节 0 结束符，返回其后位置；找不到返回 -1。 */
function utf16Terminator(bytes: Uint8Array, from = 0): number {
  for (let i = from; i + 1 < bytes.length; i += 2) {
    if (bytes[i] === 0 && bytes[i + 1] === 0) {
      return i + 2;
    }
  }
  return -1;
}

function decodeId3Text(body: Uint8Array): string {
  if (body.length === 0) {
    return '';
  }
  return decodeId3Bytes(body[0], body.subarray(1))
    .replace(/\u0000+$/u, '')
    .replace(/\u0000/gu, ' / ')
    .trim();
}

/** USLT：编码(1) 语言(3) 描述(NUL 结尾) 歌词正文。 */
function decodeUslt(body: Uint8Array): string {
  if (body.length < 4) {
    return '';
  }
  const encoding = body[0];
  const wide = encoding === 1 || encoding === 2;
  const rest = body.subarray(4);
  const cut = wide ? utf16Terminator(rest) : rest.indexOf(0) + 1;
  return decodeId3Bytes(encoding, rest.subarray(Math.max(cut, 0)))
    .replace(/\u0000+$/u, '')
    .trim();
}

/** TXXX：编码(1) 描述(NUL 结尾) 值。 */
function decodeTxxx(body: Uint8Array): { desc: string; value: string } {
  if (body.length < 2) {
    return { desc: '', value: '' };
  }
  const encoding = body[0];
  const wide = encoding === 1 || encoding === 2;
  const rest = body.subarray(1);
  let cut: number;
  let skip: number;
  if (wide) {
    cut = utf16Terminator(rest);
    skip = 2;
    if (cut >= 0) {
      cut -= skip;
    }
  } else {
    cut = rest.indexOf(0);
    skip = 1;
  }
  if (cut < 0) {
    return { desc: '', value: '' };
  }
  return {
    desc: decodeId3Bytes(encoding, rest.subarray(0, cut)).trim(),
    value: decodeId3Bytes(encoding, rest.subarray(cut + skip))
      .replace(/\u0000+$/u, '')
      .trim(),
  };
}

function parseId3v2(
  bytes: Uint8Array,
  start = 0,
): { tags: TagMap; audioStart: number } | null {
  if (bytes.length < start + 10 || latin1(bytes, start, start + 3) !== 'ID3') {
    return null;
  }
  const major = bytes[start + 3];
  if (major < 2 || major > 4) {
    return null;
  }
  const flags = bytes[start + 5];
  const size = readSynchsafe(bytes, start + 6);
  const audioStart = start + 10 + size;
  const end = Math.min(audioStart, bytes.length);
  const tags: TagMap = {};

  let p = start + 10;
  if (flags & 0x40 && p + 4 <= end) {
    // 扩展头：v2.3 长度不含自身 4 字节，v2.4 含
    const extSize = major === 4 ? readSynchsafe(bytes, p) : u32be(bytes, p);
    p += major === 4 ? extSize : extSize + 4;
  }

  while (p + 6 <= end) {
    let id: string;
    let frameSize: number;
    let headerSize: number;
    if (major === 2) {
      id = latin1(bytes, p, p + 3);
      frameSize = (bytes[p + 3] << 16) | (bytes[p + 4] << 8) | bytes[p + 5];
      headerSize = 6;
    } else {
      id = latin1(bytes, p, p + 4);
      frameSize = major === 4 ? readSynchsafe(bytes, p + 4) : u32be(bytes, p + 4);
      headerSize = 10;
    }
    if (!/^[A-Z0-9]{3,4}$/.test(id) || frameSize <= 0) {
      break;
    }
    if (p + headerSize + frameSize > end) {
      break;
    }
    const body = bytes.subarray(p + headerSize, p + headerSize + frameSize);
    if (id[0] === 'T' && id !== 'TXXX') {
      const text = decodeId3Text(body);
      if (text && !tags[id]) {
        tags[id] = text;
      }
    } else if (id === 'USLT') {
      const text = decodeUslt(body);
      if (text && !tags.USLT) {
        tags.USLT = text;
      }
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

function parseId3v1(bytes: Uint8Array): TagMap | null {
  if (bytes.length < 128) {
    return null;
  }
  const offset = bytes.length - 128;
  if (latin1(bytes, offset, offset + 3) !== 'TAG') {
    return null;
  }
  const read = (from: number, len: number): string =>
    latin1(bytes, offset + from, offset + from + len).replace(/\u0000.*$/u, '').trim();
  return {
    title: read(3, 30),
    artist: read(33, 30),
    album: read(63, 30),
    year: read(93, 4),
    genre: read(127, 1),
  };
}

const MPEG_BITRATES: Record<string, number[]> = {
  '1-1': [0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448],
  '1-2': [0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384],
  '1-3': [0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320],
  '2-1': [0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256],
  '2-2': [0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160],
  '2-3': [0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160],
};
const MPEG_SAMPLE_RATES: Record<string, number[]> = {
  '1': [44100, 48000, 32000],
  '2': [22050, 24000, 16000],
  '2.5': [11025, 12000, 8000],
};

function readMpegInfo(bytes: Uint8Array, start: number): { duration: number } | null {
  const limit = Math.min(bytes.length - 4, start + 64 * 1024);
  for (let p = start; p < limit; p += 1) {
    if (bytes[p] !== 0xff || (bytes[p + 1] & 0xe0) !== 0xe0) {
      continue;
    }
    const versionBits = (bytes[p + 1] >> 3) & 0x03;
    const layerBits = (bytes[p + 1] >> 1) & 0x03;
    if (versionBits === 1 || layerBits === 0) {
      continue;
    }
    const version = versionBits === 3 ? '1' : versionBits === 2 ? '2' : '2.5';
    const layer = 4 - layerBits;
    const bitrateIndex = (bytes[p + 2] >> 4) & 0x0f;
    const sampleRateIndex = (bytes[p + 2] >> 2) & 0x03;
    if (bitrateIndex === 0 || bitrateIndex === 15 || sampleRateIndex === 3) {
      continue;
    }
    const rateKey = version === '1' ? '1' : '2';
    const bitrate = MPEG_BITRATES[`${rateKey}-${layer}`]?.[bitrateIndex];
    const sampleRate = MPEG_SAMPLE_RATES[version]?.[sampleRateIndex];
    if (!bitrate || !sampleRate) {
      continue;
    }

    const channelMode = (bytes[p + 3] >> 6) & 0x03;
    const mono = channelMode === 3;
    const samplesPerFrame = layer === 1 ? 384 : layer === 2 ? 1152 : version === '1' ? 1152 : 576;
    const sideInfo = version === '1' ? (mono ? 17 : 32) : mono ? 9 : 17;
    const xingOffset = p + 4 + sideInfo;
    let frames = 0;
    if (xingOffset + 12 <= bytes.length) {
      const mark = latin1(bytes, xingOffset, xingOffset + 4);
      if (mark === 'Xing' || mark === 'Info') {
        const flags = u32be(bytes, xingOffset + 4);
        if (flags & 0x01) {
          frames = u32be(bytes, xingOffset + 8);
        }
      }
    }

    const hasId3v1 = latin1(bytes, bytes.length - 128, bytes.length - 125) === 'TAG';
    const audioBytes = Math.max(0, bytes.length - p - (hasId3v1 ? 128 : 0));
    const duration =
      frames > 0 ? (frames * samplesPerFrame) / sampleRate : (audioBytes * 8) / (bitrate * 1000);
    return { duration: Math.round(duration) };
  }
  return null;
}

function normalizeDate(value?: string | null): string | undefined {
  if (!value) {
    return undefined;
  }
  const match = String(value).match(/((?:19|20)\d{2})[-/.](\d{1,2})[-/.](\d{1,2})/);
  if (!match) {
    return undefined;
  }
  const month = match[2].padStart(2, '0');
  const day = match[3].padStart(2, '0');
  return `${match[1]}-${month}-${day}`;
}

function cleanGenre(value?: string | null): string | undefined {
  if (!value) {
    return undefined;
  }
  // ID3v1 流派是索引数字；TCON 可能是 "(17)Rock"
  const match = String(value).match(/^\((\d+)\)(.*)$/u);
  if (match) {
    return match[2].trim() || undefined;
  }
  return String(value).trim() || undefined;
}

function pick(tags: TagMap, ...keys: string[]): string | null {
  for (const key of keys) {
    const value = tags[key];
    if (value !== undefined && value !== null && String(value).trim() !== '') {
      return String(value).trim();
    }
  }
  return null;
}

function parseMp3(bytes: Uint8Array): AudioMetadata {
  const id3 = parseId3v2(bytes, 0);
  const tags: TagMap = id3 ? { ...id3.tags } : {};
  const v1 = parseId3v1(bytes);
  if (v1) {
    if (!pick(tags, 'TIT2', 'TT2') && v1.title) tags.v1Title = v1.title;
    if (!pick(tags, 'TPE1', 'TP1', 'TPE2', 'TP2') && v1.artist) tags.v1Artist = v1.artist;
    if (!pick(tags, 'TALB', 'TAL') && v1.album) tags.v1Album = v1.album;
    if (!pick(tags, 'TYER', 'TYE', 'TDRC') && v1.year) tags.v1Year = v1.year;
    if (!pick(tags, 'TCON', 'TCO') && v1.genre) tags.v1Genre = v1.genre;
  }
  const info = readMpegInfo(bytes, id3 ? id3.audioStart : 0);
  let duration = 0;
  const tlen = Number(pick(tags, 'TLEN'));
  if (Number.isFinite(tlen) && tlen > 0) {
    duration = Math.round(tlen / 1000);
  }
  if (!duration && info) {
    duration = info.duration;
  }

  const date = pick(tags, 'TDRC', 'TYER', 'TYE', 'v1Year');
  return {
    format: 'mp3',
    title: pick(tags, 'TIT2', 'TT2', 'v1Title') ?? undefined,
    artist: pick(tags, 'TPE1', 'TP1', 'TPE2', 'TP2', 'v1Artist') ?? undefined,
    album: pick(tags, 'TALB', 'TAL', 'v1Album') ?? undefined,
    genre: cleanGenre(pick(tags, 'TCON', 'TCO', 'v1Genre')),
    language: pick(tags, 'TLAN') ?? undefined,
    releaseDate: normalizeDate(date),
    duration: duration || undefined,
    lyric:
      pick(
        tags,
        'USLT',
        'TXXX:LYRICS',
        'TXXX:UNSYNCEDLYRICS',
        'TXXX:UNSYNCED LYRICS',
        'TXXX:LYRIC',
      ) ?? undefined,
  };
}

function parseFlac(bytes: Uint8Array): AudioMetadata | null {
  if (bytes.length < 8 || latin1(bytes, 0, 4) !== 'fLaC') {
    return null;
  }
  const tags: TagMap = {};
  let sampleRate = 0;
  let totalSamples = 0;
  let p = 4;
  while (p + 4 <= bytes.length) {
    const header = bytes[p];
    const last = (header & 0x80) !== 0;
    const type = header & 0x7f;
    const len = (bytes[p + 1] << 16) | (bytes[p + 2] << 8) | bytes[p + 3];
    const body = bytes.subarray(p + 4, Math.min(p + 4 + len, bytes.length));

    if (type === 0 && body.length >= 18) {
      sampleRate = (body[10] << 12) | (body[11] << 4) | (body[12] >> 4);
      totalSamples = (body[13] & 0x0f) * 2 ** 32 + u32be(body, 14);
    } else if (type === 4 && body.length >= 8) {
      let q = 0;
      const vendorLen = u32le(body, q);
      q += 4 + vendorLen;
      if (q + 4 <= body.length) {
        const count = u32le(body, q);
        q += 4;
        for (let i = 0; i < count && q + 4 <= body.length; i += 1) {
          const itemLen = u32le(body, q);
          q += 4;
          if (q + itemLen > body.length) {
            break;
          }
          const pair = UTF8.decode(body.subarray(q, q + itemLen));
          q += itemLen;
          const eq = pair.indexOf('=');
          if (eq > 0) {
            const key = pair.slice(0, eq).toUpperCase();
            if (!tags[key]) {
              tags[key] = pair.slice(eq + 1);
            }
          }
        }
      }
    }
    p += 4 + len;
    if (last) {
      break;
    }
  }
  const duration = sampleRate > 0 ? Math.round(totalSamples / sampleRate) : 0;
  const date = pick(tags, 'DATE', 'YEAR');
  return {
    format: 'flac',
    title: pick(tags, 'TITLE') ?? undefined,
    artist: pick(tags, 'ARTIST', 'ALBUMARTIST') ?? undefined,
    album: pick(tags, 'ALBUM') ?? undefined,
    genre: cleanGenre(pick(tags, 'GENRE')),
    language: pick(tags, 'LANGUAGE') ?? undefined,
    releaseDate: normalizeDate(date),
    duration: duration || undefined,
    lyric:
      pick(tags, 'LYRICS', 'UNSYNCEDLYRICS', 'UNSYNCED LYRICS', 'LYRIC') ?? undefined,
  };
}

const WAV_INFO_KEYS: Record<string, string> = {
  INAM: 'title',
  IART: 'artist',
  IPRD: 'album',
  IALB: 'album',
  IGNR: 'genre',
  ICRD: 'date',
  ILNG: 'language',
  ILYR: 'lyric',
};

function parseWav(bytes: Uint8Array): AudioMetadata | null {
  if (
    bytes.length < 12 ||
    latin1(bytes, 0, 4) !== 'RIFF' ||
    latin1(bytes, 8, 12) !== 'WAVE'
  ) {
    return null;
  }
  const info: TagMap = {};
  let byteRate = 0;
  let dataSize = 0;
  let embedded: TagMap | null = null;
  let p = 12;
  while (p + 8 <= bytes.length) {
    const id = latin1(bytes, p, p + 4);
    const size = u32le(bytes, p + 4);
    const bodyStart = p + 8;
    const body = bytes.subarray(bodyStart, Math.min(bodyStart + size, bytes.length));

    if (id === 'fmt ' && body.length >= 16) {
      byteRate = u32le(body, 8);
    } else if (id === 'data') {
      dataSize = Math.min(size, bytes.length - bodyStart);
    } else if (id === 'LIST' && body.length >= 4 && latin1(body, 0, 4) === 'INFO') {
      let q = 4;
      while (q + 8 <= body.length) {
        const subId = latin1(body, q, q + 4).trim();
        const subLen = u32le(body, q + 4);
        const value = UTF8.decode(
          body.subarray(q + 8, Math.min(q + 8 + subLen, body.length)),
        )
          .replace(/\u0000+$/u, '')
          .trim();
        if (value && subId) {
          const key = WAV_INFO_KEYS[subId] ?? subId;
          if (!info[key]) {
            info[key] = value;
          }
        }
        q += 8 + subLen + (subLen % 2);
      }
    } else if (id.toLowerCase() === 'id3 ') {
      const id3 = parseId3v2(body, 0);
      if (id3) {
        embedded = id3.tags;
      }
    }
    p = bodyStart + size + (size % 2);
  }
  const duration = byteRate > 0 ? Math.round(dataSize / byteRate) : 0;
  const embeddedTitle = embedded ? pick(embedded, 'TIT2', 'TT2') : null;
  const embeddedArtist = embedded ? pick(embedded, 'TPE1', 'TP1') : null;
  const embeddedAlbum = embedded ? pick(embedded, 'TALB', 'TAL') : null;

  return {
    format: 'wav',
    title: embeddedTitle || info.title || undefined,
    artist: embeddedArtist || info.artist || undefined,
    album: embeddedAlbum || info.album || undefined,
    genre: cleanGenre(info.genre || (embedded ? pick(embedded, 'TCON') : null)),
    language: info.language || undefined,
    releaseDate: normalizeDate(info.date || (embedded ? pick(embedded, 'TDRC', 'TYER') : null)),
    duration: duration || undefined,
    lyric:
      info.lyric ||
      (embedded
        ? pick(embedded, 'USLT', 'TXXX:LYRICS', 'TXXX:UNSYNCEDLYRICS') ?? undefined
        : undefined),
  };
}

/** 供测试使用：直接解析字节数组。 */
export function parseAudioMetadataBytes(
  bytes: Uint8Array,
  extension: string,
): AudioMetadata | null {
  const ext = extension.toLowerCase();
  if (ext === '.mp3') {
    return parseMp3(bytes);
  }
  if (ext === '.flac') {
    return parseFlac(bytes);
  }
  if (ext === '.wav') {
    return parseWav(bytes);
  }
  return null;
}

/** 读取并解析音频文件；格式不支持或内容异常时返回 null。 */
export async function parseAudioMetadata(file: File): Promise<AudioMetadata | null> {
  const ext = file.name.slice(file.name.lastIndexOf('.')).toLowerCase();
  if (!['.mp3', '.wav', '.flac'].includes(ext)) {
    return null;
  }
  try {
    const bytes = new Uint8Array(await file.arrayBuffer());
    return parseAudioMetadataBytes(bytes, ext);
  } catch {
    return null;
  }
}

/** 把标签里的 ISO 639 语言码转成后台常用的中文语种；无法识别时原样返回。 */
export function normalizeLanguage(value?: string): string | undefined {
  if (!value) {
    return undefined;
  }
  const code = value.trim().toLowerCase();
  const map: Record<string, string> = {
    chi: '国语',
    zho: '国语',
    cmn: '国语',
    zh: '国语',
    yue: '粤语',
    eng: '英语',
    en: '英语',
    jpn: '日语',
    ja: '日语',
    kor: '韩语',
    ko: '韩语',
    fra: '法语',
    fre: '法语',
    fr: '法语',
    deu: '德语',
    ger: '德语',
    de: '德语',
    spa: '西班牙语',
    es: '西班牙语',
    rus: '俄语',
    ru: '俄语',
    por: '葡萄牙语',
    ita: '意大利语',
    it: '意大利语',
  };
  return map[code] ?? value.trim();
}
