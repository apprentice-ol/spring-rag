# postgres 自定义镜像构建说明

本目录构建 `rag-postgres:pg16-pgsearch` 镜像：pgvector（基础镜像自带）+ pg_search（BM25 关键词检索扩展）。

## pg-search.deb 不入版本库

`pg-search.deb`（pg_search 扩展安装包，约 68MB）是 ParadeDB 的二进制 release 产物，**不纳入 git**（见根 `.gitignore` 的 `*.deb`）。本地 `docker compose build` 前需先把它放到本目录，否则 `Dockerfile` 的 `COPY pg-search.deb` 会失败。

### 获取 pg-search.deb

`Dockerfile` 使用的是 **pg_search 0.25.0 / PostgreSQL 16 / Debian bookworm / amd64**：

1. 打开 ParadeDB releases：<https://github.com/paradedb/paradedb/releases/tag/v0.25.0>
2. 下载 PG16 + bookworm + amd64 的 `.deb` 资产（文件名形如 `postgresql-16-pg-search_0.25.0...amd64.deb`）
3. **重命名为 `pg-search.deb`** 放到本目录（与 `Dockerfile` 同级）

随后即可构建：

```bash
docker compose build postgres                       # 本地版 docker-compose.yml
docker compose -f docker-compose.server.yml build postgres   # 服务器版
```

> 升级 pg_search 版本时，同步改 `Dockerfile` 注释里的版本号与本说明。
