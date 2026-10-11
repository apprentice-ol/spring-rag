#!/usr/bin/env python3
"""按 traceId 查 OpenObserve 的 trace（诊断辅助）。

用法：python scripts/oo_trace_query.py <traceId> [输出json] [字段,逗号分隔]

凭据：OO_AUTH（base64(账号:密码)，与 .env / docker-compose 同源）——
优先环境变量，缺省时回退读项目根 .env 的 OO_AUTH；绝不硬编码。
端点：OO_URL 可覆盖（默认 http://localhost:5080）。
时间窗：默认「近 7 天」，可用 OO_START_US / OO_END_US（微秒）覆盖。
"""
import json
import os
import sys
import time
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def _auth():
    v = os.environ.get("OO_AUTH", "").strip()
    if not v:
        env_path = os.path.join(ROOT, ".env")
        if os.path.exists(env_path):
            for line in open(env_path, encoding="utf-8"):
                line = line.strip()
                if line.startswith("OO_AUTH="):
                    v = line.split("=", 1)[1].strip()
                    break
    if not v:
        sys.exit("缺少 OO_AUTH：在项目根 .env 配置 OO_AUTH=base64(<OO账号>:<OO密码>)，或设置同名环境变量")
    return v


if len(sys.argv) < 2:
    sys.exit(__doc__)

NOW_US = int(time.time() * 1_000_000)
AUTH = _auth()
TRACE = sys.argv[1]
OUT = sys.argv[2] if len(sys.argv) > 2 else f"trace-{TRACE[:8]}.json"
FIELDS = sys.argv[3] if len(sys.argv) > 3 else "span_name, duration"
OO_URL = os.environ.get("OO_URL", "http://localhost:5080")
START_US = int(os.environ.get("OO_START_US", NOW_US - 7 * 86400 * 1_000_000))
END_US = int(os.environ.get("OO_END_US", NOW_US + 86400 * 1_000_000))


def q(sql):
    body = {"query": {"sql": sql, "start_time": START_US, "end_time": END_US, "from": 0, "size": 500}}
    req = urllib.request.Request(
        OO_URL + "/api/default/_search?type=traces",
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json", "Authorization": "Basic " + AUTH})
    with urllib.request.urlopen(req) as r:
        return json.load(r)


d = q(f'SELECT {FIELDS} FROM "default" WHERE trace_id = \'{TRACE}\' ORDER BY start_time')
json.dump(d, open(OUT, "w", encoding="utf-8"), ensure_ascii=False)
hits = d.get("hits", [])
print("spans:", len(hits))
for h in hits:
    dur = h.get("duration", 0) / 1000
    name = h.get("span_name", "")
    print(f"{dur:9.1f}ms  {name}")
