# ============================================================
#  多阶段构建：编译阶段用完整 Maven+JDK，运行阶段只要 JRE
#  这样最终镜像里没有源码、没有 Maven、没有 JDK —— 体积小、攻击面也小
# ============================================================

# ---------- 阶段 1：构建 ----------
FROM maven:3.8-eclipse-temurin-8 AS build
WORKDIR /app

# 在容器内生成 Maven 配置（阿里云镜像），不拷贝宿主机上的 settings.xml。
# 为什么：项目里那份 .mvn/settings.xml 是给 Windows 本地用的，里面写死了
# D:\... 这样的本地仓库路径 —— 放到 Linux 容器里会直接导致构建失败。
RUN mkdir -p /root/.m2 && printf '%s' \
    '<settings><mirrors><mirror><id>aliyun</id><name>aliyun</name><url>https://maven.aliyun.com/repository/public</url><mirrorOf>central</mirrorOf></mirror></mirrors></settings>' \
    > /root/.m2/settings.xml

# 先只拷 pom，让依赖层能被缓存：源码改动不会触发重新下载依赖
COPY backend/pom.xml .
RUN mvn -B dependency:go-offline

# 再拷源码并打包
COPY backend/src src
RUN mvn -B -DskipTests package

# ---------- 阶段 2：运行 ----------
FROM eclipse-temurin:8-jre
WORKDIR /app

ENV TZ=Asia/Shanghai
ENV RAG_SEED_DIR=/app/seed

COPY --from=build /app/target/*.jar /app/app.jar
COPY seed /app/seed

EXPOSE 8081

ENTRYPOINT ["java", "-Dfile.encoding=UTF-8", "-jar", "/app/app.jar"]
