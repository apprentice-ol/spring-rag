#!/usr/bin/env python3
"""部署准备（**本地**执行）：校验语料 → 生成结构对齐脚本 → 导出语料 dump + 校验清单。

产出（都在 `data/deploy/`，该目录已被 .gitignore 忽略）：
    corpus-<日期>.dump     语料+评测集数据（pg_dump --data-only，6 张表）
    manifest-<日期>.txt    各表行数，供服务器导入后核对
    （脚本同时刷新 scripts/ensure-schema.sql，供服务器对齐旧库结构）

为什么要这一步（2026-09-20 实测）：
  - `init.sql` 的自迁移**只覆盖写成 ALTER 的列**；仅存在于建表体里的列（如 sa_eval_item 的
    difficulty/irt_diff/…）不会补到已存在的表上 → 服务器查询报 column does not exist；
  - `doc_id` 是随机 UUID：**重灌会产生全新 id**，本地评测集的 expected_doc_ids 会全部悬空，
    所以语料与评测集必须**成组搬迁**。

用法：
    python scripts/deploy-prepare.py                 # 默认导出语料+评测集
    python scripts/deploy-prepare.py --skip-corpus   # 只做校验 + 生成结构脚本
"""
from __future__ import annotations

import argparse
import datetime
import io
import os
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
SEP = "\x1f"

# 语料簇：这 6 张表必须同进同出（doc_id 是它们之间的关联键）
CORPUS_TABLES = ["sa_doc_collection", "sa_document", "sa_chunk_parent",
                 "spring_ai_store_vector", "sa_eval_dataset", "sa_eval_item"]

# 对象存储里的**原始文件**：库里只有分块文本，文档正文/图片在 RustFS。
# 不一起搬的话：检索正常，但「文档预览」与文档里的图片 404（source_location 指向 S3 key）。
STORAGE_BUCKETS = ["customer-platform-kb", "customer-platform-assets"]

# 生成结构对齐脚本时排除的表：框架自管 / 本地备份 / 运行时由代码建
SCHEMA_EXCLUDE = ("spring_ai_chat_memory", "spring_ai_store_vector", "bak_dup_cleanup_document",
                  "bak_dup_cleanup_eval_item", "bak_dup_cleanup_map", "bak_dup_cleanup_vector",
                  "bak_rename_document", "ops_agent_session", "ops_engine_session", "ops_engine_slots")


def load_env() -> dict[str, str]:
    values: dict[str, str] = {}
    env_file = REPO_ROOT / ".env"
    if env_file.exists():
        for line in env_file.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, _, v = line.partition("=")
                values[k.strip()] = v.strip().strip('"').strip("'")
    values.update({k: v for k, v in os.environ.items() if k not in values})
    return values


class Db:
    """通过 docker exec 访问本地 postgres。"""

    def __init__(self, env: dict[str, str], container: str, database: str):
        self.password = env.get("POSTGRES_PASSWORD", "postgres")
        self.user = env.get("POSTGRES_USER", "postgres")
        self.container = container
        self.database = database

    def _docker(self, *args: str, stdin: str | None = None) -> subprocess.CompletedProcess:
        return subprocess.run(
            ["docker", "exec", "-i", "-e", f"PGPASSWORD={self.password}", self.container, *args],
            input=stdin, capture_output=True, text=True, encoding="utf-8")

    def rows(self, sql: str) -> list[list[str]]:
        proc = self._docker("psql", "-U", self.user, "-d", self.database,
                            "-t", "-A", "-F", SEP, "-c", sql)
        if proc.returncode != 0:
            raise RuntimeError(proc.stderr.strip()[:400])
        return [line.split(SEP) for line in proc.stdout.split("\n") if line.strip()]

    def scalar(self, sql: str) -> str:
        r = self.rows(sql)
        return r[0][0] if r and r[0] else ""


