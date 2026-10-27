# MusicLive（newmusic）

在线音乐社交平台。后端 Spring Boot 2.6.13 + MySQL 8 + Redis，前端 React 18 + TypeScript + Vite 5 + Ant Design 5。

已交付模块：01 基础架构 / 02 前端框架 / 03 缓存与并发 / 04 WebSocket / 05 音乐房 / 06 搜索 / 07 播放器与歌词 / 08 AI 推荐 / 09 管理后台 / 10 社交基础 / 11 IK 分词与中文搜索优化 / 12 播放体验升级 / 13 音乐房升级 / 14 AI 能力增强 / 15 推荐系统升级 / 16 社交能力升级 / 17 数据平台与运营分析 / 18 内容安全与合规 / 19 高可用与运维。

- 需求文档：`docs/PRD/`
- 系统设计：`docs/系分/`
- **功能目录（人工测试用）：[docs/功能目录.md](docs/功能目录.md)**
- 待办与历史验收：[docs/待办.md](docs/待办.md)
- 数据库脚本：`sql/`
- 验收脚本：`scripts/`
- C 端前端说明：`web/README.md`
- 管理后台前端：`web-admin/`（独立端口 5174）

---

## 1. 环境要求

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| JDK | 8 编译目标；实测 17 / 21 | Maven 默认用 JDK 17；IDEA 项目 SDK 为 21 也可（Lombok 已升到 1.18.46） |
| Maven | 3.9.x | |
| MySQL | 8.0+ | 本机 3306，字符集 `utf8mb4` |
| Redis | 6/7 | 本机 6379，Redisson 客户端 |
| Node.js | ≥ 18（实测 24.x） | 前端与验收脚本；`npm run test` 依赖 Node ≥ 23.6 的原生 TS 类型擦除 |

> **本机 MySQL 3306 与 Redis 6379 都是共享实例**，上面还有其它项目的库（`emp_db`、`music_db`）和键（`ranking:*`）。
> 本项目只操作 `newmusic` 库，Redis 键一律以 `ml:` 开头；**禁止** `FLUSHDB` / `FLUSHALL`，SQL 跨库操作必须带 `newmusic.` 限定符。

---

## 2. 首次启动

按以下顺序执行一次即可。之后日常启动只需确保 MySQL / Redis 已运行，再执行第 2.3、2.5、2.6 步；需要管理后台时再执行第 2.7 步。

### 2.1 初始化 MySQL

在仓库根目录执行（PowerShell 不支持 `<` 重定向，下面用 `cmd /c`；Git Bash / WSL 可直接用 `mysql -uroot -p < sql/schema.sql`）：

```powershell
# -p 会交互式提示输入 root 密码；显式指定 utf8mb4，避免中文数据被按 GBK 解释
cmd /c "mysql --default-character-set=utf8mb4 -uroot -p < sql/schema.sql"
cmd /c "mysql --default-character-set=utf8mb4 -uroot -p < sql/data.sql"
```

- `schema.sql`：`CREATE DATABASE IF NOT EXISTS newmusic` + 全部建表，幂等。
- `data.sql`：种子数据（含模块 08 推荐的 40 首演示歌曲、模块 09 的后台账号与 RBAC、模块 11 的搜索词典），`INSERT IGNORE`，幂等。
- 账号需要 `CREATE DATABASE` 权限。

**存量库升级**：如果 `newmusic` 库是在模块 06 之前创建的，再补执行一次拼音列迁移；模块 09 的后台表、`t_user.force_change_password` 列和演示管理员同样需要补一次后台种子；模块 10 的 12 张社交表和内容表互动计数列需要补一次社交迁移；模块 11 的 6 张词典表和 `admin:dict:*` 权限需要补一次词典种子；模块 12~19 各自还有一份增量脚本；歌词/审核修复还给 `t_audit_task` 加了未完成任务唯一索引。所有脚本都幂等，可重复执行：

