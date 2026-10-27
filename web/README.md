# MusicLive Web（模块 02）

React 18 + TypeScript + Vite + Ant Design 5 的 C 端前端（管理后台见 `../web-admin/`）。
已覆盖模块 01–10 的 C 端页面，设计文档见 `docs/系分/02-前端界面.md`。

## 环境要求

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| Node.js | ≥ 18（实测 24.x） | Vite 5 要求；本仓库的 `scripts/smoke.mjs` 用到全局 `fetch` |
| npm | ≥ 9 | |
| 后端 | 模块 01 已在 `http://localhost:8081` 运行 | 前端通过 Vite 代理访问，不存在跨域问题 |

## 启动

```bash
cd web
npm install
npm run dev          # http://localhost:5173
```

> **国内网络**：直连 `registry.npmjs.org` 实测约 192 KB/s（单个 packument 请求 25s+），
> 装依赖会非常慢。改用镜像命令（只在本次安装生效，不改全局 npm 配置）：
>
> ```bash
> npm install --registry=https://registry.npmmirror.com
> ```
>
> 实测同一套依赖 13s 装完。`package-lock.json` 里的 `resolved` 已统一写回
> `registry.npmjs.org`，镜像地址不会带进仓库。

后端地址默认 `http://localhost:8081`，可用环境变量覆盖：

```bash
# PowerShell
$env:BACKEND_ORIGIN="http://192.168.1.10:8081"; npm run dev
```

`vite.config.ts` 会把 `/api/**`（业务接口）和 `/media/**`（音频/图片静态资源）一起代理到后端，
因此前端代码里始终使用相对路径 `/media/demo/demo-01.wav`。

## 常用脚本

| 命令 | 作用 |
| --- | --- |
| `npm run dev` | 开发服务器（HMR） |
| `npm run typecheck` | `tsc --noEmit`，严格模式全量类型检查 |
| `npm run build` | 先类型检查再打包到 `dist/` |
| `npm run preview` | 预览打包产物 |

## 演示账号

来自 `sql/data.sql` 种子数据：

| 账号 | 密码 | 角色 | 可做什么 |
| --- | --- | --- | --- |
| `admin` | `admin123` | 管理员 | 全站内容管理，含歌曲删除、批量删除 |
| `operator` | `operator123` | 运营 | 曲库录入/审核/上架，**无**歌曲删除权限（按钮会隐藏） |
| `demo` | `demo123456` | 普通用户 | 歌单增删改查、个人资料 |

> 用 `operator` 登录后台可以直观看到「权限码控制按钮显隐」的效果：
> 删除类按钮不渲染，但即便伪造请求，后端 `@PreAuthorize` 仍会拒绝（403 / 40301）。

## 目录结构

```
web/
├── index.html
├── vite.config.ts          # 别名 @ → src、dev 代理、Less 配置
├── tsconfig.json
└── src/
    ├── main.tsx            # ConfigProvider(主题/中文) → AntD App → QueryClient → Router
    ├── api/                # 与后端的唯一契约层
    │   ├── request.ts      # Axios 实例、R<T> 拆包、401 刷新重放、统一 ApiError
    │   ├── auth.ts song.ts catalog.ts playlist.ts
    │   ├── social.ts       # 模块 10：点赞/收藏/评论/关注/通知/私信/举报/分享
    │   └── player.ts       # 播放地址解析适配层（模块 07 会替换内部实现）
    ├── stores/             # Zustand：userStore / themeStore / playerStore / wsStore
    ├── hooks/              # useInfiniteList、useDebouncedValue、usePerm…
    ├── layouts/            # MainLayout（C 端）/ AdminLayout / BlankLayout
    ├── router/             # 路由表 + RequireAuth / RequirePerm 守卫
    ├── components/
    │   ├── common/         # Section、Boundary、EmptyState、LoadMore、主题开关…
    │   ├── business/       # SongTable、SongCard、各表单弹窗
    │   ├── social/         # ActionButtons、CommentSection、FollowButton、收藏/分享/举报弹窗
    │   └── player/         # AudioEngine（常驻 <audio>）、PlayerBar（底部播放条）
    ├── pages/              # home / login / search / song / artist / album / playlist / user / notification / message / settings / error
    ├── services/history.ts # 最近播放（MVP 落 localStorage）
    └── styles/             # variables.less（CSS 变量 + 暗色）+ global.less
```

