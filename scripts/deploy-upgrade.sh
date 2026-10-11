#!/usr/bin/env bash
# ============================================================
# 服务器部署（**在服务器上执行**）：日常升级 与 全量换库，一个入口两种模式
#
# 场景 A · 日常升级（默认）——保留现有数据，只对齐结构 / 换语料：
#   bash scripts/deploy-upgrade.sh                              # 只对齐结构 + 清缓存 + 重启（不换语料）
#   bash scripts/deploy-upgrade.sh --corpus /path/corpus-<日期>.dump --storage /path/rustfs-<日期>.tgz
#   bash scripts/deploy-upgrade.sh --corpus /path/corpus-<日期>.dump --purge-eval-history
#
# 场景 B · 全量换库（--full-restore）——清空全库再导入，本地与服务器完全一致：
#   bash scripts/deploy-upgrade.sh --full-restore --dump /path/fulldb-<日期>.dump \
#        --storage /path/rustfs-<日期>.tgz --manifest /path/manifest-full-<日期>.txt
#
# 参数：
#   --corpus <file>         语料 dump（本地 scripts/deploy-prepare.py 产出，pg_dump --data-only 6 表）
#   --storage <file>        对象存储包（rustfs-<日期>.tgz：原始文档 + 图片资产）；
#                           不带的话：检索正常，但文档预览与文档内图片 404
#   --purge-eval-history    随语料一起清空 sa_eval_run/metric/item_trace
#                           （换了 sa_eval_item 后，旧运行的 item_id 会对不上，逐题明细错位）
#   --full-restore          场景 B：清空全库再导入 --dump（危险操作，须显式指定才启用）
#   --dump <file>           全量数据包（fulldb-*.dump；仅 --full-restore 用）
#   --manifest <file>       行数对照清单（manifest-full-*.txt；校验段逐表核对）
#   --init-sql <path>       显式 init.sql 路径（全量换库建缺失表用；缺省自动查找）
#   --skip-backup           跳过备份（**不推荐**）
#   --dry-run               预演：只做只读预检，不写任何数据
#
# 为什么需要它（2026-09-20 实测）：
#   init.sql 的自迁移只覆盖「写成 ALTER 的列」；仅存在于建表体里的列（如 sa_eval_item 的
#   difficulty/irt_diff/irt_disc/acs/acs_std，当年只进了手工脚本）不会被补到已存在的表上
#   → 新代码一查就报 column does not exist。
#   全量换库时「清空在建表之前」：旧表若已有重名集合，建唯一索引会直接失败 → 应用起不来。
#
# 环境变量可覆盖：PG_CONTAINER / REDIS_CONTAINER / STORAGE_CONTAINER / DB / DB_USER / COMPOSE_FILE / PROJECT_DIR
# ============================================================
set -euo pipefail

PG_CONTAINER="${PG_CONTAINER:-rag-postgres}"
REDIS_CONTAINER="${REDIS_CONTAINER:-rag-redis}"
STORAGE_CONTAINER="${STORAGE_CONTAINER:-rag-rustfs}"
DB="${DB:-springai_rag}"
DB_USER="${DB_USER:-postgres}"
COMPOSE_FILE="${COMPOSE_FILE:-docker/docker-compose.dev.yml}"
PROJECT_DIR="${PROJECT_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
BACKUP_DIR="$PROJECT_DIR/data/backup"

CORPUS=""
STORAGE=""
FULL=0
DUMP=""
MANIFEST=""
INIT_SQL_OPT=""
PURGE_EVAL=0
SKIP_BACKUP=0
DRY_RUN=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --corpus) CORPUS="$2"; shift 2 ;;
    --storage) STORAGE="$2"; shift 2 ;;
    --purge-eval-history) PURGE_EVAL=1; shift ;;
    --full-restore) FULL=1; shift ;;
    --dump) DUMP="$2"; shift 2 ;;
    --manifest) MANIFEST="$2"; shift 2 ;;
    --init-sql) INIT_SQL_OPT="$2"; shift 2 ;;
    --skip-backup) SKIP_BACKUP=1; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    *) echo "未知参数: $1"; exit 1 ;;
  esac
done