```powershell
cmd /c "mysql --default-character-set=utf8mb4 -uroot -p < sql/06-search-seed.sql"
cmd /c "mysql --default-character-set=utf8mb4 -uroot -p < sql/09-admin-seed.sql"
cmd /c "mysql --default-character-set=utf8mb4 -uroot -p < sql/10-social-seed.sql"
cmd /c "mysql --default-character-set=utf8mb4 -uroot -p < sql/11-ik-seed.sql"
cmd /c "mysql --default-character-set=utf8mb4 -uroot -p < sql/12-play-seed.sql"
cmd /c "mysql --default-character-set=utf8mb4 -uroot -p < sql/13-room-upgrade-seed.sql"
cmd /c "mysql --default-character-set=utf8mb4 -uroot -p < sql/14-ai-seed.sql"
cmd /c "mysql --default-character-set=utf8mb4 -uroot -p < sql/15-rec-upgrade-seed.sql"
cmd /c "mysql --default-character-set=utf8mb4 -uroot -p < sql/16-social-upgrade-seed.sql"
cmd /c "mysql --default-character-set=utf8mb4 -uroot -p < sql/17-data-platform-seed.sql"
cmd /c "mysql --default-character-set=utf8mb4 -uroot -p < sql/18-content-safety-seed.sql"
cmd /c "mysql --default-character-set=utf8mb4 -uroot -p < sql/19-ops-seed.sql"
cmd /c "mysql --default-character-set=utf8mb4 -uroot -p < sql/20-audit-task-open-unique.sql"
```

`sql/10-social-seed.sql` 只做 `CREATE TABLE IF NOT EXISTS` 和缺列补齐，不含种子数据；点赞、评论、关注等全部由用户行为产生。各脚本新建的表、权限点与 Redis 键见 `sql/README.md`。

`sql/08-recommend-seed.sql` 与 `data.sql` 第 8 节内容一致，全新初始化时无需单独执行。

### 2.2 启动 Redis

确保 `127.0.0.1:6379` 可连即可（默认无密码、`database=0`）。连接参数集中在 `src/main/resources/application.yml` 的 `newmusic.infra.redis` 下，也可用环境变量覆盖。

### 2.2.1 启动 Elasticsearch（可选，本机已装）

`SEARCH_ENGINE=mysql`（默认）不依赖 ES；要用 ES 引擎或词典页的「重载 / 重建索引」，
先把本机的 ES 9.5.5 起起来（新开一个窗口，保持运行）：

```powershell
& "E:\06_Development_tools\2026-10-22-22-45-56--elasticsearch-9.5.5-windows-x86_64\elasticsearch-9.5.5\bin\elasticsearch.bat"
curl.exe -u elastic:<你的ES密码> "http://127.0.0.1:9200/_cat/health?v"
```

然后启动后端时带上 `SEARCH_ENGINE=es`、`DICT_ES_ENABLED=true`、`DICT_ES_USERNAME`、`DICT_ES_PASSWORD`。
启动会自动建别名 `music_song` → `music_song_v{yyyyMMddHHmmss}` 并灌入种子文档；
索引版本变化（`schemaVersion`）或别名缺失时会自动重建，失败只告警并降级回 MySQL。

完整的启动、验收与排错步骤见 **[docs/ES-接入与启动指引.md](docs/ES-接入与启动指引.md)**。

### 2.3 配置环境变量

**`MYSQL_PASSWORD` 是必填项，没有默认值。** 未设置时应用会直接报
`Could not resolve placeholder 'MYSQL_PASSWORD'` 并拒绝启动（这是刻意设计，避免用错误密码连库后抛出一串 1045 / Druid 初始化异常）。

PowerShell（只对当前终端生效）：

```powershell
$env:MYSQL_PASSWORD = "你的MySQL root密码"
```

cmd：

```bat
set MYSQL_PASSWORD=你的MySQL root密码
```

常用可选变量：

