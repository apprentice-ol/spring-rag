# 部署与升级指南（本地 → 服务器）

> 2026-10-11 文档整理：本文由两份文档合并而成，原文完整保留，各部分以 `---` 分隔。
>
> - 第一部分 · 打包部署指南：本地打包、首次部署、日常更新、常见问题与 OpenObserve OOM 排查
> - 第二部分 · 升级部署手册：面向已有数据的服务器，含语料迁移决策、八步升级、回滚与全库替换
>
> 环境词表（四词制）：`local`（开发·IDE 裸跑）· `docker`（docker 开发·本机 compose）· `dev`（测试·服务器，本文主场景）· `prod`（正式·预留）；与 `pipeline.yml` 的 `deploy.targets` 一一对应。

---

<!-- 原文件：docs/deploy.md -->

# 打包部署指南（本地 → 服务器）

面向「我在本地开发，要部署到服务器」的完整流程。端口表、compose 差异、日常运维见 [docker/README.md](../docker/README.md)。

- 服务器以 `root@<SERVER_IP>`、项目目录 `/srv/www/springai-rag` 为例（按实际替换；IP/账号一律放 `.env`，不进文档与 git）
- 服务器要求：Docker + Docker Compose、2核4G 起步、放行端口 9081（app）/ 5080（OO）/ 9000（RustFS）

---

## 一、本地打包（每次部署都做）

```bash
cd /d/04_projects/20_springai-rag
bash scripts/build-local.sh
```

三步自动完成：前端 `npm run build` → dist 拷进 jar 的 `static/` → `mvn package`。产物：

```
platform-bootstrap/target/platform-bootstrap-0.0.1-SNAPSHOT.jar   app.jar ~3.2MB
   ← **全部业务代码**（maven-shade 已把 14 个 platform-* 模块合并进来）+ 前端 static
platform-bootstrap/target/lib/                          380 个 jar ~252MB（**仅第三方依赖**）
```

> **部署只需传这 1 个 jar**（约 3.2MB）。`lib/` 只有第三方依赖，pom 不变就不用重传。
> 服务器上若残留旧的 `lib/platform-*.jar` 也不影响——Dockerfile 的 classpath 已把 `app.jar` 放在前面。

> 前置：JDK 21（`D:/03_developtools/02_jdk/ms-21.0.11`）+ Node.js。产物路径在 build-local.sh 内写死，换机器改脚本头部。

## 二、首次部署（服务器从零开始）

### 1. 服务器拉代码 + 放置 deb

```bash
# 服务器上
cd /opt && git clone <你的仓库地址> spring-rag
cd spring-rag
# 唯一手动文件：pg-search.deb（~68MB，不入 git）
#   按 docker/postgres/README.md 从 ParadeDB GitHub release v0.25.0 下载，改名放 docker/postgres/
#   （首次 up --build 时构建 rag-postgres 镜像用；镜像建成后不再需要）
```

### 2. 配置 .env

```bash
cp .env.example .env && vim .env
```

| 变量 | 必填 | 说明 |
|---|---|---|
| `SERVER_IP` | ✅ | 服务器外网 IP（拼 OO/RustFS 公网地址用） |
| `DEEPSEEK_API_KEY` | ✅ | 对话模型（compose 对必填项 fail-fast，缺了启动即报错） |
| `BAILIAN_API_KEY` | ✅ | embedding / rerank / VLM 共用 |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` 等 8 个账号变量 | ❌ | 留空回退默认值；要改密码才填（见 .env.example） |
| `LANGFUSE_AUTH` 等 | ❌ | 服务器版用不到（本地 collector 专用） |

### 3. 上传构建产物（本地执行）

```bash
# 本地 Git Bash
cd /d/04_projects/20_springai-rag
scp platform-bootstrap/target/platform-bootstrap-0.0.1-SNAPSHOT.jar root@<SERVER_IP>:/srv/www/springai-rag/
scp -r platform-bootstrap/target/lib                            root@<SERVER_IP>:/srv/www/springai-rag/
```

> 服务器上 docker/（含 compose 与 Dockerfile） / init.sql 都来自 git clone，无需 scp；**只有 jar 和 lib/ 是构建产物**（不入 git）。

### 4. 启动

```bash
# 服务器上
cd /srv/www/springai-rag
docker compose --project-directory . -f docker/docker-compose.dev.yml up -d --build
docker compose --project-directory . -f docker/docker-compose.dev.yml logs -f app   # 看到 Started + 无 ERROR 即成功
```

首次会构建两个镜像（rag-postgres 装.deb 约 1-2 分钟；app 拷 251MB lib），并自动建库建表（`init.sql` 幂等，含 sa_* 业务表与 pgvector 兜底）。

### 5. Nginx 对外入口（80 端口反代，2026-10-12 重装后重建版）

服务器对外的统一入口是 Nginx 80，两段反代都必须配（配置文件已入库：`docker/nginx/springai-rag.conf`，直接拷贝到 `/etc/nginx/conf.d/` 即可，安装 CentOS 后默认的 `default.conf` 需删除——原生 server 段与 `default_server` 冲突）：

```bash
scp docker/nginx/springai-rag.conf root@<SERVER_IP>:/etc/nginx/conf.d/
ssh root@<SERVER_IP> 'rm -f /etc/nginx/conf.d/default.conf && nginx -t && systemctl enable --now nginx'
```

| 路径 | 反代目标 | 用途 | 关键配置 |
|---|---|---|---|
| `/api/rag/` | `127.0.0.1:9081`（app 容器） | 前端页面 + API + SSE 流式对话 | `proxy_buffering off`（SSE 必须）+ HTTP/1.1 + 600s 读写超时 + `client_max_body_size 200m`（文档上传） |
| `/storage/` | `127.0.0.1:9000`（RustFS 容器） | 图片等对象存储公网地址 `http://<IP>/storage/customer-platform-assets/{key}` | 端口随 `.env` 的 `RUSTFS_PORT` 同步改 |

