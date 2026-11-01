#!/usr/bin/env bash
# Build target/mu.jar, the whole of mu in one file, run by bin/mu.
#
#   scripts/build.sh
#
# The version is written into resources/musics/version.txt first, so the
# jar knows it where there is no repo; the file is git-ignored and removed
# afterwards. On a tagged commit it is the tag (v0.4.0-alpha.1 gives
# 0.4.0-alpha.1), else 0.3.<merges>.<commits>, counted by git.
set -euo pipefail
cd "$(dirname "$0")/.."

if tag=$(git describe --exact-match --tags 2>/dev/null); then
  version="${tag#v}"
else
  merges=$(git rev-list --count --first-parent --merges main)
  commits=$(git rev-list --count develop)
  version="0.3.$merges.$commits"
fi

mkdir -p resources/musics
echo "$version" > resources/musics/version.txt
trap 'rm -f resources/musics/version.txt' EXIT

lein uberjar
echo
echo "built target/mu.jar, mu $version -- run it with bin/mu"
