#!/usr/bin/env bash
# Render the human-facing Markdown docs (doc/md/, plus README and
# CLAUDE.md) to HTML in doc/html/ and PDF in doc/pdf/, both git-ignored
# (but doc/html/algo-cookbook.html, which scripts/algo-cookbook.clj
# writes): the .md files stay the source, GitHub renders them too.
#
#   scripts/docs.sh           HTML + PDF
#   scripts/docs.sh --html    HTML only (fast)
#
# Then open doc/html/index.html (the README) in a browser, or the PDFs
# in doc/pdf/. Needs pandoc; PDFs need google-chrome (or chromium).
set -euo pipefail
cd "$(dirname "$0")/.."

out=doc/html
pdf=doc/pdf
mkdir -p "$out" "$pdf"
cp scripts/docs/docs.css "$out/"

# source .md -> page name
declare -A pages=(
  [README.md]=index
  [doc/md/setup.md]=setup
  [doc/md/algorithms.md]=algorithms
  [doc/md/bridge-table.md]=bridge-table
  [doc/md/counterpoint.md]=counterpoint
  [doc/md/algo-context.md]=algo-context
  [doc/md/algo-space.md]=algo-space
  [doc/md/grid.md]=grid
  [doc/md/mu-repl.md]=mu-repl
  [doc/md/mu-tutorial.md]=mu-tutorial
  [doc/md/grammar-notes.md]=grammar-notes
  [doc/md/parsing.md]=parsing
  [doc/md/lilypond.md]=lilypond
  [doc/md/domain.md]=domain
  [doc/md/walk-through.md]=walk-through
  [doc/md/tutorial.md]=tutorial
  [doc/md/decisions.md]=decisions
  [CLAUDE.md]=CLAUDE
)
nav='<nav class="docs"><a href="index.html">musics</a> · <a href="setup.html">setup</a> · <a href="tutorial.html">tutorial</a> · <a href="mu-tutorial.html">mu</a> · <a href="algorithms.html">algorithms</a> · <a href="algo-cookbook.html">cookbook</a> · <a href="parsing.html">parsing</a> · <a href="lilypond.html">lilypond</a> · <a href="domain.html">domain</a> · <a href="walk-through.html">walk-through</a> · <a href="decisions.html">decisions</a> · <a href="CLAUDE.html">architecture</a></nav>'
navfile=$(mktemp); echo "$nav" > "$navfile"; trap 'rm -f "$navfile"' EXIT

for src in "${!pages[@]}"; do
  name=${pages[$src]}
  title=$(grep -m1 '^# ' "$src" | sed 's/^# //; s/`//g')
  pandoc "$src" --from gfm --to html5 --standalone \
    --metadata title="${title:-$name}" --variable title= \
    --css docs.css --lua-filter scripts/docs/md-links.lua \
    --include-before-body "$navfile" \
    --output "$out/$name.html"
done
echo "HTML: $out/index.html (${#pages[@]} pages + the cookbook)"

[[ "${1:-}" == "--html" ]] && exit 0
chrome=$(command -v google-chrome || command -v chromium || command -v chromium-browser || true)
if [[ -z "$chrome" ]]; then echo "no Chrome/Chromium: skipping PDFs"; exit 0; fi
for f in "$out"/*.html; do
  "$chrome" --headless=new --no-pdf-header-footer --print-to-pdf="$pdf/$(basename "${f%.html}").pdf" "$f" 2>/dev/null
done
echo "PDF:  $pdf/"