不配 `/storage/`：文档入库正常，但前端图片不显示；不配 `/api/rag/`：`http://<IP>/` 打不开前端。用别的端口方案就改 `.env` 的 `RUSTFS_PORT` 并同步 `RAG_STORAGE_S3_PUBLIC_URL`。

> 重装系统后 nginx 配置会随盘丢失，2026-10-12 那次重装即靠本仓库归档文件恢复。

### 6. OpenObserve 创建 viewer 账号（链路跳转依赖）

```text
浏览器开 http://<SERVER_IP>:5080 → 用 .env 的 ZO_ROOT_USER_EMAIL / ZO_ROOT_USER_PASSWORD 登录
→ IAM → 用户 → 新建一个「只看链路」的账号，邮箱/密码 = .env 的 OO_VIEWER_EMAIL / OO_VIEWER_PASSWORD
```

前端「链路追踪」页与对话气泡「链路」按钮展示这个 viewer 账号，登录后可看 trace/logs。

> ⚠️ 安全：仓库为公开时，compose 里的账号默认值等于公开。服务器 `.env` 必须给 `ZO_ROOT_*` 与
> `OO_VIEWER_*` 设置强密码；**OO 管理员密码在首次初始化后改 .env 不生效**（已写入数据卷），
> 需登录 OO UI → IAM → 用户 → 修改密码。

### 7. 部署验证清单

```bash
# ① 健康检查
curl http://localhost:9081/api/rag/ping/health          # {"status":"UP"}

# ② 前端页面（浏览器）
#    http://<SERVER_IP>:9081/api/rag/                   # 对话页
#    http://<SERVER_IP>:9081/api/rag/doc.html           # Knife4j

# ③ 发一条会走检索的对话（react 约 10-20s，等流结束）
curl -s -N -X POST "http://localhost:9081/api/rag/chat/stream?question=test&conversationId=deploy-check&agent=react" | grep -E "event:(trace|meta)"

# ④ 轨迹落库（trace_id 有值 + message_id 非空）
docker exec rag-postgres psql -U postgres -d springai_rag \
  -c "SELECT id, message_id, left(trace_id,8) trace8, paradigm, create_time FROM sa_agent_trace ORDER BY id DESC LIMIT 1;"

# ⑤ 可观测性走的是服务器版（不应再出现连 localhost:3000 的报错）
docker compose --project-directory . -f docker/docker-compose.dev.yml logs app 2>&1 | grep -c "localhost:3000"   # 期望 0
```

再在浏览器前端发一条消息：气泡下方应出现「轨迹 / traceId / 链路」操作条，点「链路」能打开 OpenObserve 该次请求的完整 span 树。

---

## 三、日常更新（只换业务代码）

> **服务器上已有数据、且本次涉及表结构或知识库语料变更时，走本文下半部分「升级部署手册」**
> （含结构对齐、语料迁移、缓存失效、回滚）。下面这节只适用于纯业务代码更新。

