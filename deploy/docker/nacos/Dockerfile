FROM eclipse-temurin:17-jre-jammy
WORKDIR /opt/nacos
# 构建上下文由prepare脚本从本机官方发行包中准备，不复制原Nacos数据和凭证。
COPY nacos-server.jar /opt/nacos/target/nacos-server.jar
COPY application.properties /opt/nacos/conf/application.properties
RUN mkdir -p /opt/nacos/data /opt/nacos/logs && chown -R 10001:10001 /opt/nacos
USER 10001:10001
EXPOSE 8080 8848 9848 9849
# 直接exec Java作为PID1，容器停止时能收到SIGTERM。
ENTRYPOINT ["java", "-Xms256m", "-Xmx512m", "-Duser.home=/opt", "-Dnacos.standalone=true", "-Dnacos.deployment.type=merged", "-Dnacos.home=/opt/nacos", "-jar", "/opt/nacos/target/nacos-server.jar", "--spring.config.additional-location=file:/opt/nacos/conf/"]
