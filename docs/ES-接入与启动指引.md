# Elasticsearch 接入与启动指引

本文是**本机已经装好 ES 之后**的启动与验收清单：先起 ES，再起后端（`SEARCH_ENGINE=es`），
确认别名、索引与检索都正常，最后跑验收脚本。所有口令一律用占位符 `<你的ES密码>`，
**不要把真实口令写进本文件、`application.yml`、脚本或提交记录**。

---

## 1. 本机环境

| 项 | 值 |
| --- | --- |
| ES 安装目录（`ES_HOME`） | `E:\06_Development_tools\2026-10-22-22-45-56--elasticsearch-9.5.5-windows-x86_64\elasticsearch-9.5.5` |
| 版本 | 9.5.5（zip 包，自带 JDK，不需要额外设 `JAVA_HOME`） |
| 集群 / 节点 | `my-elasticsearch` / `node-1` |
| 模式 | `discovery.type: single-node`（单节点，未组集群） |
| 网络 | `network.host: localhost` → 只监听本机 |
| 安全 | `xpack.security.enabled: true`（HTTP 走 **Basic Auth**） |
| 传输加密 | `xpack.security.http.ssl.enabled: false`、`xpack.security.transport.ssl.enabled: false` |
| 插件 | 无（未装 `analysis-ik` / `analysis-pinyin` / `analysis-stconvert`） |

因为没装 IK/拼音/繁简插件，项目会自动选用 **lite 映射**：

- 中文：`standard` 分词 + `cjk_bigram`（**含单字**，`output_unigrams: true`）——
  否则搜「天」「晴」这种单字会命中 0 条；
- 拼音：由应用侧 pinyin4j 物化成 `namePinyin` / `namePinyinFirst` 等字段，
  索引侧用 `edge_ngram(1..20)`，所以 `zhou`、`zhoujielun`、`zjl` 都能命中；
- 装了 IK/拼音/繁简三个插件后，会改用 `es/music_song-index.json`（full 映射），代码不用改。

> 索引里没有排序插件、也没有 `music_song` 之外的任何项目索引；
> 本项目的索引只有**一个别名 `music_song` → 一个物理索引 `music_song_v{yyyyMMddHHmmss}`**。

---

## 2. 启动顺序

```
① Elasticsearch（9200） → ② MySQL（3306） / Redis（6379） → ③ 后端（8081/8082） → ④ 前端（5173/5174）
```

MySQL / Redis 是**共享实例**（3306 上还有 `emp_db`、`music_db`；6379 的 db0 有别项目的 `ranking:*` 键），
本项目只读写 `newmusic.` 库与 `ml:` 前缀的键，**不要对它们执行 `DROP DATABASE` / `FLUSHDB` / `KEYS *`**。

---

## 3. 启动 Elasticsearch

另开一个 **PowerShell 窗口**（这个窗口要一直开着，Ctrl+C 才停服务）：

```powershell
& "E:\06_Development_tools\2026-10-22-22-45-56--elasticsearch-9.5.5-windows-x86_64\elasticsearch-9.5.5\bin\elasticsearch.bat"
```

看到 `started` 后，在**另一个**窗口做健康检查（口令换成你自己的）：

```powershell
curl.exe -u elastic:<你的ES密码> "http://127.0.0.1:9200"
curl.exe -u elastic:<你的ES密码> "http://127.0.0.1:9200/_cat/health?v"
```

预期：

- 根路径返回带 `"version" : { "number" : "9.5.5" ... }` 的 JSON，`tagline` 是 `You Know, for Search`；
- `_cat/health` 的 `status` 为 `green`（单节点、0 副本）。

几个常用确认命令：

```powershell
# 有没有装插件（本项目期望为空）
curl.exe -u elastic:<你的ES密码> "http://127.0.0.1:9200/_cat/plugins?v"

# 后端建好的别名与索引（第一次启动后端之前这里是空的）
curl.exe -u elastic:<你的ES密码> "http://127.0.0.1:9200/_cat/aliases/music_song?v"
curl.exe -u elastic:<你的ES密码> "http://127.0.0.1:9200/_cat/indices/music_song*?v&h=index,docs.count,health"
```

