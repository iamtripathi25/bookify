#!/usr/bin/env bash
# One-command on-sale stampede against a Bookify deployment. Needs JDK 21+ (no build step).
#   ./burst.sh http://localhost:8080
#   BOOKIFY_ADMIN_KEY=... ./burst.sh https://<live-url>
set -euo pipefail
cd "$(dirname "$0")"
if ! command -v java >/dev/null 2>&1; then
  echo "java not found: install JDK 21 or newer" >&2
  exit 2
fi
major=$(java -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.specification.version/ {print $2}')
if [ "${major%%.*}" -lt 21 ]; then
  echo "JDK 21 or newer required (found $major)" >&2
  exit 2
fi
exec java -Xss512k burst/Burst.java "$@"
