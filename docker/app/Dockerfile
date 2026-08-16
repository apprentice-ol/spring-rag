# springai-rag 运行时镜像：app.jar + lib 全部打进镜像（自包含，不依赖运行时挂载）。
#
# build context = 项目根（compose 的 build.context 指向 `.`，本文件在 docker/app/ 下由 dockerfile 键引用），需含：
#   - rag-core-0.0.1-SNAPSHOT.jar   build-local.sh 产出的瘦 jar（rag-core/target/ 下），scp 原名上传即可，无需重命名
#   - lib/                          build-local.sh 产出的依赖（rag-core/target/lib/），scp 成 lib/
#
# 更新业务代码 / 依赖：scp 新 jar / lib 到项目根 → docker compose up -d --build（重 build 镜像）。
# 运行时数据（LiveRAG parquet）走 compose 挂载的 data/，不进镜像。
#
# 注意：jar 文件名含版本号（rag-core-0.0.1-SNAPSHOT.jar），升级版本时同步改下方 COPY 源名。
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY lib/ /app/lib/
COPY rag-core-0.0.1-SNAPSHOT.jar /app/app.jar
ENV JAVA_OPTS="-Xms256m -Xmx768m -XX:MaxMetaspaceSize=256m -XX:+UseG1GC"
EXPOSE 9081
ENTRYPOINT ["sh","-c","java $JAVA_OPTS -cp \"/app/lib/*:/app/app.jar\" com.nageoffer.ai.rag.SpringAiRagApplication"]
