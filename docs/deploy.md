# 打包部署指南（本地 → 服务器）

面向「我在本地开发，要部署到服务器」的完整流程。端口表、compose 差异、日常运维见 [docker/README.md](../docker/README.md)。

- 服务器以 `root@<SERVER_IP>`、项目目录 `/opt/spring-rag` 为例（按实际替换；IP/账号一律放 `.env`，不进文档与 git）
- 服务器要求：Docker + Docker Compose、2核4G 起步、放行端口 9081（app）/ 5080（OO）/ 9000（RustFS）

---

## 一、本地打包（每次部署都做）

```bash
cd /d/04_projects/20_springai-rag
bash build-local.sh
```

三步自动完成：前端 `npm run build` → dist 拷进 jar 的 `static/` → `mvn package`。产物：

```
rag-core/target/rag-core-0.0.1-SNAPSHOT.jar   瘦 jar ~1.6MB（业务代码 + 前端页面）
rag-core/target/lib/                          373 个依赖 jar ~251MB（pom 不变就不变）
```

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
scp rag-core/target/rag-core-0.0.1-SNAPSHOT.jar root@<SERVER_IP>:/opt/spring-rag/
scp -r rag-core/target/lib                            root@<SERVER_IP>:/opt/spring-rag/
```

> 服务器上 compose / docker/ 目录 / init.sql 都来自 git clone，无需 scp；**只有 jar 和 lib/ 是构建产物**（不入 git）。

### 4. 启动

```bash
# 服务器上
cd /opt/spring-rag
docker compose -f docker-compose.server.yml up -d --build
docker compose -f docker-compose.server.yml logs -f app   # 看到 Started + 无 ERROR 即成功
```

首次会构建两个镜像（rag-postgres 装.deb 约 1-2 分钟；app 拷 251MB lib），并自动建库建表（`init.sql` 幂等，含 sa_* 业务表与 pgvector 兜底）。

### 5. Nginx 反代 RustFS（图片显示依赖）

app 生成的图片公网地址形如 `http://<IP>/storage/springai-rag-assets/{key}`，需在服务器 Nginx 加一段反代到 RustFS：

```nginx
location /storage/ {
    proxy_pass http://127.0.0.1:9000/;   # RUSTFS_PORT 改过端口的话同步改这里
}
```

不配的话：文档入库正常，但前端图片不显示。用别的端口方案就改 `.env` 的 `RUSTFS_PORT` 并同步 `RAG_STORAGE_S3_PUBLIC_URL`。

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
docker compose -f docker-compose.server.yml logs app 2>&1 | grep -c "localhost:3000"   # 期望 0
```

再在浏览器前端发一条消息：气泡下方应出现「轨迹 / traceId / 链路」操作条，点「链路」能打开 OpenObserve 该次请求的完整 span 树。

---

## 三、日常更新（只换业务代码）

```bash
# ① 本地重新打包
bash build-local.sh

# ② 传新 jar（依赖没变就不传 lib/，pom 动过才传）
scp rag-core/target/rag-core-0.0.1-SNAPSHOT.jar root@<SERVER_IP>:/opt/spring-rag/

# ③ 服务器重建 app 容器
ssh root@<SERVER_IP> "cd /opt/spring-rag && docker compose -f docker-compose.server.yml up -d --build app"

# ④ 验证（同上 7-①③）
```

**compose / docker 目录 / SQL 有变更时**（如这次的 Dockerfile 移入 docker/app/）：服务器 `git pull` 后再 up。

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

**已实施修复**（docker-compose.server.yml 的 `openobserve` 段）：
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
scp docker-compose.server.yml root@<SERVER_IP>:/opt/spring-rag/

# 服务器：腾内存 → 重建 OO（配置变了会自动 recreate）→ 等 WAL 消化完 → 恢复
docker stop rag-app
systemctl stop mysqld postfix
docker compose -f docker-compose.server.yml up -d openobserve
docker logs -f rag-openobserve
docker stats rag-openobserve        # 稳定后应 <1G
docker compose -f docker-compose.server.yml up -d app
systemctl start mysqld postfix
```

**加 swap（强烈建议，防下次启动被瞬杀）**：

```bash
fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
echo '/swapfile none swap sw 0 0' >> /etc/fstab
```

**长期建议**：升 8G 或把 OO 独立部署；跑批时 `TELEMETRY_STEP_LOG_LEVEL=OFF`、采样率降到 0.1，
可显著减少 WAL 写入源（代价：OO 里 step 输入/输出明文缺失，span 本身仍在）。
