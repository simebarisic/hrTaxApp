#!/usr/bin/env bash
# Backup Postgres baze (transakcije, keshirani HNB tecajevi) iz docker-compose
# kontejnera "hrtaxapp-postgres" u lokalnu .sql datoteku.
#
# Upotreba:
#   ./scripts/backup-db.sh                 # sprema u backups/hrtaxapp-YYYY-MM-DD-HHMMSS.sql
#   ./scripts/backup-db.sh moj-backup.sql   # sprema u zadano ime

set -euo pipefail

CONTAINER_NAME="hrtaxapp-postgres"
DB_NAME="hrtaxapp"
DB_USER="hrtaxapp"
BACKUP_DIR="$(dirname "$0")/../backups"

mkdir -p "$BACKUP_DIR"

if [ -n "${1:-}" ]; then
    OUTPUT_FILE="$1"
else
    OUTPUT_FILE="$BACKUP_DIR/hrtaxapp-$(date +%Y-%m-%d-%H%M%S).sql"
fi

if ! docker ps --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}\$"; then
    echo "Greska: kontejner '$CONTAINER_NAME' ne radi. Pokreni 'docker compose up -d postgres' prvo." >&2
    exit 1
fi

echo "Spremam backup baze '$DB_NAME' u $OUTPUT_FILE ..."
docker exec "$CONTAINER_NAME" pg_dump -U "$DB_USER" -d "$DB_NAME" --clean --if-exists > "$OUTPUT_FILE"

echo "Gotovo. Velicina: $(du -h "$OUTPUT_FILE" | cut -f1)"
