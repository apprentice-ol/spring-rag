#!/usr/bin/env bash
# 本地构建产物（lib 外置部署模式）：
#   1. 前端 dist → jar 的 static
#   2. mvn package → 瘦 app.jar（业务代码 + 前端 dist，每次变）+ lib/（依赖，pom 不动就不变）
#   3. 产物同步到项目根 —— Dockerfile 的 COPY 源是「context 根」，
#      不刷新根目录那份，compose --build 会静默地拿旧 jar 构建出旧镜像（零报错）
# 产物在 platform-bootstrap/target/ 下：
#   - platform-bootstrap-0.0.1-SNAPSHOT.jar   瘦 jar（业务 + dist）
#   - lib/                          依赖（首次传服务器，之后不变）
# 项目根（Dockerfile COPY 源 / scp 上传源）：
#   - platform-bootstrap-0.0.1-SNAPSHOT.jar + lib/
# 用法：bash build-local.sh
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
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

echo "=== [3/4] 后端打包（mvn package：瘦 jar + 依赖拷到 lib/）==="
mvn -B clean package -DskipTests -pl platform-bootstrap -am

echo "=== [4/4] 同步到项目根（Dockerfile COPY 源 / scp 上传源）==="
JAR="platform-bootstrap-0.0.1-SNAPSHOT.jar"
cp -f "platform-bootstrap/target/$JAR" "$JAR"
# lib/ 必须整份刷新：只比对数量或"目录存在就跳过"都会漏掉增删的依赖
# （曾漏掉 platform-console-*.jar → 启动即 NoClassDefFoundError）
rm -rf lib && cp -r platform-bootstrap/target/lib .
echo "  lib/ 已刷新：$(ls lib/ | wc -l) 个 jar"

echo ""
echo "✅ 完成。产物："
ls -lh "$JAR" | awk '{print "  瘦 jar（根目录，scp/COPY 源）："$5"\t"$9}'
echo "  依赖：$(ls lib/ | wc -l) 个 jar，共 $(du -sh lib/ | cut -f1)"
echo ""
echo "部署："
echo "  本地  docker compose up -d --build"
echo "  服务器 scp $JAR root@<SERVER_IP>:/srv/www/spring-rag/ && docker compose -f docker-compose.server.yml up -d --build app"
echo "  ⚠ 漏掉 --build 会复用旧镜像，跑的是旧 jar"
echo "  详见 docker/README.md"
