# 提供 Java 17 运行环境
FROM eclipse-temurin:17-jre-jammy

# 容器里的工作目录
WORKDIR /app

# 将本机打包好的 JAR 复制到镜像中
COPY --chown=10001:10001 target/ticket-event-service-1.0-SNAPSHOT.jar app.jar

# 与其他业务容器统一使用非root用户。
USER 10001:10001

# 声明应用使用的端口
EXPOSE 8061

# 容器启动时执行
ENTRYPOINT ["java", "-jar", "app.jar"]
