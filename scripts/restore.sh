#!/usr/bin/env bash
# Restore a dump made by backup.sh into the compose Postgres.
# Usage: scripts/restore.sh <dump-file> [--yes]
#
# The target database must be EMPTY (fresh volume, or drop and recreate it first; see docs/operations.md).
# Stop the app first so nothing writes while restoring:  docker compose -f compose.prod.yaml stop app
set -euo pipefail

cd "$(dirname "$0")/.."
DUMP="${1:-}"
[ -n "$DUMP" ] && [ -f "$DUMP" ] || { echo "Usage: $0 <dump-file> [--yes]" >&2; exit 1; }
COMPOSE_FILE="${COMPOSE_FILE:-compose.prod.yaml}"
COMPOSE_ENV="${COMPOSE_ENV:-.env}"

envval() { [ -f "$COMPOSE_ENV" ] && grep -E "^$1=" "$COMPOSE_ENV" | tail -1 | cut -d= -f2- | tr -d '\r' || true; }
PGUSER_="${POSTGRES_USER:-$(envval POSTGRES_USER)}"
PGDB_="${POSTGRES_DB:-$(envval POSTGRES_DB)}"
[ -n "$PGUSER_" ] && [ -n "$PGDB_" ] || { echo "POSTGRES_USER / POSTGRES_DB not set (env or $COMPOSE_ENV)" >&2; exit 1; }

if [ "${2:-}" != "--yes" ]; then
  echo "About to restore '$DUMP' into database '$PGDB_'. It must be empty; existing objects will cause errors."
  read -r -p "Type RESTORE to continue: " answer
  [ "$answer" = "RESTORE" ] || { echo "Aborted."; exit 1; }
fi

# --exit-on-error: stop at the first problem instead of leaving a half-restored database.
docker compose -f "$COMPOSE_FILE" --env-file "$COMPOSE_ENV" exec -T postgres \
  pg_restore -U "$PGUSER_" -d "$PGDB_" --no-owner --exit-on-error < "$DUMP"
echo "Restore complete. Start the app again:  docker compose -f $COMPOSE_FILE --env-file $COMPOSE_ENV start app"