# ---- 参数校验 ----
if [[ "$FULL" == "1" ]]; then
  [[ -n "$DUMP" ]] || { echo "--full-restore 必须配 --dump <fulldb-*.dump>"; exit 1; }
  [[ -z "$CORPUS" ]] || { echo "--corpus 不能与 --full-restore 同用（全量换库已含全部数据）"; exit 1; }
  [[ -f "$DUMP" ]] || { echo "找不到全量数据包: $DUMP"; exit 1; }
  if [[ "$PURGE_EVAL" == "1" ]]; then
    echo "  [!] --purge-eval-history 在全量模式下无意义（全库已清空），已忽略"
  fi
else
  [[ -z "$DUMP" && -z "$MANIFEST" ]] || { echo "--dump / --manifest 仅用于 --full-restore 模式"; exit 1; }
  if [[ -n "$CORPUS" && ! -f "$CORPUS" ]]; then echo "找不到语料 dump: $CORPUS"; exit 1; fi
fi

cd "$PROJECT_DIR"
psql_do() { docker exec -i "$PG_CONTAINER" psql -U "$DB_USER" -d "$DB" -v ON_ERROR_STOP=1 "$@"; }
q() { docker exec -i "$PG_CONTAINER" psql -U "$DB_USER" -d "$DB" -t -A -c "$1"; }
STEP=0
step() { STEP=$((STEP + 1)); echo "=== [$STEP] $* ==="; }

if [[ "$FULL" == "1" ]]; then MODE_LABEL="全量换库（--full-restore）"; else MODE_LABEL="日常升级"; fi
echo "=============================================="
echo " 模式  ：$MODE_LABEL"
echo " 目标库：$DB @ $PG_CONTAINER"
if [[ "$FULL" == "1" ]]; then echo " 数据包：$DUMP"; fi
if [[ -n "$CORPUS" ]]; then echo " 语料包：$CORPUS"; fi
echo "=============================================="

if [[ "$DRY_RUN" == "1" ]]; then
  echo "=== 预演模式（--dry-run）：只做只读预检，不写任何数据 ==="
  if [[ "$FULL" == "1" ]]; then
    echo "  将执行：备份 → 清空全库 → 建缺失表(init.sql) → 补缺失列 → 导入全量包 → 重置序列 → 存储/缓存 → 重启 → 行数核对"
    if [[ -f platform-bootstrap/src/main/resources/sql/init.sql ]]; then
      echo "  找到 init.sql（用于建缺失表）"
    else
      echo "  [!] 缺 platform-bootstrap/src/main/resources/sql/init.sql —— 传上来再跑（git pull 或 scp）"
    fi
  else
    ALTER_CNT=$(grep -c 'ADD COLUMN IF NOT EXISTS' scripts/ensure-schema.sql 2>/dev/null || echo "?")
    STEPS_MSG="备份 → 重名预检 → 结构对齐（${ALTER_CNT} 条幂等 ALTER）"
    if [[ -n "$CORPUS" ]]; then STEPS_MSG="$STEPS_MSG → 语料导入（6 表整体替换）"; fi
    if [[ -n "$STORAGE" ]]; then STEPS_MSG="$STEPS_MSG → 对象存储导入"; fi
    STEPS_MSG="$STEPS_MSG → 清缓存 → 重启 → 校验"
    echo "  将执行：$STEPS_MSG"
    DUP=$(q "SELECT lower(name) FROM sa_doc_collection WHERE deleted = 0 GROUP BY lower(name) HAVING count(*) > 1;")
    if [[ -n "$DUP" ]]; then
      echo "  [!] 发现重名集合（正式执行会被拦下，请先改名或逻辑删除）："
      echo "$DUP" | sed 's/^/    - /'
    else
      echo "  重名预检：OK 无重名"
    fi
  fi
  echo
  echo "预演结束。实际执行请去掉 --dry-run；换语料 / 换库再加 --corpus / --full-restore --dump。"
  exit 0
fi

step "备份"
if [[ "$SKIP_BACKUP" == "1" ]]; then
  echo "  [!] 已跳过（--skip-backup）——出事无法回滚"
