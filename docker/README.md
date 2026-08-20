# Docker 部署总览（唯一入口文档）

本地开发、服务器部署、端口、账号、常见运维都从这里查。
两个 compose 有意保持独立（本地含 collector/langfuse，服务器直连 OpenObserve），公共服务定义存在重复属预期——服务器命令 `docker compose -f docker-compose.server.yml up -d --build` 长期不变。

## 文件地图

```
docker/
├── README.md                                # 本文档
├── app/Dockerfile                           # 应用运行时镜像（build context=项目根，COPY jar + lib/）
├── postgres/Dockerfile                      # pg16 + pgvector + pg_search 自定义镜像（BM25 关键词检索）
├── postgres/01-pg-search.sql                # 首启自动建扩展
├── postgres/README.md                       # pg-search.deb（~68MB，不入 git）下载放置说明 ⚠️ 构建前置
├── postgres/pg-search.deb                   # 手动放置（gitignore *.deb）
├── otel-collector/otel-collector-config.yaml # 本地 collector：OTLP 扇出 OpenObserve + Langfuse
└── langfuse/docker-compose.langfuse.yml      # 本地可选：Langfuse 自托管（独立 compose project）

项目根
├── docker-compose.yml                       # 本地开发编排（postgres/redis/rustfs/openobserve/otel-collector/app）
├── docker-compose.server.yml                # 服务器编排（无 collector/langfuse，app 直连 OpenObserve）
├── .env / .env.example                      # 凭据与账号（三方共用：compose 自动注入 / Spring config.import / 本地裸跑）
├── .dockerignore                            # app 镜像 build context 排除项（按根目录生效）
└── build-local.sh                           # 构建产物：前端 dist 进 jar → 瘦 jar + lib/（rag-core/target/）
```

## 端口一览

| 端口 | 服务 | 说明 |
|---|---|---|
| 9081 | app | 前端 + API（context-path `/api/rag`；Knife4j `doc.html`） |
| 5080 | openobserve | OO Web UI + OTLP/日志 API |
| 9000 | rustfs | S3 对象存储（服务器经 Nginx `/storage/` 反代则不开宿主端口） |
| 4318 | otel-collector | OTLP/HTTP 接收（仅本地 compose） |
| 3000 | langfuse-web | Langfuse UI（仅本地可选） |
| 9090 | langfuse-minio | Langfuse 附件 console（仅本地可选） |

## 本地启动

```bash
# ① 首次前置：见 docker/postgres/README.md 下载 pg-search.deb 放到 docker/postgres/
# ② 凭据：cp .env.example .env，填 DEEPSEEK_API_KEY / BAILIAN_API_KEY（LANGFUSE_AUTH 可选）
# ③ 构建产物并拷到项目根（app 镜像的 COPY 在根目录找 jar + lib；jar/lib 均被 gitignore）
bash build-local.sh                                   # 前置 JDK 21 + Node，产出 rag-core/target/{jar, lib/}
cp rag-core/target/rag-core-0.0.1-SNAPSHOT.jar .
cp -r rag-core/target/lib .
# ④ 起整套
docker compose up -d --build
```

- 访问 `http://localhost:9081/api/rag/`（前端已打进 jar）；Knife4j：`/api/rag/doc.html`
- Langfuse（可选）：`docker compose -f docker/langfuse/docker-compose.langfuse.yml up -d`
  → `localhost:3000` 首启注册 admin → 项目设置拿 pk/sk → 填 `.env` 的 `LANGFUSE_AUTH`（base64(pk:sk)）
- 本地 IDEA 裸跑 app：collector 在 `localhost:4318`，根 `.env` 被自动读取（application.yaml `spring.config.import`）

## 服务器部署

> 逐步操作手册（含 Nginx 反代、OO viewer 账号、验证清单、常见问题）见 **[../docs/deploy.md](../docs/deploy.md)**，本节为速览。

