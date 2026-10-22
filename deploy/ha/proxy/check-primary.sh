#!/bin/sh
# 只把仍在多数派内且可写的PRIMARY作为后端；不输出查询结果或凭证。
PATH=/usr/bin:/bin
export PATH
export MYSQL_PWD="$(cat /run/secrets/mysql-password)"
result="$(mysql --connect-timeout=2 --skip-ssl -h "$3" -P "$4" -u root -Nse "SELECT COUNT(*) FROM performance_schema.replication_group_members WHERE MEMBER_ID=@@server_uuid AND MEMBER_STATE='ONLINE' AND MEMBER_ROLE='PRIMARY' AND @@global.super_read_only=0" 2>/dev/null)" || exit 1
[ "$result" = "1" ]
