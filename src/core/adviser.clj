(ns core.adviser
  "what-next -- a rule-based 'what should I do now' suggester, reading
   two kinds of signal: current STATE (read fresh from core.repo/
   core.async-engine/core.wall's own real accessors, nothing cached or
   duplicated here) and recent ACTIVITY (a bounded log of REPL-facing
   verbs, appended to from musics.clj's own thin wrappers -- the single
   seam every one of them already funnels through), plus an OPTIONAL
   intent argument passed directly to a single call -- one of
   #{:input :composing :configuring :playing} -- rather than a
   separately DECLARED, persisted mode: nothing here is stored across
   calls, by design (the user's own call -- no need to track 'what
   you're doing' as state when the caller can just say so, once, right
   at the point of asking).

   Deliberately a plain, inspectable list of candidate checks -- each a
   {:tier n :intent kw-or-nil :text \"...\"} map -- sorted by a small
   scoring fn, not a declarative rule-table abstraction or anything
   resembling inference/ML: every suggestion what-next can ever produce
   is traceable to one named check in candidates below.

   Passing an intent NARROWS priority, it never HIDES a suggestion
   outright: a :tier 0 candidate (uncommitted staged edits, the one
   thing genuinely urgent regardless of what you're doing) always sorts
   first; a :tier 1 candidate whose own :intent matches the one you
   passed sorts next; every other still-true candidate follows after
   that, reordered, not dropped.

   wipe! resets ONLY this ns's own state (the activity log) -- the
   repo, session, engine, wall/preset registries are all untouched,
   unlike musics.clj/reset."
  (:require [core.registries :as reg]
            [core.repo :as repo]
            [core.async-engine :as engine]
            [core.wall :as wall]))

(def intents
  "The closed vocabulary what-next's optional intent argument accepts --
   see this ns's own docstring."
  #{:input :composing :configuring :playing})

(def ^:private log-limit 30)

(defn log-activity!
  "Append {:action action :detail detail :when now} to the shared
   activity log, trimming to the most recent log-limit entries. Called
   from musics.clj's own thin wrappers -- never from anywhere lower-
   level, so this never runs from inside a live voice's own go-block."
  ([action] (log-activity! action nil))
  ([action detail]
   (swap! reg/*adviser-log*
          (fn [log]
            (vec (take-last log-limit
                             (conj log {:action action :detail detail
                                        :when (System/currentTimeMillis)})))))
   nil))

(defn recent-activity
  "The activity log, oldest first, capped to n (default: everything kept,
   at most log-limit entries)."
  ([] @reg/*adviser-log*)
  ([n] (vec (take-last n @reg/*adviser-log*))))

(defn wipe!
  "Reset this ns's own state -- the activity log -- without touching
   the repo, session, engine, or wall/preset registries. For starting
   the adviser's own tracking over mid-session; not a substitute for
   musics.clj/reset."
  []
  (reset! reg/*adviser-log* [])
  nil)

;; ============================================================
;; State readers -- each reads straight from the real, live source,
;; nothing cached here
;; ============================================================

(defn- outstanding-staged-sids
  "sid -> {id -> node} for every staging area with pending, not-yet-
   committed-or-aborted edits -- core.repo has no 'list every staged
   sid' accessor of its own (only staged-edits for ONE known sid), so
   this reads *repo-staging* directly, same as core.wall's own
   `registered` fn already reaches into *algo-registry* directly for
   the equivalent 'every entry at once' need."
  []
  (into {} (filter (fn [[_ edits]] (seq edits))) @reg/*repo-staging*))

(defn- nothing-committed-yet?
  "True until :ROOT has at least one author-visible child -- :ROOT
   itself always exists (musics.clj bootstraps/reset commits a fresh
   one), so this is genuinely 'has anything been written', not 'does a
   session exist at all'."
  []
  (empty? (:children (repo/current :ROOT))))

(defn- ever-played?
  "Best-effort from the activity log alone -- core.async-engine's own
   :voices only ever reflects voices RIGHT NOW; a voice that already
   finished playing leaves no trace there for state alone to find."
  []
  (boolean (some #(#{:play :play-add :play-change} (:action %)) @reg/*adviser-log*)))

(defn- currently-playing?
  []
  (boolean (and engine/*engine* (seq @(:voices engine/*engine*)))))

(defn- algo-registered-but-nothing-assigned?
  []
  (and (seq (wall/algos))
       (empty? (remove nil? (vals (engine/algo-assignments))))))

;; ============================================================
;; Candidates -- one entry per named check, plain data, no abstraction
;; layer between a check and its own suggestion text
;; ============================================================

(defn- candidates []
  (let [staged-sids     (vec (keys (outstanding-staged-sids)))
        nothing-yet?    (nothing-committed-yet?)
        played?         (ever-played?)]
    (cond-> []
      (seq staged-sids)
      (conj {:tier 0 :intent nil
             :text (str "Uncommitted staged edit(s): " (pr-str staged-sids)
                        " -- (commit! " (first staged-sids) ") or (abort-staged! " (first staged-sids) ")")})

      nothing-yet?
      (conj {:tier 1 :intent :input
             :text "Nothing committed yet -- write some .mus text: (parse \"...\"), then (commit! sid)."})

      (and (not nothing-yet?) (not played?))
      (conj {:tier 1 :intent :playing
             :text "You have committed material but haven't played anything yet -- try (play :yourId) or (display :yourId)."})

      (currently-playing?)
      (conj {:tier 1 :intent :playing
             :text "A voice is currently playing -- (pause!)/(stop!) it, or (assign-algo! path name) to change it live."})

      (algo-registered-but-nothing-assigned?)
      (conj {:tier 1 :intent :configuring
             :text (str "Algorithm(s) registered but nothing's using one: " (pr-str (vec (keys (wall/algos))))
                        " -- (assign-algo! path name) or (play id :algo name).")})

      (seq (wall/presets))
      (conj {:tier 2 :intent :configuring
             :text (str "Preset(s) available: " (pr-str (vec (keys (wall/presets))))
                        " -- switch a voice with (assign-algo! path presetName).")})

      :always
      (conj {:tier 2 :intent nil
             :text (str "Pipeline: write (.mus text, parse/commit!) -> compose (variables/sq/algo "
                        "generators) -> configure (register-algo!/configure-preset!/assign-algo!) "
                        "-> play (play/pause!/stop!).")}))))

(defn what-next
  "Up to n (default 3) suggested next steps, most relevant first --
   :tier 0 candidates (genuinely urgent regardless of intent) always
   sort first, then :tier 1 candidates whose own :intent matches the
   OPTIONAL intent argument (default nil -- no bias, priority comes
   from state/activity alone), then every other still-true candidate,
   reordered rather than hidden (see this ns's own docstring). A given
   intent is validated -- a typo'd intent argument should fail loudly,
   not silently match nothing."
  ([] (what-next 3 nil))
  ([n] (what-next n nil))
  ([n intent]
   (when (and intent (not (intents intent)))
     (throw (ex-info (str intent " is not a recognized intent -- expected one of " intents)
                      {:given intent :expected intents})))
   (let [score (fn [candidate]
                 (let [tier (:tier candidate), cand-intent (:intent candidate)]
                   (cond
                     (= tier 0) 0
                     (and (= tier 1) (= cand-intent intent)) 1
                     (= tier 1) 2
                     :else 3)))]
     (->> (candidates) (sort-by score) (take n) (mapv :text)))))
