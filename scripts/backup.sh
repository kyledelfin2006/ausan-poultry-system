#!/usr/bin/env bash
# Dump the compose Postgres (custom format) to a dated file and prune old dumps.
#
# Env (all optional):
#   BACKUP_DIR      where dumps go                 (default ./backups)
#   BACKUP_KEEP     how many dumps to keep         (default 14)
#   COMPOSE_FILE    compose file                   (default compose.prod.yaml)
#   COMPOSE_ENV     env file passed to compose     (default .env)
#   POSTGRES_USER / POSTGRES_DB   read from COMPOSE_ENV if unset
set -euo pipefail

cd "$(dirname "$0")/.."
BACKUP_DIR="${BACKUP_DIR:-./backups}"
BACKUP_KEEP="${BACKUP_KEEP:-14}"
COMPOSE_FILE="${COMPOSE_FILE:-compose.prod.yaml}"
COMPOSE_ENV="${COMPOSE_ENV:-.env}"

envval() { [ -f "$COMPOSE_ENV" ] && grep -E "^$1=" "$COMPOSE_ENV" | tail -1 | cut -d= -f2- | tr -d '\r' || true; }
PGUSER_="${POSTGRES_USER:-$(envval POSTGRES_USER)}"
PGDB_="${POSTGRES_DB:-$(envval POSTGRES_DB)}"
[ -n "$PGUSER_" ] && [ -n "$PGDB_" ] || { echo "POSTGRES_USER / POSTGRES_DB not set (env or $COMPOSE_ENV)" >&2; exit 1; }

mkdir -p "$BACKUP_DIR"
FILE="$BACKUP_DIR/ausan-$(date +%Y%m%d-%H%M%S).dump"
TMP="$FILE.partial"

# -T: no TTY, so binary output is not mangled. Written to .partial first so a failed run never leaves a bad dump.
if ! docker compose -f "$COMPOSE_FILE" --env-file "$COMPOSE_ENV" exec -T postgres \
      pg_dump -U "$PGUSER_" -d "$PGDB_" -Fc > "$TMP"; then
  rm -f "$TMP"; echo "pg_dump failed" >&2; exit 1
fi
[ -s "$TMP" ] || { rm -f "$TMP"; echo "dump is empty" >&2; exit 1; }
mv "$TMP" "$FILE"
echo "Backup written: $FILE ($(wc -c < "$FILE") bytes)"

# Retention: keep the newest $BACKUP_KEEP dumps.
ls -1t "$BACKUP_DIR"/ausan-*.dump 2>/dev/null | tail -n +"$((BACKUP_KEEP + 1))" | while read -r old; do
  echo "Pruning $old"; rm -f -- "$old"
done