def check_corpus(db: Db) -> list[str]:
    """语料关联完整性自检：任一项不通过都不要往下走（服务器上会表现为搜不到/引用悬空）。"""
    problems = []
    checks = [
        ("父块 → 文档", "SELECT count(*) FROM sa_chunk_parent p JOIN sa_document d ON d.doc_id = p.doc_id",
         "SELECT count(*) FROM sa_chunk_parent"),
        ("子块 → 文档", "SELECT count(*) FROM spring_ai_store_vector v JOIN sa_document d ON d.doc_id = v.metadata->>'doc_id'",
         "SELECT count(*) FROM spring_ai_store_vector"),
        ("评测期望文档 → 文档",
         "SELECT count(d.doc_id) FROM (SELECT jsonb_array_elements_text(expected_doc_ids) AS x"
         " FROM sa_eval_item) e LEFT JOIN sa_document d ON d.doc_id = e.x",
         "SELECT count(*) FROM (SELECT jsonb_array_elements_text(expected_doc_ids) FROM sa_eval_item) t"),
    ]
    for name, sql_ok, sql_total in checks:
        ok, total = db.scalar(sql_ok), db.scalar(sql_total)
        flag = "OK " if ok == total else "!! "
        if ok != total:
            problems.append(f"{name}：{ok}/{total} 条能对上")
        print(f"   {flag}{name}: {ok}/{total}")

    # 集合重名会让服务器启动时唯一索引创建失败 → 启动即挂（init.sql mode=always）
    dup = db.rows("SELECT lower(name), count(*) FROM sa_doc_collection "
                  "WHERE deleted = 0 GROUP BY lower(name) HAVING count(*) > 1")
    if dup:
        problems.append("集合重名（服务器启动会失败）：" + ", ".join(f"{r[0]}×{r[1]}" for r in dup))
        print(f"   !! 集合重名: {[f'{r[0]}×{r[1]}' for r in dup]}")
    else:
        print("   OK 集合无重名")
    return problems


def generate_ensure_schema(db: Db, out_path: Path) -> int:
    """从现状库导出幂等 ALTER（只针对 sa_* 表；刻意不带 NOT NULL，避免旧库有数据时失败）。"""
    sql = """
    SELECT c.relname, a.attname, format_type(a.atttypid, a.atttypmod),
           pg_get_expr(d.adbin, d.adrelid)
    FROM pg_attribute a
    JOIN pg_class c ON c.oid = a.attrelid
    JOIN pg_namespace n ON n.oid = c.relnamespace
    LEFT JOIN pg_attrdef d ON d.adrelid = c.oid AND d.adnum = a.attnum
    WHERE n.nspname = 'public' AND a.attnum > 0 AND NOT a.attisdropped AND c.relkind = 'r'
    ORDER BY c.relname, a.attnum"""
    by_table: dict[str, list[tuple[str, str, str]]] = {}
    for row in db.rows(sql):
        if len(row) < 4:
            continue
        table, column, typ, default = row[0], row[1], row[2].upper(), row[3]
        if table in SCHEMA_EXCLUDE or not table.startswith("sa_"):
            continue
        d = f" DEFAULT {default}" if default and "nextval" not in default else ""
        by_table.setdefault(table, []).append((column, typ.replace("CHARACTER VARYING", "VARCHAR"), d))

    lines = [
        "-- ============================================================",
        "-- 表结构对齐脚本（由 scripts/deploy-prepare.py 从现状库生成，对旧库执行，幂等）",
        "--",
        "-- 为什么需要：init.sql 每次启动都跑，但列级迁移只覆盖「写成 ALTER 的列」。",
        "--   仅存在于建表体里的列（如 sa_eval_item 的 difficulty/irt_diff/irt_disc/acs/acs_std）",
        "--   不会被补到**已存在**的表上，随后新代码查询即报 column does not exist。",
        "--",
        "-- 生成方式：pg_catalog（format_type 取精确类型，pgvector 列才是 vector(1024) 而非 user-defined）；",
        "--   只针对 sa_* 业务表；**刻意不带 NOT NULL**（旧表有数据时 NOT NULL 无默认会让 ADD COLUMN 失败中止）。",
        f"-- 生成时间：{datetime.date.today().isoformat()}",
        "-- 用法：docker exec -i <pg容器> psql -U <用户> -d <库> -v ON_ERROR_STOP=1 < scripts/ensure-schema.sql",
        "-- ============================================================",
        "",
    ]
    count = 0
    for table in sorted(by_table):
        lines.append(f"-- {table}")
        for column, typ, default in by_table[table]:
            lines.append(f"ALTER TABLE {table} ADD COLUMN IF NOT EXISTS {column} {typ}{default};")
            count += 1
        lines.append("")
    out_path.write_text("\n".join(lines), encoding="utf-8")
    return count


