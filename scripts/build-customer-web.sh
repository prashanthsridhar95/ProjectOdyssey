#!/usr/bin/env bash
# Builds the customer web app (Compose Multiplatform for the browser) into mobile/app/customer-web/build/dist/wasmJs/productionExecutable.
# docker compose serves that folder on :5175. First build downloads Node.js and takes a few minutes.
set -euo pipefail
cd "$(dirname "$0")/../mobile"
./gradlew :app:customer-web:wasmJsBrowserDistribution
