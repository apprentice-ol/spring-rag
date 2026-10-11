#!/usr/bin/env python3
"""从 OpenObserve 日志重建历史评测的**逐题检索证据**，捞出 badcase。

背景：run 1–82 的 `sa_eval_metric`（逐题分数）被删且无备份，但评测跑批时每题都过检索链，
逐题证据仍在 **OO 日志流**里（`rag_step` 字段可查、`message` 字段带内容）。本脚本把它们捞回来：

    OO 日志(query + 每通道 top-K 分数与预览)
      → 按 trace_id 聚合、按评卷时间窗归属到 run
      → query 匹配 sa_eval_item（黄金集）→ 定位到题
      → 预览文本反查 spring_ai_store_vector → doc_id
      → 与 expected_doc_ids 比对 → 算出每题「期望文档是否被检索到 / 最好排名」
      → 输出 CSV（含 badcase 列表）

**这是重建不是还原**：只覆盖检索环节（日志里有什么就用什么），不重算生成侧指标；
原始 `recall@k` 是在「融合+重排后的最终列表」上算的，这里给的是**证据级代理**
（期望文档跨通道最好排名），用于找 badcase 足够，不要当作与当时数值逐位对齐。

用法：
    python scripts/rebuild-eval-badcases.py --run 23
    python scripts/rebuild-eval-badcases.py --run 23 --out docs/snapshots/rebuild-run23.csv
"""
from __future__ import annotations

import argparse
import base64
import csv
import datetime
import json
import os
import re
import subprocess
import sys
import urllib.request
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

# psql 字段分隔符：内容里可能含逗号/竖线/引号，用不可见的分隔符最稳
SEP = "\x1f"

# 通道日志里的行格式：[向量检索] query="…", topK=… ／ [向量检索]   #1 得分=0.79 预览="…"
QUERY_RE = re.compile(r'query="([^"]+)"')
HIT_RE = re.compile(r"#(\d+)\s*得分=([\d.]+)\s*预览=\"(.*)\"\s*$")
# 评测跑批的运行边界（run 起止），用于把日志归属到某个 run
START_RE = re.compile(r"启动跑批: runId=(\d+)")
DONE_RE = re.compile(r"run (\d+) 完成")


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


def psql(env: dict[str, str], sql: str, container: str, database: str) -> list[str]:
    """执行 SQL，返回按行拆分的结果（-t -A：无表头、竖线分隔）。"""
    proc = subprocess.run(
        ["docker", "exec", "-e", f"PGPASSWORD={env.get('POSTGRES_PASSWORD', 'postgres')}",
         container, "psql", "-U", env.get("POSTGRES_USER", "postgres"), "-d", database,
         "-t", "-A", "-F", SEP, "-c", sql],
        capture_output=True, text=True, encoding="utf-8",
    )
    if proc.returncode != 0:
        raise RuntimeError(proc.stderr.strip() or "psql 执行失败")
    return [line for line in proc.stdout.split("\n") if line.strip()]


