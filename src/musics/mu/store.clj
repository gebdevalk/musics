(ns musics.mu.store
  "Musics text read into the repo: a file, the GUI editor's text. It is
   mu input, run on a stack of its own; the music it leaves is stored,
   each part under its own name or an auto id, in the order written --
   [verse: …] [chorus: …] stores :verse and :chorus. Its bindings (:=)
   and definitions stay mu's; every mu word can be used."
  (:require [musics.engine :as engine]
            [musics.input.reader.builder :as builder]
            [musics.mu.algos]
            [musics.mu.eval :as e]
            [musics.mu.kernel]
            [musics.mu.library]
            [musics.mu.material :as mt]
            [musics.mu.tools]
            [musics.mu.print :as p]
            [musics.mu.value :as v]
            [musics.registries :as reg]
            [musics.repo :as repo]))

(defn- commit-root!
  "Commit a fresh :ROOT, with every default."
  []
  (repo/commit-node! :ROOT (get (:repo (builder/empty-session)) :ROOT)))

;; A real :ROOT exists before anything is read; defonce, so reloading this
;; ns keeps what is stored.
(defonce ^:private _bootstrap
  (when (nil? (repo/current :ROOT)) (commit-root!)))

(defn read!
  "Read text and store the music it leaves. Returns {:ids ids}, in the
   order written, or nil when it can't be read (the error printed)."
  [text]
  (try
    (let [left (e/run-text [] text)]
      (when-let [x (first (remove #(or (v/material? %) (v/leaf? %)) left))]
        (e/mu-error "text to store leaves only music, not" (p/show x)))
      {:ids (mt/store-all! (map vector left (repeat nil)))})
    (catch clojure.lang.ExceptionInfo e
      (println (.getMessage e))
      nil)))

(defn read-file!
  "Read the musics text in the file at path (see read!)."
  [path]
  (read! (slurp path)))

(defn reset-all!
  "Clear everything: what is stored, the session, every registered algo
   and conductor entry, the MIDI output; a fresh :ROOT is committed."
  []
  (repo/reset-all!)
  (reg/reset-all!)
  (commit-root!)
  (engine/disconnect!)
  (println "[musics] Reset."))
