#!/usr/bin/env bash
# Renders the CurseForge description images: every tools/curseforge/*.html becomes docs/curseforge/images/<name>.png,
# drawn at 3x by headless Google Chrome. Each page gives its size in GUI units as <meta name="size" content="WxH">.
# Usage: tools/curseforge/render.sh [name ...]   (no names: all pages)
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
out="$here/../../docs/curseforge/images"
chrome="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
mkdir -p "$out"

pages=()
if [ $# -gt 0 ]; then
    for n in "$@"; do pages+=("$here/$n.html"); done
else
    for f in "$here"/*.html; do pages+=("$f"); done
fi

for page in "${pages[@]}"; do
    name="$(basename "$page" .html)"
    size="$(sed -n 's/.*<meta name="size" content="\([0-9]*x[0-9]*\)".*/\1/p' "$page" | head -1)"
    [ -n "$size" ] || { echo "$name: no <meta name=\"size\">" >&2; exit 1; }
    "$chrome" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=3 \
        --window-size="${size/x/,}" --virtual-time-budget=5000 \
        --screenshot="$out/$name.png" "file://$page" >/dev/null 2>&1
    echo "$name.png ($size at 3x)"
done
