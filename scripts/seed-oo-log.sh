#!/usr/bin/env bash
# 往 OpenObserve 灌一条「业务异常日志」，用来验收 ops 诊断的日志反查链路。
#
# 为什么需要它：反查的数据面是 OpenObserve（ops.openobserve.*），线上该指业务系统的日志流；
# 本地默认指向平台自身的日志流（springai_rag_logs），里面没有业务异常。灌一条假业务日志，
# 才能在页面上把「给关键字/时间窗 → 反查 → 补出接口/错误/报文 → 确认 → 诊断」整条链跑通。
#
# 用法：
#   scripts/seed-oo-log.sh                          # 随机 traceId + 默认关键字
#   scripts/seed-oo-log.sh <traceId> <关键字> <服务名>
#
# 灌完把打印出来的 traceId 粘进对话框（消息里带 32 位 hex 会直接短路进诊断并走 traceId 精查），
# 或者用「<关键字> 最近10分钟」走关键字模糊查。
#
# 账号取 .env（OO_USERNAME/OO_PASSWORD），脚本里不写任何凭据。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [ -f "$ROOT/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "$ROOT/.env"
  set +a
fi

OO_URL="${OPENOBSERVE_URL:-http://localhost:5080/api/default}"
STREAM="${OO_STREAM:-springai_rag_logs}"

TRACE_ID="${1:-$(od -An -tx1 -N16 /dev/urandom | tr -d ' \n')}"
KEYWORD="${2:-下单失败}"
SERVICE="${3:-order-service}"

TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT
# 用 python 拼 JSON（报文里有引号，shell heredoc 会把 \" 吃掉；见本脚本第一次跑失败的教训）
python - "$TRACE_ID" "$KEYWORD" "$SERVICE" > "$TMP" <<'PY'
import json
import sys

trace_id, keyword, service = sys.argv[1], sys.argv[2], sys.argv[3]
# 报文那段带「请求报文：{...}」——反查只从自己写明是报文的日志行里提取 payload
# 带上接口路径：反查挖出报文/报错后会回头补一次接口推断（否则卡片只能问用户接口是哪个）
# 日志行自带「请求报文：」「响应：」标记——反查只从写明标记的行里提取这两个高影响槽
body = (f"{service} ERROR {keyword}：POST /api/invoice/reverse InvalidParamException: invoiceCode 不能为空 "
        f"请求报文：{{\"invoiceCode\":\"\",\"invoiceNumber\":\"00412345\","
        f"\"reason\":\"INVOICE_ERROR\",\"operator\":\"zhangsan\"}}")
resp = (f"{service} INFO 响应：{{\"code\":\"INVOICE_CODE_REQUIRED\","
        f"\"message\":\"invoiceCode 不能为空\",\"success\":false}}")
json.dump([{
    "severity": "ERROR",
    "service_name": service,
    "host": f"{service}-01",
    "trace_id": trace_id,
    "body": body,
}, {
    "severity": "INFO",
    "service_name": service,
    "host": f"{service}-01",
    "trace_id": trace_id,
    "body": resp,
}], sys.stdout, ensure_ascii=True)  # 纯 ASCII 转义：Windows 上 stdout 默认 GBK，直接写中文会变成非法 UTF-8
PY

echo "→ 灌日志: stream=${STREAM} trace_id=${TRACE_ID} keyword=${KEYWORD}"
RESP="$(curl -s -u "${OO_USERNAME:-}:${OO_PASSWORD:-}" -H 'Content-Type: application/json' \
  -X POST "${OO_URL}/${STREAM}/_json" --data-binary "@${TMP}" --max-time 15 || true)"
echo "  ${RESP}"

# 判定放宽：只看 failed 计数（0 = 全部入库；曾经因为写死 successful":1 而把 2 行的情况误报成失败）
case "$RESP" in
  *'"failed":0'*|*'"failed": 0'*|*'"successful":1'*|*'"successful": 1'*)
    cat <<TIP

✅ 已入库。两种测法：

  ① traceId 精查（推荐，最确定）——对话框里发：
     traceId ${TRACE_ID} 帮我看看为什么失败

  ② 关键字 + 时间窗模糊查——对话框里发：
     ${KEYWORD}，最近10分钟
     （反查要求「关键字 + 时间窗」都有；body 进视图会被截到 200 字，关键字与报文要写在前 200 字内）
TIP
    ;;
  *)
    echo "⚠️  入库失败：检查 OpenObserve 是否在跑（默认 http://localhost:5080）、.env 里的 OO_USERNAME/OO_PASSWORD"
    ;;
esac
