#!/usr/bin/env bash
# 本地构建产物（lib 外置部署模式）：
#   1. 前端 dist → jar 的 static
#   2. mvn package → 瘦 app.jar（业务代码 + 前端 dist，每次变）+ lib/（依赖，pom 不动就不变）
# 产物在 rag-core/target/ 下：
#   - rag-core-0.0.1-SNAPSHOT.jar   瘦 jar（业务 + dist）
#   - lib/                          依赖（首次传服务器，之后不变）
# 用法：bash build-local.sh
set -euo pipefail

PROJECT_DIR="D:/04_projects/20_springai-rag"
export JAVA_HOME="D:/03_developtools/02_jdk/ms-21.0.11"
export PATH="$JAVA_HOME/bin:$PATH"

cd "$PROJECT_DIR"

echo "=== [1/3] 前端构建（npm run build）==="
cd frontend && npm run build && cd ..

echo "=== [2/3] dist 复制到 jar 的 static（Spring Boot 打进 jar）==="
STATIC="rag-core/src/main/resources/static"
rm -rf "$STATIC"
mkdir -p "$STATIC"
cp -r frontend/dist/* "$STATIC/"

echo "=== [3/3] 后端打包（mvn package：瘦 jar + 依赖拷到 lib/）==="
mvn -B clean package -DskipTests -pl rag-core -am

echo ""
echo "✅ 完成。产物："
ls -lh rag-core/target/rag-core-0.0.1-SNAPSHOT.jar | awk '{print "  瘦 jar："$5"\t"$9}'
echo "  依赖：$(ls rag-core/target/lib/ | wc -l) 个 jar，共 $(du -sh rag-core/target/lib/ | cut -f1)"
