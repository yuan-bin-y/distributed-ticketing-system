#!/bin/sh
set -eu
export HA_MYSQL_PASSWORD="$(cat /run/secrets/mysql-password)"
export HA_NACOS_IDENTITY="$(cat /run/secrets/nacos-identity)"
export HA_NACOS_TOKEN_SECRET="$(cat /run/secrets/nacos-token-secret)"
exec java -Xms128m -Xmx256m -XX:ActiveProcessorCount=2 -XX:MaxDirectMemorySize=64m -Duser.home=/opt -Dnacos.standalone=false -Dnacos.deployment.type=merged -Dnacos.home=/opt/nacos -Dnacos.inetutils.ip-address="$NACOS_NODE_IP" -jar /opt/nacos/target/nacos-server.jar --spring.config.additional-location=file:/run/ha/nacos/
