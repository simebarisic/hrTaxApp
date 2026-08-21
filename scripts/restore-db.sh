#!/usr/bin/env bash
# Vraca (restore) prethodno spremljen backup u docker-compose Postgres
# kontejner "hrtaxapp-postgres". OPREZ: --clean u backup-db.sh znaci da ovaj
# restore BRISE postojece podatke prije vracanja backupa.
#
# Upotreba:
#   ./scripts/restore-db.sh backups/hrtaxapp-2026-08-21-120000.sql

set -euo pipefail

CONTAINER_NAME="hrtaxapp-postgres"
DB_NAME="hrtaxapp"
DB_USER="hrtaxapp"

if [ -z "${1:-}" ]; then
    echo "Upotreba: $0 <putanja-do-backup.sql>" >&2
    exit 1
fi

INPUT_FILE="$1"
if [ ! -f "$INPUT_FILE" ]; then
    echo "Greska: datoteka '$INPUT_FILE' ne postoji." >&2
    exit 1
fi

if ! docker ps --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}\$"; then
    echo "Greska: kontejner '$CONTAINER_NAME' ne radi. Pokreni 'docker compose up -d postgres' prvo." >&2
    exit 1
fi

read -p "Ovo BRISE postojece podatke u '$DB_NAME' i vraca backup iz '$INPUT_FILE'. Nastaviti? [y/N] " confirm
if [ "$confirm" != "y" ] && [ "$confirm" != "Y" ]; then
    echo "Prekinuto."
    exit 0
fi

echo "Vracam backup ..."
docker exec -i "$CONTAINER_NAME" psql -U "$DB_USER" -d "$DB_NAME" < "$INPUT_FILE"

echo "Gotovo."