### 3.1 口令忘了 / 想重置

```powershell
& "E:\06_Development_tools\2026-10-22-22-45-56--elasticsearch-9.5.5-windows-x86_64\elasticsearch-9.5.5\bin\elasticsearch-reset-password.bat" -u elastic
```

把新口令设进后端的 `DICT_ES_PASSWORD` 环境变量即可，别写进文件。

### 3.2 关于建用户

本机演示用内置 `elastic` 就够（它是超级用户）。如果要建专用账号，用 ES 的 Security API
（同样不要把口令写进文件）：

```powershell
curl.exe -X POST -u elastic:<你的ES密码> "http://127.0.0.1:9200/_security/user/newmusic_app" `
  -H "Content-Type: application/json" `
  -d '{\"password\":\"<应用账号口令>\",\"roles\":[\"superuser\"]}'
```

生产环境不要给 `superuser`：按索引模式 `music_song*` 授 `read` / `write` / `manage` 即可，
并打开 `xpack.security.http.ssl.enabled`（改为 `https://` 后 `DICT_ES_URL` 换成 `https://...`）。

---

## 4. 启动后端（ES 模式）

在仓库根目录 `E:\07_Data\IdeaProjects\newmusic` 执行：

```powershell
$env:MYSQL_PASSWORD = "<你的MySQL root密码>"
$env:SEARCH_ENGINE = "es"
$env:DICT_ES_ENABLED = "true"
$env:DICT_ES_URL = "http://127.0.0.1:9200"
$env:DICT_ES_USERNAME = "elastic"
$env:DICT_ES_PASSWORD = "<你的ES密码>"
$env:DICT_ES_AUTO_INIT = "true"
$env:NEWMUSIC_ADMIN_CAPTCHA_ECHO = "true"
mvn spring-boot:run
```

cmd 窗口等价写法：

```bat
set MYSQL_PASSWORD=<你的MySQL root密码>
set SEARCH_ENGINE=es
set DICT_ES_ENABLED=true
set DICT_ES_URL=http://127.0.0.1:9200
set DICT_ES_USERNAME=elastic
set DICT_ES_PASSWORD=<你的ES密码>
mvn spring-boot:run
```

IntelliJ IDEA 里跑 `NewmusicApplication` 时，在 Run Configuration → Environment variables
填上面这些键值（注意 IDE 用的 JDK 21 需要 Lombok **≥ 1.18.30**，否则编译期注解处理器直接报错）。

### 4.1 为什么两个开关都要开

| 开关 | 作用 |
| --- | --- |
| `DICT_ES_ENABLED=true` | 允许词典模块探测 ES、调 `_analyze`、重建索引 |
| `SEARCH_ENGINE=es` | 让 `/api/v1/search` **优先使用** ES 引擎 |

只开 `DICT_ES_ENABLED` 的话，搜索仍然走 MySQL（本地词典改写 + `LIKE`）；
两个都开、但 ES 不可达时，`SearchEngineRouter` 会自动降级到 MySQL，
响应里 `degraded=true`、`engine=mysql`。

### 4.2 启动时会自动建索引

`EsIndexInitializer` 在启动末尾做三件事（**失败只告警，不阻塞启动**）：

1. 别名 `music_song` 不存在 → 全量重建；
2. 别名指向的索引 `mappings._meta.schemaVersion` < 代码里的 `schemaVersion`（当前 **4**）→ 重建；
3. 版本一致 → 什么都不做。

重建过程是「建新物理索引 → 灌数据 → `_refresh` → 原子切换别名 → 删除旧的 `music_song_v*`」，
所以检索过程中不会出现「索引里空一半」的窗口。

日志是 GBK 的（见 README 的说明），用 ASCII 锚点查：

```powershell
Select-String -Path .\boot.log -Pattern "EsIndexInitializer|schemaVersion="
```

第一次启动会看到类似：

