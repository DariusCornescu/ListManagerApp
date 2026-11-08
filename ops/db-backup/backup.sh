#!/bin/sh
# Dump the database, check the dump, then upload it to Cloudflare R2.
#
# Required: DATABASE_URL, R2_ACCOUNT_ID, R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY, R2_BUCKET.
# Optional: R2_ENDPOINT overrides the R2 address (used for local tests against MinIO).
#
# Nothing is uploaded unless the dump is complete and readable. No value of any variable is
# ever printed, because DATABASE_URL holds the database password.
set -eu

fail() {
    echo "backup failed: $*" >&2
    exit 1
}

missing=""
for name in DATABASE_URL R2_ACCOUNT_ID R2_ACCESS_KEY_ID R2_SECRET_ACCESS_KEY R2_BUCKET; do
    eval "value=\${$name:-}"
    [ -n "$value" ] || missing="$missing $name"
done
[ -z "$missing" ] || fail "missing settings:$missing"

endpoint="${R2_ENDPOINT:-https://${R2_ACCOUNT_ID}.r2.cloudflarestorage.com}"
export AWS_ACCESS_KEY_ID="$R2_ACCESS_KEY_ID"
export AWS_SECRET_ACCESS_KEY="$R2_SECRET_ACCESS_KEY"
export AWS_DEFAULT_REGION=auto
export AWS_PAGER=""

# 1. Use the client that matches the server. A newer pg_dump's output can fail to restore on the
#    server it came from.
version_num="$(psql "$DATABASE_URL" --no-psqlrc -tAc "SHOW server_version_num" 2>/dev/null)" \
    || fail "could not reach the database"
major=$((version_num / 10000))
bin="/usr/libexec/postgresql${major}"
[ -x "$bin/pg_dump" ] || fail "no pg_dump for PostgreSQL ${major} in this image; add it to the Dockerfile"

stamp="$(date -u +%Y%m%dT%H%M%SZ)"
key="listmanager/$(date -u +%Y/%m/%d)/listmanager-${stamp}-pg${major}.dump"
dump="$(mktemp /tmp/listmanager.XXXXXX)"
trap 'rm -f "$dump" "$dump.list"' EXIT

# 2. Dump to a local file first. Streaming straight to the bucket would upload a truncated
#    file that looks valid if pg_dump stopped halfway.
"$bin/pg_dump" --format=custom --no-owner --no-privileges --file="$dump" "$DATABASE_URL" \
    || fail "pg_dump could not dump the database"

# 3. The dump must be readable and must hold the tables the app cannot live without.
"$bin/pg_restore" --list "$dump" > "$dump.list" || fail "the dump is not readable by pg_restore"
for table in users products global_sessions; do
    grep -q "TABLE DATA public ${table} " "$dump.list" \
        || fail "the dump has no data for table ${table}"
done

# 4. Upload, then confirm the object is there with the same size.
size="$(wc -c < "$dump" | tr -d ' ')"
aws s3 cp "$dump" "s3://${R2_BUCKET}/${key}" --endpoint-url "$endpoint" --only-show-errors \
    || fail "the upload to the bucket did not succeed"
stored="$(aws s3api head-object --bucket "$R2_BUCKET" --key "$key" --endpoint-url "$endpoint" \
    --query ContentLength --output text)" || fail "the uploaded backup could not be found"
[ "$stored" = "$size" ] || fail "the stored backup has $stored bytes instead of $size"

echo "backup ok: ${key} (${size} bytes, PostgreSQL ${major})"
