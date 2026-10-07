#!/bin/bash
set -eu
# 只初始化缺失配置；重启时保留用户已经发布的值。
for config in /configs/*.yaml; do
  data_id=$(basename "$config")
  code=$(curl -sS --max-time 15 -o /tmp/nacos-config-response -w '%{http_code}' --get "http://nacos:8848/nacos/v1/cs/configs" --data-urlencode "dataId=$data_id" --data-urlencode 'group=TICKET_DOCKER')
  if [ "$code" = 200 ]; then
    echo "Keep existing config: $data_id"
  elif [ "$code" = 404 ]; then
    result=$(curl -fsS --max-time 15 'http://nacos:8848/nacos/v1/cs/configs' --data-urlencode "dataId=$data_id" --data-urlencode 'group=TICKET_DOCKER' --data-urlencode "content@$config" --data-urlencode 'type=yaml')
    [ "$result" = true ] || { echo "Failed to publish $data_id" >&2; exit 1; }
    echo "Published config: $data_id"
  else
    echo "Nacos configuration query returned HTTP $code" >&2
    exit 1
  fi
done
