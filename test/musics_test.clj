(ns musics-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.java.io :as io]
            [musics :as m]
            [core.domain.flat-domain :as d]))

(defn reset-state-fixture [f]
  (reset! m/session nil)
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
      (reset! m/session nil)
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
      (reset! m/session nil)
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
