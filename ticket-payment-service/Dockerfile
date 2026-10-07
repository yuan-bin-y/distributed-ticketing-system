# 复用本机已有的Java17运行镜像；先通过Maven生成可执行JAR。
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --chown=10001:10001 target/ticket-payment-service-1.0-SNAPSHOT.jar /app/app.jar
# 业务服务无须以root运行；密钥和服务凭证通过Compose只读挂载。
USER 10001:10001
EXPOSE 8064
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

