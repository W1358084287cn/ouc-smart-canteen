# ============================================
# OUC 智慧食堂 —— 多阶段构建 Dockerfile
# 阶段一：Maven 构建 jar
# 阶段二：JRE 8 精简运行环境
# ============================================

# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-8 AS builder

WORKDIR /build

# 先复制 pom.xml 并预下载依赖，利用 Docker 层缓存加速后续构建
COPY pom.xml .
RUN mvn -B dependency:go-offline -Dmaven.test.skip=true || true

# 复制源码并打包
COPY src ./src
RUN mvn -B clean package -Dmaven.test.skip=true -DskipTests

# ---------- 运行阶段 ----------
FROM eclipse-temurin:8-jre-jammy

# 安装中文字体与常用工具，避免二维码/验证码中文乱码
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        fontconfig \
        fonts-noto-cjk \
        tzdata \
        curl \
    && ln -snf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && echo "Asia/Shanghai" > /etc/timezone \
    && fc-cache -fv \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# 创建文件上传持久化目录
RUN mkdir -p /data/img

# 复制构建产物
COPY --from=builder /build/target/*.jar /app/app.jar

# 时区与 JVM 参数
ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-Xms256m -Xmx512m -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"

EXPOSE 8080

# 健康检查：确认应用已就绪
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=5 \
    CMD curl -fs http://localhost:8080/front/index.html || exit 1

# 以生产环境配置启动
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar --spring.profiles.active=prod"]