```bash
# ① 本地构建产物
bash build-local.sh          # → rag-core/target/{rag-core-0.0.1-SNAPSHOT.jar, lib/}

# ② 上传（jar + lib + compose + docker/ 目录；服务器首次需 git clone 或整目录 scp）
scp rag-core/target/rag-core-0.0.1-SNAPSHOT.jar  <服务器>:/项目根/
scp -r rag-core/target/lib                        <服务器>:/项目根/
scp docker-compose.server.yml                     <服务器>:/项目根/
scp -r docker                                     <服务器>:/项目根/   # 至少 app/ 与 postgres/ 子目录

# ③ 服务器 .env（至少必填；账号类留空回退默认值）
#    SERVER_IP=服务器外网IP
#    DEEPSEEK_API_KEY=... / BAILIAN_API_KEY=...
#    可选覆盖：POSTGRES_USER/PASSWORD、RUSTFS_*、ZO_ROOT_*、OO_VIEWER_*（见 .env.example）

# ④ 启动
docker compose -f docker-compose.server.yml up -d --build
```

**部署后必做**：
1. OpenObserve 创建 viewer 账号：登录 `http://<IP>:5080`（管理员账号见 .env 的 `ZO_ROOT_*`）→ IAM → 用户
   → 默认展示 root 账号（`ZO_ROOT_USER_EMAIL/PASSWORD`；如需单独展示账号可在 `.env` 配 `OO_VIEWER_*` 覆盖）。前端「链路追踪」页与对话气泡「链路」按钮用它登录。
2. 安全组放行 9081（app）/ 5080（OO）/ 9000（rustfs，或用 Nginx `/storage/` 反代后不开）。

**升级业务代码**：重新 `build-local.sh` → scp 新 jar（依赖变了才传 lib/）→ `up -d --build`。
服务器版 app 经 `TELEMETRY_CONFIG=application-telemetry-server.yaml` 直连 OpenObserve（无 collector/langfuse），无需额外配置。

## 两个 compose 的差异（改一处记得看另一处）

| 差异点 | 本地 docker-compose.yml | 服务器 docker-compose.server.yml |
|---|---|---|
| otel-collector | ✅ 扇出 OO + Langfuse | ❌ app 直连 OO（`TELEMETRY_CONFIG`） |
| langfuse | 可选另起（独立 compose） | 无 |
| RustFS 公网地址 | `localhost:9000` | `http://${SERVER_IP}/storage`（Nginx 反代） |
| 端口变量 | 固定 | `RUSTFS_PORT` / `APP_PORT` 可覆盖 |
| 数据挂载 | named volumes | 另挂 `./data`（LiveRAG parquet 缓存） |

## 常见运维

```bash
# 应用日志
docker compose -f docker-compose.server.yml logs -f app        # 本地去掉 -f 参数

# 进 PostgreSQL
docker exec -it rag-postgres psql -U postgres -d springai_rag

# 重启单个服务
docker compose -f docker-compose.server.yml restart app
```

**OpenObserve 频繁 OOM（Exit 137）**：4G 无 swap 宿主机 + WAL 重放内存峰值 + 无 restart 策略导致，
排查证据、compose 内存参数与救活步骤见 [../docs/deploy.md](../docs/deploy.md)「OpenObserve OOM 排查与修复」。

**表结构迁移（重要教训）**：`init.sql` 每次启动都会执行（`spring.sql.init.mode=always`），新增列必须写成幂等形式：

```sql
ALTER TABLE sa_agent_trace ADD COLUMN IF NOT EXISTS trace_id VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_sa_agent_trace_msg ON sa_agent_trace (message_id);
```

已部署的库若自动迁移未生效（曾发生：sa_agent_trace 缺 `trace_id` 列 → 新轨迹插入报错被吞 → Agent 轨迹分析无新记录），
手动补列即可（幂等可重复执行）：进 psql 后执行上述 ALTER / CREATE INDEX。

**数据卷**：`pg-data` / `redis-data` / `rustfs-data` / `oo-data` 为 named volumes（`docker volume ls` 查看，down 不删，`down -v` 才删）；
服务器版另挂 `./data`（LiveRAG parquet 放 `data/liverag/` 被应用复用）。
