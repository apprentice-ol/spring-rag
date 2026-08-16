#!/usr/bin/env bash
# =============================================================================
# 敏感信息提交检查：公网 IP / 邮箱账号 / 密码 / API key / token
#
# 用法：
#   scripts/check-secrets.sh             # 检查全部入库文本文件
#   scripts/check-secrets.sh --cached    # 只检查暂存区（pre-commit 用）
#
# 命中即阻断（exit 1）。示例一律用占位（xxx / <占位> / ${VAR} / example / Dev* 假值）。
# 确认是误报时：git commit --no-verify 跳过，或把对应模式加进下方 PLACEHOLDER 白名单。
# =============================================================================
set -uo pipefail

PLACEHOLDER='(xxx|XXX|\$\{|<[A-Za-z一-龥]|dev\.local|DevOnly|DevPg2026|DevRedis2026|DevCh2026|DevMinioSecret2026|lf-[a-z-]*dev|a1b2c3d4|example|changeme|placeholder|dummy|your[_-]|占位|sk-lf-|pk-lf-|git@|base64\(|localhost|=[[:space:]]*null\b|:[[:space:]]*(string|number|boolean|any|undefined)\b)'

# 只扫文本类文件；排除构建产物/依赖/锁文件（误报重灾区）
SCAN_EXT='\.(md|yml|yaml|java|xml|properties|sh|sql|ts|js|vue|json|example|txt|cfg|conf|ini|env[^/]*)$|^\.env'
EXCLUDE='(node_modules/|target/|frontend/dist/|src/main/resources/static/|package-lock\.json|\.min\.|/dist/)'

if [ "${1:-}" = "--cached" ]; then
  LIST=$(git diff --cached --name-only --diff-filter=ACM 2>/dev/null)
else
  LIST=$(git ls-files 2>/dev/null)
fi

# 过滤出待扫文件（批量 grep，Windows 下逐文件循环太慢）
mapfile -t FILES < <(echo "$LIST" | grep -E "$SCAN_EXT" | grep -vE "$EXCLUDE" | while IFS= read -r f; do [ -f "$f" ] && echo "$f"; done)

HITS=0
if [ ${#FILES[@]} -gt 0 ]; then
  # ① 邮箱账号（@domain 形态）
  grep -nHE '[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+' "${FILES[@]}" 2>/dev/null \
    | grep -viE "$PLACEHOLDER" | sed 's|^|邮箱账号 |' && HITS=1

  # ② API key / token（sk- / pk- 开头的真实 key 形态）
  grep -nHE '(sk|pk)-[A-Za-z0-9]{20,}' "${FILES[@]}" 2>/dev/null \
    | grep -viE "$PLACEHOLDER" | sed 's|^|API-key |' && HITS=1

  # ③ 密码/密钥/令牌赋值（关键词后跟非占位值；\b 排除 continuationToken 等复合词）
  grep -niHE '\b(password|passwd|secret|token|api[_-]?key|access[_-]?key|encryption[_-]?key)\b[[:space:]]*[:=][[:space:]]*[^[:space:]$<{]' "${FILES[@]}" 2>/dev/null \
    | grep -viE "$PLACEHOLDER" | sed 's|^|密码/密钥 |' && HITS=1

  # ④ 公网 IPv4（排除环回/私网/广播/示例 DNS、占位、以及 -/_ 连接的版本号形态如 jdbc-1.5.5.1）
  grep -nHE '\b([0-9]{1,3}\.){3}[0-9]{1,3}\b' "${FILES[@]}" 2>/dev/null \
    | grep -vE '(127\.|0\.0\.0\.0|10\.|192\.168\.|172\.(1[6-9]|2[0-9]|3[01])\.|255\.|8\.8\.8\.8|1\.1\.1\.1|x\.x\.x\.x)' \
    | grep -viE "$PLACEHOLDER" | grep -vE '([-_][0-9]{1,3}\.[0-9]|[Vv]ersion)' \
    | sed 's|^|公网IP |' && HITS=1
fi

if [ "$HITS" = "1" ]; then
  echo ""
  echo "❌ 检出疑似敏感信息（真实 IP / 账号 / 密码 / key）。请改为占位（xxx、<占位>、\${VAR}）或移入 .env 后重试。"
  echo "   确认全部为误报：git commit --no-verify"
  exit 1
fi
echo "✅ 敏感信息检查通过（$([ "${1:-}" = "--cached" ] && echo 暂存区 || echo 全部入库文件)，共 ${#FILES[@]} 个文本文件）"
exit 0
