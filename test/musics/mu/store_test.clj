(ns ^:repl musics.mu.store-test
  "Musics text read into the repo as mu input: files, the editor's text."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [musics.mu.eval :as e]
            [musics.mu.store :as store]
            [musics.repo :as repo]
            [musics.test-support :refer [with-fresh-session]]))

(use-fixtures :each (fn [t] (let [saved @e/state]
                              (try (with-fresh-session (t))
                                   (finally (reset! e/state saved))))))

(deftest a-text-stores-its-parts-in-order
  (is (= [:verse :chorus] (:ids (store/read! "[verse: c4 d e] [chorus: f g a]"))))
  (is (= [:verse :chorus] (filterv #{:verse :chorus} (repo/root-children))))
  (testing "a named context is stored, not linked into :ROOT"
    (is (= [:loud] (:ids (store/read! "^{ loud: !ff }"))))
    (is (repo/current :loud))
    (is (not (some #{:loud} (repo/root-children))))))

(deftest a-variable-is-bound-then-used
  (store/read! "'motif [c4 d] := [v: \\motif e]")
  (is (= [[60] [62] [64]] (map :pitches (:children (repo/current :v))))))

(deftest what-is-not-music-is-refused
  (is (str/includes? (with-out-str (is (nil? (store/read! "[v: c4] 7")))) "leaves only music"))
  (is (str/includes? (with-out-str (store/read! "transpose c d ( c4 e )"))
                     "a command needs a line: write [transpose c d ( c4 e )]")))

(deftest every-corpus-file-reads
  (doseq [f (.listFiles (io/file "corpus/mus")) :when (str/ends-with? (.getName f) ".mus")]
    (testing (.getName f)
      (let [out (with-out-str (is (seq (:ids (store/read-file! (.getPath f))))))]
        (is (not (str/includes? out "can't read")) out)))))
