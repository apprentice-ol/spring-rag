#!/usr/bin/env bash
# 往 OpenObserve 灌一条「业务异常日志」，用来验收 ops 诊断的日志反查链路。
#
# 为什么需要它：反查的数据面是 OpenObserve（ops.openobserve.*），线上该指业务系统的日志流；
# 本地默认指向平台自身的日志流（springai_rag_logs），里面没有业务异常。灌一条假业务日志，
# 才能在页面上把「给关键字/时间窗 → 反查 → 补出接口/错误/报文 → 确认 → 诊断」整条链跑通。
#
# 用法：
#   scripts/seed-oo-log.sh                          # 随机 traceId / orderNo + 默认关键字
#   scripts/seed-oo-log.sh <traceId> <关键字> <服务名> <orderNo>
#
# 灌完有三种测法（脚本会把随机值打印出来）：
#   ① traceId 精查   —— 「traceId <32位hex> 帮我看看为什么失败」
#   ② 中文关键字查   —— 「<关键字>，最近10分钟」
#   ③ orderNo 业务键 —— 「orderNo <orderNo> 报错了，最近10分钟」
#      （真实排查里用户手上往往只有业务单号、没有 traceId，这条链路必须有数据可测）
#
# orderNo 会同时写在报错描述与请求报文首字段，且都在正文前 200 字内——body 进 OO 视图
# 会被截到 200 字，写在靠后位置的关键字搜不到（本脚本注释里的老坑）。
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
# 业务单号：形如 ORD+8位hex（11 字符）——短于自动补全对业务键的 30 字截断阈值，
# 且不是 32 位 hex，不会被 looksLikeTraceId 误判成标准 traceId（那会走精查而非模糊查）
ORDER_NO="${4:-ORD$(od -An -tx1 -N4 /dev/urandom | tr -d ' \n')}"

TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT
# 用 python 拼 JSON（报文里有引号，shell heredoc 会把 \" 吃掉；见本脚本第一次跑失败的教训）
python - "$TRACE_ID" "$KEYWORD" "$SERVICE" "$ORDER_NO" > "$TMP" <<'PY'
import json
import sys

trace_id, keyword, service, order_no = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
# 报文那段带「请求报文：{...}」——反查只从自己写明是报文的日志行里提取 payload
# 带上接口路径：反查挖出报文/报错后会回头补一次接口推断（否则卡片只能问用户接口是哪个）
# 日志行自带「请求报文：」「响应：」标记——反查只从写明标记的行里提取这两个高影响槽
#
# orderNo 出现两次且都靠前：① 报错描述里（orderNo=XXX）② 请求报文 JSON 首字段。
# 为什么必须靠前：body 进 OO 视图会被截到 200 字，而业务键模糊查是在这条正文上做匹配的，
# 写在报文末尾的关键字搜不到。真实日志也常把业务单号前置在错误行里。
body = (f"{service} ERROR {keyword}：orderNo={order_no} POST /api/invoice/reverse "
        f"InvalidParamException: invoiceCode 不能为空 "
        f"请求报文：{{\"orderNo\":\"{order_no}\",\"invoiceCode\":\"\",\"invoiceNumber\":\"00412345\","
        f"\"reason\":\"INVOICE_ERROR\",\"operator\":\"zhangsan\"}}")
resp = (f"{service} INFO 响应：{{\"orderNo\":\"{order_no}\",\"code\":\"INVOICE_CODE_REQUIRED\","
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

echo "→ 灌日志: stream=${STREAM} trace_id=${TRACE_ID} orderNo=${ORDER_NO} keyword=${KEYWORD}"
RESP="$(curl -s -u "${OO_USERNAME:-}:${OO_PASSWORD:-}" -H 'Content-Type: application/json' \
  -X POST "${OO_URL}/${STREAM}/_json" --data-binary "@${TMP}" --max-time 15 || true)"
echo "  ${RESP}"

# 判定放宽：只看 failed 计数（0 = 全部入库；曾经因为写死 successful":1 而把 2 行的情况误报成失败）
case "$RESP" in
  *'"failed":0'*|*'"failed": 0'*|*'"successful":1'*|*'"successful": 1'*)
    cat <<TIP

✅ 已入库。三种测法（反查都要求「关键字 + 时间窗」都有）：

  ① traceId 精查（最确定，走全链路）——对话框里发：
     traceId ${TRACE_ID} 帮我看看为什么失败

  ② 中文关键字模糊查——对话框里发：
     ${KEYWORD}，最近10分钟

  ③ orderNo 业务键模糊查（模拟真实排查：手上只有单号、没有 traceId）——对话框里发：
     orderNo ${ORDER_NO} 报错了，最近10分钟
     （第二个字段在报错描述、第三个在请求报文首字段，都在正文前 200 字内；body 进视图会被截到 200 字，写在报文末尾的关键字搜不到）
TIP
    ;;
  *)
    echo "⚠️  入库失败：检查 OpenObserve 是否在跑（默认 http://localhost:5080）、.env 里的 OO_USERNAME/OO_PASSWORD"
    ;;
esac
