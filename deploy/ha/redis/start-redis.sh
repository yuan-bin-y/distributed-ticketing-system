#!/bin/sh
set -eu
password="$(cat /run/secrets/redis-password)"
if [ ! -f /data/redis.conf ]; then
    {
        echo "bind 0.0.0.0"
        echo "protected-mode yes"
        echo "appendonly yes"
        echo "requirepass $password"
        echo "masterauth $password"
        echo "replica-announce-ip $REDIS_NODE_IP"
        echo "min-replicas-to-write 1"
        echo "min-replicas-max-lag 5"
        if [ "$HOSTNAME" != redis1 ]; then echo "replicaof 172.30.246.51 6379"; fi
    } > /data/redis.conf
fi
exec redis-server /data/redis.conf
