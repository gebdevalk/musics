(ns musics-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.java.io :as io]
            [musics :as m]
            [input.reader.flat-core-builder :as flat]
            [core.domain.flat-domain :as d]
            [core.domain.resolve :as r]))

(defn reset-state-fixture [f]
  ;; A session is never nil in real use (see flat/empty-session) -- match
  ;; that here too, rather than resetting to a state real code never sees.
  (reset! m/session (flat/empty-session))
  (f))

(use-fixtures :each reset-state-fixture)

;; ============================================================
;; Parse
;; ============================================================

(deftest parse-returns-new-ids
  (let [new-ids (m/parse "[verse: c4 d4]")]
    (is (= #{:verse} new-ids) "parse returns the newly-added top-level ids")
    (is (d/container? (m/find :verse)) "id resolves to a container in the session")))

(deftest parse-registers-ids
  (m/parse "[verse: c4 d4]")
  (m/parse "[chorus: g4 a4 b4]")
  (let [all-ids (set (m/ids))]
    (is (all-ids :verse) "verse registered")
    (is (all-ids :chorus) "chorus registered")))

(deftest parse-error-returns-nil
  (binding [*out* (java.io.StringWriter.)]
    (is (nil? (m/parse "[c4 d4")) "unclosed bracket returns nil")))

(deftest root-children-accumulates-every-top-level-parse
  (m/parse "[verse: c4 d4]")
  (m/parse "[chorus: g4 a4]")
  (m/parse "[song: :verse :chorus]")
  (is (= [:verse :chorus :song] (m/root-children))
      "every top-level parse this session has seen, in call order -- not just the latest"))

(deftest locate-navigates-the-session-with-no-repo-argument
  (m/parse "[verse: c4 d4]")
  (let [{:keys [part]} (m/locate :verse [1])]
    (is (d/leaf? part))
    (is (= [62] (:pitches part)))))

(deftest cross-parse-references-resolve
  ;; This is the regression test for the bug that motivated the session
  ;; refactor: separately-parsed parts referenced from a later parse used
  ;; to silently vanish, since each parse built its own isolated repo.
  (m/parse "[verse: c4 d4]")
  (m/parse "[chorus: g4 a4]")
  (m/parse "[song: :verse :chorus]")
  (let [song-children (m/children (:repo @m/session) :song)]
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
  (m/parse "[a b c]")
  (let [repo (:repo @m/session)
        loc  (r/locate repo :ROOT [0 0])]
    (is (= 2 (count (:ctx-chain loc))))))

;; ============================================================
;; Find
;; ============================================================

(deftest find-by-keyword
  (m/parse "[verse: c4 d4]")
  (let [c (m/find :verse)]
    (is (d/container? c))
    (is (= :verse (:id c)))))

(deftest find-by-string
  (m/parse "[verse: c4 d4]")
  (is (d/container? (m/find "verse"))))

(deftest find-nonexistent-returns-nil
  (is (nil? (m/find :bogus)) "bogus keyword returns nil"))

;; ============================================================
;; Children / Leaves
;; ============================================================

(deftest children-of-named-part
  (m/parse "[verse: c4 d4]")
  (let [ch (m/children :verse)]
    (is (= 2 (count ch)) "two children")
    (is (every? d/leaf? ch) "both are leaves")))

(deftest leaves-of-named-part
  (m/parse "[verse: c4 d4]")
  (let [ls (m/leaves :verse)]
    (is (= 2 (count ls)) "two leaves")
    (is (every? d/leaf? ls) "both are leaves")))

;; ============================================================
;; Persistence
;; ============================================================

(deftest write-load-round-trips-session
  (m/parse "[verse: c4 d4]")
  (let [tmp (java.io.File/createTempFile "musics-session" ".edn")]
    (try
      (m/write (.getPath tmp))
      (reset! m/session (flat/empty-session))
      (is (nil? (m/find :verse)) "session really was cleared before load")
      (m/load (.getPath tmp))
      (is (d/container? (m/find :verse)) "verse resolves again after load")
      (is (= 2 (count (m/children :verse))) "verse's children survived the round-trip")
      (finally (io/delete-file tmp true)))))

(deftest load-then-parse-does-not-collide-ids
  ;; Bare (unnamed) sequences mint auto-ids like :SEQ.1 -- the real
  ;; collision risk this session refactor was meant to fix. Confirm the
  ;; counter keeps counting up across a load instead of restarting at 0
  ;; and clobbering what was loaded.
  (m/parse "[c4 d4]")                                       ;; mints :SEQ.1
  (let [tmp        (java.io.File/createTempFile "musics-session" ".edn")
        seq-1-repo (:repo @m/session)]
    (try
      (m/write (.getPath tmp))
      (reset! m/session (flat/empty-session))
      (m/load (.getPath tmp))
      (let [new-ids    (m/parse "[g4 a4]")                  ;; would also want :SEQ.1 if reset
            leaf-shape (fn [container]
                         ;; Leaf/Context both embed atoms (reference-
                         ;; identity, never = across a round-trip even
                         ;; with equal content) -- compare pitches/duration
                         ;; instead of whole records.
                         (mapv (juxt :duration :pitches) (:children container)))]
        (is (not= :SEQ.1 (first new-ids)) "auto-id counter continued past what was loaded")
        (is (= (leaf-shape (get seq-1-repo :SEQ.1))
               (leaf-shape (get (:repo @m/session) :SEQ.1)))
            "the loaded :SEQ.1 was not overwritten by the new parse"))
      (finally (io/delete-file tmp true)))))