else
  mkdir -p "$BACKUP_DIR"
  STAMP=$(date +%Y%m%d-%H%M%S)
  if [[ "$FULL" == "1" ]]; then TAG="restore"; else TAG="upgrade"; fi
  docker exec "$PG_CONTAINER" pg_dump -Fc -U "$DB_USER" -d "$DB" -f "/tmp/pre-$TAG-$STAMP.dump"
  docker cp "$PG_CONTAINER:/tmp/pre-$TAG-$STAMP.dump" "$BACKUP_DIR/pre-$TAG-$STAMP.dump"
  docker exec "$PG_CONTAINER" rm -f "/tmp/pre-$TAG-$STAMP.dump"
  echo "  已备份: $BACKUP_DIR/pre-$TAG-$STAMP.dump ($(du -h "$BACKUP_DIR/pre-$TAG-$STAMP.dump" | cut -f1))"
  echo "  回滚: docker exec -i $PG_CONTAINER pg_restore -U $DB_USER -d $DB --clean --if-exists < 该文件"
fi

if [[ "$FULL" == "1" ]]; then
  step "清空 public schema 全部表"
  # 含运行时表（ops_engine_* 等）：不清的话旧会话残留会和导入的 task/finding 对不上
  psql_do -q <<'SQL'
DO $$
DECLARE r record;
BEGIN
  FOR r IN SELECT tablename FROM pg_tables WHERE schemaname = 'public' LOOP
    EXECUTE 'TRUNCATE TABLE public.' || quote_ident(r.tablename) || ' CASCADE';
  END LOOP;
END $$;
SQL
  echo "  已清空 $(q "SELECT count(*) FROM pg_tables WHERE schemaname='public';") 张表"

  step "建缺失的表"
  # 首选：应用启动时自建（新 jar 的 classpath 里有 init.sql，启动即建全量表结构）。
  # 这样服务器上不需要放任何 SQL 文件——先更新 jar 并重启一次 app，本步自动跳过。
  KEY_TABLES="sa_agent_finding sa_agent_task sa_eval_item_trace sa_chunk_parent sa_eval_item sa_document"
  MISSING_TABLES=""
  for tb in $KEY_TABLES; do
    if [[ "$(q "SELECT count(*) FROM pg_tables WHERE schemaname='public' AND tablename='$tb';")" != "1" ]]; then
      MISSING_TABLES="$MISSING_TABLES $tb"
    fi
  done
  if [[ -z "$MISSING_TABLES" ]]; then
    echo "  业务表已就绪（由应用启动时的 init.sql 建好），跳过"
  else
    echo "  缺表:$MISSING_TABLES"
    INIT_SQL=""
    for cand in "$INIT_SQL_OPT" "platform-bootstrap/src/main/resources/sql/init.sql" "app/src/main/resources/sql/init.sql" "sql/init.sql" "init.sql"; do
      if [[ -n "$cand" && -f "$cand" ]]; then INIT_SQL="$cand"; break; fi
    done
    if [[ -n "$INIT_SQL" ]]; then
      { echo "SET client_min_messages TO WARNING;"; cat "$INIT_SQL"; } | psql_do -q
      echo "  已应用 $INIT_SQL（表数 $(q "SELECT count(*) FROM pg_tables WHERE schemaname='public';")）"
    else
      echo "  [!] 找不到 init.sql —— 走应用自建这条路："
      echo "      ① 传新 jar：scp platform-bootstrap/target/*.jar root@<IP>:/srv/www/spring-rag/"
      echo "      ② 重启应用（它会自动建全部表，含本次新增的 sa_agent_task/sa_agent_finding/sa_chunk_parent 等）："
      echo "         docker compose --project-directory . -f docker/docker-compose.dev.yml up -d --build app"
      echo "      ③ 等 health 通过后，重跑本脚本（表已在 → 本步自动跳过）"
      echo "      或 ②′ 传任意位置的 init.sql 后用 --init-sql <路径> 指定"
      exit 1
    fi
  fi
else
  step "预检：集合重名（有重名则启动时唯一索引创建失败 → 应用起不来）"
  DUP=$(q "SELECT lower(name) FROM sa_doc_collection WHERE deleted = 0 GROUP BY lower(name) HAVING count(*) > 1;")
  if [[ -n "$DUP" ]]; then
    echo "  发现重名集合，请先改名或逻辑删除后重跑："
    echo "$DUP" | sed 's/^/    - /'
    echo "  参考 SQL：UPDATE sa_doc_collection SET deleted = 1 WHERE id IN (...);"
    exit 1
  fi
  echo "  OK 无重名"
fi