```
EsIndexInitializer - ES 索引自动初始化完成：索引 music_song_v2026... 已切换为别名 music_song，
未检测到 IK/拼音/繁简插件，使用标准分析器，扫描 88 条、写入 88 条、耗时 3308ms
```

已有索引且版本一致时只看到一行：

```
EsIndexInitializer - ES 索引已就绪：别名=music_song schemaVersion=4 版本=9.5.5
```

> **不要在后端运行时执行 `mvn compile` / `mvn test`**：Maven 会重写 `target/classes`，
> 正在运行的 JVM 再加载类时会抛 `NoClassDefFoundError`（表现是启动/请求突然 500）。
> 要编译就先停后端。

---

## 5. 验收：确认「真的在用 ES」

### 5.1 最直接的三条

```powershell
# 1) 响应体 engine=es、degraded=false
curl.exe -s "http://localhost:8081/api/v1/search?keyword=qingtian&type=song" -H "X-Forwarded-For: 10.98.1.1"

# 2) 响应头 X-Search-Engine: es
curl.exe -s -D - -o NUL "http://localhost:8081/api/v1/search?keyword=qingtian&type=song" -H "X-Forwarded-For: 10.98.1.2"

# 3) 索引里有文档（种子里 SONG 至少 6 条）
curl.exe -u elastic:<你的ES密码> "http://127.0.0.1:9200/music_song/_count"
```

> 中文关键词在 Git Bash / cmd 里直接写在 URL 上会被转成 GBK，导致搜不到；
> 要么用浏览器/前端页面，要么先把关键词做 URL 编码（`晴天` → `%E6%99%B4%E5%A4%A9`）。
> PowerShell 的 `curl.exe` 与 Node 脚本没有这个问题。

### 5.2 自动化验收脚本

| 命令 | 覆盖 | 本机实测 |
| --- | --- | --- |
| `$env:ES_PASSWORD="<你的ES密码>"; node scripts/verify-es.mjs` | ES 专项：别名/schemaVersion/单字分析/四类文档计数、中文与拼音、分类 Tab、筛选排序、歌词、游标不重不漏、后台增改删的写穿透、非 `music_song*` 索引前后一致 | **83 通过 / 0 失败** |
| `$env:MYSQL_PASSWORD="..."; $env:APP_LOG=".tmp\boot-es.log"; node scripts/verify-06.mjs` | 模块 06 全量（检索/拼音/联想/筛选排序/深分页/限流/历史/热搜）。ES 模式下自动跳过 5 条「BR-15-011 加和权重」的 MySQL 口径断言，并改为断言 ES 行为 | **201 通过 / 0 失败 / 10 跳过** |
| `$env:MYSQL_PASSWORD="..."; node scripts/verify-11.mjs` | 模块 11 词典（远程词典 200/ETag/304、四类词典 CRUD、分词与改写、重建索引） | **85 通过 / 0 失败 / 2 跳过** |
| `$env:MYSQL_PASSWORD="..."; mvn test` | 后端单测（含 `EsSearchEngineTest` 10 项） | **214 项全部通过**（`NewmusicApplicationTests` 需要 `MYSQL_PASSWORD`，否则上下文起不来） |

`verify-es.mjs` 只依赖 Node 18+ 内置 `fetch`，不需要装 npm 包；口令只从 `ES_PASSWORD` 读。
它**不执行任何 SQL、不创建任何 Redis 键**，共享的 MySQL/Redis 实例不受影响。

---

## 6. 检索行为速查