| 变量 | 默认值 | 用途 |
| --- | --- | --- |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` / `REDIS_DATABASE` | `127.0.0.1` / `6379` / 空 / `0` | Redis 连接 |
| `PLAY_SIGN_SECRET` | 演示密钥 | 播放地址签名，生产必须覆盖 |
| `SEARCH_ENGINE` | `mysql` | 搜索引擎；装好 ES 后改为 `es`（`DICT_ES_ENABLED` 也要为 `true`），不可达时自动降级 MySQL |
| `SEARCH_PINYIN_BACKFILL` | `true` | 启动时补全拼音列；生产迁移完成后建议置 `false` |
| `DICT_ES_ENABLED` | `false` | 词典管理里的 ES 探测 / 分词 / 重载开关；本机没有 ES 时保持 `false`，搜索自动走本地改写 + MySQL LIKE |
| `DICT_ES_URL` | `http://127.0.0.1:9200` | ES 地址；开了 `xpack.security.http.ssl.enabled` 时改成 `https://` |
| `DICT_ES_USERNAME` / `DICT_ES_PASSWORD` | 空 / 空 | ES Basic Auth 账号口令，只从环境变量读，不要写进 `application.yml` |
| `DICT_ES_INDEX_ALIAS` | `music_song` | 检索别名；物理索引是 `别名_v{yyyyMMddHHmmss}`，重建时原子切换 |
| `DICT_ES_AUTO_INIT` | `true` | 启动时别名缺失或 `schemaVersion` 落后则自动全量重建（失败只告警，不阻塞启动） |
| `DICT_ES_SCHEMA_VERSION` | `4` | 与 `es/music_song-index*.json` 的 `_meta.schemaVersion` 对应；改 mapping 时两边一起 +1 |
| `DICT_INTERNAL_IPS` | `127.0.0.1,0:0:0:0:0:0:0:1,::1` | 允许拉取 `/api/v1/internal/dict/ik` 的来源 IP（逗号分隔，支持 `*`）；ES 与本应用不同机时必须显式加入 ES 出口 IP |
| `RATE_LIMIT_WHITELIST` | 空 | 限流 IP 白名单（逗号分隔） |
| `WS_ALLOWED_ORIGINS` | `*` | WebSocket 握手 Origin 白名单，生产必须列具体域名 |
| `ADMIN_JWT_ALLOWED_ORIGINS` | `http://localhost:5174,http://127.0.0.1:5174` | 管理后台 CSRF 校验的 Origin 白名单；部署时必须改成真实后台域名 |
| `ADMIN_JWT_COOKIE_SECURE` | `false` | 管理后台 Cookie 是否仅 HTTPS 发送，生产必须 `true` |
| `NEWMUSIC_ADMIN_CAPTCHA_ECHO` | `true` | 登录验证码在响应中回显（仅开发便利）；生产必须 `false` |

> 不要把真实密码写进 `application.yml`、脚本或提交到仓库。

### 2.4 生成演示音频（可选）

仓库已带 `media/demo/demo-01.wav … demo-06.wav`。如果目录为空，重新生成：

```bash
node scripts/make-demo-audio.mjs
```

脚本零依赖，直接写 PCM WAV，输出与 `data.sql` 中的 `audio_standard` 路径一一对应。

### 2.4.1 批量导入本地歌曲（mp3 / flac / wav，可选）

不用手写 JSON：给一个文件夹，脚本直接读音频标签（缺失时按「歌手 - 歌名」文件名兜底），
逐个上传拿 `/media/uploads/...` URL，再按 ≤200 行一批调后台导入接口。

```powershell
node scripts/import-local-songs.mjs "D:\Music\周杰伦" --dry-run     # 只看解析结果
node scripts/import-local-songs.mjs "D:\Music\周杰伦" --publish     # 导入并上架
node scripts/import-local-songs.mjs --self-test                     # 解析器自测，35 项
```

前置：后端起在 8081 且 `NEWMUSIC_ADMIN_CAPTCHA_ECHO=true`（脚本自动过图形验证码）。
单文件 ≤50MB，不转码；歌手不存在默认记失败，可加 `--create-artist`。
参数、元数据映射与排错见 [docs/本地歌曲批量导入.md](docs/本地歌曲批量导入.md)。

### 2.5 启动后端

在仓库根目录执行：

```powershell
$env:MYSQL_PASSWORD = "你的MySQL root密码"
mvn spring-boot:run
```

| 服务 | 地址 |
| --- | --- |
| 业务接口 | `http://localhost:8081/api/v1` |
| Knife4j 接口文档 | `http://localhost:8081/doc.html` |
| 健康检查（独立管理端口） | `http://localhost:8082/actuator/health` |

启动成功判据（另开一个终端）：

```powershell
Invoke-WebRequest http://localhost:8082/actuator/health -UseBasicParsing    # {"status":"UP"}
Invoke-WebRequest http://localhost:8081/api/v1/search/hot -UseBasicParsing  # HTTP 200
```

`/api/v1/search/hot` 会真正查 `newmusic` 库，返回 200 同时证明数据库认证已通过。

### 2.6 启动前端

```bash
cd web
npm install --registry=https://registry.npmmirror.com
npm run dev
```

打开 `http://localhost:5173`。Vite 会把 `/api`、`/media`、`/ws` 代理到 `http://localhost:8081`，因此前端代码始终使用相对路径，开发态无跨域问题。

