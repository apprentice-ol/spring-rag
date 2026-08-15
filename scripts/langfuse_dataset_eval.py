#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Langfuse v3 数据集实验：把“RAG 问题 + 模型回答”关联到黄金数据集 run item，并（可选）LLM 打分写回。

做什么（v3 上实现“问题+回答 vs expectedOutput”的完整链路）：
  1. 从 Langfuse 拉数据集条目（input=问题, expectedOutput=黄金答案, id=datasetItemId）
  2. 把 input 作为问题发给应用 /api/rag/chat/stream（SSE），收集模型回答
  3. 按 conversationId(=session.id) 反查 Langfuse trace，找到 rag.answer observation
  4. POST /api/public/dataset-run-items 关联：runName + datasetItemId + traceId + observationId
     -> 这就是“模型提问/回答 与 黄金数据集”的关联点：datasetItem 提供 input/expectedOutput，
        trace/observation 提供模型回答，二者挂在同一个 run item 上
  5. 可选（--score + DEEPSEEK_API_KEY）：调 DeepSeek 比较 answer vs expectedOutput 出分，
     POST /api/public/scores（datasetRunId + traceId + observationId）写回 Langfuse

用法：
  python scripts/langfuse_dataset_eval.py --dataset liveRAG --run liveRAG-naive-20260815 --limit 5
  DEEPSEEK_API_KEY=sk-xxx python scripts/langfuse_dataset_eval.py --dataset liveRAG --run ... --score

