#!/usr/bin/env node
/**
 * 模块 19 降级状态纯函数断言（Node 原生擦除 TS 类型，零依赖）。
 *
 * 重点覆盖 C 端最容易出错的边界：
 * - 未降级时不出公告；
 * - 状态接口失败（undefined）时导航不能被判空；
 * - 只隐藏依赖已关闭功能的入口，未知路径保留；
 * - 后端没下发 banner 时用等级与功能名兜底；
 * - 未知功能键回退显示原键，不吞信息。
 */

import {
  degradeBannerText,
  degradeFeatureLabel,
  isFeatureDisabled,
  visibleNavPaths,
} from '../src/utils/degrade.ts';

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

const NORMAL = {
  level: 0,
  levelName: '正常',
  degraded: false,
  disabledFeatures: [],
  banner: null,
  updatedAt: 0,
};

const paths = ['/', '/recommend', '/feed', '/rooms', '/search', '/user'];

// 正常态
eq('正常态无公告', degradeBannerText(NORMAL), null);
eq('正常态导航全保留', visibleNavPaths(paths, NORMAL), paths);
eq('无状态时导航全保留', visibleNavPaths(paths, undefined), paths);
eq('无状态时不判定关闭', isFeatureDisabled(undefined, 'feature.recommend'), false);

// 降级态：推荐流关闭
const degraded = {
  level: 2,
  levelName: '中度降级',
  degraded: true,
  disabledFeatures: ['feature.recommend', 'feature.ai'],
  banner: '系统当前处于中度降级，部分功能暂不可用',
  updatedAt: 1,
};
eq('推荐入口被隐藏', visibleNavPaths(paths, degraded), ['/', '/feed', '/rooms', '/search', '/user']);
eq('推荐流判定关闭', isFeatureDisabled(degraded, 'feature.recommend'), true);
eq('搜索未被误判', isFeatureDisabled(degraded, 'feature.search.hot'), false);
eq('优先使用后端公告', degradeBannerText(degraded), '系统当前处于中度降级，部分功能暂不可用');

// 后端没给 banner 时的兜底文案
eq(
  '兜底公告包含等级与功能名',
  degradeBannerText({ ...degraded, banner: null }),
  '系统当前处于中度降级，推荐流、AI 能力暂不可用',
);
eq(
  '没有关闭功能时的兜底公告',
  degradeBannerText({ ...degraded, disabledFeatures: [], banner: null }),
  '系统当前处于中度降级，部分功能暂不可用',
);

// 未知键与空值
eq('未知功能名回退原键', degradeFeatureLabel('feature.future'), 'feature.future');
eq('已知功能名', degradeFeatureLabel('feature.social.comment'), '评论');
eq(
  '未知功能的开关不影响导航',
  visibleNavPaths(paths, { ...degraded, disabledFeatures: ['feature.future'] }),
  paths,
);
eq(
  '导航列表为空时不报错',
  visibleNavPaths([], degraded),
  [],
);

console.log(`\n通过 ${passed}  失败 ${failed}`);
process.exit(failed === 0 ? 0 : 1);