| 场景 | 请求 | 预期 |
| --- | --- | --- |
| 中文 | `keyword=晴天&type=song` | `total=1`，`matchedFields=["name","lyric"]`，`highlight.name` 已高亮 |
| 单字 | `keyword=天&type=song` | 种子歌曲里命中《晴天》《倔强》《突然好想你》 |
| 全拼 | `keyword=qingtian` | 命中《晴天》，`matchedFields=["name.pinyin"]`，无高亮（字符对不上） |
| 首字母 | `keyword=qt` | 命中《晴天》，`matchedFields=["name.pinyin.first"]` |
| 单字符守卫 | `keyword=q` | 走全拼前缀召回，但**不会**出现 `name.pinyin.first`（D-46） |
| 歌手首字母 | `keyword=zjl&type=all` | artists 段命中周杰伦；ES 下歌曲侧也能按歌手首字母搜到（MySQL 实现不读该列，两种引擎口径不同） |
| 歌词 | `keyword=小黄花` / `keyword=故事的小黄花&type=lyric` | 命中《晴天》，`matchedFields` 含 `lyric`，片段 ±30 字且已剥离 LRC |
| 分类 Tab | `keyword=周杰伦&type=artist|album|playlist` | 各自命中；版权/语种/流派/年代/时长筛选**只对歌曲文档生效** |
| 筛选 | `keyword=天&genre=摇滚` | 只删行、不改分（ES 用 filter 上下文） |
| 排序 | `sort=hot|newest|duration_asc|duration_desc` | 按 `hotScore`/`publishTime`/`duration` 排序 |
| 游标 | `pageSize=3` + 上一页返回的 `cursor` | `search_after` 翻页不重不漏，末页不再给 `cursor` |

---

## 7. 手工重建索引

三种方式任选：

1. **管理后台页面**：登录后台 → 系统 → 词典管理 → 「重建索引」（需要 `dict:reindex` 权限）；
2. **管理 API**（先登录拿 Access Token，验证码回显需要 `NEWMUSIC_ADMIN_CAPTCHA_ECHO=true`）：

   ```powershell
   curl.exe -X POST "http://localhost:8081/api/v1/admin/dict/reindex" `
     -H "Authorization: Bearer <后台 Access Token>"
   ```

3. **重启后端**并让 schemaVersion 变化（见 7.1），启动自检会自动重建。

重建前可以先看状态：`GET /api/v1/admin/dict/status`，其中
`effectiveStatus` / `notice` 会说明「已连通（无 IK 插件）」等情况。

### 7.1 什么时候要改 schemaVersion

改了 `src/main/resources/es/music_song-index*.json`（字段、分析器、分词器）就必须**同时**：

1. 把两个 mapping 文件里的 `mappings._meta.schemaVersion` +1；
2. 把 `DictProperties.Es.schemaVersion` 的默认值 +1；
3. 把 `application.yml` 里 `DICT_ES_SCHEMA_VERSION` 的默认值 +1。

否则旧索引不会重建，新字段/新分析器对存量数据不生效——表现是「代码明明改了，搜出来还是老样子」。
本次为了修「单字中文搜不到」，schemaVersion 已从 3 升到 **4**。

---

## 8. 常见问题

| 现象 | 原因 / 处理 |
| --- | --- |
| `curl: (52) Empty reply` 或 `401 security_exception` | 口令错 / 没带 `-u`。先用 `curl.exe -u elastic:<密码> http://127.0.0.1:9200` 确认；后端侧检查 `DICT_ES_PASSWORD` |
| `Failed to connect to 127.0.0.1 port 9200` | ES 没起，或 `network.host` 不是 localhost。看 ES 窗口是否停在 `started` |
| 后端起来了，但搜索 `engine=mysql` | `SEARCH_ENGINE` 没设成 `es`，或 `DICT_ES_ENABLED=false`，或 ES 不可达（此时 `degraded=true` 属于预期降级） |
| `/dict/status` 显示「已连通（无 IK 插件）」 | 正常：索引用 lite 映射。主词典词条仍影响应用层改写，改完要重建索引 |
| 搜单字「天」0 条 | 索引是旧 schemaVersion（<4）。看启动日志是否有重建；没有就手工重建，或重启后端 |
| 切到歌手/专辑/歌单 Tab 永远空 | 给这几类文档错误地套了歌曲筛选（`copyrightStatus` 等）。当前实现已按 `docType` 分支；若再出现，先 `curl` 看该文档的 `_source` 有没有这些字段 |
| 翻页重复 / 永远翻不到末页 | 游标里的分被截断成整数，`search_after` 与 ES 浮点 `_score` 对不上。当前实现（schemaVersion 4 的代码）游标带精确 `_score`；若从旧版本升级，重启即可 |
| 响应里的中文是 `??` / `ï¿½` | 请求侧编码问题（Git Bash 的 GBK 参数）不是 ES 的问题；用 Node/浏览器验证。ES 写入侧已强制 UTF-8（`StringHttpMessageConverter` 置顶），`verify-es.mjs` 会断言 `_source.name` 与请求完全一致 |
| 启动时报 `NoClassDefFoundError: com/example/newmusic/...` | 后端运行期间又跑了 `mvn compile`/`mvn test`。停后端 → 重新编译 → 再启动 |
| `Port 8081 was already in use` | 上次的 JVM 没退干净：`Get-NetTCPConnection -LocalPort 8081 -State Listen \| Select -Expand OwningProcess` 找到 PID 后 `Stop-Process -Id <PID> -Force`，再启动 |
| 控制台日志中文乱码 | JVM 以 GBK 写日志，用 ASCII 锚点（`EsIndexInitializer`、`schemaVersion=`）检索；不要按中文 grep |
| ES 启动报内存不足 | `config\jvm.options` 里调小 `-Xms/-Xmx`（演示 512m~1g 足够），再重启 |

