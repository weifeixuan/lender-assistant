# 多阶段构建：构建阶段带 Maven+JDK，运行阶段只带 JRE（镜像小、攻击面小）
# 国内网络可加镜像前缀构建：docker build --build-arg REGISTRY_PREFIX=docker.1ms.run/ .
# 或在 compose 里设 REGISTRY_PREFIX 环境变量。
# 注意：ARG 必须在任何 FROM 之前（全局作用域）才能被 FROM 行引用。

ARG REGISTRY_PREFIX=

# ---------- 构建阶段 ----------
FROM ${REGISTRY_PREFIX}maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# 先拷 pom 单独下依赖：源码改动时不重拉依赖层（Docker 层缓存）
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q package -DskipTests

# ---------- 运行阶段 ----------
FROM ${REGISTRY_PREFIX}eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/lender-assistant-0.1.0-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
