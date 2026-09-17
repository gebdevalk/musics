# Uncertain docs — not deleted, not confirmed current

Moved here 2026-09-17 rather than deleted outright, because both
explicitly self-describe as unsettled scratch work rather than
reference documentation, and the specific ideas in them were never
confirmed either built or abandoned:

- **`functions.txt`** — 2026-08-07 brainstorm mapping Clojure seq
  functions to possible musical operations, written during the
  `sequable-domain`/`sq` exploration. Its own header says "not yet
  implemented... brainstorming for what transforms would be worth
  building on top of `sq` once it's decided whether that line of work
  is kept." `sq` itself was kept and shipped (see `CLAUDE.md`'s
  domain-model section), so that top-level question is settled — but
  nobody has checked whether the specific transform ideas listed here
  were later built (under these names or different ones), never
  pursued, or made obsolete by something else. Check against current
  `musics.core`/`algo.common.*` before assuming any given idea here is
  still open.
- **`fun-examples.txt`** — companion to `functions.txt`, real REPL
  output from the same exploration session, same "scratch, not
  shipped reference" status. Keep paired with `functions.txt` if either
  moves again.

If you determine one of these is fully superseded (every idea in it
either shipped or was deliberately rejected elsewhere), delete it. If
it still names a real gap, promote the relevant part into
`doc/algorithms.md` or a `doc/decisions.md` entry and delete the rest.