后端不在本机 8081 时：

```powershell
$env:BACKEND_ORIGIN = "http://192.168.1.10:8081"
npm run dev
```

### 2.7 启动管理后台（web-admin）

管理后台是与 C 端完全独立的 SPA，必须经过 Vite 的同源代理访问后端：后台认证只用 `HttpOnly` Cookie（`ml_admin_access` / `ml_admin_refresh`），并且后端会校验 `Origin`，直接打开 8081 或绕过 5174 都会导致 Cookie / CSRF 校验失败。

```bash
cd web-admin
npm install --registry=https://registry.npmmirror.com
npm run dev
```

打开 `http://localhost:5174`，用第 5 节的「管理后台账号」登录。后端不在本机 8081 时同样设置 `$env:BACKEND_ORIGIN` 后再 `npm run dev`。

- 登录验证码默认在接口响应的 `devCaptcha` 字段回显，方便本地联调；生产环境必须设置 `NEWMUSIC_ADMIN_CAPTCHA_ECHO=false`，改为人工识图。
- 超级管理员登录需要 TOTP 动态码，演示密钥见第 5 节；可用任意 TOTP App（如 Google Authenticator）导入该 Base32 密钥。
- 后台 JWT 与 C 端 JWT 使用不同密钥和 issuer（`ml-admin`），两边的 Token 不能互相使用。

---

## 3. 在 IDEA 中启动

1. Maven 面板执行 **Reload All Projects**，确认 Lombok 为 `1.18.46`（`pom.xml` 已显式固定；Spring Boot 默认管理的 1.18.24 在 JDK 21 下会抛 `NoSuchFieldError: JCTree$JCImport.qualid`）。
2. Run → Edit Configurations → `NewmusicApplication` → **Environment variables** 增加
   `MYSQL_PASSWORD=你的MySQL root密码`。
3. 启动前确认 8081 没有被占用（见下一节）；否则会报 `Port 8081 is already in use`。
4. 项目字节码目标保持 **1.8**，不要把 Language Level 改成 21。

---

## 4. 停止与重启

停掉 Maven 后，`spring-boot:run` fork 出的 JVM **可能仍然存活并占用 8081**。重启前按端口补一刀：

```powershell
Get-NetTCPConnection -LocalPort 8081 -State Listen |
  Select-Object -ExpandProperty OwningProcess -Unique |
  ForEach-Object { Stop-Process -Id $_ -Force }
```

然后重新执行第 2.5 步。改过 `application.yml` 后需重启后端；`target/classes` 里的配置由 `mvn spring-boot:run` 自动复制。

---

## 5. 验收与测试

验收脚本零第三方依赖，运行前需已启动后端（部分用例还需要 Redis / `MYSQL_PASSWORD`）。
统一支持 `API_BASE`（默认 `http://localhost:8081/api/v1`）和 `REDIS_CLI`（默认 `C:\Program Files\Redis\redis-cli`）。

