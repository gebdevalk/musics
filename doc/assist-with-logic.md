# Assist with core.logic

How the help system could work if it reasoned over facts with core.logic
(`clojure.core.logic`, already a dependency, used by `musics.algo.logic.tree`
and `musics.algo.logic.counterpoint`) instead of printing sentences written by
hand.

## Summary

Today's help is hand-written. It tells you what to do next from four
fixed checks, and its texts have drifted from the system they describe.
If each REPL action were described as a fact (what it needs, what it
gives), a small core.logic layer could:

- work out the suggestions instead of storing them;
- answer goals ("what do I still need to do to hear a live tree?");
- explain why an action can't be taken yet.

`assist` is for the REPL: it answers in words. The GUI doesn't ask
questions; it shows the same facts as the state of its buttons.

The algo questions `musics.algo.logic.tree` already answers work the same way,
so one `assist` entry point could answer both kinds of question. The
code would be about the same size as today's adviser. What it buys is
more answers and less drift, not fewer lines.

## The help system today

| Part | Where | Size | What it does |
|---|---|---|---|
| Adviser | `src/musics/adviser.clj` | 208 lines | `what-next`: 4 state checks + 1 fixed reminder, ranked by tier and an optional intent; `top-intent`; an activity log |
| REPL wrappers | `src/musics/core.clj` (`print-suggestions!`, `uh?`, `advise`, `advise!`, `wipe-adviser!`) | ~85 lines | print suggestions; `advise!` reads a phase from stdin |
| GUI highlight | `src/musics/gui/core.clj` (`intent->opener`), `src/musics/gui/state.clj` (`suggested-intent`) | ~40 lines | highlights the panel button for the top intent |
| Command help | `musics.core/help` | 15 lines | first docstring line of each public `musics.core` fn |
| Algo catalog | `musics.core/show-algos` + `algo-ns-syms`/`algo-category`/`algo-tree` | ~95 lines | walks `musics/algo/` on disk, prints docstrings |
| Algo questions | `src/musics/algo/logic/tree.clj` | 330 lines | core.logic over the algo registry: `find-algos`, `how`, `steps`, `feeds`, `why-not`, `examples`, `surprise` |
| Tests | `test/adviser_test.clj` | 153 lines | activity log and ranking |

## Problems with it

**The suggestions are sentences, and they drift.** Each one is a
string written for one combination of states:

- `:stage`/`:commit` survive in `advise`'s docstring, in the GUI's
  `intent->opener`, and in `adviser_test`, although `parse` commits at
  once.
- `advise`'s docstring says `(advise 4)` is `:configure`, but position 4
  is `:play`.
- `uh?`'s docstring mentions "uncommitted staged edits".
- Nothing in the adviser knows about `musics.algo.tree` (`live!`, `tctx`,
  `build-tree`, `gui`), `render`, drums or counterpoint. Its one algo
  check ("registered but unused") predates trees.

**It can only react.** It looks at the current state and prints what
fits. It can't answer "I want X, what's missing?" or "why can't I do
Y?".

**Its machinery outweighs its content.** The file has four checks. Most
of the rest is ranking and selection: tiers (tier 0 has no candidate),
a `:conductor` intent (no candidate), intents by position
(`resolve-intent`, `numbered-intents`), the interactive `advise!`, and
two entry points (`uh?`, `advise`) that do the same thing.

**Two catalogs of algos.** `show-algos` reads docstrings from disk. The
registry (`t/algos`, `lt/find-algos`) knows types, params, ranges and
categories, and `test/musics/algo/catalog_test.clj` keeps it complete.

## The idea: actions as facts

Describe each REPL action once: what state it needs, what state it
produces, and how to call it.

```clojure
{:action :parse   :needs #{}                      :gives #{:committed}
 :call "(parse \"[name: ...]\")"}
{:action :connect :needs #{}                      :gives #{:connected}
 :call "(connect)"}
{:action :play    :needs #{:committed :connected} :gives #{:playing}
 :call "(play :name)"}
{:action :tctx    :needs #{:tree}                 :gives #{:tctx}
 :call "(t/tctx tree)"}
{:action :live!   :needs #{:tree :tctx :connected} :gives #{:playing :live}
 :call "(t/live! :name tree tctx)"}
{:action :render  :needs #{:committed}            :gives #{:midi-file}
 :call "(render :name \"out.mid\")"}
```

The current state is a set of facts read fresh from the system:
- `:committed` from `musics.repo`
- `:connected` from `musics.core/receiver`
- `:playing` from `musics.engine`'s voices
- `:live` from `musics.wall`'s registry

This is the same thing the adviser's four readers do today.

History is a second source of facts, read from the activity log. Some
things leave no trace in the state once they are over:
- a piece played and stopped (`:played`);
- a file rendered (`:rendered`);
- a tree that went live and was stopped.

The log turns them into facts too, so an action can need or exclude
one like any state fact. For example, after `(play :verse)` has
finished, "try it with `:algo`" or "render it" are good next steps. The
log also lets `assist` avoid suggesting what was just done, prefer the
step after the last action, and notice the same call failing
repeatedly.

