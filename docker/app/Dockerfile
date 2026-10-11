# customer-platform 运行时镜像：app.jar + lib 全部打进镜像（自包含，不依赖运行时挂载）。
#
# 位置：docker/app/Dockerfile；两个 compose 均以 `dockerfile: docker/app/Dockerfile` 引用；build context = 项目根。
# COPY 源 = 构建产物原位（唯一事实源，根下不再另存副本）：
#   - platform-bootstrap/target/platform-bootstrap-0.0.1-SNAPSHOT.jar   build-local.sh 产出的瘦 jar
#   - platform-bootstrap/target/lib/                                   第三方依赖
# 服务器部署按相同相对路径摆放（部署包已内置该结构）。
#
# 更新业务代码：重新 build-local.sh → 上传新 jar（到 platform-bootstrap/target/）→ docker compose --project-directory . -f docker/docker-compose.server.yml up -d --build。
#   jar 里含全部业务代码（shade 合并了 platform-* 模块）+ 前端；lib/ 只有第三方依赖，pom 不变就不用重传。
#   classpath 把 app.jar 放前：服务器上若残留旧的 lib/platform-*.jar，也不会盖过新代码。
# 更新依赖：pom 变更后传整个 lib/ 再 up --build。
# 运行时数据（LiveRAG parquet）走 compose 挂载的 data/，不进镜像。
#
# 注意：jar 文件名含版本号（platform-bootstrap-0.0.1-SNAPSHOT.jar），升级版本时同步改下方 COPY 源名。
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY platform-bootstrap/target/lib/ /app/lib/
COPY platform-bootstrap/target/platform-bootstrap-0.0.1-SNAPSHOT.jar /app/app.jar
ENV JAVA_OPTS="-Xms256m -Xmx768m -XX:MaxMetaspaceSize=256m -XX:+UseG1GC"
EXPOSE 9081
ENTRYPOINT ["sh","-c","java $JAVA_OPTS -cp \"/app/app.jar:/app/lib/*\" com.jjx.customer.platform.PlatformApplication"]
