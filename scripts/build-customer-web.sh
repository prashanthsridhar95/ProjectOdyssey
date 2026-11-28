#!/usr/bin/env bash
# Builds the customer web app (Compose Multiplatform for the browser) into mobile/app/customer-web/build/dist/wasmJs/productionExecutable.
# docker compose serves that folder on :5175. First build downloads Node.js and takes a few minutes.
# After the build it pre-compresses the big files (brotli and gzip, best level) and tells the page to start downloading
# the WebAssembly straight away instead of waiting for customer.js to ask for it.
set -euo pipefail
cd "$(dirname "$0")/../mobile"
./gradlew :app:customer-web:wasmJsBrowserDistribution
out=app/customer-web/build/dist/wasmJs/productionExecutable
# Cache epoch: the .wasm files are cached for a year under their names, so bump this to force every browser onto new copies.
epoch=r2
for w in "$out"/*.wasm; do
  base=$(basename "$w" .wasm); new="$base.$epoch.wasm"
  mv "$w" "$out/$new"
  sed -i.bak "s/\"$base\.wasm\"/\"$new\"/g" "$out/customer.js" && rm -f "$out/customer.js.bak"
done
# customer.js gets a content-hashed name too: Cloudflare keeps .js in browsers for hours whatever we send, so a fixed
# name could serve a stale copy after a release. index.html (never cached) points at the current one.
rm -f "$out"/customer.*.js
hash=$(shasum "$out/customer.js" | cut -c1-12)
mv "$out/customer.js" "$out/customer.$hash.js"
sed -i.bak "s|<script src=\"customer.js\" defer></script>|<script src=\"customer.$hash.js\" defer></script>|" "$out/index.html" && rm -f "$out/index.html.bak"
grep -q "customer.$hash.js" "$out/index.html" || { echo "index.html does not load customer.$hash.js" >&2; exit 1; }
rm -f "$out"/*.br "$out"/*.gz "$out"/composeResources/*/*/*.br "$out"/composeResources/*/*/*.gz "$out"/customer.js.map
files=("$out"/*.wasm "$out"/customer.*.js)
while IFS= read -r f; do files+=("$f"); done < <(find "$out/composeResources" -name '*.ttf')
for f in "${files[@]}"; do
  if command -v brotli >/dev/null; then brotli -q 11 -f -k -o "$f.br" "$f"; fi
  gzip -9 -k -f -n "$f"
done
# Preload the WebAssembly so it downloads alongside customer.js, not after it.
links=""
for w in "$out"/*.wasm; do links+="    <link rel=\"preload\" href=\"$(basename "$w")\" as=\"fetch\" type=\"application/wasm\" crossorigin>\n"; done
python3 - "$out/index.html" "$links" <<'PY'
import sys
p, links = sys.argv[1], sys.argv[2].replace('\\n', '\n')
s = open(p).read()
if 'rel="preload"' not in s:
    s = s.replace('    <script src="config.js"></script>', links + '    <script src="config.js"></script>', 1)
open(p, 'w').write(s)
PY
echo "Built $out"; ls -la "$out"/*.wasm* "$out"/customer.*
