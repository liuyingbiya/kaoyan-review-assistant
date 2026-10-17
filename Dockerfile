# ============================================================
#  多阶段构建：编译阶段用完整 Maven+JDK，运行阶段只要 JRE
#  这样最终镜像里没有源码、没有 Maven、没有 JDK —— 体积小、攻击面也小
# ============================================================

# ---------- 阶段 1：构建 ----------
FROM maven:3.8-eclipse-temurin-8 AS build
WORKDIR /app

# 先只拷 pom，让依赖层能被缓存：源码改动不会触发重新下载依赖
COPY backend/pom.xml .
COPY backend/.mvn/maven.config .mvn/maven.config
COPY backend/.mvn/settings.xml .mvn/settings.xml
RUN mvn -B -q dependency:go-offline

# 再拷源码并打包
COPY backend/src src
RUN mvn -B -q -DskipTests package

# ---------- 阶段 2：运行 ----------
FROM eclipse-temurin:8-jre
WORKDIR /app

ENV TZ=Asia/Shanghai
ENV RAG_SEED_DIR=/app/seed

COPY --from=build /app/target/*.jar /app/app.jar
COPY seed /app/seed

EXPOSE 8081

ENTRYPOINT ["java", "-Dfile.encoding=UTF-8", "-jar", "/app/app.jar"]