---

## 9. 安全与共享实例注意事项

- **口令只走环境变量**：`MYSQL_PASSWORD` / `DICT_ES_PASSWORD`。不要把值写进 `application.yml`、
  MD 文档、脚本或提交记录；本文全部用 `<你的ES密码>` 占位。
- **ES 侧只碰本项目的东西**：写入与查询都走别名 `music_song`；重建只删除 `music_song_v*`；
  `verify-es.mjs` 会断言「非 `music_song*` 索引集合前后一致」。
  不要在 Kibana/curl 里对本机 ES 执行 `DELETE _all`、`PUT _cluster/settings` 之类的全局操作。
- **MySQL 只碰 `newmusic.`**：所有 SQL 显式限定库名/表名；不做全库级 DDL/DML。
- **Redis 只碰 `ml:` 前缀**：不执行 `FLUSHDB`/`FLUSHALL`/`KEYS *`（本机 redis-cli 3.0 的 `--scan`
  会静默返回空，用 `KEYS` 也别用来清理，只用来核对前后数量）。
- **生产环境**：打开 HTTPS（`xpack.security.http.ssl.enabled: true`，`DICT_ES_URL` 改 `https://`）、
  建专用账号并最小授权、`number_of_replicas ≥ 1`、配置快照仓库；
  演示用的 `NEWMUSIC_ADMIN_CAPTCHA_ECHO=true` 与 `ADMIN_JWT_COOKIE_SECURE=false` 必须改掉。

---

## 10. 关闭

1. 后端窗口 `Ctrl+C`（或 `TaskStop` 掉 `mvn spring-boot:run`），确认 8081/8082 没有残留监听：
   ```powershell
   Get-NetTCPConnection -LocalPort 8081,8082 -State Listen -ErrorAction SilentlyContinue
   ```
2. ES 窗口 `Ctrl+C`，等它打印 `stopped`；确认 9200 已释放：
   ```powershell
   Get-NetTCPConnection -LocalPort 9200 -State Listen -ErrorAction SilentlyContinue
   ```
3. **不要**顺手停 MySQL 3306 / Redis 6379：它们是共享实例，别的项目还在用。

---

## 11. 相关文档

- `README.md`：完整启动步骤、环境变量表、验收脚本清单、常见问题；
- `docs/系分/06-搜索.md`：检索/拼音/联想/筛选排序/深分页/限流的系分与验收表；
- `docs/PRD/20-模块11-IK分词.md`：词典、改写、ES 降级与重建的产品口径；
- `docs/PRD/15-模块06-搜索与AI搜索.md`：搜索相关的 PRD 验收项；
- `scripts/verify-es.mjs`：本文所有 ES 断言的实现（可当作可执行的接入说明）。