def export_corpus(db: Db, out_dump: Path) -> None:
    """容器内 dump → docker cp 取回（避免二进制经 shell 管道被改写）。"""
    inner = "/tmp/corpus-export.dump"
    args = ["pg_dump", "-Fc", "--data-only", "-U", db.user, "-d", db.database]
    for t in CORPUS_TABLES:
        args += ["-t", t]
    args += ["-f", inner]
    proc = db._docker(*args)
    if proc.returncode != 0:
        raise RuntimeError(proc.stderr.strip()[:400])
    out_dump.parent.mkdir(parents=True, exist_ok=True)
    cp = subprocess.run(["docker", "cp", f"{db.container}:{inner}", str(out_dump)],
                        capture_output=True, text=True, encoding="utf-8")
    if cp.returncode != 0:
        raise RuntimeError(cp.stderr.strip()[:400])
    db._docker("rm", "-f", inner)


def export_storage(container: str, out_path: Path) -> None:
    """打包 RustFS 的两个 bucket（原始文档 + 图片资产）。"""
    inner = "/tmp/rustfs-export.tgz"
    args = ["docker", "exec", container, "tar", "czf", inner, "-C", "/data", *STORAGE_BUCKETS]
    proc = subprocess.run(args, capture_output=True, text=True, encoding="utf-8")
    if proc.returncode != 0:
        raise RuntimeError(proc.stderr.strip()[:400])
    out_path.parent.mkdir(parents=True, exist_ok=True)
    cp = subprocess.run(["docker", "cp", f"{container}:{inner}", str(out_path)],
                        capture_output=True, text=True, encoding="utf-8")
    if cp.returncode != 0:
        raise RuntimeError(cp.stderr.strip()[:400])
    subprocess.run(["docker", "exec", container, "rm", "-f", inner],
                   capture_output=True, text=True, encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="部署准备（本地）")
    parser.add_argument("--container", default="ragent-postgres")
    parser.add_argument("--database", default="springai_rag")
    parser.add_argument("--storage-container", default="rustfs", help="RustFS 容器名")
    parser.add_argument("--skip-corpus", action="store_true", help="只做校验与结构脚本，不导出语料")
    parser.add_argument("--skip-storage", action="store_true", help="不导出对象存储（原始文件/图片）")
    args = parser.parse_args()

    db = Db(load_env(), args.container, args.database)
    stamp = datetime.date.today().strftime("%Y%m%d")
    out_dir = REPO_ROOT / "data" / "deploy"

    print("[1/4] 语料关联完整性自检")
    problems = check_corpus(db)

    print("\n[2/4] 生成结构对齐脚本")
    n = generate_ensure_schema(db, REPO_ROOT / "scripts" / "ensure-schema.sql")
    print(f"   scripts/ensure-schema.sql：{n} 条幂等 ALTER")

    manifest = [f"# 语料校验清单  {stamp}", "# 服务器导入后核对下表行数（脚本 deploy-upgrade.sh 会对比）"]
    for t in CORPUS_TABLES:
        manifest.append(f"{t}\t{db.scalar(f'SELECT count(*) FROM {t}')}")
    (out_dir).mkdir(parents=True, exist_ok=True)
    manifest_path = out_dir / f"manifest-{stamp}.txt"
    manifest_path.write_text("\n".join(manifest) + "\n", encoding="utf-8")
    print("\n[3/4] 校验清单")
    for line in manifest[2:]:
        table, rows = line.split("\t")
        print(f"   {table:26s} {rows:>7s}")

    if args.skip_corpus:
        print("\n[4/5] 已跳过语料导出（--skip-corpus）")
    else:
        print("\n[4/5] 导出语料（容器内 dump → docker cp）")
        dump_path = out_dir / f"corpus-{stamp}.dump"
        export_corpus(db, dump_path)
        print(f"   {dump_path.relative_to(REPO_ROOT)}  {dump_path.stat().st_size / 1048576:.1f} MB")

    if args.skip_storage:
        print("\n[5/5] 已跳过对象存储导出（--skip-storage）")
        print("   [!] 不带原始文件的话：检索正常，但文档预览与文档内图片会 404")
    else:
        print("\n[5/5] 导出对象存储（原始文档 + 图片资产）")
        storage_path = out_dir / f"rustfs-{stamp}.tgz"
        export_storage(args.storage_container, storage_path)
        print(f"   {storage_path.relative_to(REPO_ROOT)}  {storage_path.stat().st_size / 1048576:.1f} MB")

    if problems:
        print("\n[!] 自检发现的问题（先处理再部署）：")
        for p in problems:
            print(f"   - {p}")
        return 1
    print("\n准备完成。下一步见 docs/deploy-upgrade.md")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
