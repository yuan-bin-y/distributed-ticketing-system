FROM haproxy:3.0-alpine
USER root
RUN apk add --no-cache mariadb-client mariadb-connector-c
COPY check-primary.sh /usr/local/bin/check-primary
RUN chmod 755 /usr/local/bin/check-primary
