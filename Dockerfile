# customer-platform 运行时镜像：app.jar + lib 全部打进镜像（自包含，不依赖运行时挂载）。
#
# 本文件位于项目根，两个 compose 均以 `dockerfile: Dockerfile` 引用；build context = 项目根，需含：
#   - platform-bootstrap-0.0.1-SNAPSHOT.jar   build-local.sh 产出的瘦 jar（platform-bootstrap/target/ 下），scp 原名上传即可，无需重命名
#   - lib/                          build-local.sh 产出的依赖（platform-bootstrap/target/lib/），scp 成 lib/
#
# 更新业务代码：scp 新 jar 到项目根 → docker compose up -d --build（重 build 镜像）。
#   jar 里含全部业务代码（shade 合并了 platform-* 模块）+ 前端；lib/ 只有第三方依赖，pom 不变就不用重传。
#   classpath 把 app.jar 放前：服务器上若残留旧的 lib/platform-*.jar，也不会盖过新代码。
# 更新依赖：pom 变更后 scp 整个 lib/ 再 up --build。
# 运行时数据（LiveRAG parquet）走 compose 挂载的 data/，不进镜像。
#
# 注意：jar 文件名含版本号（platform-bootstrap-0.0.1-SNAPSHOT.jar），升级版本时同步改下方 COPY 源名。
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY lib/ /app/lib/
COPY platform-bootstrap-0.0.1-SNAPSHOT.jar /app/app.jar
ENV JAVA_OPTS="-Xms256m -Xmx768m -XX:MaxMetaspaceSize=256m -XX:+UseG1GC"
EXPOSE 9081
ENTRYPOINT ["sh","-c","java $JAVA_OPTS -cp \"/app/app.jar:/app/lib/*\" com.jjx.customer.platform.PlatformApplication"]
