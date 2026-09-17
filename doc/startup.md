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
5. `(require '[musics.core :as m])` — the DSL/session API. This is the whole
   interface; `core.async-engine`/`output.midi.midi-live` don't need
   requiring directly, `musics.core` wraps both.
6. Write and parse your music. `(parse ...)` commits the result
   immediately — it walks against whatever's already committed, and the
   result is visible right away, no separate commit step:
   ```clojure
   (m/parse "[verse: !mf c4 d e f]")
   ```
   Parse as many named parts as you like, in one call or several; later
   parses can reference earlier ones once they're committed. A single
   `(parse ...)` call can also define more than one part at once
   (`"[a: ...] [b: ...]"`), committing together as one atomic batch.
7. Open the MIDI receiver and wire up the engine (once per session):
   ```clojure
   (m/connect)
   ```
   This opens the receiver, builds the engine against `core.repo`'s live
   registry (a brand-new voice always starts from whatever's currently
   committed — no separate "point playback" step needed), and does a
   brief silent warm-up burst to avoid an audio crackle on the first
   real note.
8. Play:
   ```clojure
   (m/play :verse)                    ;; single part
   (m/play #{:melody :bass})          ;; polyphony -- forks each
                                       ;; onto its own MIDI channel
   ```
9. Stop/silence if needed:
    ```clojure
    (m/stop!)
    (m/all-notes-off)
    ```

## Live mutation while playing

Because each voice reads through its own private snapshot, captured
once at birth, a later commit never glitches whatever's already
sounding. Nothing else is currently playing when you redefine a part,
so a fresh `play` call just picks up the change automatically:

```clojure
(m/parse "[melody: g4 a b c5]")     ;; redefine an existing part -- c5
                                     ;; is a duration change here, not an
                                     ;; octave -- committed immediately
(m/play :melody)                    ;; a brand-new voice always starts
                                     ;; from whatever's current
```

If a voice IS already sounding the pre-edit content and you want to cut
it over at a chosen boundary instead of restarting it, schedule the
redirect:

```clojure
(m/schedule-tx! :melody :exit)      ;; the next time :melody's section
                                     ;; finishes, redirect that ONE voice
                                     ;; to whatever's currently committed
```

See `core.repo`/`core.conductor` in `CLAUDE.md`'s Architecture section for
the full store/signal design this builds on.

## Shortcut: `mu!` for parsing several parts in a row

Step 6 above is the general form: `(parse "...")`. If you're parsing a
lot of parts back to back, `(m/mu!)` drops into a nested REPL where a
bare (quoted) musics string commits itself, no wrapper call needed:

```clojure
(m/mu!)
mu=> "[verse: !mf c4 d e f]"
{:ids [:verse]}
mu=> (+ 1 2)                 ;; ordinary Clojure still works here too
3
mu=> (exit)
user=>
```

Quotes are still required — this removes the `s!`/`parse` wrapper *call*,
not the string literal. A bare `[verse: ...]` typed with no quotes reads
as an ordinary Clojure vector (and errors on tokens like `!mf`) before
`music-eval` ever sees it, since only bare strings are intercepted, not
arbitrary syntax.

**Leaving `mu!`**: `(exit)`, `(quit)`, `:repl/quit`, or plain EOF
(Ctrl+D) all work — verified directly against a real `lein repl` session,
not just assumed. This needed its own fix: reply's `(exit)`/`(quit)` (the
ones `lein repl`'s own banner advertises) are handled client-side, outside
`clojure.main/repl`'s read/eval loop entirely, so a nested loop like
`mu!` never saw them on its own — typing `(exit)` failed with an
unresolved-symbol error instead of leaving until `music-read` (`mu!`'s
own `:read` hook) started recognizing those forms explicitly. See
`music-eval`/`music-read`'s docstrings in `musics.core` for exactly what
each hook does.

## Calling an algorithm

There's no musics-text syntax for this — `@[ name Arg... ]`
(`AtomicAlgo`)/`@{ name ... }` (`ElementAlgo`) were removed from the
grammar, see CLAUDE.md's "Algorithm registries" section for why. There's
no separate registry for these either anymore (`input/algo_registry.clj`
was removed along with its `musics.core` wrappers — a leftover mechanism
with no grammar entry point left to serve) — call a generative helper
directly as a Clojure function instead:

```clojure
(require '[algo.common.isorhythm :as iso])
(iso/color-talea [60 62 64 65 67 65 64 62] [1/4 1/8 1/16 1/4])
;; => a flat [pitch duration] pair seq -- splice it into a play call, or
;;    build it into real Leaf records and commit-node! it as a real part
```

If you want one wired up as a *per-voice playback* transform instead of
a one-off call, wrap it as a factory and build it under a real name:
`(m/register-factory! :myAlgo (fn [name] (m/build-algo! name my-fn "optional doc")))`,
then `(m/build! :myAlgo :myAlgo)`, then `(m/play id :algo :myAlgo)` (or
`(m/assign-algo! path :myAlgo)` to prepare a track before it starts) —
see CLAUDE.md's "Wall: per-voice playback algorithms" section, and
`doc/algorithms.md`'s "Wall algorithms: writing and using one" for the
fuller walkthrough.

## Other gotchas

- `!tempo:N`/`!Tempo:N`/`!T:N` control playback speed (falls back to 120
  BPM if a part's ctx-chain never sets one). See CLAUDE.md's "Grammar"
  section for the LilyPond-style `note-value=BPM` form and named
  markings (`!allegro`, `!presto`, ...).
- This checklist assumes a normal `lein repl` session, where you just
  keep working after `(m/play ...)` returns. If instead you run a
  one-shot script via `lein run -m clojure.main script.clj`, the MIDI
  receiver's non-daemon thread keeps the JVM alive after playback
  finishes -- end the script with `(System/exit 0)` after your
  `Thread/sleep`, or the process will hang.
- A color fed to `color-talea` (or any repeating pitch cycle in
  general) needs **absolute, capital-letter** pitches
  (`C4 D4 E4 ...`), not lowercase. Lowercase pitch letters are always
  *relative* pitch resolution (nearest fourth/fifth from the previous
  note, LilyPond-`\relative`-style) — a lowercase color never actually
  returns to its starting pitch on each cycle, it just keeps climbing
  (or descending) indefinitely, well past a sensible MIDI range given
  enough repeats. Confirmed the hard way, not just a theoretical
  caveat: an earlier draft of the `colorTalea` example above used
  lowercase pitches and drifted up to MIDI pitch 299 by note 140 before
  the fix.