| 命令 | 覆盖范围 |
| --- | --- |
| `node scripts/smoke.mjs` | 模块 01/02 冒烟，67 项 |
| `node scripts/verify-lyrics-audit.mjs` | 歌词随导入写入、后台歌词读写与类型校验、删后重传复活墓碑、并发写入、待审歌曲入审核台、通过后上架、旧 Cookie 重新登录，37 项 |
| `node scripts/import-local-songs.mjs --self-test` | 本地导入工具：WAV/FLAC/MP3 标签与时长解析、三种格式内嵌歌词与 `.lrc` 解码、文件名兜底、年代推导，35 项（不联网） |
| `node scripts/verify-03.mjs` | 缓存 / 布隆 / 锁 / 限流 / 登录态，58 项 |
| `node scripts/verify-04.mjs` | WebSocket 握手 / 心跳 / 可靠消息 / 断线补偿，134 项 |
| `$env:MYSQL_PASSWORD="..."; node scripts/verify-05.mjs` | 音乐房，82 项 |
| `$env:MYSQL_PASSWORD="..."; node scripts/verify-06.mjs` | 搜索 / 拼音 / 联想 / 热搜（约 2.5 分钟）。引擎感知：ES 模式跳过 5 条 MySQL 加和权重断言，实测 201 通过 / 10 跳过；MySQL 模式 206 通过 / 5 跳过 |
| `$env:ES_PASSWORD="..."; node scripts/verify-es.mjs` | Elasticsearch 专项：别名与 schemaVersion、单字分析、四类文档计数、中文/拼音/首字母、分类 Tab、筛选排序、歌词、游标不重不漏、后台增改删写穿透、非 `music_song*` 索引前后一致，83 项（不碰 MySQL/Redis） |
| `$env:MYSQL_PASSWORD="..."; node scripts/verify-08.mjs` | AI 推荐，141 项通过 / 8 项人工或跳过 |
| `$env:MYSQL_PASSWORD="..."; node scripts/verify-09.mjs` | 管理后台：认证 / TOTP / RBAC / CSRF / 用户 / 内容 / 审核 / 举报 / 配置 / 日志，188 项（含 C 端登录回归） |
| `$env:MYSQL_PASSWORD="..."; node scripts/verify-10.mjs` | 社交基础：点赞 / 收藏夹 / 评论回复 / 关注 / 黑名单 / 通知 / 举报 / 分享 / 私信，181+ 项 |
| `$env:MYSQL_PASSWORD="..."; node scripts/verify-11.mjs` | IK 词典：远程词典 200/ETag/304、四类词典 CRUD/导入导出、分词与搜索改写、歌手同步、ES 降级、RBAC |
| `$env:MYSQL_PASSWORD="..."; node scripts/verify-12.mjs` | 播放体验：音质切换 / 自动降质 / 倍速 / 跨设备续播 / 设置同步，101 项通过 / 1 项环境跳过 |
| `$env:MYSQL_PASSWORD="..."; node scripts/verify-13.mjs` | 音乐房升级：权威播放态 / 时钟同步 / 队列 / 投票 / 榜单 / 弹幕，54 项 |
| `$env:MYSQL_PASSWORD="..."; node scripts/verify-14.mjs` | AI 能力：网关缓存 / 预算熔断 / 额度 / 翻译 / 情绪标注 / 批处理 / 后台成本，81 项 |
| `$env:MYSQL_PASSWORD="..."; node scripts/verify-15.mjs` | 推荐升级：召回 / 融合 / 排序 / 重排 / 实验 / 指标 / 离线评估 / 监控，145 项 |
| `$env:MYSQL_PASSWORD="..."; node scripts/verify-16.mjs` | 社交升级：个人主页 / 动态 / 关注流 / 发现页 / 消息中心 / 私信 / 群聊 / 亲密度 / 粉丝榜，104 项 |
| `$env:MYSQL_PASSWORD="..."; node scripts/verify-17.mjs` | 数据平台：埋点采集 / 实时看板 / 指标体系 / 漏斗留存 / 画像人群 / 报表订阅导出 / ETL / 质量对账血缘，133 项 |
| `$env:MYSQL_PASSWORD="..."; node scripts/verify-18.mjs` | 内容安全：敏感词 DFA/归一化/白名单/L1-L3、规则与阈值、人工工作台、处置与信用分、申诉回避与恢复、举报合并与自动隐藏、版权投诉、未成年人、AI 标识、RBAC，147 项 |
| `$env:MYSQL_PASSWORD="..."; node scripts/verify-19.mjs` | 运维：健康检查 / 限流 / 熔断 / 降级 / 告警与预案 / 混沌 / 指标与链路 / 发布回滚 / 备份校验 / 成本 / 配置热更新 / 缓存巡检，169 项（需 8082 管理端口与共享实例快照） |

> 带 `MYSQL_PASSWORD` 是为了执行写库硬证据与收尾清理。不带时相关小节会记为「跳过」而不是「通过」——脚本刻意不把「没验」算成绿。

前端检查：

```bash
cd web
npm run test      # 12 套纯函数断言：歌词 / 搜索 URL / 播放顺序 / 可播放性 / WS / 社交 / 埋点 / 内容安全 / 降级公告
npm run build     # tsc --noEmit + vite build

cd ../web-admin
npm run typecheck # tsc --noEmit
npm run build     # tsc --noEmit + vite build
```

C 端演示账号（来自 `sql/data.sql`）：

| 账号 | 密码 | 角色 |
| --- | --- | --- |
| `admin` | `admin123` | 管理员 |
| `operator` | `operator123` | 运营（无删除权限） |
| `demo` | `demo123456` | 普通用户 |

管理后台账号（来自 `sql/09-admin-seed.sql`，与 C 端账号分属 `t_user` / `t_admin_user` 两套表）：