step "结构对齐（补缺失列，幂等）"
if [[ ! -f scripts/ensure-schema.sql ]]; then
  echo "  找不到 scripts/ensure-schema.sql —— 先在本地跑 python scripts/deploy-prepare.py 并提交该文件"
  exit 1
fi
{ echo "SET client_min_messages TO WARNING;"; cat scripts/ensure-schema.sql; } | psql_do -q
echo "  已应用 scripts/ensure-schema.sql（$(grep -c 'ADD COLUMN IF NOT EXISTS' scripts/ensure-schema.sql) 条幂等 ALTER）"

step "数据导入"
if [[ "$FULL" == "1" ]]; then
  docker cp "$DUMP" "$PG_CONTAINER:/tmp/restore.dump"
  # pg_restore 只要有一条非致命错误就返回非 0（如为「已排除表的序列」做 setval 时找不到序列），
  # 但它默认会继续处理完整包。这里刻意不让 set -e 中断——成败由校验段的行数核对判定。
  if docker exec "$PG_CONTAINER" pg_restore -U "$DB_USER" -d "$DB" --data-only --no-owner /tmp/restore.dump; then
    echo "  全量导入完成"
  else
    echo "  [!] pg_restore 报了错（见上）。若只是 setval 找不到某些'已排除表'的序列，可忽略；"
    echo "      真正的成败看校验段的行数核对（会逐表标 OK/!!）"
  fi
  docker exec "$PG_CONTAINER" rm -f /tmp/restore.dump
elif [[ -n "$CORPUS" ]]; then
  docker cp "$CORPUS" "$PG_CONTAINER:/tmp/corpus-import.dump"
  echo "  -- 清空目标表（6 张语料/评测表整体替换）"
  psql_do -q -c "TRUNCATE sa_chunk_parent, spring_ai_store_vector, sa_document, sa_doc_collection, sa_eval_dataset, sa_eval_item CASCADE;"
  if [[ "$PURGE_EVAL" == "1" ]]; then
    psql_do -q -c "TRUNCATE sa_eval_item_trace, sa_eval_metric, sa_eval_run CASCADE;"
    echo "  -- 已清空旧评测运行记录（--purge-eval-history）"
  fi
  echo "  -- 恢复数据"
  docker exec "$PG_CONTAINER" pg_restore -U "$DB_USER" -d "$DB" --data-only --no-owner "/tmp/corpus-import.dump"
  docker exec "$PG_CONTAINER" rm -f /tmp/corpus-import.dump
else
  echo "  跳过（未传 --corpus；本模式数据保持不动）"
fi

if [[ "$FULL" == "1" || -n "$CORPUS" ]]; then
  step "重置自增序列（--data-only 不搬序列，不重置下次入库会主键冲突）"
  psql_do -q <<'SQL'
DO $$
DECLARE r record; mx bigint;
BEGIN
  FOR r IN SELECT table_name, column_name FROM information_schema.columns
           WHERE table_schema = 'public' AND column_default LIKE 'nextval%' LOOP
    EXECUTE format('SELECT COALESCE(max(%I), 1) FROM public.%I', r.column_name, r.table_name) INTO mx;
    PERFORM setval(pg_get_serial_sequence('public.' || quote_ident(r.table_name), r.column_name), mx);
  END LOOP;
END $$;
SQL
  echo "  已重置 $(q "SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND column_default LIKE 'nextval%';") 个序列"
fi

step "对象存储（原始文档 + 图片资产）"
if [[ -z "$STORAGE" ]]; then
  echo "  [!] 未传 --storage：检索可用，但文档预览与文档内图片会 404（source_location 指向的 S3 key 不存在）"
elif [[ ! -f "$STORAGE" ]]; then
  echo "  找不到存储包: $STORAGE"
  exit 1
elif ! docker exec "$STORAGE_CONTAINER" sh -c "command -v tar" >/dev/null 2>&1; then
  echo "  容器 $STORAGE_CONTAINER 内没有 tar，无法就地解包"
  echo "  手工做法：先取卷名 docker inspect $STORAGE_CONTAINER --format '{{range .Mounts}}{{if eq .Destination \"/data\"}}{{.Name}}{{end}}{{end}}'"
  echo "           再 docker run --rm -v <卷名>:/data -v \"\$PWD/data/deploy\":/in alpine:3 sh -c 'tar xzf /in/$(basename "$STORAGE") -C /data'"
  exit 1
