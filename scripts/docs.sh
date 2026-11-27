#!/usr/bin/env bash
# Render the human-facing Markdown docs (doc/md/, plus README and
# CLAUDE.md) to HTML in doc/html/, git-ignored: the .md files stay the
# source, GitHub renders them too.
#
#   scripts/docs.sh
#
# Then open doc/html/index.html (the README) in a browser. Needs pandoc.
set -euo pipefail
cd "$(dirname "$0")/.."

out=doc/html
mkdir -p "$out"
# what a run writes is rebuilt whole: a page whose source is gone goes too
find "$out" -maxdepth 1 -name '*.html' -delete
cp scripts/docs/docs.css "$out/"

# source .md -> page name
declare -A pages=(
  [README.md]=index
  [doc/md/setup.md]=setup
  [doc/md/counterpoint.md]=counterpoint
  [doc/md/algo-context.md]=algo-context
  [doc/md/grid.md]=grid
  [doc/md/mu-repl.md]=mu-repl
  [doc/md/mu-words.md]=mu-words
  [doc/md/start-here.md]=start-here
  [doc/md/cheat-sheet.md]=cheat-sheet
  [doc/md/polations.md]=polations
  [doc/md/algo-access.md]=algo-access
  [doc/md/mu-tutorial.md]=mu-tutorial
  [doc/md/algo-cookbook.md]=algo-cookbook
  [doc/md/namespace.md]=namespace
  [doc/md/grammar-notes.md]=grammar-notes
  [doc/md/parsing.md]=parsing
  [doc/md/lilypond.md]=lilypond
  [doc/md/domain.md]=domain
  [doc/md/walk-through.md]=walk-through
  [doc/md/tutorial.md]=tutorial
  [doc/md/instruments.md]=instruments
  [doc/md/blending.md]=blending
  [doc/md/lilypond-chordmode-chords.md]=lilypond-chordmode-chords
  [doc/md/algo-signal-chains.md]=algo-signal-chains
  [doc/md/feedback.md]=feedback
  [doc/md/row-forms.md]=row-forms
  [doc/md/pull-voice.md]=pull-voice
  [doc/md/hoketus-dynamic.md]=hoketus-dynamic
  [doc/md/why/decisions.md]=decisions
  [doc/md/why/algo-audit.md]=algo-audit
  [doc/md/why/assist-with-logic.md]=assist-with-logic
  [doc/md/why/core-logic.md]=core-logic
  [doc/md/why/ideas.md]=ideas
  [doc/md/why/other-libs.md]=other-libs
  [doc/md/why/scoped-tctx.md]=scoped-tctx
  [doc/md/why/postfix-notation.md]=postfix-notation
  [doc/md/why/audits/docs-audit.md]=docs-audit
  [CLAUDE.md]=CLAUDE
)
nav='<nav class="docs"><a href="index.html">musics</a> · <a href="start-here.html">start here</a> · <a href="setup.html">setup</a> · <a href="tutorial.html">tutorial</a> · <a href="cheat-sheet.html">cheat sheet</a> · <a href="mu-tutorial.html">mu</a> · <a href="mu-words.html">words</a> · <a href="mu-repl.html">design</a> · <a href="algo-cookbook.html">algo cookbook</a> · <a href="parsing.html">parsing</a> · <a href="lilypond.html">lilypond</a> · <a href="domain.html">domain</a> · <a href="walk-through.html">walk-through</a> · <a href="decisions.html">why</a> · <a href="CLAUDE.html">architecture</a></nav>'
navfile=$(mktemp); echo "$nav" > "$navfile"; stampfile=$(mktemp); trap 'rm -f "$navfile" "$stampfile"' EXIT

for src in "${!pages[@]}"; do
  name=${pages[$src]}
  title=$(grep -m1 '^# ' "$src" | sed 's/^# //; s/`//g')
  printf '<p class="stamp">%s &middot; rendered %s</p>\n' "$(printf '%s' "${title:-$name}" | sed 's/&/\&amp;/g; s/</\&lt;/g')" "$(date '+%Y-%m-%d %H:%M')" > "$stampfile"
  toc=(); [[ "$name" == decisions ]] && toc=(--toc --toc-depth=2)
  pandoc "$src" --from gfm --to html5 --standalone \
    --metadata title="${title:-$name}" --variable title= \
    "${toc[@]}" --css docs.css --lua-filter scripts/docs/md-links.lua \
    --include-before-body "$navfile" --include-before-body "$stampfile" \
    --output "$out/$name.html"
done
echo "HTML: $out/index.html (${#pages[@]} pages)"
