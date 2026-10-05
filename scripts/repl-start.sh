#!/usr/bin/env bash
# Start a headless nREPL for this project unless one is already listening.
# Returns at once; the REPL needs ~20 s to load. Log: target/repl.log.
# Used by the SessionStart hook in .claude/settings.json; evaluate with
# scripts/nrepl.py.
cd "$(dirname "$0")/.." || exit 1
PORT="${NREPL_PORT:-7888}"
if (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null; then
  echo "nREPL already running on port $PORT"
  exit 0
fi
mkdir -p target
setsid nohup lein repl :headless :port "$PORT" > target/repl.log 2>&1 < /dev/null &
echo "nREPL starting on port $PORT (log: target/repl.log)"
