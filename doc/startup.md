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
5. `(require '[musics :as m])` — the DSL/session API.
6. `(require '[core.engine.async-engine :as engine])` and
   `(require '[output.midi.midi-live :as live])`.

   **Known repo-state gotcha:** on the `mutations` branch, `musics.clj`'s
   own `connect`/`play` wrappers are commented out (they still reference
   the old, now-removed `output.midi.engine`), so playback goes through
   `core.engine.async-engine` directly instead of `(connect)`/`(play ...)`.
   Check whether that's still true before following the docstring at the
   top of `musics.clj` literally.
7. Write and parse your music:
   ```clojure
   (m/parse "[verse: !mf c4 d4 e4 f4]")
   ```
   This registers `:verse` in the session repo. Parse as many named parts
   as you like; later parses can reference earlier ones.
8. Open the MIDI receiver:
   ```clojure
   (def rcv (live/open-receiver))
   ```
   Auto-finds the VirMIDI → Fluidsynth connection.
9. Wire up the engine once:
   ```clojure
   (engine/set-engine! (engine/engine rcv (atom (:repo @m/session)) :ROOT))
   ```
10. Play:
    ```clojure
    (engine/play :verse)                    ;; single part
    (engine/play [:par :melody :bass])       ;; polyphony -- forks each
                                              ;; onto its own MIDI channel
    ```
11. Stop/silence if needed:
    ```clojure
    (engine/stop!)
    (doseq [ch (range 4)] (live/all-notes-off rcv ch))
    ```

## Other gotchas

- `!tempo:N` as the first instruction in a part controls its playback
  speed (default is 92 BPM if unset).
- This checklist assumes a normal `lein repl` session, where you just
  keep working after `(engine/play ...)` returns. If instead you run a
  one-shot script via `lein run -m clojure.main script.clj`, the MIDI
  receiver's non-daemon thread keeps the JVM alive after playback
  finishes -- end the script with `(System/exit 0)` after your
  `Thread/sleep`, or the process will hang.