```bash
# ① 本地打包（产物位于 platform-bootstrap/target/：瘦 jar + lib/）
bash scripts/build-local.sh

# ② 传这一个 jar（3.2MB，含全部业务代码 + 前端）
scp platform-bootstrap/target/platform-bootstrap-0.0.1-SNAPSHOT.jar root@<SERVER_IP>:/srv/www/springai-rag/platform-bootstrap/target/

# ③ 服务器重建（--build 必须，否则复用旧镜像跑旧 jar）
ssh root@<SERVER_IP> "cd /srv/www/springai-rag && docker compose --project-directory . -f docker/docker-compose.dev.yml up -d --build app"

# ④ 验证（同上 7-①③）
```

> 历史坑（2026-09-20 修）：16 模块拆分后业务代码在 `lib/platform-*.jar`，只传薄 jar = 跑旧代码。
> 现在 pom 用 maven-shade 把模块合并进 app.jar，且 Dockerfile classpath 改成 `app.jar:lib/*`（app 优先），
> 所以**一个 jar 就是全部**。

**pom 变更过时**（新增/升级依赖）：还要把 `platform-bootstrap/target/lib/` 全量传到服务器 `lib/`（约 252MB）。
判据：本地 `ls platform-bootstrap/target/lib | wc -l`（当前 380）与服务器 `ls /srv/www/springai-rag/lib | wc -l` 不一致。

**compose / Dockerfile / SQL 有变更时**：服务器 `git pull`（或 scp 对应文件）后再 up。

**compose 位置变更（2026-10-11，一次性）**：compose 文件已从项目根移入 `docker/`（git 重命名，`git pull` 自动完成）。
此后所有 compose 命令必须带 `--project-directory .`（在项目根执行）——缺了它项目名会推断成 `docker`，
数据卷名从 `<目录名>_pg-data` 漂移成 `docker_pg-data`（等于挂空库启动）。

## 四、常见问题

| 现象 | 原因与处理 |
|---|---|
| 启动报 `请在 .env 配置 DEEPSEEK_API_KEY` | .env 缺必填项，按第二节表格补 |
| postgres 镜像构建 `COPY pg-search.deb failed` | deb 没放 / 名字不对，见 docker/postgres/README.md |
| 前端某功能新增列后旧数据异常（如 Agent 轨迹无新记录） | 表缺列：init.sql 自动迁移未生效时手动补幂等 ALTER（实例见 docker/README.md「表结构迁移」） |
| 日志刷 `Failed to connect to localhost:3000` | 服务器 compose 未含 `TELEMETRY_CONFIG`（旧版文件），git pull 最新 compose |
| 图片不显示 | Nginx /storage 反代没配（见二-5） |
| OpenObserve 频繁 OOM 宕机（Exit 137） | 4G 无 swap + WAL 重放内存峰值 + 无 restart 策略，见下方「OpenObserve OOM 排查与修复」 |

### OpenObserve 频繁 OOM 宕机：排查与修复（2026-08-20）

**现象**：OO 周期性挂掉，`docker ps -a` 显示 `Exited (137)`；`docker inspect` 输出
`RestartCount=0 ExitCode=137 OOMKilled=false RestartPolicy=no`；启动日志在重放 WAL 后 5~15 秒内戛然而止。

