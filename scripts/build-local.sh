#!/usr/bin/env bash
# 本地构建产物（lib 外置部署模式）：
#   1. 前端 dist → jar 的 static
#   2. mvn package → app.jar（**全部业务代码 + 前端**，约 3MB）+ lib/（仅第三方依赖）
#      platform-bootstrap/pom.xml 用 maven-shade-plugin 把 14 个项目模块（com.jjx.customer）
#      合并进 app.jar，并把它们排除出 lib/ → **部署只需传这一个 jar**。
#      （2026-09-20 之前是「薄 jar + lib 里的模块 jar」，只传薄 jar 等于没更新业务代码，已修）
#   3. 产物就位（唯一位置，不再向根目录另存副本）
# 产物位置（Dockerfile COPY 源 / 服务器上传源）：
#   platform-bootstrap/target/platform-bootstrap-0.0.1-SNAPSHOT.jar   app.jar（全部业务代码 + 前端 dist）
#   platform-bootstrap/target/lib/                                   仅第三方依赖（pom 不变就不用重传）
# 用法：bash scripts/build-local.sh            # 构建（前端 dist 进 jar → 瘦 jar + lib/）
#       bash scripts/build-local.sh --package  # 构建 + 打「全新服务器部署包」（dist/spring-rag-<stamp>.tar.gz，一次性物料不入库）
set -euo pipefail

PACKAGE=0
if [[ "${1:-}" == "--package" ]]; then PACKAGE=1; fi

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"   # scripts/ 的上层 = 项目根
export JAVA_HOME="D:/03_developtools/02_jdk/ms-21.0.11"
export PATH="$JAVA_HOME/bin:$PATH"

cd "$PROJECT_DIR"

echo "=== [1/3] 前端构建（npm run build）==="
cd frontend && npm run build && cd ..

echo "=== [2/3] dist 复制到 jar 的 static（Spring Boot 打进 jar）==="
STATIC="platform-bootstrap/src/main/resources/static"
rm -rf "$STATIC"
mkdir -p "$STATIC"
cp -r frontend/dist/* "$STATIC/"

echo "=== [3/3] 后端打包（mvn package：瘦 jar + 依赖到 target/）==="
mvn -B clean package -DskipTests -pl platform-bootstrap -am
JAR="platform-bootstrap-0.0.1-SNAPSHOT.jar"

echo ""
echo "✅ 完成。产物（唯一位置）："
ls -lh "platform-bootstrap/target/$JAR" | awk '{print "  瘦 jar："$5"\t"$9}'
echo "  依赖：$(ls platform-bootstrap/target/lib/ | wc -l) 个 jar，共 $(du -sh platform-bootstrap/target/lib/ | cut -f1)"
echo ""
if [[ "$PACKAGE" == "1" ]]; then
  echo "=== 服务器部署包（全新空服务器一次性物料） ==="
  STAMP=$(date +%Y%m%d-%H%M)
  OUT="dist/spring-rag-${STAMP}.tar.gz"
  mkdir -p dist
  # lib/ 与 docker/ 较大属正常（lib ~250M、PG 镜像资产 ~68M）——全新部署一次性传输
  tar czf "$OUT" \
    platform-bootstrap/target/platform-bootstrap-0.0.1-SNAPSHOT.jar \
    platform-bootstrap/target/lib/ \
    .dockerignore \
    docker/ \
    scripts/ \
    .env.example
  echo "✓ 部署包: $OUT ($(du -h "$OUT" | cut -f1))"
  echo "  上传: scp $OUT root@<SERVER_IP>:/srv/www/springai-rag/"
  echo "  启动: tar xzf 包 && cp .env.example .env（填值）&& docker compose --project-directory . -f docker/docker-compose.dev.yml up -d --build --wait"
else
  echo "部署："
  echo "  本地  docker compose --project-directory . -f docker/docker-compose.yml -f docker/docker-compose.override.yml up -d --build"
  echo "  服务器 scp platform-bootstrap/target/$JAR root@<SERVER_IP>:/srv/www/<项目名>/platform-bootstrap/target/ && docker compose --project-directory . -f docker/docker-compose.dev.yml up -d --build app"
  echo "  ⚠ 漏掉 --build 会复用旧镜像，跑的是旧 jar"
  echo "  详见 docker/README.md"
fi
