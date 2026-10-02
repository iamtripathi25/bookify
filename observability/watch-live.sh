#!/usr/bin/env bash
# Point the local Prometheus at a deployed Bookify, so the local Grafana dashboard can watch it.
#   observability/watch-live.sh https://bookify-production-xxxx.up.railway.app
#   observability/watch-live.sh --off
# Then pick Environment = live at the top of the dashboard.
set -euo pipefail
cd "$(dirname "$0")"
target=live/targets.json
if [ "${1:-}" = "--off" ]; then
  rm -f "$target"
  echo "stopped watching the live instance"
  exit 0
fi
url="${1:?usage: $0 <https-url> | --off}"
host="${url#https://}"; host="${host#http://}"; host="${host%%/*}"
printf '[{"targets": ["%s"], "labels": {"env": "live"}}]\n' "$host" > "$target"
echo "watching https://$host/actuator/prometheus (env=live); refresh Grafana in ~15s"