**证据链**：
- `dmesg | grep -i "killed process|out of memory"` → `Out of memory: Kill process ... (openobserve) score 385 ... anon-rss:1491680kB`
- `free -h` → 总内存 3.7G、Swap 0、已用 1.9G（宿主机还跑 mysql / redis / nginx / postfix / python）
- 启动日志：`replay wal file ... json_size: 367MB, arrow_size: 532MB` → 启动时把积压 WAL 全量读进内存
  （官方 issue [openobserve#5023](https://github.com/openobserve/openobserve/issues/5023)；
  重放 OOM 已由 [PR #5021](https://github.com/openobserve/openobserve/pull/5021) 修复）
- `OOMKilled=false` 的原因：系统级 OOM 不走容器 cgroup，Docker 不标记；
  `RestartPolicy=no` 导致杀完不自动拉起

**根因**：4G 无 swap 的宿主机同时跑 mysql + redis + nginx + postfix + RAG 全套；OO 启动重放 WAL 时
RSS 冲到 ~1.5G 被内核 OOM killer 杀掉；被杀 → WAL 无法消化 → 下次启动积压更大 → 峰值更高 → 恶性循环。

**已实施修复**（docker/docker-compose.dev.yml 的 `openobserve` 段）：
- `restart: unless-stopped`、`mem_limit: 2g`
- `ZO_MEM_TABLE_MAX_SIZE=512`（默认 = 总内存 50% ≈ 1.85G，4G 机器必须显式压小）
- `ZO_MAX_FILE_SIZE_IN_MEMORY=128`（默认 256MB）
- `ZO_MAX_FILE_SIZE_ON_DISK=64`（单个 WAL 文件上限）
- `ZO_MAX_FILE_RETENTION_TIME=300`（默认 600s，WAL/MemTable 更早落盘）
- `ZO_FILE_PUSH_INTERVAL=30`（默认 60s，WAL 转 parquet 更频繁，减少积压）
- `ZO_COMPACT_FAST_MODE=false`（官方：关闭快速合并可减少约 50% 合并内存）

参数含义以官方文档为准：<https://openobserve.ai/docs/administration/configuration/environment-variables>

**救活步骤**：

```bash
# 本地传改好的 compose
scp docker/docker-compose.dev.yml root@<SERVER_IP>:/srv/www/springai-rag/docker/

# 服务器：腾内存 → 重建 OO（配置变了会自动 recreate）→ 等 WAL 消化完 → 恢复
docker stop rag-app
systemctl stop mysqld postfix
docker compose --project-directory . -f docker/docker-compose.dev.yml up -d openobserve
docker logs -f rag-openobserve
docker stats rag-openobserve        # 稳定后应 <1G
docker compose --project-directory . -f docker/docker-compose.dev.yml up -d app
systemctl start mysqld postfix
```

**加 swap（强烈建议，防下次启动被瞬杀）**：

```bash
fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
echo '/swapfile none swap sw 0 0' >> /etc/fstab
```

**长期建议**：升 8G 或把 OO 独立部署；跑批时 `TELEMETRY_STEP_LOG_LEVEL=OFF`、采样率降到 0.1，
可显著减少 WAL 写入源（代价：OO 里 step 输入/输出明文缺失，span 本身仍在）。

---

<!-- 原文件：docs/deploy-upgrade.md -->

# 升级部署手册（本地 → 已有数据的服务器）

面向「服务器上已有数据、要更新到新版本」的场景。首次部署见本文上半部分「打包部署指南」，日常换代码见其第三节。

> 与旧版的关键差别：本版新增了**父子分块**（`sa_chunk_parent`）与**评测轨迹拆表**（`sa_eval_item_trace`），
> 且知识库语料的 `doc_id` 是**随机 UUID**——这决定了语料必须成组迁移，不能只搬一半。

---

## 0. 先做决策：服务器上的知识库语料怎么办？

| 方案 | 做法 | 代价 | 何时选 |
|---|---|---|---|
| **A. 搬本地语料**（推荐） | 本地 `deploy-prepare.py` 导出 → 服务器 `--corpus` + `--storage` 导入 | ~106MB + ~27MB 传输，导入几分钟 | 想让服务器和本地**完全一致**；评测集要能直接跑 |
| B. 服务器重新入库 | 走文档上传/入库链，**必须显式传原 doc_id** | 需一份「文件 ↔ 原 doc_id」映射；传错 id 则评测引用悬空 | 本地语料不想要，服务器有自己的源文件 |
| C. 不动语料 | 只对齐表结构，不换数据 | 服务器继续用旧分块（无父子分块效果） | 只想更新应用功能 |

**注意：分块在库里，原始文件在对象存储**
`sa_document.source_location` 指向 RustFS 的 S3 key（`customer-platform-kb` bucket），文档里的图片在
`customer-platform-assets`。**只搬库不搬存储 → 检索正常，但文档预览与图片 404**。所以要带 `--storage`。

**为什么不能"只重灌分块"**：`IngestionEngineService:84` —— 不传 `docId` 就用随机 `taskId` 当 `docId`。
重灌 → UUID 全变 → `sa_eval_item.expected_doc_ids` 的 **1032 条引用全部悬空**（评测集报废，
且评测详情页按 doc_id 回表拿文档名会全空）。

---

## 1. 本地准备

```bash
cd /d/04_projects/20_springai-rag

# ① 打包（前端 + 后端）
bash scripts/build-local.sh

# ② 校验语料 + 生成结构对齐脚本 + 导出语料 dump
python scripts/deploy-prepare.py
```

`deploy-prepare.py` 做五件事，任何一项不通过都会**报错退出**（别硬上）：

1. **语料关联自检**：父块→文档、子块→文档、评测期望文档→文档，三条 join 必须 100% 对上
2. **集合重名检查**：重名会让服务器启动时唯一索引创建失败（`init.sql` 的 `CREATE UNIQUE INDEX` 建在已存在的表上）
3. **生成 `scripts/ensure-schema.sql`**：239 条幂等 ALTER，专治「旧库缺列」
4. **导出 `data/deploy/corpus-<日期>.dump`** + `manifest-<日期>.txt`（行数清单）
5. **导出 `data/deploy/rustfs-<日期>.tgz`**（对象存储两个 bucket；`--skip-storage` 可跳过）

想跳过语料只更新结构：`python scripts/deploy-prepare.py --skip-corpus`。

## 2. 上传

```bash
# 构建产物（必传）
scp platform-bootstrap/target/platform-bootstrap-0.0.1-SNAPSHOT.jar root@<SERVER_IP>:/srv/www/springai-rag/
scp -r platform-bootstrap/target/lib                            root@<SERVER_IP>:/srv/www/springai-rag/   # pom 没动过可跳过

# 脚本与结构补丁（会随 git pull 到，但先传更稳）
scp scripts/ensure-schema.sql scripts/deploy-upgrade.sh root@<SERVER_IP>:/srv/www/springai-rag/scripts/

# 语料 + 对象存储（选方案 A 时）
scp data/deploy/corpus-<日期>.dump data/deploy/rustfs-<日期>.tgz     root@<SERVER_IP>:/srv/www/springai-rag/data/deploy/
```

> 服务器端 compose / SQL / scripts 都来自 git clone，`git pull` 亦可（见 deploy.md 第三节）。

## 3. 服务器执行

```bash
cd /srv/www/springai-rag

# 先预演（只预检，不写数据）
bash scripts/deploy-upgrade.sh --dry-run

# 正式执行（方案 A：连带语料一起换）
bash scripts/deploy-upgrade.sh   --corpus  data/deploy/corpus-<日期>.dump   --storage data/deploy/rustfs-<日期>.tgz

# 方案 C（只更新应用，不动语料）
bash scripts/deploy-upgrade.sh
```

脚本八步（每步都有输出，出错即停）：

| 步 | 做什么 | 说明 |
|---|---|---|
| 1 | **全库备份** | 落到 `data/backup/pre-upgrade-<时间戳>.dump`，回滚用 |
| 2 | 预检集合重名 | 有重名 → 直接退出并给出待处理清单 |
| 3 | 结构对齐 | 应用 `ensure-schema.sql`（幂等） |
| 4 | 导入语料（可选） | TRUNCATE 6 表 → `pg_restore --data-only` → **重置自增序列** |
| 5 | 导入对象存储（可选） | 解包 `rustfs-*.tgz` 到 `rag-rustfs:/data`（覆盖式，保留服务器原有文件） |
| 6 | 清缓存 | `sa_cache_answer` + Redis `docver*`（语料换了，旧答案必须失效） |
| 7 | 重建重启 app | `docker compose --project-directory . -f docker/docker-compose.dev.yml up -d --build app` |
| 8 | 校验 | health + 行数 + 检索冒烟 |

**第 4 步的两个关键点**（都是踩过的坑）：
- **重置序列**：`--data-only` 不搬序列值，不重置的话下一次文档入库会主键冲突
- **`--purge-eval-history`**：换了 `sa_eval_item` 后，旧运行记录的 `item_id` 会对不上（逐题明细错位）。
  要保留历史运行就接受错位，不要就加这个参数一起清空

## 4. 验证清单

```bash
# ① 健康
curl -s http://localhost:9081/api/rag/ping/health

# ② 行数与本地清单逐项对照（方案 A 时）
cat data/deploy/manifest-<日期>.txt
docker exec rag-postgres psql -U postgres -d springai_rag -c "
SELECT (SELECT count(*) FROM sa_document) 文档,
       (SELECT count(*) FROM sa_chunk_parent) 父块,
       (SELECT count(*) FROM spring_ai_store_vector) 向量,
       (SELECT count(*) FROM sa_eval_item) 评测条目;"

# ③ 检索冒烟（应出现 citations 事件）
curl -s -N -X POST "http://localhost:9081/api/rag/chat/stream?question=test&conversationId=deploy-check" \
  | grep -E "^event:(citations|meta)"

# ④ 原始文件到位（预览/图片不 404）
docker exec rag-rustfs sh -c "for b in customer-platform-kb customer-platform-assets; do echo -n \"$b: \"; find /data/$b -type f | wc -l; done"
#    本地参考：kb 约 1.3k 文件、assets 若干（与 rustfs-<日期>.tgz 内文件数一致）

# ⑤ 父子分块生效（返回的引用应带父块内容，不是碎片）
#    前端提问一条知识库问题，看回答是否连贯、角标是否正常

# ⑥ 评测：看板能打开、能跑一个小规模 run
```

## 5. 回滚

```bash
# 数据回滚（第 1 步的备份）
docker exec -i rag-postgres pg_restore -U postgres -d springai_rag --clean --if-exists \
  < data/backup/pre-upgrade-<时间戳>.dump

# 应用回滚：把上一版 jar 传回去再 up -d --build app
docker compose --project-directory . -f docker/docker-compose.dev.yml up -d --build app
```

---

## 6. 本次升级已知的坑（都已在工具里处理）

| 现象 | 原因 | 处置 |
|---|---|---|
| 评测相关接口报 `column "difficulty" does not exist` | `sa_eval_item` 的 5 个 IRT 列当年只进了手工脚本、没写进 `init.sql` 的 ALTER 区 | 第 3 步的 `ensure-schema.sql` 已覆盖 |
| 应用**启动即失败**，日志有 unique 冲突 | 服务器存在重名集合，`CREATE UNIQUE INDEX uk_sa_doc_collection_name` 失败（`init.sql` 每次启动都跑） | 第 2 步预检会拦下；改名或逻辑删除后重跑 |
| 导入语料后新文档入库报主键冲突 | `--data-only` 不搬序列 | 第 4 步已自动 `setval` |
| 换了语料但回答还是旧的 | `sa_cache_answer` 语义缓存 / Redis docver 没清 | 第 6 步已自动清 |
| 后台改了 prompt 却不生效 | 服务器**绑定了能力包**时运行时读 DB 版本，classpath 改动被忽略 | 查 `SELECT count(*) FROM sa_prompt_binding;` 非 0 即已绑定 → 需在管理后台重新 release 或解绑 |
| 新库没有父子分块效果 | `sa_chunk_parent` 是空的，存量向量无 `parent_key` | 走方案 A（搬本地语料）或按原 doc_id 重灌 |

> 旧导入器时代的三个一次性数据库脚本（孪生副本清理 / IRT 列迁移 / 重灌重置）已于 2026-10-11 删除——
> 其职能由「重灌 + `deploy-upgrade.sh` 的语料替换 / 全库清空」流程取代（git 历史可找回）。


---

## 附：方案 D —— 全库替换（一键换库）

```bash
# 本地
python scripts/deploy-prepare.py                 # 生成 ensure-schema.sql + 清单
# 另需导出整库数据包（排除运行时状态表）：
docker exec -e PGPASSWORD=$POSTGRES_PASSWORD ragent-postgres sh -c "pg_dump -Fc --data-only --schema=public   -U postgres -d springai_rag -T ops_engine_session -T ops_engine_slots -T ops_agent_session   -T spring_ai_chat_memory -T 'bak_*' -f /tmp/fulldb.dump"
docker cp ragent-postgres:/tmp/fulldb.dump data/deploy/fulldb-<日期>.dump

# 服务器
bash scripts/deploy-upgrade.sh --full-restore --dump     data/deploy/fulldb-<日期>.dump   --storage  data/deploy/rustfs-<日期>.tgz   --manifest data/deploy/manifest-full-<日期>.txt
```

步骤：**备份 → 清空全库 → 建缺失表 → 补缺失列 → 导入 → 重置序列 → 导入对象存储 → 清 Redis → 重启 → 行数核对**。

两个关键点（否则必踩）：
- **结构对齐必须在清空之前**：清空只删数据、**不补列**；服务器旧表缺列（如 `sa_eval_item` 的 IRT 列）
  时，导入数据会直接报 `column does not exist`
- **序列必须重置**：`--data-only` 不搬序列值，不重置则下次文档入库主键冲突

数据包内容（本地实测）：25 张表 / 52,623 行 / 257.7 MB；**排除**运行时状态表
（`ops_engine_session/slots`、`ops_agent_session`——本地那 90MB 是历史残留）、本地备份表（`bak_*`）、
框架表（`spring_ai_chat_memory`）。对象存储包（`customer-platform-kb` + `customer-platform-assets`）26.7 MB / 1301 文件。

> 注意：全库替换会**清掉服务器上的 prompt 版本与能力包绑定**（`sa_prompt_*`），运行时回落到 classpath。
> 若服务器自己改过 prompt，先单独 dump 那几张表。