| 账号 | 密码 | 角色 | 说明 |
| --- | --- | --- | --- |
| `superadmin` | `admin123` | SUPER_ADMIN | 需要 TOTP，演示密钥 `JBSWY3DPEHPK3PXP` |
| `admin` | `admin123` | ADMIN | 用户 / 内容 / 审核 / 举报 / 日志查看 |
| `operator` | `operator123` | OPERATOR | 看板 + 运营配置，无敏感权限 |

> 这些 TOTP 密钥与密码只用于本机演示，生产环境必须重置；超级管理员账号的 TOTP 不能关闭。

---

## 6. 端口一览

| 端口 | 服务 |
| --- | --- |
| 8081 | 后端业务接口（含 `/api/v1/admin`）/ WebSocket / 静态媒体 |
| 8082 | Actuator 健康检查 / liveness / readiness / Prometheus（仅 127.0.0.1） |
| 5173 | C 端 Vite 开发服务器（`web/`） |
| 5174 | 管理后台 Vite 开发服务器（`web-admin/`） |
| 3306 | MySQL（共享实例） |
| 6379 | Redis（共享实例） |

---

## 7. 常见问题

| 现象 | 原因与处理 |
| --- | --- |
| `Could not resolve placeholder 'MYSQL_PASSWORD'` | 启动进程没有拿到环境变量。终端启动看 `$env:MYSQL_PASSWORD`；IDEA 启动看 Run Configuration 的 Environment variables。 |
| `Access denied for user 'root'@'localhost'`（1045） | 密码不对或没传进去。先用 `mysql -uroot -p` 手工验证；不要把密码写进 `application.yml`。 |
| `Port 8081 is already in use` | 上一轮 fork 的 JVM 未退出，按第 4 节按端口结束进程。 |
| `NoSuchFieldError: JCTree$JCImport.qualid` | IDEA 用 JDK 21 编译而 Lombok 过旧。确认 Maven 已重新加载 `pom.xml`（Lombok 1.18.46），再 Build → Rebuild Project。 |
| `boot.log` 中文乱码 | JVM 以 GBK 写日志。排查时只用 ASCII 锚点（类名、SQL 片段、`=数字`），不要 grep 中文。 |
| 搜索拼音结果为空 | 首次启动会异步补全拼音列（`SEARCH_PINYIN_BACKFILL=true`）；等启动日志完成后重试，或手工执行 `sql/06-search-seed.sql`。 |
| 推荐接口返回空 | 确认 `data.sql` 已导入（至少 40 首候选），并检查 Redis 中 `ml:rec:*` 是否为上一轮残留；验收脚本会自行清理探测账号数据。 |
| 后台登录返回 403 / 登录后接口立刻 401 | 必须通过 `http://localhost:5174` 的 Vite 代理访问，`Origin` 要命中 `ADMIN_JWT_ALLOWED_ORIGINS`；Cookie 是 `HttpOnly` + `SameSite=Strict`，直接访问 8081 或从其他端口打开都会丢会话。 |
| 后台登录提示需要动态码 / 动态码错误 | `superadmin` 开启了 TOTP，用 `JBSWY3DPEHPK3PXP` 生成 6 位码；服务器与本机时钟偏差超过 30 秒会持续失败。 |
| 后台账号被锁 | 连续 5 次密码错误会锁定 30 分钟，锁定状态在 `t_admin_user.lock_until`。本地联调不要反复试错；确需解锁时清空该账号的 `fail_count` / `lock_until`（SQL 必须带 `newmusic.` 限定）。 |
| 点赞 / 收藏 / 评论返回 50020 或目标不存在 | 社交接口的 `targetType` 是**大写字符串**（`SONG` / `PLAYLIST` / `ALBUM` / `COMMENT` / `ROOM` / `ROOM_MESSAGE` 等），与通知表里的数字类型不是同一套编码。另注意社交列表分页参数是 `page/size`，不是模块 01 的 `pageNum/pageSize`。 |
| 模块 10 接口报「表不存在」 | 存量库需要执行一次 `sql/10-social-seed.sql`（幂等）；该脚本只建表/补列，不写种子数据。 |
| 搜索词典页报「表不存在」/ 没有「搜索词典」菜单 | 存量库执行一次 `sql/11-ik-seed.sql`（幂等）；它建 6 张词典表并补 `admin:dict:view/edit/reindex` 权限。权限变更后需重新登录后台。 |
| 词典页「重载分析器 / 重建索引」返回 71008 | 本机未配置 Elasticsearch（`DICT_ES_ENABLED=false`）。此时搜索走本地兜底：繁简转换、首尾停用词、整词同义词、拼音别名 + MySQL LIKE，属于预期行为，不是故障。ES 已启动时检查 `DICT_ES_URL` / 账号口令与 ES 是否可达。 |
| 搜索响应 `engine=mysql` 而期望 `es` | `SEARCH_ENGINE` 没设成 `es`，或 `DICT_ES_ENABLED=false`；二者都开但 ES 不可达时会 `degraded=true` 自动降级。响应头 `X-Search-Engine` 与 `/dict/status` 可确认。 |
| 搜单字「天」「晴」0 条 | 索引还是旧 schemaVersion（<4，`cjk_bigram` 只产二元词）。看启动日志有没有自动重建，或后台「重建索引」；改 mapping 时要同步 +1 三处版本号（见 `docs/ES-接入与启动指引.md` §7.1）。 |
| ES 模式翻页一直重复前几条 / 到不了末页 | 游标里的分被截断成整数，`search_after` 与 ES 浮点 `_score` 对不上。当前实现已让游标携带精确 `_score`，重启后端即可。 |
| 切到歌手 / 专辑 / 歌单 Tab 永远空 | 这几类文档没有 `copyrightStatus` / 语种 / 流派字段，不能套用歌曲筛选。当前实现按 `docType` 分支；若回归，先看该文档 `_source` 有无这些字段。 |
| 后台新建/审核通过的歌搜不到 | 写穿透在事务 `afterCommit` 后 upsert 并带 `refresh=true`；先确认该歌 `status=1` 且 `audit_status=1`（歌单还要求公开），再看日志有没有 `同步歌曲索引失败`。 |
| 启动后立刻报 `NoClassDefFoundError` | 后端运行期间又执行了 `mvn compile` / `mvn test`，`target/classes` 被重写。停后端 → 重新编译 → 再启动。 |
| ES 拉取 `/api/v1/internal/dict/ik` 返回 403 | 这是内部接口，只按 TCP 来源 IP（`getRemoteAddr()`，不信任 `X-Forwarded-For`）校验 `DICT_INTERNAL_IPS`。ES 与本应用不同机时把 ES 出口 IP 加进去。 |
| 数据平台页报「表不存在」/ 没有「数据平台」菜单 | 存量库执行一次 `sql/17-data-platform-seed.sql`（幂等）；它建 16 张表并补 `admin:data:*` 权限，权限变更后需重新登录后台。 |
| 内容安全页报「表不存在」/ 没有「内容安全」菜单 | 存量库执行一次 `sql/18-content-safety-seed.sql`（幂等）；它建 11 张表、补 `admin:audit:*` 等权限并给 `t_playlist` 补 AI 标识列。权限变更后需重新登录后台。 |
| 处置或申诉返回 99001，日志为 `Unknown column 'violation_count90d'` | 库列名是 `violation_count_90d`；实体已用 `@TableField` 显式映射。若二次开发新增同类数字后缀字段，也要显式写列名，不要依赖默认驼峰转换。 |
| 实时看板数值全是 0 或显示「回落 T+1」 | Redis 不可用时实时指标会回落 ADS，这是设计好的降级；埋点仍会正常入库，恢复 Redis 后新数据立即计入实时链路。 |

---

## 8. 目录结构

```
newmusic/
├── src/main/java/com/example/newmusic/
│   ├── common/            # 统一响应、异常、工具
│   ├── infra/             # 安全、Redis、缓存、限流、WebSocket 基础设施
│   └── module/            # 业务模块：user（认证/用户）/ music（曲库/歌单）/ search / play / recommend / room / ws / admin / social / dict / recupgrade / dataplatform / contentsafety
├── src/main/resources/    # application.yml、mapper XML、C 端与后台两套 JWT 密钥
├── sql/                   # schema.sql、data.sql 及各模块迁移/种子脚本（09-admin ~ 18-content-safety，说明见 sql/README.md）
├── scripts/               # 验收脚本与演示音频生成
├── media/demo/            # 演示音频
├── docs/                  # PRD 与系统设计
├── web/                   # C 端 React 前端（5173）
└── web-admin/             # 管理后台 React 前端（5174）
```
