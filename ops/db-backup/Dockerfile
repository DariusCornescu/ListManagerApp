# Daily backup of the production database to Cloudflare R2. Render runs this as a cron job
# (see backend-fastapi/render.yaml and DEPLOYMENT.md).
#
# backup.sh dumps with the client that matches the server's major version. A newer pg_dump can
# read an older server, but its dump can fail to restore there (pg_dump 18 writes
# `SET transaction_timeout`, which PostgreSQL 16 rejects). So the image carries clients 16 to 18
# side by side, under /usr/libexec/postgresqlNN.
#
# The Alpine release is pinned. Packages are pinned to a major version only, because Alpine
# removes old patch builds from its mirrors, and an exact pin would make later builds fail.
FROM alpine:3.23

RUN apk add --no-cache postgresql16-client postgresql17-client postgresql18-client aws-cli

WORKDIR /backup
COPY backup.sh .
# A Windows checkout can turn the script's line endings into CRLF, which sh cannot run.
RUN sed -i 's/\r$//' backup.sh

ENTRYPOINT ["sh", "/backup/backup.sh"]