依赖：仅 Python 标准库。凭据优先取环境变量，未设置时读 .env 里的 LANGFUSE_AUTH / LANGFUSE_PK+SK。
"""

import argparse
import base64
import json
import os
import sys
import time
import urllib.parse
import urllib.request
import uuid


# ---------- 配置 ----------

DEFAULT_LF_URL = "http://localhost:3000"
DEFAULT_APP_URL = "http://localhost:9081"


def load_env_file(path=".env"):
    """极简 .env 读取（不覆盖已存在的环境变量）。"""
    if not os.path.exists(path):
        return
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, _, value = line.partition("=")
            key = key.strip()
            value = value.strip().strip('"').strip("'")
            os.environ.setdefault(key, value)


def auth_header():
    auth = os.environ.get("LANGFUSE_AUTH")
    if auth:
        return "Basic " + auth
    pk = os.environ.get("LANGFUSE_PK") or os.environ.get("LANGFUSE_PUBLIC_KEY")
    sk = os.environ.get("LANGFUSE_SK") or os.environ.get("LANGFUSE_SECRET_KEY")
    if not pk or not sk:
        print("缺少 Langfuse 凭据：请设置 LANGFUSE_AUTH（base64(pk:sk)）或 LANGFUSE_PK/LANGFUSE_SK")
        sys.exit(2)
    return "Basic " + base64.b64encode(f"{pk}:{sk}".encode()).decode()


class LangfuseClient:
    def __init__(self, base_url, auth):
        self.base = base_url.rstrip("/") + "/api/public"
        self.auth = auth

    def _request(self, method, path, body=None):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(
            self.base + path,
            data=data,
            headers={"Authorization": self.auth, "Content-Type": "application/json"},
            method=method,
        )
        with urllib.request.urlopen(req, timeout=60) as r:
            return json.load(r)

    def get(self, path):
        return self._request("GET", path)

    def post(self, path, body):
        return self._request("POST", path, body)

    def list_dataset_items(self, dataset_name, limit):
        items = []
        page = 1
        while len(items) < limit:
            resp = self.get(
                f"/dataset-items?datasetName={urllib.parse.quote(dataset_name)}"
                f"&page={page}&limit={min(50, limit - len(items))}"
            )
            data = resp.get("data", [])
            items.extend(it for it in data if it.get("status") == "ACTIVE")
            if not data or len(items) >= resp.get("meta", {}).get("totalItems", 0):
                break
            page += 1
        return items[:limit]

    def find_trace_by_session(self, session_id, retries=5, wait=4):
        """按 sessionId（=应用的 conversationId）反查 trace；等待采集落库。"""
        for _ in range(retries):
            resp = self.get("/traces?sessionId=" + urllib.parse.quote(session_id) + "&page=1&limit=5")
            if resp.get("data"):
                return resp["data"][0]
            time.sleep(wait)
        return None

    def find_observation(self, trace_id, name="rag.answer"):
        trace = self.get("/traces/" + trace_id)
        for o in trace.get("observations", []):
            if o.get("name") == name:
                return o
        return None

    def link_run_item(self, run_name, dataset_item_id, trace_id, observation_id):
        return self.post("/dataset-run-items", {
            "runName": run_name,
            "datasetItemId": dataset_item_id,
            "traceId": trace_id,
            "observationId": observation_id,
        })

    def post_score(self, name, value, comment, dataset_run_id, trace_id, observation_id):
        return self.post("/scores", {
            "name": name,
            "value": value,
            "comment": comment,
            "datasetRunId": dataset_run_id,
            "traceId": trace_id,
            "observationId": observation_id,
        })


def run_chat(app_url, question, agent):
    """调用应用 SSE 接口，返回 (conversationId, 完整回答)。"""
    cid = str(uuid.uuid4())
    q = urllib.parse.quote(question)
    url = f"{app_url}/api/rag/chat/stream?question={q}&conversationId={cid}&agent={urllib.parse.quote(agent or 'naive')}"
    req = urllib.request.Request(url, data=b"", headers={"Accept": "text/event-stream"}, method="POST")
    parts = []
    event = "message"
    buf = b""
    with urllib.request.urlopen(req, timeout=180) as r:
        while True:
            chunk = r.read(8192)
            if not chunk:
                break
            buf += chunk
            while b"\n" in buf:
                line, buf = buf.split(b"\n", 1)
                text = line.decode("utf-8", "replace").strip()
                if text.startswith("event:"):
                    event = text[6:].strip()
                elif text.startswith("data:"):
                    payload = text[5:]
                    # SSE 规范：去掉 "data:" 后的一个可选空格，保留其余（含纯空格分片）
                    if payload.startswith(" "):
                        payload = payload[1:]
                    if event == "message" and payload and not payload.startswith('"'):
                        parts.append(payload)
    return cid, "".join(parts)


def judge_with_deepseek(api_key, model, question, answer, expected):
    """DeepSeek chat completions：比较 answer 与 expectedOutput，返回 (score 0~1, reason)。"""
    prompt = (
        "你是一个答案正确性评估器。请比较“模型回答”和“黄金标准答案”，判断模型回答是否正确、完整。\n"
        "问题：\n" + str(question) + "\n\n"
        "黄金标准答案（expectedOutput）：\n" + str(expected) + "\n\n"
        "模型回答（answer）：\n" + str(answer) + "\n\n"
        '只输出 JSON：{"score": 0到1的数字, "reason": "一句话理由"}'
    )
    body = {
        "model": model,
        "messages": [{"role": "user", "content": prompt}],
        "temperature": 0,
        "response_format": {"type": "json_object"},
    }
    req = urllib.request.Request(
        "https://api.deepseek.com/chat/completions",
        data=json.dumps(body).encode(),
        headers={"Authorization": "Bearer " + api_key, "Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=120) as r:
        resp = json.load(r)
    content = resp["choices"][0]["message"]["content"]
    parsed = json.loads(content)
    return float(parsed["score"]), str(parsed["reason"])


def main():
    parser = argparse.ArgumentParser(description="Langfuse v3 数据集 run + LLM 打分")
    parser.add_argument("--dataset", default="liveRAG", help="Langfuse 数据集名")
    parser.add_argument("--run", required=True, help="数据集 run 名（实验名）")
    parser.add_argument("--limit", type=int, default=3, help="本次跑多少条")
    parser.add_argument("--agent", default="naive", help="RAG 范式：naive/react 等")
    parser.add_argument("--lf-url", default=DEFAULT_LF_URL)
    parser.add_argument("--app-url", default=DEFAULT_APP_URL)
    parser.add_argument("--score", action="store_true", help="跑完调用 DeepSeek 打分并写回 Langfuse")
    parser.add_argument("--judge-model", default="deepseek-v4-flash", help="打分模型")
    args = parser.parse_args()

    load_env_file()
    client = LangfuseClient(args.lf_url, auth_header())

    items = client.list_dataset_items(args.dataset, args.limit)
    if not items:
        print(f"数据集 {args.dataset} 没有可用条目")
        sys.exit(1)
    print(f"数据集 {args.dataset}: 取 {len(items)} 条")

    api_key = os.environ.get("DEEPSEEK_API_KEY") if args.score else None
    if args.score and not api_key:
        print("--score 需要设置 DEEPSEEK_API_KEY")
        sys.exit(2)

    summary = []
    for it in items:
        item_id = it["id"]
        question = it.get("input")
        expected = it.get("expectedOutput")
        try:
            cid, answer = run_chat(args.app_url, question, args.agent)
            trace = client.find_trace_by_session(cid)
            if trace is None:
                print(f"[{item_id}] 未找到 trace（sessionId={cid}），跳过")
                continue
            trace_id = trace["id"]
            obs = client.find_observation(trace_id, "rag.answer")
            obs_id = obs["id"] if obs else None
            if obs and obs.get("output") is not None:
                # 以 Langfuse 落库的 rag.answer output 为准（比本地拼 SSE 更可靠）
                answer = obs["output"]
            run_item = client.link_run_item(args.run, item_id, trace_id, obs_id)
            print(f"[{item_id}] 已关联 run={run_item['datasetRunName']} "
                  f"trace={trace_id} obs={obs_id} answer_len={len(answer)}")

            score_row = None
            if args.score:
                value, reason = judge_with_deepseek(api_key, args.judge_model, question, answer, expected)
                client.post_score("Answer Correctness", value, reason,
                                  run_item["datasetRunId"], trace_id, obs_id)
                print(f"          打分 score={value} reason={reason[:60]}")
                score_row = (value, reason)

            summary.append({
                "item": item_id,
                "question": str(question)[:60],
                "answer": str(answer)[:60],
                "expected": str(expected)[:60],
                "trace": trace_id,
                "runItem": run_item["id"],
                "score": score_row[0] if score_row else None,
            })
        except Exception as e:  # noqa: BLE001
            print(f"[{item_id}] 失败: {e}")

    print("\n===== 汇总 =====")
    for row in summary:
        print(f"{row['item']} | Q:{row['question']} | A:{row['answer']} | GT:{row['expected']} | score={row['score']}")
    print("\n在 Langfuse 查看：Datasets → %s → Runs → %s（每条 run item 同时挂 datasetItem 与 trace）" % (args.dataset, args.run))


if __name__ == "__main__":
    main()
