#!/usr/bin/env bash
# Publishes the local backend (127.0.0.1:8080) at https://carwashapis.prashanthsridhar.com through Cloudflare.
set -euo pipefail
cd "$(dirname "$0")"
exec cloudflared tunnel --config ./config.yml run carwash
