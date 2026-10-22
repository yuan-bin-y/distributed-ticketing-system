#!/bin/sh
set -eu
password="$(cat /run/secrets/redis-password)"
if [ ! -f /data/sentinel.conf ]; then
    {
        echo "bind 0.0.0.0"
        echo "port 26379"
        echo "protected-mode no"
        echo "sentinel resolve-hostnames no"
        echo "sentinel announce-hostnames no"
        echo "sentinel announce-ip $SENTINEL_NODE_IP"
        echo "sentinel monitor ticket-master 172.30.246.51 6379 2"
        echo "sentinel auth-pass ticket-master $password"
        echo "sentinel down-after-milliseconds ticket-master 4000"
        echo "sentinel failover-timeout ticket-master 20000"
        echo "sentinel parallel-syncs ticket-master 1"
    } > /data/sentinel.conf
fi
exec redis-sentinel /data/sentinel.conf
