#!/usr/bin/env bash
# Stvara secrets/htpasswd (bcrypt) za basic auth ispred frontenda.
set -euo pipefail
USER_NAME="${1:?Upotreba: $0 <korisnik>}"
cd "$(dirname "$0")/.."
mkdir -p secrets
# koristi httpd:alpine image da ne treba lokalni apache2-utils
docker run --rm -it -v "$PWD/secrets:/out" httpd:2.4-alpine sh -c "htpasswd -cB /out/htpasswd '$USER_NAME'"
echo "Gotovo: secrets/htpasswd"