else
  docker cp "$STORAGE" "$STORAGE_CONTAINER:/tmp/rustfs-import.tgz"
  # 覆盖式解包：同名 key 覆盖，服务器上的其他文件保留（不动它自己的图片资产，避免会话里的图 404）
  docker exec "$STORAGE_CONTAINER" sh -c "tar xzf /tmp/rustfs-import.tgz -C /data && rm -f /tmp/rustfs-import.tgz"
  echo "  已解包到 $STORAGE_CONTAINER:/data（两个 bucket）"
  docker exec "$STORAGE_CONTAINER" sh -c "for b in customer-platform-kb customer-platform-assets; do echo -n \"  \$b: \"; find /data/\$b -type f 2>/dev/null | wc -l; done"
fi

step "清缓存（数据变了，旧答案/版本号必须失效）"
if [[ "$FULL" == "1" ]]; then
  echo "  全库清空已含 sa_cache_answer，跳过（只清 Redis docver）"
else
  psql_do -q -c "TRUNCATE sa_cache_answer;" && echo "  sa_cache_answer 已清空"
fi
if docker ps --format '{{.Names}}' | grep -q "^${REDIS_CONTAINER}$"; then
  KEYS=$(docker exec "$REDIS_CONTAINER" redis-cli --scan --pattern '*docver*' | wc -l)
  if [[ "$KEYS" -gt 0 ]]; then
    docker exec "$REDIS_CONTAINER" redis-cli --scan --pattern '*docver*' | while read -r k; do
      docker exec "$REDIS_CONTAINER" redis-cli DEL "$k" >/dev/null
    done
  fi
  echo "  已删除 docver* 键 $KEYS 个（其余缓存按 docver 自然失效）"
else
  echo "  未找到 Redis 容器 ${REDIS_CONTAINER}，跳过（请手动清理缓存键）"
fi

step "重建并重启 app"
# --project-directory 固定项目目录=项目根：compose 文件在 docker/ 子目录，不携带会把项目名变 "docker"（数据卷名漂移）
docker compose --project-directory "$PROJECT_DIR" -f "$COMPOSE_FILE" up -d --build app
echo "  等待健康检查…"
for _ in $(seq 1 40); do
  if curl -sf -m 3 http://localhost:9081/api/rag/ping/health >/dev/null 2>&1; then
    echo "  app 已就绪"; break
  fi
  sleep 3
done

step "校验"
HEALTH=$(curl -s -m 5 http://localhost:9081/api/rag/ping/health || true)
echo "  health: ${HEALTH:-无响应（看 docker compose logs app）}"
if [[ -n "$MANIFEST" && -f "$MANIFEST" ]]; then
  echo "  行数对照（清单 → 实际，不一致会标 !!）："
  FAIL=0
  while IFS=$'\t' read -r table expect; do
    [[ "$table" == \#* || -z "$table" ]] && continue
    actual=$(q "SELECT count(*) FROM $table;" 2>/dev/null || echo "ERR")
    if [[ "$actual" == "$expect" ]]; then
      printf "    OK  %-26s %s\n" "$table" "$expect"
    else
      printf "    !!  %-26s 清单=%s 实际=%s\n" "$table" "$expect" "$actual"; FAIL=1
    fi
  done < "$MANIFEST"
  if [[ "$FAIL" == "0" ]]; then echo "  全部一致"; else echo "  有不一致项，见上"; fi
fi
if ! q "SELECT '文档=' || (SELECT count(*) FROM sa_document) ||
              ' 父块=' || (SELECT count(*) FROM sa_chunk_parent) ||
              ' 向量=' || (SELECT count(*) FROM spring_ai_store_vector) ||
              ' 集合=' || (SELECT count(*) FROM sa_doc_collection) ||
              ' 评测集=' || (SELECT count(*) FROM sa_eval_item);"; then
  echo "  （行数概览跳过：某表不存在，结构可能尚未就绪）"
fi
echo "  检索冒烟（应返回 citations 事件）："
curl -s -N -m 30 -X POST "http://localhost:9081/api/rag/chat/stream?question=test&conversationId=deploy-check" \
  | grep -E "^event:(citations|meta)" | head -3 || true
echo
echo "完成。若上面 health 无响应或检索无输出，先看：docker compose --project-directory \"$PROJECT_DIR\" -f $COMPOSE_FILE logs -f app"
