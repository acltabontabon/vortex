#!/usr/bin/env bash
# Renders docs/**/*.adoc to build/docs/**/*.html with Asciidoctor.
#
# Common presentation attributes (icons, source highlighting) are set here rather than
# repeated in every document header — see docs/index.adoc for the source tree this renders.
set -euo pipefail
cd "$(dirname "$0")/.."

if ! command -v asciidoctor >/dev/null 2>&1; then
  echo "error: asciidoctor not found on PATH." >&2
  echo "       install it with: gem install asciidoctor" >&2
  exit 1
fi

out_root="build/docs"
rm -rf "$out_root"

status=0
while IFS= read -r src; do
  rel="${src#docs/}"
  out="$out_root/${rel%.adoc}.html"
  mkdir -p "$(dirname "$out")"
  echo "asciidoctor $src"
  asciidoctor \
    -a icons=font \
    -a source-highlighter=highlight.js \
    -a nofooter \
    --failure-level=WARN \
    -o "$out" "$src" || status=1
done < <(find docs -name '*.adoc' | sort)

if [ "$status" -eq 0 ]; then
  echo "Rendered documentation to $out_root/"
else
  echo "Asciidoctor reported warnings or errors — see output above." >&2
fi
exit "$status"
