(ns musics-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.java.io :as io]
            [musics :as m]
            [core.repo :as repo]
            [input.reader.flat-core-builder :as flat]
            [core.domain.flat-domain :as d]
            [core.domain.resolve :as r]))

(defn reset-state-fixture [f]
  ;; core.repo's registry/staging/play-tx are defonce'd (shared across the
  ;; whole test namespace), so a leftover commit from a previous test would
  ;; otherwise leak into the next test's (find)/(children)/etc. A session
  ;; is never nil in real use either (see musics.clj's own _bootstrap) --
  ;; match that here too, rather than resetting to a state real code never
  ;; sees: a real :ROOT, committed, with playback pointed at it.
  (repo/reset-all!)
  (repo/commit-node! :ROOT (get (:repo (flat/empty-session)) :ROOT))
  (repo/play-latest!)
  (reset! m/session {:auto-ids {}})
  (f))

(use-fixtures :each reset-state-fixture)

(defn parse!
  "Test helper: parse and immediately commit, returning the ids the parse
   introduced or changed -- (parse ...) itself only stages now (see its
   docstring), so tests that don't care about the staging step use this
   to get the old immediate-visibility behavior."
  [text]
  (let [{:keys [sid ids]} (m/parse text)]
    (m/commit! sid)
    ids))

;; ============================================================
;; Parse
;; ============================================================

(deftest parse-returns-new-ids
  (let [new-ids (parse! "{verse: c4 d4}")]
    (is (= #{:verse} new-ids) "parse returns the newly-added top-level ids")
    (is (d/container? (m/find :verse)) "id resolves to a container in the session")))

(deftest parse-is-staged-until-commit
  (let [{:keys [sid ids]} (m/parse "{verse: c4 d4}")]
    (is (= #{:verse} ids))
    (is (nil? (m/find :verse)) "not visible before commit!")
    (is (map? (m/pending sid)) "staged edits are inspectable before commit")
    (m/commit! sid)
    (is (d/container? (m/find :verse)) "visible after commit!")))

(deftest aborted-parse-never-becomes-visible
  (let [{:keys [sid]} (m/parse "{verse: c4 d4}")]
    (m/abort! sid)
    (is (nil? (m/find :verse)))
    (is (nil? (m/pending sid)) "aborted sid no longer has staged edits")))

(deftest parse-registers-ids
  (parse! "{verse: c4 d4}")
  (parse! "{chorus: g4 a4 b4}")
  (let [all-ids (set (m/ids))]
    (is (all-ids :verse) "verse registered")
    (is (all-ids :chorus) "chorus registered")))

(deftest parse-error-returns-nil
  (binding [*out* (java.io.StringWriter.)]
    (is (nil? (m/parse "{c4 d4")) "unclosed bracket returns nil")))

(deftest root-children-accumulates-every-top-level-parse
  (parse! "{verse: c4 d4}")
  (parse! "{chorus: g4 a4}")
  (parse! "{song: :verse :chorus}")
  (is (= [:verse :chorus :song] (m/root-children))
      "every top-level parse this session has seen, in call order -- not just the latest"))

(deftest locate-navigates-the-session-with-no-repo-argument
  (parse! "{verse: c4 d4}")
  (let [{:keys [part]} (m/locate :verse [1])]
    (is (d/leaf? part))
    (is (= [62] (:pitches part)))))

(deftest cross-parse-references-resolve
  ;; This is the regression test for the bug that motivated the session
  ;; refactor: separately-parsed parts referenced from a later parse used
  ;; to silently vanish, since each parse built its own isolated repo.
  ;; (children ...) auto-resolves keyword children against the latest
  ;; committed tx by default now, so no explicit tree/view is needed.
  (parse! "{verse: c4 d4}")
  (parse! "{chorus: g4 a4}")
  (parse! "{song: :verse :chorus}")
  (let [song-children (m/children :song)]
    (is (= 2 (count song-children)) "song has two children")
    (is (every? d/container? song-children)
        "both children resolve to real containers, not dangling keywords")
    (is (= :verse (:id (first song-children))))
    (is (= :chorus (:id (second song-children))))))

(deftest pristine-parse-has-a-two-context-chain
  ;; A session's :ROOT always carries the one true root context (built at
  ;; session-start by flat/empty-session). Locating a leaf in a freshly-
  ;; parsed, unnamed top-level sequence should see exactly ROOT's context
  ;; and the sequence's own context -- not a third, separately-constructed
  ;; root context stacked on top.
  (parse! "{a b c}")
  (let [loc (r/locate (repo/view (repo/latest-tx)) :ROOT [0 0])]
    (is (= 2 (count (:ctx-chain loc))))))

;; ============================================================
;; play-tx: committing is decoupled from what's playing
;; ============================================================

(deftest commit-does-not-move-play-tx
  (let [before @repo/play-tx]
    (parse! "{verse: c4 d4}")
    (is (not= before (repo/latest-tx)) "commit! did mint a new tx")
    (is (= before @repo/play-tx) "but play-tx was left exactly where it was")))

(deftest play-tx-bang-repoints-playback-explicitly
  (parse! "{verse: c4 d4}")
  (let [tx1 @repo/play-tx]
    (parse! "{verse: e4 f4}")                                 ;; redefine :verse, mints a new tx
    (is (= tx1 @repo/play-tx) "still pointing at the pre-redefinition tx")
    (m/play-tx! (m/latest-tx))
    (is (= (m/latest-tx) @repo/play-tx) "explicit play-tx! moved it")))

(deftest play-latest-bang-follows-latest-commit
  (parse! "{verse: c4 d4}")
  (m/play-latest!)
  (is (= (m/latest-tx) @repo/play-tx)))

;; ============================================================
;; Inspection defaults to latest committed tx, with an explicit tx
;; argument for looking at any point in history
;; ============================================================

(deftest inspection-fns-accept-an-explicit-tx
  (parse! "{verse: c4 d4}")
  (let [tx1 (m/latest-tx)]
    (parse! "{verse: e4 f4}")                                 ;; redefine :verse
    (is (= [60] (:pitches (first (m/children :verse tx1))))
        "as of tx1, verse still has its original first note (c4)")
    (is (= [64] (:pitches (first (m/children :verse))))
        "no tx arg defaults to the latest commit, seeing the redefinition (e4)")
    (is (= #{:verse} (set (m/ids tx1))) "(ids tx) also respects the pin")))

;; ============================================================
;; Find
;; ============================================================

(deftest find-by-keyword
  (parse! "{verse: c4 d4}")
  (let [c (m/find :verse)]
    (is (d/container? c))
    (is (= :verse (:id c)))))

(deftest find-by-string
  (parse! "{verse: c4 d4}")
  (is (d/container? (m/find "verse"))))

(deftest find-nonexistent-returns-nil
  (is (nil? (m/find :bogus)) "bogus keyword returns nil"))

;; ============================================================
;; Children / Leaves
;; ============================================================

(deftest children-of-named-part
  (parse! "{verse: c4 d4}")
  (let [ch (m/children :verse)]
    (is (= 2 (count ch)) "two children")
    (is (every? d/leaf? ch) "both are leaves")))

(deftest leaves-of-named-part
  (parse! "{verse: c4 d4}")
  (let [ls (m/leaves :verse)]
    (is (= 2 (count ls)) "two leaves")
    (is (every? d/leaf? ls) "both are leaves")))

;; ============================================================
;; Context query -- ctx (display) vs. ctx-value (sampling)
;; ============================================================

(deftest ctx-value-samples-by-canonical-key-or-any-alias
  ;; !tempo:120 is written under the canonical :Tempo (see
  ;; common.defaults' :Tempo registration, :aliases [:T :tempo]).
  ;; ctx-value must canonicalize its own key argument the same way a
  ;; write already does, or every alias except the canonical spelling
  ;; would silently read back nil.
  (parse! "{verse: !tempo:120 c4}")
  (is (= 120 (m/ctx-value :verse :Tempo 0.0)) "canonical spelling")
  (is (= 120 (m/ctx-value :verse :tempo 0.0)) "lowercase alias")
  (is (= 120 (m/ctx-value :verse :T 0.0)) "single-letter alias"))

(deftest ctx-value-defaults-to-latest-tx
  (parse! "{verse: !tempo:120 c4}")
  (is (= 120 (m/ctx-value :verse :tempo 0.0))))

(deftest ctx-shows-ancestor-chain-nearest-first-root-excluded
  (parse! "{verse: !tempo:120 {inner: !vol:80 c4}}")
  (let [inner-id (first (filter keyword? (:children (m/find :verse))))
        out      (with-out-str (m/ctx inner-id))]
    (is (re-find #":inner" out) "inner's own authored value shows")
    (is (re-find #":verse" out) "ancestor's authored value shows too")
    (is (not (re-find #":ROOT" out)) ":ROOT itself is excluded")
    ;; nearest-first: :inner's own line comes before :verse's
    (is (< (.indexOf out ":inner") (.indexOf out ":verse")))))

(deftest ctx-on-unregistered-id-prints-not-found
  (is (re-find #"(?i)not found" (with-out-str (m/ctx :nope)))))

(deftest ctx-value-finds-a-value-set-on-an-intermediate-ancestor
  ;; Regression coverage: ctx-value used to build only a 2-element
  ;; [part's own context, :ROOT's context] chain, skipping anything
  ;; authored on an ancestor in between -- a value set on :outer (not
  ;; :inner's own context, not :ROOT) was invisible from :inner.
  (parse! "{outer: !key:D.major {inner: c4}}")
  (let [ks (m/ctx-value :inner :key 0.0)]
    (is (= "D" (:display (:signature ks))) "found on :outer, not just own/:ROOT")))

(deftest ctx-value-still-samples-roots-default-with-nothing-set
  (parse! "{plain: c4}")
  (let [ks (m/ctx-value :plain :key 0.0)]
    (is (= "C" (:display (:signature ks))))))

;; ============================================================
;; Expand -- ornaments sample the leaf's complete ancestor chain
;; ============================================================

(deftest expand-samples-key-from-an-intermediate-ancestor
  ;; Same gap as ctx-value, on the ornament path: orn/expand alone can
  ;; only see [leaf's own context, root-ctx] (a bare Leaf carries no
  ;; path back to its ancestors) -- musics/expand has to build the real
  ;; chain itself (full-ctx-chain) and hand it over.
  (parse! "{outer: !key:D.major {inner: c4\\trill}}")
  (let [leaf     (first (m/leaves :inner))
        expanded (m/expand leaf)]
    (is (< 1 (count expanded)) "trill actually expanded into sub-notes")
    (is (not (apply = (map :pitches expanded)))
        "a real trill alternates -- D major's key was found on :outer")))

;; ============================================================
;; Persistence
;; ============================================================

(deftest write-load-round-trips-session
  (parse! "{verse: c4 d4}")
  (let [tmp (java.io.File/createTempFile "musics-session" ".edn")]
    (try
      (m/write (.getPath tmp))
      (repo/reset-all!)
      (reset! m/session {:auto-ids {}})
      (is (nil? (m/find :verse)) "session really was cleared before load")
      (m/load (.getPath tmp))
      (is (d/container? (m/find :verse)) "verse resolves again after load")
      (is (= 2 (count (m/children :verse))) "verse's children survived the round-trip")
      (finally (io/delete-file tmp true)))))

(deftest write-load-round-trips-a-meter-record
  ;; core.repo commit-node!/write always includes :ROOT, and :ROOT's own
  ;; context now carries a real Meter record as its default -- regression
  ;; coverage for the write/load break that caused (fixed in persist.clj's
  ;; freeze/thaw: Meter/Key records can't survive a bare pr-str/edn/read-
  ;; string round-trip without explicit tagging).
  (parse! "{verse: !Meter:\"7/8(2+2+3)\" c4}")
  (let [tmp (java.io.File/createTempFile "musics-session" ".edn")]
    (try
      (m/write (.getPath tmp))
      (repo/reset-all!)
      (reset! m/session {:auto-ids {}})
      (m/load (.getPath tmp))
      (let [meter (m/ctx-value :verse :Meter 0.0)]
        (is (= 7 (:num meter)))
        (is (= 8 (:den meter)))
        (is (= [2 2 3] (:subdivisions meter))))
      (finally (io/delete-file tmp true)))))

(deftest load-then-parse-does-not-collide-ids
  ;; Bare (unnamed) sequences mint auto-ids like :s1 -- the real
  ;; collision risk this session refactor was meant to fix. Confirm the
  ;; counter keeps counting up across a load instead of restarting at 0
  ;; and clobbering what was loaded.
  (parse! "{c4 d4}")                                        ;; mints :s1
  (let [tmp      (java.io.File/createTempFile "musics-session" ".edn")
        s1-repo  (into {} (repo/view (repo/latest-tx)))]
    (try
      (m/write (.getPath tmp))
      (repo/reset-all!)
      (reset! m/session {:auto-ids {}})
      (m/load (.getPath tmp))
      (let [new-ids    (parse! "{g4 a4}")                   ;; would also want :s1 if reset
            leaf-shape (fn [container]
                         ;; Leaf/Context both embed atoms (reference-
                         ;; identity, never = across a round-trip even
                         ;; with equal content) -- compare pitches/duration
                         ;; instead of whole records.
                         (mapv (juxt :duration :pitches) (:children container)))]
        (is (not= :s1 (first new-ids)) "auto-id counter continued past what was loaded")
        (is (= (leaf-shape (get s1-repo :s1))
               (leaf-shape (get (into {} (repo/view (repo/latest-tx))) :s1)))
            "the loaded :s1 was not overwritten by the new parse"))
      (finally (io/delete-file tmp true)))))
