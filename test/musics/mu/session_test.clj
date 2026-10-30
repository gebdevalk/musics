(ns ^:repl musics.mu.session-test
  "mu's words and bindings in a session file (musics.mu.session): written
   as mu text, restored in place of mu's own, the stack left out."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [musics.input.reader.builder :as builder]
            [musics.mu.eval :as e]
            [musics.mu.kernel]
            [musics.mu.library]
            [musics.mu.print :as p]
            [musics.mu.session :as session]
            [musics.mu.tools]
            [musics.repo :as repo]
            [musics.test-support :refer [with-fresh-registries]]))

(use-fixtures :each
  (fn [t]
    (let [saved @e/state]
      (try
        (with-fresh-registries
          (reset! e/state (assoc (e/fresh-state) :ns (into {} (for [[ns words] (:ns saved)]
                                                                [ns (into {} (remove (comp :body val) words))]))))
          (repo/commit-node! :ROOT (get (:repo (builder/empty-session)) :ROOT))
          (t))
        (finally (reset! e/state saved))))))

(defn- top [& inputs]
  (swap! e/state assoc :stack [])
  (let [r (last (mapv e/eval-input inputs))]
    (if (:error r) (:error r) (str/join " " (map p/show (:ok r))))))

(defn- with-file [f]
  (let [tmp (java.io.File/createTempFile "mu-session" ".edn")]
    (try (f (.getPath tmp)) (finally (io/delete-file tmp true)))))

(deftest the-state-is-written-as-mu-text
  (top "'motif [c8 e g] :=" "'n 3 :=" "'dp 'duration-pulse :="
       ": lift ( x -- y ) $x 5 + ;" "\"tool\" in : twice ( x -- y ) $x dup [+] ; \"mine\" in")
  (let [[text skipped] (session/text)]
    (is (empty? skipped))
    (is (= (str/join "\n" ["\"mine\" in" ": lift ( x -- y ) $x 5 + ;"
                           "\"tool\" in" ": twice ( x -- y ) $x dup [+] ;"
                           "\"mine\" in"
                           "'dp 'duration-pulse :=" "'motif [C4/8 E4 G4] :=" "'n 3 :="])
           text))))

(deftest a-definitions-literals-are-written-as-they-read
  (top "[c4 d] : one ( -- y ) e ;")
  (is (str/includes? (first (session/text)) ": one ( -- y ) E4/4 ;")
      "e is a quarter only because of [c4 d] before it in its input"))

(deftest a-value-that-cant-be-written-is-skipped
  (top ": maker ( a -- q ) '( $a + ) ;" "'q 5 maker :=" "'n 3 :=")
  (let [[text skipped] (session/text)]
    (is (= ["q"] skipped) "a quotation holding a word's local")
    (is (str/includes? text "'n 3 :="))))

(deftest a-session-file-restores-them-in-place-of-mus-own
  (with-file
    (fn [f]
      (top "[verse: c4 d e f] R!" "'motif [c8 e g] :=" ": lift ( x -- y ) $x 5 + ;"
           "'r @( k:3 euclid '[60] [~] zip ) :=")
      (with-out-str (top (str "\"" f "\" save-session")))
      (top ": junk 1 ;" "'motif [a] :=" "'extra 1 :=")
      (top "[c] 7")
      (with-out-str (e/eval-input (str "\"" f "\" restore-session")))
      (testing "replaced, not merged"
        (is (re-find #"unknown word" (top "junk")))
        (is (re-find #"nothing is bound" (top "\\extra"))))
      (testing "what was saved"
        (is (= "[C4/8 E4 G4]" (top "\\motif")))
        (is (= "[!K:F.major F4/4]" (top "[c] lift")))
        (is (= "[C4/8. C4 C4/8]" (top "\\r run")))
        (is (= "[verse: C4/4 D4 E4 F4]" (top ":verse R@")) "and the repo")))))

(deftest a-file-without-mus-state-leaves-it
  (with-file
    (fn [f]
      (top "[verse: c4 d] R!" "'n 3 :=")
      (with-out-str ((requiring-resolve 'musics.persist/write) f))
      (with-out-str ((requiring-resolve 'musics.persist/restore-session) f))
      (is (= "3" (top "\\n")) "write's file holds no words or bindings"))))
