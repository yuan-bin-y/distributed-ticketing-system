#!/bin/sh
set -eu
if [ -f /run/secrets/mysql-password ]; then export LOCAL_MYSQL_PASSWORD="$(cat /run/secrets/mysql-password)"; fi
export SPRING_DATA_REDIS_PASSWORD="$(cat /run/secrets/redis-password)"
if [ -f /run/secrets/rabbit-password ]; then export LOCAL_RABBITMQ_PASSWORD="$(cat /run/secrets/rabbit-password)"; fi
export SPRING_CLOUD_NACOS_DISCOVERY_USERNAME=nacos
export SPRING_CLOUD_NACOS_CONFIG_USERNAME=nacos
export SPRING_CLOUD_NACOS_DISCOVERY_PASSWORD="$(cat /run/secrets/nacos-admin-password)"
export SPRING_CLOUD_NACOS_CONFIG_PASSWORD="$SPRING_CLOUD_NACOS_DISCOVERY_PASSWORD"
if [ -f /run/secrets/auth-admin-password ]; then
    export AUTH_BOOTSTRAP_ADMIN_USERNAME=ha_admin
    export AUTH_BOOTSTRAP_ADMIN_PASSWORD="$(cat /run/secrets/auth-admin-password)"
fi
exec java -jar /app/app.jar
