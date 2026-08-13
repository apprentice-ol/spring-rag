# springai-rag 专用 postgres：pgvector + pg_search（BM25）
# 基础镜像自带 pgvector；pg_search（ParadeDB，Tantivy 内核）通过 GitHub release deb 安装
# （ParadeDB apt 源已下线，只能从 GitHub releases 下载）。
# shared_preload_libraries 用启动参数 -c 传入（无需改数据卷里的 postgresql.conf）。
# 首次初始化时 docker-entrypoint-initdb.d/ 下的 SQL 自动执行（建 pg_search 扩展）。

FROM pgvector/pgvector:pg16

# pg_search 0.25.0（PG16 / Debian bookworm / amd64）
# 注意：pg-search.deb 不入 git（68MB 二进制，见根 .gitignore 的 *.deb），
#       本地构建前需按 docker/postgres/README.md 下载并放到本目录。
COPY pg-search.deb /tmp/pg-search.deb
RUN apt-get update && apt-get install -y --no-install-recommends /tmp/pg-search.deb \
    && rm -f /tmp/pg-search.deb \
    && rm -rf /var/lib/apt/lists/*

# 首次启动自动建扩展（幂等）；应用启动后自动建 BM25 索引（见 Bm25KeywordSearchChannel）
COPY 01-pg-search.sql /docker-entrypoint-initdb.d/01-pg-search.sql

# 启动参数直接覆盖 shared_preload_libraries（避免改数据卷 postgresql.conf 的麻烦）
CMD ["postgres", "-c", "shared_preload_libraries=pg_search"]
