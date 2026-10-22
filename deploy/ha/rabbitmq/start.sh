#!/bin/sh
set -eu
export RABBITMQ_DEFAULT_PASS="$(cat /run/secrets/rabbit-password)"
export RABBITMQ_ERLANG_COOKIE="$(cat /run/secrets/rabbit-cookie)"
printf '%s' "$RABBITMQ_ERLANG_COOKIE" > /var/lib/rabbitmq/.erlang.cookie
chmod 600 /var/lib/rabbitmq/.erlang.cookie
chown rabbitmq:rabbitmq /var/lib/rabbitmq/.erlang.cookie
exec /usr/local/bin/docker-entrypoint.sh rabbitmq-server
