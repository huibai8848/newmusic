#!/usr/bin/env node
/**
 * 模块 18 内容安全纯函数断言（Node 原生擦除 TS 类型，零依赖）。
 *
 * 覆盖前端最容易写错的显示边界：
 * - 剩余 5 分钟 / 0 分钟 / 负数；
 * - 禁用时段 `22:00:00`、`7:30` 与缺失值；
 * - 申诉状态文案与颜色（后端状态 0/1/2）；
 * - 7 天申诉窗口的边界（第 7 天可申诉、第 8 天不可）；
 * - AI 标识只认 1/true。
 */

import {
  aiLabelText,
  appealStatusColor,
  appealStatusLabel,
  canAppeal,
  formatMinorWindow,
  minorUsageLevel,
} from '../src/utils/safety.ts';

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
  }
}

// 每日时长
eq('剩余 10 分钟正常', minorUsageLevel(30, 40), 'normal');
eq('剩余 5 分钟提醒', minorUsageLevel(35, 40), 'warning');
eq('剩余 1 分钟提醒', minorUsageLevel(39, 40), 'warning');
eq('用满即用尽', minorUsageLevel(40, 40), 'exhausted');
eq('超出仍用尽', minorUsageLevel(41, 40), 'exhausted');
eq('上限为 0 视为用尽', minorUsageLevel(0, 0), 'exhausted');
eq('非数字按 0 处理', minorUsageLevel(Number.NaN, 40), 'normal');

// 禁用时段
eq('标准 HH:mm:ss', formatMinorWindow('22:00:00', '06:00:00'), '22:00 - 6:00');
eq('个位数小时', formatMinorWindow('7:30', '23:00'), '7:00 - 23:00');
eq('空值', formatMinorWindow('', ''), '--:00 - --:00');

// 申诉状态
eq('待处理文案', appealStatusLabel(0), '待处理');
eq('已通过文案', appealStatusLabel(1), '已通过');
eq('已驳回文案', appealStatusLabel(2), '已驳回');
eq('未知状态', appealStatusLabel(9), '状态 9');
eq('待处理颜色', appealStatusColor(0), 'orange');
eq('已通过颜色', appealStatusColor(1), 'green');
eq('已驳回颜色', appealStatusColor(2), 'red');
eq('未知颜色', appealStatusColor(9), 'default');

// 7 天窗口
const now = Date.UTC(2026, 9, 25, 12, 0, 0);
eq('当天可申诉', canAppeal(0, new Date(now - 60_000).toISOString(), now), true);
eq('第 7 天边界可申诉', canAppeal(0, new Date(now - 7 * 24 * 3600_000).toISOString(), now), true);
eq('第 8 天不可申诉', canAppeal(0, new Date(now - 8 * 24 * 3600_000).toISOString(), now), false);
eq('已处理不可申诉', canAppeal(1, new Date(now).toISOString(), now), false);
eq('非法时间不可申诉', canAppeal(0, 'not-a-date', now), false);

// AI 标识
eq('数字 1 展示', aiLabelText(1), 'AI 生成');
eq('布尔 true 展示', aiLabelText(true), 'AI 生成');
eq('数字 0 不展示', aiLabelText(0), null);
eq('undefined 不展示', aiLabelText(undefined), null);

console.log(`\n通过 ${passed}  失败 ${failed}`);
process.exit(failed === 0 ? 0 : 1);