A relation `stepo` (state, action, state') relates two states through
one action whose needs are met. A path of `stepo`s from the current
state to a goal is a plan. The search is the iterative deepening that
`musics.algo.logic.tree/smallest` already does for trees: the shortest plan
first, and a depth bound so an impossible goal fails quickly.

## What it gives

1. **Suggestions are derived, not stored.** "What next" becomes "the
   actions whose needs hold now and whose result isn't there yet". Its
   text comes from `:call`. A new command gets suggested once it has a
   fact, and a removed one disappears with its fact. The stale
   `:stage`/`:commit` texts could not have survived this way.
2. **It grows linearly.** Each action is one fact, and the search finds
   the combinations. Hand-written checks need one per combination of
   states.
3. **Goals.** `(assist :live)` gives the shortest sequence of actions
   from the current state, for example "`(t/tctx tree)`, `(connect)`,
   `(t/live! :name tree tctx)`". `(assist :midi-file)` with nothing
   committed starts at `(parse ...)`.
4. **"Why not" for actions.** `(why-not :play)` names the missing needs
   and the actions that give them: "nothing is committed: `(parse ...)`
   gives `:committed`". This is what `musics.algo.logic.tree/why-not` already
   does for a slot that doesn't fit.
5. **Reverse questions from the same facts:**
   - what can I do now (every action whose needs hold);
   - what gives `:x`;
   - what does `:play` need.

   No extra code is needed for each, since a relation runs in any
   direction.
6. **One source for REPL and GUI.** The REPL asks `assist` and gets
   words. The GUI shows the same facts on its buttons instead, each
   button naming the action it performs:
   - **disabled** when the action's needs don't hold (Play with
     nothing committed);
   - **coloured** when it is the next step of the current plan;
   - **plain and enabled** otherwise.

   A button's tooltip can carry `why-not` ("nothing is committed:
   parse first"). This replaces the separate intent-to-button map
   (`intent->opener`), which has to be kept in step with the adviser
   by hand.
7. **One way to ask.** Workflow questions and algo questions (`how`,
   `feeds`, `why-not`) would use the same style of relation and the same
   search. One `assist` entry point could take either:
   - a workflow goal (`:live`);
   - a type pair (`:grid` to `:pitches`), which hands over to
     `musics.algo.logic.tree/how`.

## What it costs

- **About the same size.** About 200 lines: the action table, `stepo`,
  the plan search, the state readers, and printing. That replaces
  `core.adviser` (208 lines) and most of the `musics.core` wrappers
  (~85). The gain is answers and staying current, not fewer lines.
- **Harder to read than plain checks.** The action table itself stays
  plain data. The relation and the search are core.logic, as in
  `musics.algo.logic.tree`.
- **Facts must stay true.** A wrong `:needs` gives a wrong plan. A
  test can hold the line the way `catalog_test.clj` does for algos:
  - every public `musics.core` command has a fact or a listed reason;
  - every fact's `:call` resolves to a real function.
- **The log must see every action.** Today only some `musics.core`
  wrappers log (`parse`, `connect`, `play`/`play-add`, `stop!`,
  `pause!`, `resume!`). `t/live!`, `render`, `play-change`, the GUI and
  the rest don't. History facts are only trustworthy if logging happens
  in one place for every action in the table: one wrapper per action,
  or the table naming the var to log. `reset` already clears the log
  (`musics.registries/reset-all!`).

## Independent of core.logic

- **Algo catalog.** `show-algos` and its three helpers (~95 lines) can
  become a thin printer over the registry (`t/algos`/`lt/find-algos`,
  by category), leaving one catalog of algos only. Helpers that aren't
  algos are reached through `doc` and their namespaces. They are already
  listed, with reasons, in `catalog_test.clj`'s `not-algos`.
- **Stale texts.** The `:stage`/`:commit` and position mistakes listed
  above are wrong today whatever is decided here.

## Suggested order

1. Make the registry the one algo catalog and fix the stale texts. Both
   are small and needed either way.
2. Write the action table for the commands people actually use:
   `parse`, `connect`, `play`/`play-add`/`stop!`, `render`, `t/tctx`,
   `t/live!`, `gui`, `build-tree`. Log every one of them, and add the
   coverage test.
3. Add `stepo` and the plan search, reusing `musics.algo.logic.tree`'s
   `smallest`. Build `assist` (next steps and goals) and `why-not` for
   actions.
4. Let `assist` hand type questions to `musics.algo.logic.tree`.
5. Drive the GUI's buttons from the same facts: give each button its
   action, then disable, colour or tooltip it from the facts and the
   plan.
6. Remove `core.adviser`:
   - its ranking (tiers, intents by position);
   - its hand-written candidates;
   - `uh?`, `advise`, `advise!` and `wipe-adviser!`.

   The activity log moves into `assist`'s namespace as its history
   source.

## Decisions

- **`assist` replaces `uh?`, `advise`, `advise!` and `wipe-adviser!`.**
- **Plans start from the current state plus history.** The activity log
  stays (`reg/*adviser-log*`, bounded, cleared by `reset`), as a second
  source of facts, and records every action in the table, not only
  today's handful of wrappers.
- **The algo catalog lists algos only.** It is printed from the
  registry, and helper functions that aren't algos are not listed.
