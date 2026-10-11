#!/usr/bin/env python3
"""把历史评测「运行级快照」CSV 导入 sa_eval_run（看板历史回填）。

背景：run 1–82 的记录在 2026-09-18 前后被删，本地库无 WAL/备份，条目级明细不可恢复；
但删除前拍过运行级快照（docs/snapshots/eval-history-*.csv），含 run_id / 时间 / 范式 /
检索参数 / 五项汇总分。本脚本把这批记录灌回看板，**只回填运行级**——
条目级指标（sa_eval_metric）与轨迹（sa_eval_item_trace）历史数据不存在，故不生成。

写入口径（诚实优先）：
- aggregate_metrics 只写 mean + count（CSV 确实有的）；min/max/median 缺失——
  前端 fmtScore 对缺失值显示 `-`，不伪造中位数/极值
- param_snapshot.note 标注「导入的历史运行（仅运行级汇总）」，看板上可辨认
- 保留原始 run_id（CSV 里的 run_id 即主键），便于与既有文档/结论对齐
- ON CONFLICT (id) DO UPDATE → 幂等，可反复执行

回滚：DELETE FROM sa_eval_run WHERE id < 83;   （导入的都是 83 之前的 id）

用法：
    python scripts/import-eval-history.py --dry-run     # 只打印将要执行的 SQL
    python scripts/import-eval-history.py               # 实际导入
    python scripts/import-eval-history.py --csv <path> --dataset 1
"""
from __future__ import annotations

import argparse
import csv
import json
import os
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_CSV = REPO_ROOT / "docs" / "snapshots" / "eval-history-20260918.csv"

# CSV 列 → sa_eval_run.aggregate_metrics 的指标名（与 EvalRunner.computeAggregate 一致）
METRIC_COLUMNS = {
    "recall5": "recall_at_5",
    "recall10": "recall_at_10",
    "mrr": "mrr",
    "ndcg5": "ndcg_at_5",
    "p5": "precision_at_5",
}

IMPORT_NOTE = "导入的历史运行（仅运行级汇总：均值+条目数；无条目级明细与轨迹）"


def load_env(path: Path) -> dict[str, str]:
    """读 .env（仅取 POSTGRES_*；不覆盖已存在的环境变量）。"""
    values: dict[str, str] = {}
    if not path.exists():
        return values
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def sql_literal(text: str) -> str:
    return "'" + text.replace("'", "''") + "'"


def build_statements(csv_path: Path, dataset_id: int) -> list[str]:
    statements: list[str] = []
    with csv_path.open(encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            run_id = int(row["run_id"])
            done = int(row["done"] or 0)
            total = int(row["total"] or 0)

            aggregates = {}
            for column, metric in METRIC_COLUMNS.items():
                raw = (row.get(column) or "").strip()
                if not raw:
                    continue
                # 只写确实有的：均值 + 条目数（count 是前端聚合过滤 m.count > 0 的硬要求）
                aggregates[metric] = {"mean": float(raw), "count": float(done)}

            params = {
                "topK": 10,
                "contextTopK": int(row["ctx_topk"] or 10),
                "candidateLimit": int(row["cand"] or 40),
                "recallBudget": int(row["budget"] or 20),
                "rewrite": (row.get("rewrite") or "").strip().lower() == "true",
                "paradigm": (row.get("paradigm") or "").strip() or "naive",
                "note": IMPORT_NOTE,
            }
            created = (row.get("created") or "").strip()
            created_sql = sql_literal(created) if created else "now()"

            statements.append(
                "INSERT INTO sa_eval_run (id, dataset_id, status, total, done, paradigm, "
                "param_snapshot, aggregate_metrics, started_at, create_time, update_time) VALUES ("
                f"{run_id}, {dataset_id}, {sql_literal(row['status'])}, {total}, {done}, "
                f"{sql_literal(params['paradigm'])}, "
                f"{sql_literal(json.dumps(params, ensure_ascii=False))}::jsonb, "
                f"{sql_literal(json.dumps(aggregates, ensure_ascii=False))}::jsonb, "
                f"{created_sql}, {created_sql}, now()) "
                "ON CONFLICT (id) DO UPDATE SET "
                "dataset_id = EXCLUDED.dataset_id, status = EXCLUDED.status, "
                "total = EXCLUDED.total, done = EXCLUDED.done, paradigm = EXCLUDED.paradigm, "
                "param_snapshot = EXCLUDED.param_snapshot, "
                "aggregate_metrics = EXCLUDED.aggregate_metrics, "
                "started_at = EXCLUDED.started_at, update_time = now();"
            )
    return statements


def main() -> int:
    parser = argparse.ArgumentParser(description="导入历史评测运行记录到 sa_eval_run")
    parser.add_argument("--csv", type=Path, default=DEFAULT_CSV, help="运行级快照 CSV")
    parser.add_argument("--dataset", type=int, default=1, help="目标数据集 id（默认 1=LiveRAG）")
    parser.add_argument("--container", default="ragent-postgres", help="postgres 容器名")
    parser.add_argument("--database", default="springai_rag", help="库名")
    parser.add_argument("--dry-run", action="store_true", help="只打印 SQL，不执行")
    args = parser.parse_args()

    if not args.csv.exists():
        print(f"找不到 CSV：{args.csv}", file=sys.stderr)
        return 1

    statements = build_statements(args.csv, args.dataset)
    print(f"# {args.csv.name}: {len(statements)} 条运行记录 → dataset {args.dataset}")
    if args.dry_run:
        print("\n".join(statements))
        return 0

    env = {**load_env(REPO_ROOT / ".env"), **os.environ}
    user = env.get("POSTGRES_USER", "postgres")
    password = env.get("POSTGRES_PASSWORD", "postgres")
    payload = "\n".join(statements) + "\n"
    # 逐条执行并汇总影响行数（psql -v ON_ERROR_STOP=1 保证出错即停，不半灌）
    proc = subprocess.run(
        ["docker", "exec", "-i", "-e", f"PGPASSWORD={password}", args.container,
         "psql", "-U", user, "-d", args.database, "-v", "ON_ERROR_STOP=1", "-q"],
        input=payload, text=True, encoding="utf-8", capture_output=True,
    )
    if proc.returncode != 0:
        print(proc.stderr.strip(), file=sys.stderr)
        return proc.returncode
    print(f"已导入/更新 {len(statements)} 条运行记录")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
