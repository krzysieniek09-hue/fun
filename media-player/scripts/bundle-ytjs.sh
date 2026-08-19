#!/usr/bin/env bash
# Bundles youtubei.js (YouTube.js) into a single self-contained IIFE the app
# loads lazily for on-device YouTube saving (js/lib/ytjs.bundle.min.js).
# Re-run when YouTube changes break extraction — a newer youtubei.js usually
# has the fix. Needs node/npm.
set -euo pipefail
cd "$(dirname "$0")"

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
(
  cd "$tmp"
  npm init -y > /dev/null
  npm install --no-audit --no-fund youtubei.js esbuild > /dev/null
  printf 'export * from "youtubei.js/web";\n' > entry.mjs
  npx esbuild entry.mjs --bundle --minify --format=iife --global-name=YTJS \
    --outfile=ytjs.bundle.min.js
)
mkdir -p ../js/lib
cp "$tmp/ytjs.bundle.min.js" ../js/lib/
echo "wrote js/lib/ytjs.bundle.min.js ($(du -h ../js/lib/ytjs.bundle.min.js | cut -f1))"
