#!/usr/bin/env node
/**
 * LRC 解析器断言（模块 07 T705 / AC-16-108、AC-16-109、AC-16-110）。
 *
 * 不引测试框架：Node 22.6+ 起可以原生擦除 TS 类型，本脚本直接 import `src/utils/lrc.ts`，
 * 所以零依赖、零配置，`npm run test:lrc` 即可跑。
 *
 * 断言内容与 `sql/data.sql` 里种下的联调歌词一一对应（乱序、多时间标签、offset、越界行）。
 */

import { parseLrc, findCurrentLine, plainLyric } from '../src/utils/lrc.ts';

let failed = 0;
let passed = 0;

function eq(name, actual, expected) {
  const a = JSON.stringify(actual);
  const e = JSON.stringify(expected);
  if (a !== e) {
    failed += 1;
    console.log(`✗ ${name}\n    实际: ${a}\n    期望: ${e}`);
  } else {
    passed += 1;
    console.log(`✓ ${name}`);
  }
}

// ---------------------------------------------------------------------------
// 1. 元信息 / 乱序 / 多时间标签 / 越界行（对应 demo-06 的种子歌词）
// ---------------------------------------------------------------------------
const outOfOrder = [
  '[ti:演示歌词·光年之外]',
  '[ar:邓紫棋]',
  '[offset:0]',
  '[00:00.00]A',
  '[00:40.00]C',
  '[00:10.00]B',
  '[00:25.00]D',
  '[01:20.00][01:35.00][01:50.00]MULTI',
  '[99:00.00]OVER',
].join('\n');
const p = parseLrc(outOfOrder);

eq('解析元信息 title', p.meta.title, '演示歌词·光年之外');
eq('解析元信息 artist', p.meta.artist, '邓紫棋');
eq('乱序时间戳会按升序排好', p.lines.map((l) => l.time), [0, 10, 25, 40, 80, 95, 110, 5940]);
eq('多时间标签展开成多行', p.lines.filter((l) => l.text === 'MULTI').length, 3);
eq('含时间轴 timed=true', p.timed, true);

// ---------------------------------------------------------------------------
// 2. 二分查找定位当前行
// ---------------------------------------------------------------------------
eq('前奏返回 -1', findCurrentLine(p.lines, -1), -1);
eq('t=0 命中首行', findCurrentLine(p.lines, 0), 0);
eq('t=9.99 仍停在首行', findCurrentLine(p.lines, 9.99), 0);
eq('t=10 正好切换', findCurrentLine(p.lines, 10), 1);
eq('t=110 命中最后一个 MULTI', findCurrentLine(p.lines, 110), 6);
eq('t 远超时长命中末行', p.lines[findCurrentLine(p.lines, 100000)].text, 'OVER');
eq('空歌词返回 -1', findCurrentLine([], 10), -1);

// ---------------------------------------------------------------------------
// 3. offset：正文内 [offset:] + 传入的额外偏移（用户校准），正值表示歌词提前
// ---------------------------------------------------------------------------
const off = parseLrc('[offset:500]\n[00:10.00]X\n[00:20.00]Y');
eq('文本 offset=500 → 提前 0.5s', off.lines.map((l) => l.time), [9.5, 19.5]);
eq('rawTime 保留标签原值', off.lines.map((l) => l.rawTime), [10, 20]);
eq('额外偏移叠加在标签之上（原语行为）', parseLrc('[offset:500]\n[00:10.00]X', 500).lines[0].time, 9);
eq('负 offset 表示延后', parseLrc('[offset:-500]\n[00:10.00]X').lines[0].time, 10.5);
eq('offset 非法时按 0 处理', parseLrc('[offset:abc]\n[00:10.00]X').lines[0].time, 10);