def oo_search(env: dict[str, str], sql: str, start_s: int, end_s: int, size: int = 1000) -> list[dict]:
    """查 OO 日志流（microseconds 时间窗）。"""
    base = env.get("OPENOBSERVE_URL", "http://localhost:5080/api/default").rstrip("/")
    payload = {"query": {"sql": sql, "start_time": start_s * 1_000_000, "end_time": end_s * 1_000_000},
               "from": 0, "size": size}
    req = urllib.request.Request(
        f"{base}/_search", data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    creds = f"{env['OO_USERNAME']}:{env['OO_PASSWORD']}".encode("utf-8")
    import base64
    req.add_header("Authorization", "Basic " + base64.b64encode(creds).decode())
    with urllib.request.urlopen(req, timeout=90) as resp:
        body = json.loads(resp.read().decode("utf-8"))
    if body.get("code", 200) != 200:
        raise RuntimeError(f"OO 查询失败: {body.get('message')}")
    return body.get("hits") or []


def run_window(env, container, database, run_id: int, created: str) -> tuple[int, int, str]:
    """run 的日志时间窗：优先用 `[Eval] 启动跑批 / run N 完成` 两条日志夹出精确边界。"""
    import datetime
    base = datetime.datetime.strptime(created[:19], "%Y-%m-%d %H:%M:%S")
    win_start = int(base.timestamp()) - 60
    win_end = int(base.timestamp()) + 60 * 60  # 默认给 1 小时，再按日志收紧
    rows = oo_search(env, 'SELECT _timestamp, message FROM "springai_rag_logs" '
                          f"WHERE str_match(message, '启动跑批') OR str_match(message, '完成') "
                          f"ORDER BY _timestamp", win_start, win_end, size=2000)
    start_ts = end_ts = None
    for row in rows:
        msg = str(row.get("message") or "")
        m = START_RE.search(msg)
        if m and int(m.group(1)) == run_id and start_ts is None:
            start_ts = int(row["_timestamp"]) // 1_000_000
        m = DONE_RE.search(msg)
        if m and int(m.group(1)) == run_id and end_ts is None:
            end_ts = int(row["_timestamp"]) // 1_000_000
    if start_ts is None:
        start_ts = int(base.timestamp()) - 10
    if end_ts is None:
        end_ts = start_ts + 15 * 60
    return start_ts - 5, end_ts + 5, ("精确边界" if end_ts else "按创建时间放宽")


def fetch_item_logs(env, start_s: int, end_s: int) -> dict[str, dict]:
    """拉取窗口内带 query 的通道日志 → {trace_id: {query, hits:[{rank,score,preview}]}}。"""
    rows = oo_search(env, 'SELECT _timestamp, trace_id, message FROM "springai_rag_logs" '
                          "WHERE str_match(message, 'query=\"') OR str_match(message, '预览=\"') "
                          "ORDER BY _timestamp",
                     start_s, end_s, size=10000)
    by_trace: dict[str, dict] = {}
    for row in rows:
        msg = str(row.get("message") or "")
        trace = str(row.get("trace_id") or "")
        q = QUERY_RE.search(msg)
        if q:
            by_trace.setdefault(trace, {"query": q.group(1).strip(), "hits": []})
            continue
        h = HIT_RE.search(msg)
        if h and trace in by_trace:
            by_trace[trace]["hits"].append(
                {"rank": int(h.group(1)), "score": float(h.group(2)), "preview": h.group(3).strip()})
    return by_trace


def norm_question(text: str) -> str:
    return re.sub(r"\s+", " ", (text or "").strip().lower())


def load_golden(env, container, database, dataset_id: int) -> dict[str, dict]:
    """黄金集：归一化问题 → {item_id, expected_doc_ids}。"""
    sql = ("SELECT question, id, expected_doc_ids::text FROM sa_eval_item "
           f"WHERE dataset_id = {dataset_id}")
    golden = {}
    for line in psql(env, sql, container, database):
        parts = line.split(SEP)
        if len(parts) < 3:
            continue
        question, item_id, expected = parts[0], parts[1], parts[2]
        try:
            docs = json.loads(expected) if expected else []
        except Exception:
            docs = []
        golden[norm_question(question)] = {"item_id": item_id, "expected": docs}
    return golden


def _sql_escape(text: str) -> str:
    return text.replace("'", "''")


def _resolve_pass(env, container, database, keys: list[str], resolved: dict[str, dict]) -> None:
    """一轮批量解析：把候选 key 用 OR 串成一次扫描（避免逐条 seq scan）。

    <p>不加 LIMIT：一个 key 常命中多个 chunk（同内容多份/重叠分块），限行会让排在后面的
    key 永远拿不到自己的行——实测 LIMIT 400 时 103 个 key 只解析出 57 个。</p>
    """
    for i in range(0, len(keys), 60):
        batch = keys[i:i + 60]
        conds = " OR ".join(
            "position('" + _sql_escape(k) + "' in content) > 0" for k in batch)
        array_sql = ",".join("'" + _sql_escape(k) + "'" for k in batch)
        # 关键：**不要把 content 选出来**——chunk 内容含换行，psql 按行输出会把一条记录拆成
        # 多行，解析随即失败（实测 103 个 key 只能解析出 57 个）。让库端算出「这一行命中了哪个 key」，
        # 只回标识列，行就一定是单行的。
        sql = ("SELECT metadata->>'doc_id', metadata->>'doc_name', "
               "(SELECT k FROM unnest(ARRAY[" + array_sql + "]::text[]) AS k "
               " WHERE position(k in content) > 0 LIMIT 1) AS matched "
               f"FROM spring_ai_store_vector WHERE {conds}")
        for line in psql(env, sql, container, database):
            parts = line.split(SEP)
            if len(parts) < 3 or not parts[2].strip():
                continue
            doc_id, doc_name, matched = parts[0], parts[1], parts[2].strip()
            if matched not in resolved:
                resolved[matched] = {"doc_id": doc_id, "doc_name": doc_name}


def preview_key(preview: str, length: int) -> str:
    """预览 → 定位键：折叠空白、去掉结尾省略号，取前 length 个字符。"""
    return re.sub(r"\s+", " ", preview).strip().rstrip(".").strip()[:length]


def resolve_previews(env, container, database, previews: list[str]) -> tuple[dict[str, dict], dict[str, str]]:
    """预览文本 → 向量库文档。

    <p>语料在 09-17 做过分块调整，部分 8 月的片段今天已不在库里，故做**多轮降级**：
    先用较长的键（定位精确），未命中的再用更短的键重试（容忍前缀差异）。</p>

    @return (键→文档, 预览→键)。词典以 40 字符键为准，命中方可用。
    """
    key40: dict[str, list[str]] = {}
    for p in previews:
        k = preview_key(p, 40)
        if len(k) >= 12:
            key40.setdefault(k, [])

    resolved: dict[str, dict] = {}
    _resolve_pass(env, container, database, list(key40), resolved)

    # 降级重试：长度 25 / 16（仅对尚未命中的键）
    for length in (25, 16):
        pending = {}
        for p in previews:
            if preview_key(p, 40) in resolved:
                continue
            k = preview_key(p, length)
            if len(k) >= 10:
                pending.setdefault(k, [])
        if pending:
            _resolve_pass(env, container, database, list(pending), resolved)
    return resolved


def main() -> int:
    parser = argparse.ArgumentParser(description="从 OO 日志重建历史评测的逐题检索证据")
    parser.add_argument("--run", type=int, required=True, help="run id（sa_eval_run.id）")
    parser.add_argument("--out", type=Path, help="输出 CSV（默认 docs/snapshots/rebuild-run<id>.csv）")
    parser.add_argument("--max-items", type=int, default=0, help="只处理前 N 题（0 = 全部）")
    parser.add_argument("--container", default="ragent-postgres")
    parser.add_argument("--database", default="springai_rag")
    args = parser.parse_args()

    env = load_env()
    out_path = args.out or (REPO_ROOT / "docs" / "snapshots" / f"rebuild-run{args.run}.csv")

    rows = psql(env, f"SELECT dataset_id, create_time::text, paradigm, total FROM sa_eval_run WHERE id = {args.run}",
                args.container, args.database)
    if not rows:
        print(f"run {args.run} 不存在", file=sys.stderr)
        return 1
    dataset_id, created, paradigm, total = rows[0].split(SEP)
    print(f"run {args.run} | dataset={dataset_id} | 范式={paradigm} | 题数={total} | 创建={created}")

    start_s, end_s, how = run_window(env, args.container, args.database, args.run, created)
    import datetime
    fmt = lambda ts: datetime.datetime.fromtimestamp(ts).strftime("%m-%d %H:%M:%S")
    print(f"日志窗口：{fmt(start_s)} ~ {fmt(end_s)}（{how}）")

    by_trace = fetch_item_logs(env, start_s, end_s)
    print(f"窗口内逐题检索记录：{len(by_trace)} 个 trace")

    golden = load_golden(env, args.container, args.database, int(dataset_id))
    print(f"黄金集题目：{len(golden)}")

    # query → 题（对不上的多为同窗口的并发闲聊，或 rewrite 后的改写句）
    matched = []
    for trace, item in by_trace.items():
        g = golden.get(norm_question(item["query"]))
        if g:
            matched.append((trace, item, g))
    print(f"能对上黄金集：{len(matched)} / {len(by_trace)}")

    if args.max_items:
        matched = matched[:args.max_items]

    previews = [h["preview"] for _, item, _ in matched for h in item["hits"]]
    print(f"待解析预览片段：{len(set(previews))} 条 …")
    resolved = resolve_previews(env, args.container, args.database, previews)
    print(f"反查到文档：{len(resolved)} 条")

    def lookup(preview: str) -> dict | None:
        for length in (40, 25, 16):
            key = preview_key(preview, length)
            if key in resolved:
                return resolved[key]
        return None

    out_rows = []
    hit_count = 0
    for trace, item, g in matched:
        expected = set(g["expected"])
        best_rank, best_doc = None, None
        retrieved = []
        unresolved = 0
        for h in sorted(item["hits"], key=lambda x: x["rank"]):
            doc = lookup(h["preview"])
            if not doc:
                unresolved += 1
                continue
            doc_id = doc["doc_id"]
            retrieved.append(doc_id)
            if doc_id in expected and best_rank is None:
                best_rank, best_doc = h["rank"], doc_id
        # 未解析的预览会让「未命中」存疑：如实标注，别把解析失败当 badcase
        if best_rank:
            verdict = "命中"
        elif unresolved:
            verdict = "未命中(存疑)"
        else:
            verdict = "未命中"
        if best_rank:
            hit_count += 1
        out_rows.append({
            "item_id": g["item_id"],
            "question": item["query"],
            "verdict": verdict,
            "best_rank": best_rank or "",
            "expected_doc_ids": ";".join(g["expected"]),
            "retrieved_docs": ";".join(dict.fromkeys(retrieved)),
            "hits": len(item["hits"]),
            "preview_unresolved": unresolved,
            "trace_id": trace,
        })

    out_path.parent.mkdir(parents=True, exist_ok=True)
    with out_path.open("w", encoding="utf-8-sig", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=list(out_rows[0].keys()) if out_rows else ["item_id"])
        writer.writeheader()
        writer.writerows(out_rows)

    print(f"\n重建 {len(out_rows)} 题：命中期望文档 {hit_count}，未命中 {len(out_rows) - hit_count}")
    print(f"已写出：{out_path.relative_to(REPO_ROOT)}")
    if out_rows:
        print("\n未命中样例（badcase）：")
        for r in [r for r in out_rows if r["verdict"] == "未命中"][:5]:
            print(f'  #{r["item_id"]} {r["question"][:70]}')
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