## 几个容易踩的点

1. **ID 一律是字符串**
   雪花 ID 超过 `2^53-1`，后端通过阈值序列化器输出字符串。前端所有 ID 类型都是 `string`，
   比较时不要用 `===` 混着数字用（例如 `String(a) === String(b)` 更保险）。
2. **更新必须回传 `version`**
   歌单/歌曲/歌手的 `PUT` 接口都带乐观锁：不回传 `version` 会得到 `10001`，
   版本过期会得到 `10008`（说明别人先改过，应刷新后重试）。
3. **HTTP 200 不代表业务成功**
   统一响应体 `R<T>` 里 `code === 0` 才是成功。`request.ts` 已把 `data` 拆出来，
   业务失败会抛出带 `code/traceId` 的 `ApiError`。
4. **登录态失效只刷新一次**
   401 或业务码 40101/40102/40103 会触发一次静默刷新并重放原请求；
   并发多个 401 只会发起一次刷新（单飞）。刷新失败才清登录态并跳 `/login?redirect=…`。
5. **页面跳转不中断播放**
   `<AudioEngine>` 挂在 `main.tsx` 的根节点（不在任何路由树内），底部的 `<PlayerBar>`
   由 `MainLayout` / `AdminLayout` 渲染，因此切页、进出后台都不会销毁 `<audio>`。
6. **颜色不要硬编码**
   所有颜色走 `styles/variables.less` 里的 CSS 变量，暗色模式由 `<html data-theme="dark">` 切换；
   AntD 组件主题由 `ConfigProvider` 的 `darkAlgorithm` 负责。硬编码色值必然在暗色下露馅。

## 与后端的联调清单

| 页面 | 主要接口 |
| --- | --- |
| 首页 | `GET /music/playlists?isRecommend=1`、`GET /music/songs`、`GET /music/artists` |
| 搜索 | `GET /music/{songs,artists,albums,playlists}?keyword=`、`GET /music/tags/hot` |
| 歌曲详情 | `GET /music/songs/{id}`、`GET /music/tags/song/{id}`、`GET /music/lyrics/{songId}` + `social.ts` |
| 歌手/专辑 | `GET /music/artists/{id}`、`GET /music/albums/{id}` + 列表接口、`GET/POST/DELETE /artist-follows/{artistId}` |
| 歌单详情 | `GET/PUT/DELETE /music/playlists/{id}`、`POST/DELETE /music/playlists/{id}/songs`、`PUT …/songs/order` |
| 个人中心 | `GET/PUT /users/me`、`GET /music/playlists?mine=true`、`GET /collects`、`GET /follows/{following, followers}`、`GET /likes/songs`、`GET /reports/my` |
| 通知中心 | `GET /notifications`、`GET /notifications/unread-count`、`PUT /notifications/read`、`PUT /notifications/read-all`、`GET/PUT /notifications/settings` |
| 私信 | `GET /messages/conversations`、`GET /messages?conversationId=`、`POST /messages`、`PUT /messages/read`、`POST /messages/{id}/revoke` |
| 设置 | `GET/PUT /privacy-settings`、`GET/PUT /notifications/settings`、`GET/POST/DELETE /blacklist` |
| 社交动作 | `GET/POST/DELETE /likes`、`GET /collects/status`、`POST/DELETE /collects`、`GET/POST /comments`、`POST /share`、`GET /reports/reasons`、`POST /reports` |

> 社交接口的 `targetType` 是**大写字符串**（`SONG`/`PLAYLIST`/`ALBUM`/`COMMENT`/`ROOM`/`ROOM_MESSAGE`…），
> 通知表里的 `targetType` 是数字；两套编码不要混用。社交列表分页参数是 `page/size`。

## 交付状态

模块 01–10 的 C 端页面均已交付。仍需人工完成的是浏览器走查（而非代码）：
音乐房、播放器/歌词、推荐页、管理后台，以及模块 10 的点赞/收藏/评论/关注/通知/私信全链路。
自动化验收见仓库根目录 `README.md` 第 5 节；未实现的能力不渲染假按钮，统一以禁用态 + 说明文案呈现。