// override 模式：正文标签被丢弃，传入值就是总偏移
eq('override 忽略正文标签', parseLrc('[offset:500]\n[00:10.00]X', 300, 'override').lines[0].time, 9.7);
eq('override 配合用户校准', parseLrc('[offset:500]\n[00:10.00]X', 300 - 100, 'override').lines[0].time, 9.8);
eq('override 不影响 meta.offsetMs（仍报标签原值）', parseLrc('[offset:500]\n[00:10.00]X', 300, 'override').meta.offsetMs, 500);

// 复刻 useLyric 的组合规则：t_lyric.offset_ms 与正文 [offset:] 是同一个槽位，取其一。
// 原实现把两者相加 —— 种子 demo-03 正文写 [offset:500]、列上也是 500（同一个 offset 记两遍），
// 于是按 1000ms 生效，该行从种子标注的 4.5s 偏到 4.0s。见 docs/系分/07-播放器与歌词.md §10 v1.3。
const compose = (content, entityOffsetMs, userOffsetMs) =>
  entityOffsetMs === 0
    ? parseLrc(content, userOffsetMs)
    : parseLrc(content, entityOffsetMs + userOffsetMs, 'override');
const demo03 = '[ti:x]\n[offset:500]\n[00:05.00]第 1 行';
eq('正文与列值同为 500 时不重复计（种子 demo-03 应落在 4.5s）', compose(demo03, 500, 0).lines[0].time, 4.5);
eq('用户校准仍叠加（BR-16-069 第二项）', compose(demo03, 500, -300).lines[0].time, 4.8);
eq('列值为 0 → 用正文标签', compose(demo03, 0, 0).lines[0].time, 4.5);
// 这条是"列值优先"存在的理由：样板 [offset:0] 不该让后台的校准失效
eq('正文样板 [offset:0] 不吞掉后台校准', compose('[offset:0]\n[00:05.00]X', 300, 0).lines[0].time, 4.7);
eq('列值覆盖文件自带 offset', compose('[offset:500]\n[00:05.00]X', 200, 0).lines[0].time, 4.8);

// ---------------------------------------------------------------------------
// 4. 毫秒的三种写法
// ---------------------------------------------------------------------------
eq('[00:12.5] 视为 12.5s 而非 12.005s', parseLrc('[00:12.5]x').lines[0].time, 12.5);
eq('[00:13:250] 冒号分隔毫秒', parseLrc('[00:13:250]x').lines[0].time, 13.25);
eq('[100:00.00] 三位分钟', parseLrc('[100:00.00]x').lines[0].time, 6000);

// ---------------------------------------------------------------------------
// 5. 逐字标签：剥离但标记（逐字渲染属 T718）
// ---------------------------------------------------------------------------
const word = parseLrc('[00:12.50][00:15.00]故事<00:12.80>的<00:13.10>小黄花');
eq('识别出逐字标签', word.hasWordLevel, true);
eq('逐字标签被剥离', word.lines.map((l) => l.text), ['故事的小黄花', '故事的小黄花']);

// ---------------------------------------------------------------------------
// 6. 兜底与脏数据
// ---------------------------------------------------------------------------
eq('null → 空结果', parseLrc(null).lines, []);
eq('空串 timed=false', parseLrc('').timed, false);
eq('纯文本 timed=false', parseLrc('第一行\n第二行').timed, false);
eq('纯文本保留全部行', parseLrc('第一行\n第二行').lines.map((l) => l.text), ['第一行', '第二行']);
eq('plainLyric 取纯文本', plainLyric('甲\n乙'), ['甲', '乙']);
eq('无时间轴的 [xxx] 行被丢弃', parseLrc('[这不是元信息]\n正文').lines.map((l) => l.text), ['正文']);
eq('只有时间标签没有文字的行被丢弃', parseLrc('[00:01.00]\n[00:02.00]有词').lines.length, 1);
eq('空行不影响解析', parseLrc('\n\n[00:01.00]A\n\n').lines.length, 1);

console.log(failed === 0 ? `\n全部通过（${passed} 项）` : `\n${failed} 项失败，${passed} 项通过`);
process.exit(failed === 0 ? 0 : 1);
