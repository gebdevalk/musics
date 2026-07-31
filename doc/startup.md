# startup — REPL playback checklist

How to get from a fresh shell to hearing music via the REPL. For the
one-time system configuration this depends on (installing qsynth, the
VirMIDI kernel module, etc.), see `doc/setup.md`.

## One-time system setup (per boot, until `snd-virmidi` auto-loads)

1. Start `qsynth`.
2. Load the virtual MIDI kernel module:
   ```bash
   sudo modprobe snd-virmidi
   ```
   Needs an interactive terminal for the password.
3. Optional check — `aconnect -l` should show `Virtual Raw MIDI 2-0..3`
   connected to `FLUID Synth`. This happens automatically if qsynth's
   MIDI Auto-connect setting is on.

## Every REPL session, to hear sound

4. Start a REPL: `lein repl` (from the project root).
5. `(require '[musics :as m])` — the DSL/session API. This is the whole
   interface; `core.async-engine`/`output.midi.midi-live` don't need
   requiring directly, `musics.clj` wraps both.
6. Write and parse your music. `(parse ...)` only **stages** the result —
   it walks against whatever's already committed, but nothing becomes
   visible until you commit it:
   ```clojure
   (def r (m/parse "{verse: !mf c4 d4 e4 f4}"))
   (m/commit! (:sid r))
   ```
   Parse as many named parts as you like, in one call or several; later
   parses can reference earlier ones once they're committed. A single
   `(parse ...)` call can also define more than one part at once
   (`"{a: ...} {b: ...}"`), landing under one `sid` and committing
   together.
7. Point playback at what you just committed. Committing alone never
   moves what's playing — this is deliberate, so a prepared edit can't
   glitch something already sounding:
   ```clojure
   (m/play-latest!)   ;; or (m/play-tx! some-specific-tx)
   ```
8. Open the MIDI receiver and wire up the engine (once per session):
   ```clojure
   (m/connect)
   ```
   This opens the receiver, builds the engine against `core.repo/play-tx`
   (not a snapshot of the repo — later commits + a `play-latest!`/`play-tx!`
   call are picked up live), and does a brief silent warm-up burst to avoid
   an audio crackle on the first real note.
9. Play:
   ```clojure
   (m/play :verse)                    ;; single part
   (m/play [:par :melody :bass])       ;; polyphony -- forks each
                                       ;; onto its own MIDI channel
   ```
10. Stop/silence if needed:
    ```clojure
    (m/stop!)
    (m/all-notes-off)
    ```

## Live mutation while playing

Since committing and playing are separate steps, you can prepare an edit
mid-performance and either cut over to it immediately or schedule it for a
specific moment:

```clojure
(def r (m/parse "{melody: g4 a4 b4 c5}"))   ;; redefine an existing part
(m/commit! (:sid r))                        ;; committed, but not playing yet
(m/play-latest!)                            ;; ...cut over right now, or:
(m/schedule-tx! :melody :exit :latest)      ;; ...cut over the next time
                                             ;; :melody's section finishes
```

See `core.repo`/`core.conductor` in `CLAUDE.md`'s Architecture section for
the full versioned-store/signal design this builds on.

## Other gotchas

- `!Tempo:N` (or `!T:N`) as an instruction controls playback speed
  (default 92 BPM if unset) — **known bug**: this currently never actually
  reaches playback due to a context-key case mismatch (see CLAUDE.md's
  "Known rough edges"); real playback always runs at the hardcoded 120 BPM
  fallback regardless of what a score's tempo instructions say.
- This checklist assumes a normal `lein repl` session, where you just
  keep working after `(m/play ...)` returns. If instead you run a
  one-shot script via `lein run -m clojure.main script.clj`, the MIDI
  receiver's non-daemon thread keeps the JVM alive after playback
  finishes -- end the script with `(System/exit 0)` after your
  `Thread/sleep`, or the process will hang.
